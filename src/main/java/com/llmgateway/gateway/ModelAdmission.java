package com.llmgateway.gateway;

import com.llmgateway.error.GatewayException;
import com.llmgateway.routing.CallerContext;
import com.llmgateway.routing.ModelCircuitBreaker;
import com.llmgateway.routing.ModelRoute;
import com.llmgateway.routing.Permit;
import com.llmgateway.routing.RateLimiter;
import com.llmgateway.routing.TokenUsage;
import com.llmgateway.upstream.InvokeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.OptionalInt;
import java.util.UUID;

/**
 * 对话和视频共用的准入。熔断按模型加账号，在选账号时已经拿到名额。
 * 这里接着做模型级限流。限流拒绝会归还这个账号的半开名额，并且不计失败。
 * 新的入口调用这里，不要在适配器里再写一套。
 */
@Component
public class ModelAdmission {

    private static final Logger log = LoggerFactory.getLogger(ModelAdmission.class);

    private final ModelCircuitBreaker breaker;
    private final RateLimiter rates;
    private final TokenUsage usage;

    public ModelAdmission(ModelCircuitBreaker breaker, RateLimiter rates, TokenUsage usage) {
        this.breaker = breaker;
        this.rates = rates;
        this.usage = usage;
    }

    /**
     * 账号已经过了熔断。再查模型级 RPM。TPM 不参与这次判断。
     * 拒绝或计数失败时归还 {@code circuitPermit}，调用方不用再放一次。
     */
    public Permit enter(ModelRoute route, int accountId, Permit circuitPermit, CallerContext caller, int estimatedTokens) {
        try {
            String reservationId = UUID.randomUUID().toString();
            rates.inspect(route, caller, estimatedTokens, reservationId).ifPresent(ex -> {
                throw ex;
            });
            return circuitPermit.withReservation(reservationId);
        } catch (RuntimeException ex) {
            quietlyRelease(route, accountId, circuitPermit);
            throw ex;
        }
    }

    /** 超时、连接失败，以及其它还没形成 HTTP 结果的上游错误。记在真正打过的那个账号上。 */
    public void fail(ModelRoute route, int accountId, Permit permit) {
        try {
            breaker.fail(route.modelId(), accountId, permit);
        } catch (RuntimeException ex) {
            log.warn("记录熔断失败 model={} account={}", route.modelName(), accountId, ex);
        }
    }

    /**
     * 换账号之前，把前一个账号自己的 HTTP 结果记进它的熔断。
     * 不补 Token，也不累计用量。那些只跟最终交还调用方的那一次有关。
     */
    public void observe(ModelRoute route, int accountId, Permit permit, InvokeResult result) {
        applyCircuit(route, accountId, permit, result);
    }

    public void finish(ModelRoute route, int accountId, Permit permit, InvokeResult result,
                       int estimatedTokens, OptionalInt actualTokens) {
        applyCircuit(route, accountId, permit, result);
        if (CallOutcome.of(result) == CallOutcome.SUCCESS) {
            recordUsage(route, actualTokens.orElse(estimatedTokens));
        }
    }

    private void applyCircuit(ModelRoute route, int accountId, Permit permit, InvokeResult result) {
        try {
            switch (CallOutcome.of(result)) {
                case SUCCESS -> breaker.succeed(route.modelId(), accountId, permit);
                case FAILURE -> breaker.fail(route.modelId(), accountId, permit);
                case NEUTRAL -> breaker.release(route.modelId(), accountId, permit);
            }
        } catch (RuntimeException ex) {
            log.warn("回写熔断状态失败 model={} account={}", route.modelName(), accountId, ex);
        }
    }

    /** 累加失败只记日志。调用已经成功，不能再改成 503。这个累计不参与限流。 */
    private void recordUsage(ModelRoute route, int tokens) {
        if (tokens <= 0) {
            return;
        }
        try {
            usage.add(route.modelId(), tokens);
        } catch (RuntimeException ex) {
            log.warn("累计 Token 失败 model={}", route.modelName(), ex);
        }
    }

    private void quietlyRelease(ModelRoute route, int accountId, Permit permit) {
        try {
            breaker.release(route.modelId(), accountId, permit);
        } catch (RuntimeException ex) {
            log.warn("归还熔断名额失败 model={} account={}", route.modelName(), accountId, ex);
        }
    }
}
