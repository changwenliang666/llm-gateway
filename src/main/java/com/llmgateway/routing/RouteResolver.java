package com.llmgateway.routing;

import com.llmgateway.error.ErrorTypes;
import com.llmgateway.error.GatewayException;
import com.llmgateway.upstream.AdapterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 把数据库里的模型收成这次请求的 {@link ModelRoute}。
 *
 * <p>快照在返回后不再变。同一次请求的重试继续用这份账号列表，不会因为中途有人停用 Key 而换池。
 * 下一次新请求会重新查库，所以停用马上对新请求生效。
 *
 * <p>拒绝发生在选账号之前，调用方看到的是明确的 404 或 400，而不是一个打到错误地址的上游错误：
 * 模型不存在或 {@code enabled} 不是 1 时 404；{@code adapter_name} 没有对应的适配器 Bean 时 400；
 * 联表后一个可用 Key 都没有时 503。
 */
@Component
public class RouteResolver {

    private final ModelCatalog catalog;
    private final AdapterRegistry adapters;

    public RouteResolver(ModelCatalog catalog, AdapterRegistry adapters) {
        this.catalog = catalog;
        this.adapters = adapters;
    }

    /**
     * 按调用方传入的模型名查库。停用模型仍能查到行，这里单独变成 404，
     * 避免和「从未配置」混成同一种失败，也避免停用模型继续打上游。
     */
    public ModelRoute resolve(String modelName) {
        ModelRow row = catalog.findModel(modelName);
        if (row == null || row.getEnabled() != 1) {
            throw new GatewayException(404, ErrorTypes.NOT_FOUND, "模型不存在或已停用");
        }
        // 适配器是代码里的 Bean。表里写了尚未实现的名字时，在这里拦住，不把请求发给未知协议。
        if (adapters.find(row.getAdapterName()).isEmpty()) {
            throw new GatewayException(400, ErrorTypes.INVALID_REQUEST, "不支持的协议适配器");
        }
        // 候选已经按账号 id、Key id 升序分好组。轮询只在这个顺序上取下标，不再排序。
        List<AccountCandidate> accounts = catalog.findEligibleAccounts(row.getId(), row.getProviderId());
        if (accounts.isEmpty()) {
            throw new GatewayException(503, ErrorTypes.SERVER_ERROR, "没有可用的供应商账号");
        }
        // 调用名应与上游名相同。上游名为空时退回调用名，避免发出 model 为空的请求。
        String upstream = StringUtils.hasText(row.getUpstreamModelName())
                ? row.getUpstreamModelName().trim()
                : row.getModelName();
        return new ModelRoute(
                row.getId(),
                row.getModelName(),
                row.getAdapterName(),
                row.getBaseUrl(),
                upstream,
                accounts,
                row.getLoadBalanceEnabled() == 1,
                row.getRateLimitEnabled() == 1,
                positive(row.getRpm()),
                positive(row.getTpm()));
    }

    /** 空和小于等于 0 的 RPM 表示不限制请求数。TPM 仍留在快照上，准入不读取。 */
    private static Integer positive(Integer value) {
        if (value == null || value <= 0) {
            return null;
        }
        return value;
    }
}
