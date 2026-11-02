package com.llmgateway.upstream;

import com.llmgateway.config.GatewayProperties;
import com.llmgateway.error.ErrorTypes;
import com.llmgateway.error.GatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

/**
 * 所有适配器共用的 HTTP 出口。适配器决定 URL、方法和头，本类只负责发出去并翻译传输错误。
 *
 * <p>{@code timeout} 是编排层从请求头算出来的整次等待预算，这里不写死 30 秒或 60 秒。
 * 连接超时是另一层更短的上限，取 {@code gateway.connect-timeout-ms}，避免域名不可达时把整段预算都耗在握手上。
 * 不跟随重定向，防止 Key 被带到另一个主机。
 *
 * <p>超时变成 504，连不上变成 502。这两种都不在适配器里重试，编排层只重试带 HTTP 状态的 401、429 和 5xx。
 * 日志只记主机名，不记 Authorization，也不把 API Key 写进异常信息。
 */
@Component
public class UpstreamClient {

    private static final Logger log = LoggerFactory.getLogger(UpstreamClient.class);

    private final HttpClient httpClient;

    public UpstreamClient(GatewayProperties properties) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1, properties.getConnectTimeoutMs())))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public UpstreamReply send(URI uri, String method, Map<String, String> headers, byte[] body,
                              Duration timeout, boolean stream) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout);
        headers.forEach(builder::header);
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.POST(HttpRequest.BodyPublishers.ofByteArray(body == null ? new byte[0] : body));
        }
        try {
            if (stream) {
                HttpResponse<InputStream> response = httpClient.send(
                        builder.build(), HttpResponse.BodyHandlers.ofInputStream());
                return new UpstreamReply(response.statusCode(), null, response.body());
            }
            HttpResponse<byte[]> response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new UpstreamReply(response.statusCode(), response.body(), null);
        } catch (HttpTimeoutException ex) {
            log.warn("上游超时 uriHost={}", uri.getHost());
            throw new GatewayException(504, ErrorTypes.SERVER_ERROR, "上游调用超时");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new GatewayException(502, ErrorTypes.SERVER_ERROR, "上游连接失败");
        } catch (IOException ex) {
            log.warn("上游连接失败 uriHost={}", uri.getHost());
            throw new GatewayException(502, ErrorTypes.SERVER_ERROR, "上游连接失败");
        }
    }
}
