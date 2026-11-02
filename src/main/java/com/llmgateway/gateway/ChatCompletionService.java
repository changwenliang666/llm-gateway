package com.llmgateway.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmgateway.error.ErrorTypes;
import com.llmgateway.error.GatewayException;
import com.llmgateway.routing.AccountLoadBalancer;
import com.llmgateway.routing.CallerContext;
import com.llmgateway.routing.ModelRoute;
import com.llmgateway.routing.Permit;
import com.llmgateway.routing.RateWindows;
import com.llmgateway.routing.RouteResolver;
import com.llmgateway.routing.Selection;
import com.llmgateway.timeout.TimeoutPolicy;
import com.llmgateway.upstream.AdapterOperation;
import com.llmgateway.upstream.AdapterRegistry;
import com.llmgateway.upstream.InvokeCommand;
import com.llmgateway.upstream.InvokeResult;
import com.llmgateway.upstream.JsonBodies;
import com.llmgateway.upstream.ProtocolAdapter;
import com.llmgateway.upstream.ResponseWriter;
import com.llmgateway.upstream.wan.TaskBindingStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * 对话入口的编排，对应 {@code POST /v1/chat/completions}。
 *
 * <p>本类不认识具体供应商。它只做六件事，顺序不能调换：
 * <ol>
 *   <li>读 JSON，取出模型名和这次的超时。</li>
 *   <li>让 {@link RouteResolver} 确认模型存在、适配器已注册、至少有一个可用账号。</li>
 *   <li>确认这个适配器声明了 {@link AdapterOperation#CHAT_COMPLETION}。视频模型在这里被拒绝。</li>
 *   <li>把业务方的 {@code message} 字符串收成一条 user 消息，并在没传 {@code thinking} 时关掉思考。</li>
 *   <li>选账号时按模型加账号查熔断，打开的账号跳过。选定后再查模型级限流。</li>
 *   <li>交给适配器。401、429、5xx 且还没写出字节时，换下一个还没熔断的账号再试一次。</li>
 *   <li>每次真正打到上游的调用，无论成败，都写进 {@code model_tasks}。</li>
 * </ol>
 * 正文在重试前只改一次，第二次仍用同一份 {@code messages}，不会把 {@code message} 再解释一遍。
 */
@Service
public class ChatCompletionService {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionService.class);

    private final ObjectMapper mapper;
    private final RouteResolver routes;
    private final AdapterRegistry adapters;
    private final AccountLoadBalancer loadBalancer;
    private final ModelAdmission admission;
    private final TimeoutPolicy timeoutPolicy;
    private final TaskBindingStore tasks;

    public ChatCompletionService(ObjectMapper mapper, RouteResolver routes, AdapterRegistry adapters,
                                 AccountLoadBalancer loadBalancer, ModelAdmission admission,
                                 TimeoutPolicy timeoutPolicy, TaskBindingStore tasks) {
        this.mapper = mapper;
        this.routes = routes;
        this.adapters = adapters;
        this.loadBalancer = loadBalancer;
        this.admission = admission;
        this.timeoutPolicy = timeoutPolicy;
        this.tasks = tasks;
    }

    /**
     * 处理一次对话。异常交给 {@code GatewayExceptionHandler} 写成 JSON。
     * 流式成功时适配器已经把字节写进 response，这里看到 {@code committed} 就不再写第二遍。
     */
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // 空正文、数组、坏 JSON 都在这里变成 400，后面的步骤都假设 body 是对象。
        ObjectNode body = JsonBodies.object(mapper, request.getInputStream());
        String model = JsonBodies.requiredText(body, "model");
        // 超时先算好。重试第二个账号时继续用同一个预算，不会重新读请求头。
        Duration timeout = timeoutPolicy.resolve(request);
        // 404、适配器未注册、没有可用账号都在 resolve 里抛出，本方法不再查表。
        ModelRoute route = routes.resolve(model);
        ProtocolAdapter adapter = adapters.required(route.adapterName());
        if (!adapter.supports(AdapterOperation.CHAT_COMPLETION)) {
            throw new GatewayException(400, ErrorTypes.INVALID_REQUEST, "该模型不能用于对话接口");
        }
        // 先确认这是文本模型，再改正文。视频模型打到这里时，业务方应看到协议不匹配，而不是缺消息。
        rewritePlainMessage(body);
        disableThinkingByDefault(body);
        // 缺省 stream 当 false。只有 JSON 布尔 true 才走 SSE，字符串 "true" 不会被当成流式。
        boolean stream = body.path("stream").asBoolean(false);
        // 先按账号熔断选路，再按模型限流。熔断打开不消耗 RPM。限流拒绝会归还这个账号的半开名额。
        int estimate = RateWindows.estimate(mapper.writeValueAsBytes(body));
        long started = System.nanoTime();
        Selection selected = loadBalancer.select(route);
        Permit permit = admission.enter(route, selected.account().accountId(), selected.circuitPermit(),
                CallerContext.from(request), estimate);
        InvokeResult result;
        try {
            result = call(adapter, command(selected, body, timeout, stream), response, selected);
            // retryable：401、429 或 5xx。committed：字节已经写给调用方，不能换账号再写一次。
            if (result.retryable() && !result.committed()) {
                Optional<Selection> next = loadBalancer.nextAccount(route, selected);
                if (next.isPresent()) {
                    admission.observe(route, selected.account().accountId(), permit, result);
                    selected = next.get();
                    permit = selected.circuitPermit().withReservation(permit.reservationId());
                    result = call(adapter, command(selected, body, timeout, stream), response, selected);
                }
            }
        } catch (RuntimeException | IOException ex) {
            admission.fail(route, selected.account().accountId(), permit);
            throw ex;
        }
        admission.finish(route, selected.account().accountId(), permit, result, estimate,
                adapter.reportedTotalTokens(result));
        // 非流式结果一直留在内存里，到这里才写给调用方，所以上面才有机会丢掉第一次的失败正文。
        if (!result.committed()) {
            ResponseWriter.writeBuffered(response, result);
        }
        log.info("调用完成 operation=chat model={} accountId={} keyId={} status={} elapsedMs={}",
                route.modelName(), selected.account().accountId(), selected.credential().keyId(),
                result.status(), (System.nanoTime() - started) / 1_000_000L);
    }

    /**
     * 把业务方的 {@code message} 字符串收成上游需要的一条 user 消息。
     * 调用方不用自己拼 role 和 content。若正文里还带着 {@code messages}，以这条字符串为准盖掉。
     * 字段在发给上游前删掉，供应商只看到标准的 {@code messages} 数组。
     * 转换只做一次，后面的账号重试继续用同一份正文。
     */
    private static void rewritePlainMessage(ObjectNode body) {
        JsonNode message = body.get("message");
        if (message == null || !message.isTextual() || message.asText().isBlank()) {
            throw new GatewayException(400, ErrorTypes.INVALID_REQUEST, "message 必须是非空字符串");
        }
        ArrayNode messages = body.putArray("messages");
        ObjectNode turn = messages.addObject();
        turn.put("role", "user");
        turn.put("content", message.asText());
        body.remove("message");
    }

    /**
     * 思考模式默认关闭。千问和 DeepSeek 都认 {@code thinking.type=disabled}，
     * 不关的话短句也会先想很久。业务方显式传入 thinking 时原样保留，包括打开思考。
     * null 和缺字段都算没传；空对象算传了，不再补默认值。
     */
    private static void disableThinkingByDefault(ObjectNode body) {
        JsonNode thinking = body.get("thinking");
        if (thinking != null && !thinking.isNull()) {
            return;
        }
        body.putObject("thinking").put("type", "disabled");
    }

    /**
     * 打一次上游并记账。连接失败、超时这类还没拿到 HTTP 状态的情况记 FAILED 再把原异常抛出去。
     * 拿到了状态码，哪怕是 401 或 500，也按结果记账，让重试前的那一次调用留在表里。
     */
    private InvokeResult call(ProtocolAdapter adapter, InvokeCommand command, HttpServletResponse response,
                               Selection selection) throws IOException {
        InvokeResult result;
        try {
            result = adapter.invoke(command, response);
        } catch (RuntimeException | IOException ex) {
            CallRecords.recordFailure(tasks, selection);
            throw ex;
        }
        CallRecords.record(tasks, mapper, selection, result);
        return result;
    }

    private static InvokeCommand command(Selection selection, ObjectNode body, Duration timeout, boolean stream) {
        return new InvokeCommand(AdapterOperation.CHAT_COMPLETION, selection, body, timeout, stream, null);
    }
}
