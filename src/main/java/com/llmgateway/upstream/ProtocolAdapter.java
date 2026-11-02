package com.llmgateway.upstream;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;

/**
 * 供应商协议。name 必须与 models.adapter_name 一致。
 * 新供应商实现本接口并交给 Spring 即可，注册表会按名字收集。
 * Google、字节若提供 OpenAI 兼容的 Chat Completions，沿用 openai 适配器，只在模型表写新的地址。
 * 原生协议再新增一个实现。适配器只改协议要求的头和模型名，不负责选账号、限流或熔断。
 */
public interface ProtocolAdapter {

    String name();

    /** 管理页上的名称。不覆盖时与 {@link #name()} 相同。 */
    default String label() {
        return name();
    }

    boolean supports(AdapterOperation operation);

    default List<String> operationNames() {
        return Arrays.stream(AdapterOperation.values()).filter(this::supports).map(Enum::name).toList();
    }

    InvokeResult invoke(InvokeCommand command, HttpServletResponse response) throws IOException;

    /**
     * 上游正文里的总 Token。没有用量字段时返回空，限流保持调用前的估算。
     * 各家 JSON 不同，解析留在适配器里，限流器只接收一个整数。
     */
    default OptionalInt reportedTotalTokens(InvokeResult result) {
        return OptionalInt.empty();
    }
}
