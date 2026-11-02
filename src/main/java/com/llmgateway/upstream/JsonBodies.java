package com.llmgateway.upstream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmgateway.error.ErrorTypes;
import com.llmgateway.error.GatewayException;

import java.io.IOException;
import java.io.InputStream;

/**
 * 请求体只做三件与协议无关的事：读成 JSON 对象、取出必填的模型名、把 {@code model} 换成上游名。
 * {@code message} 收成 {@code messages}、默认关闭思考，都在对话编排里，不放这里，避免视频请求被误改。
 */
public final class JsonBodies {

    private JsonBodies() {
    }

    public static ObjectNode object(ObjectMapper mapper, InputStream input) throws IOException {
        byte[] raw = input.readAllBytes();
        if (raw.length == 0) {
            throw new GatewayException(400, ErrorTypes.INVALID_REQUEST, "请求体不是合法 JSON");
        }
        JsonNode tree;
        try {
            tree = mapper.readTree(raw);
        } catch (IOException ex) {
            throw new GatewayException(400, ErrorTypes.INVALID_REQUEST, "请求体不是合法 JSON");
        }
        if (!(tree instanceof ObjectNode object)) {
            throw new GatewayException(400, ErrorTypes.INVALID_REQUEST, "请求体必须是 JSON 对象");
        }
        return object;
    }

    public static String requiredText(ObjectNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new GatewayException(400, ErrorTypes.INVALID_REQUEST, "缺少模型名");
        }
        return node.asText();
    }

    /**
     * 覆盖调用方传来的 model。调用名和上游名应当相同；仍以表里的上游名为准，
     * 避免调用方在正文里另写一个模型，绕过这次选路用到的地址和 Key。
     */
    public static byte[] replaceModel(ObjectMapper mapper, ObjectNode body, String upstreamModel) throws IOException {
        body.put("model", upstreamModel);
        return mapper.writeValueAsBytes(body);
    }

    /**
     * 可以换账号或换 Key 再试一次的状态。401 多半是这把 Key 无效，429 是这把 Key 被限流，
     * 5xx 是上游暂时失败。400 一类参数错误换账号也还是错，不重试。
     */
    public static boolean retryableStatus(int status) {
        return status == 401 || status == 429 || status >= 500;
    }
}
