package com.llmgateway.upstream;

import com.llmgateway.error.ErrorTypes;
import com.llmgateway.error.GatewayException;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 启动时收集全部 ProtocolAdapter。同名适配器属于配置错误，直接启动失败。 */
@Component
public class AdapterRegistry {

    private final Map<String, ProtocolAdapter> byName;

    public AdapterRegistry(List<ProtocolAdapter> adapters) {
        this.byName = adapters.stream()
                .collect(Collectors.toUnmodifiableMap(ProtocolAdapter::name, Function.identity()));
    }

    public Optional<ProtocolAdapter> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /** 管理页用。新增适配器 Bean 之后这里自动出现，不用改页面。 */
    public List<ProtocolAdapter> all() {
        return byName.values().stream()
                .sorted(Comparator.comparing(ProtocolAdapter::name))
                .toList();
    }

    public ProtocolAdapter required(String name) {
        return find(name).orElseThrow(
                () -> new GatewayException(400, ErrorTypes.INVALID_REQUEST, "不支持的协议适配器"));
    }
}
