package com.llmgateway.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 万相视频的创建和查询。
 *
 * <p>创建和对话几乎同一条链：选路、选账号、调用、失败换下一个账号、把结果记进 {@code model_tasks}。
 * 差别是创建请求会带上百炼要求的异步头，上游马上返回 {@code output.task_id}，网关不等视频生成完。
 * 这个任务号就是表的主键，后面的查询靠它找回创建时的账号。
 *
 * <p>查询不再插入新行，只更新状态。账号也不能换：百炼的任务属于创建它的那个账号，
 * 换到别的账号会查不到。同一个账号有多把 Key 时，可以换下一把 Key 再查一次。
 * 表里没有这条任务时才退回普通轮询，方便任务记录丢失后的单账号补查。
 */
@Service
public class VideoSynthesisService {

    private static final Logger log = LoggerFactory.getLogger(VideoSynthesisService.class);

    private final ObjectMapper mapper;
    private final RouteResolver routes;
    private final AdapterRegistry adapters;
    private final AccountLoadBalancer loadBalancer;
    private final ModelAdmission admission;
    private final TimeoutPolicy timeoutPolicy;
    private final TaskBindingStore bindings;

    public VideoSynthesisService(ObjectMapper mapper, RouteResolver routes, AdapterRegistry adapters,
                                 AccountLoadBalancer loadBalancer, ModelAdmission admission,
                                 TimeoutPolicy timeoutPolicy, TaskBindingStore bindings) {
        this.mapper = mapper;
        this.routes = routes;
        this.adapters = adapters;
        this.loadBalancer = loadBalancer;
        this.admission = admission;
        this.timeoutPolicy = timeoutPolicy;
        this.bindings = bindings;
    }

    /**
     * 创建视频任务。{@code input}、{@code parameters} 原样留给适配器转发，这里只要求有模型名。
     * 两次尝试都记账：第一次失败换账号后，表里会有两行，任务号不同。
     */
    public void create(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ObjectNode body = JsonBodies.object(mapper, request.getInputStream());
        String model = JsonBodies.requiredText(body, "model");
        Duration timeout = timeoutPolicy.resolve(request);
        ModelRoute route = prepare(model, AdapterOperation.VIDEO_CREATE, "该模型不能用于视频接口");
        int estimate = RateWindows.estimate(mapper.writeValueAsBytes(body));
        long started = System.nanoTime();
        Selection first = loadBalancer.select(route);
        Permit permit = admission.enter(route, first.account().accountId(), first.circuitPermit(),
                CallerContext.from(request), estimate);
        ProtocolAdapter adapter = adapters.required(route.adapterName());
        InvokeResult result;
        try {
            result = call(adapter, createCommand(first, body, timeout), response, first);
            // 视频创建始终先缓冲再返回，committed 正常是 false。判断仍留着，避免以后适配器提前写出后被写两遍。
            if (result.retryable() && !result.committed()) {
                Optional<Selection> next = loadBalancer.nextAccount(route, first);
                if (next.isPresent()) {
                    admission.observe(route, first.account().accountId(), permit, result);
                    first = next.get();
                    permit = first.circuitPermit().withReservation(permit.reservationId());
                    result = call(adapter, createCommand(first, body, timeout), response, first);
                }
            }
        } catch (RuntimeException | IOException ex) {
            admission.fail(route, first.account().accountId(), permit);
            throw ex;
        }
        admission.finish(route, first.account().accountId(), permit, result, estimate,
                adapter.reportedTotalTokens(result));
        ResponseWriter.writeBuffered(response, result);
        log.info("调用完成 operation=video-create model={} accountId={} keyId={} status={} elapsedMs={}",
                route.modelName(), first.account().accountId(), first.credential().keyId(),
                result.status(), (System.nanoTime() - started) / 1_000_000L);
    }

    /**
     * 查询已创建的任务。路径里的 taskId 是创建响应的 {@code output.task_id}，
     * 查询参数 model 用来找到协议和地址，因为任务号本身不含适配器信息。
     * 查询失败且正文里没有新状态时，不把仍在跑的任务改成 FAILED。
     */
    public void query(String taskId, String model, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (model == null || model.isBlank()) {
            throw new GatewayException(400, ErrorTypes.INVALID_REQUEST, "缺少模型名");
        }
        Duration timeout = timeoutPolicy.resolve(request);
        ModelRoute route = prepare(model, AdapterOperation.VIDEO_QUERY, "该模型不能用于视频查询");
        long started = System.nanoTime();
        // 有记录就锁回创建账号，只在该账号的 Key 之间轮换。没有记录才走普通的账号选择。
        // 创建账号自己的熔断已打开时 503，不改投别的账号。
        Selection first = bindings.findAccountId(taskId)
                .map(accountId -> loadBalancer.selectKey(route, accountId))
                .orElseGet(() -> loadBalancer.select(route));
        Permit permit = admission.enter(route, first.account().accountId(), first.circuitPermit(),
                CallerContext.from(request), 1);
        ProtocolAdapter adapter = adapters.required(route.adapterName());
        InvokeResult result;
        try {
            result = adapter.invoke(queryCommand(first, timeout, taskId), response);
            // 查询不换账号。nextKey 只在同一账号还有另一把 Key 时才有值，并且不会推进账号游标。
            if (result.retryable() && !result.committed()) {
                Optional<Selection> next = loadBalancer.nextKey(first);
                if (next.isPresent()) {
                    result = adapter.invoke(queryCommand(next.get(), timeout, taskId), response);
                    first = next.get();
                }
            }
        } catch (RuntimeException | IOException ex) {
            admission.fail(route, first.account().accountId(), permit);
            throw ex;
        }
        admission.finish(route, first.account().accountId(), permit, result, 1,
                adapter.reportedTotalTokens(result));
        // 查询不是新调用，不能再 insert。只把已有行的状态改成这次看到的结果。
        CallRecords.refreshStatus(bindings, mapper, taskId, result);
        ResponseWriter.writeBuffered(response, result);
        log.info("调用完成 operation=video-query model={} accountId={} keyId={} status={} elapsedMs={}",
                route.modelName(), first.account().accountId(), first.credential().keyId(),
                result.status(), (System.nanoTime() - started) / 1_000_000L);
    }

    private ModelRoute prepare(String model, AdapterOperation operation, String mismatch) {
        ModelRoute route = routes.resolve(model);
        if (!adapters.required(route.adapterName()).supports(operation)) {
            throw new GatewayException(400, ErrorTypes.INVALID_REQUEST, mismatch);
        }
        return route;
    }

    private InvokeResult call(ProtocolAdapter adapter, InvokeCommand command, HttpServletResponse response,
                               Selection selection) throws IOException {
        InvokeResult result;
        try {
            result = adapter.invoke(command, response);
        } catch (RuntimeException | IOException ex) {
            CallRecords.recordFailure(bindings, selection);
            throw ex;
        }
        CallRecords.record(bindings, mapper, selection, result);
        return result;
    }

    private static InvokeCommand createCommand(Selection selection, ObjectNode body, Duration timeout) {
        return new InvokeCommand(AdapterOperation.VIDEO_CREATE, selection, body, timeout, false, null);
    }

    private static InvokeCommand queryCommand(Selection selection, Duration timeout, String taskId) {
        return new InvokeCommand(AdapterOperation.VIDEO_QUERY, selection, null, timeout, false, taskId);
    }
}
