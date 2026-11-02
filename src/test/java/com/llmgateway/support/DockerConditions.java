package com.llmgateway.support;

/** Docker 不可用时集成测试跳过，不把套件打成失败。 */
public final class DockerConditions {

    private DockerConditions() {
    }

    private static boolean reported;

    public static boolean available() {
        boolean ready;
        try {
            ready = org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable ex) {
            ready = false;
        }
        if (!ready && !reported) {
            reported = true;
            System.err.println("Docker 不可用，跳过 GatewayFlowTest。单元测试仍会执行。");
        }
        return ready;
    }
}
