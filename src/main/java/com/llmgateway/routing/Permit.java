package com.llmgateway.routing;

/**
 * 熔断放行结果。probing 为 true 时，这次请求占着半开名额，结束时必须成功、失败或归还。
 * reservationId 是同一次请求的限流记录。熔断器自己的放行不填它。
 */
public record Permit(boolean allowed, boolean probing, String reservationId) {

    public Permit(boolean allowed, boolean probing) {
        this(allowed, probing, null);
    }

    public static Permit closed() {
        return new Permit(true, false);
    }

    public static Permit probe() {
        return new Permit(true, true);
    }

    public static Permit rejected() {
        return new Permit(false, false);
    }

    public Permit withReservation(String reservationId) {
        return new Permit(allowed, probing, reservationId);
    }
}
