package com.llmgateway.admin;

import com.llmgateway.admin.AdminPayloads.AccountBody;
import com.llmgateway.admin.AdminPayloads.AccountView;
import com.llmgateway.admin.AdminPayloads.AdapterView;
import com.llmgateway.admin.AdminPayloads.InvocationBody;
import com.llmgateway.admin.AdminPayloads.KeyBody;
import com.llmgateway.admin.AdminPayloads.KeyView;
import com.llmgateway.admin.AdminPayloads.ModelBody;
import com.llmgateway.admin.AdminPayloads.ModelView;
import com.llmgateway.admin.AdminPayloads.ProviderBody;
import com.llmgateway.admin.AdminPayloads.ProviderView;
import com.llmgateway.admin.AdminPayloads.RateLimitBody;
import com.llmgateway.error.ErrorTypes;
import com.llmgateway.error.GatewayException;
import com.llmgateway.routing.ModelCatalog;
import com.llmgateway.upstream.AdapterRegistry;
import com.llmgateway.upstream.ProtocolAdapter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 管理写操作。可调度账号的定义直接问 {@link ModelCatalog}，和调用时的选路用同一套条件。
 */
@Service
public class AdminService {

    private final AdminStore store;
    private final ModelCatalog catalog;
    private final AdapterRegistry adapters;

    public AdminService(AdminStore store, ModelCatalog catalog, AdapterRegistry adapters) {
        this.store = store;
        this.catalog = catalog;
        this.adapters = adapters;
    }

    public List<AdapterView> adapters() {
        return adapters.all().stream()
                .map(adapter -> new AdapterView(adapter.name(), adapter.label(), adapter.operationNames()))
                .toList();
    }

    public List<ProviderView> providers() {
        return store.providers().stream().map(AdminService::providerView).toList();
    }

    @Transactional
    public ProviderView createProvider(ProviderBody body) {
        StoredProvider row = new StoredProvider();
        row.setProviderName(requiredName(body == null ? null : body.providerName(), "供应商名称"));
        write(() -> store.insertProvider(row));
        return providerView(requireProvider(row.getId()));
    }

    @Transactional
    public ProviderView updateProvider(int id, ProviderBody body) {
        StoredProvider row = requireProvider(id);
        row.setProviderName(requiredName(body == null ? null : body.providerName(), "供应商名称"));
        write(() -> store.updateProvider(row));
        return providerView(requireProvider(id));
    }

    public List<AccountView> accounts(Integer providerId) {
        if (providerId != null) {
            requireProvider(providerId);
        }
        return store.accounts(providerId).stream().map(AdminService::accountView).toList();
    }

    @Transactional
    public AccountView createAccount(AccountBody body) {
        if (body == null || body.providerId() == null) {
            throw bad("缺少供应商");
        }
        requireProvider(body.providerId());
        StoredAccount row = new StoredAccount();
        row.setProviderId(body.providerId());
        row.setAccountName(requiredName(body.accountName(), "账号名称"));
        row.setEnabled(flag(body.enabled(), 1));
        write(() -> store.insertAccount(row));
        return accountView(requireAccount(row.getId()));
    }

    @Transactional
    public AccountView updateAccount(int id, AccountBody body) {
        StoredAccount row = requireAccount(id);
        row.setAccountName(requiredName(body == null ? null : body.accountName(), "账号名称"));
        row.setEnabled(flag(body == null ? null : body.enabled(), row.getEnabled()));
        write(() -> store.updateAccount(row));
        return accountView(requireAccount(id));
    }

    public List<KeyView> keys(int accountId) {
        requireAccount(accountId);
        return store.keys(accountId).stream().map(AdminService::keyView).toList();
    }

    @Transactional
    public KeyView createKey(int accountId, KeyBody body) {
        requireAccount(accountId);
        if (body == null || !StringUtils.hasText(body.apiKey()) || ApiKeys.keepStored(body.apiKey())) {
            throw bad("请填写 API Key");
        }
        StoredKey row = new StoredKey();
        row.setProviderAccountId(accountId);
        row.setKeyName(requiredName(body.keyName(), "密钥名称"));
        row.setApiKey(body.apiKey().trim());
        row.setEnabled(flag(body.enabled(), 1));
        write(() -> store.insertKey(row));
        return keyView(requireKey(row.getId()));
    }

    @Transactional
    public KeyView updateKey(int id, KeyBody body) {
        StoredKey row = requireKey(id);
        row.setKeyName(requiredName(body == null ? null : body.keyName(), "密钥名称"));
        row.setEnabled(flag(body == null ? null : body.enabled(), row.getEnabled()));
        boolean replace = body != null && !ApiKeys.keepStored(body.apiKey());
        if (replace) {
            row.setApiKey(body.apiKey().trim());
        }
        write(() -> store.updateKey(row, replace));
        return keyView(requireKey(id));
    }

    public List<ModelView> models() {
        return store.models().stream().map(this::modelView).toList();
    }

    @Transactional
    public ModelView createModel(ModelBody body) {
        StoredModel row = draft(null, body);
        write(() -> store.insertModel(row));
        replaceAccounts(row.getId(), row.getProviderId(), body.accountIds());
        if (row.getLoadBalanceEnabled() == 1) {
            ensureLoadBalance(row.getId(), row.getProviderId());
        }
        return modelView(requireModel(row.getId()));
    }

    @Transactional
    public ModelView updateModel(int id, ModelBody body) {
        StoredModel current = requireModel(id);
        StoredModel row = draft(current, body);
        row.setId(id);
        write(() -> store.updateModel(row));
        if (body != null && body.accountIds() != null) {
            replaceAccounts(id, row.getProviderId(), body.accountIds());
        }
        if (row.getLoadBalanceEnabled() == 1) {
            ensureLoadBalance(id, row.getProviderId());
        }
        return modelView(requireModel(id));
    }

    @Transactional
    public ModelView updateInvocation(int id, InvocationBody body) {
        StoredModel row = requireModel(id);
        if (body == null) {
            throw bad("缺少调用配置");
        }
        row.setAdapterName(requiredAdapter(body.adapterName()));
        row.setUpstreamModelName(blankToNull(body.upstreamModelName()));
        row.setBaseUrl(requiredUrl(body.baseUrl()));
        write(() -> store.updateInvocation(row));
        return modelView(requireModel(id));
    }

    @Transactional
    public ModelView updateLoadBalance(int id, Boolean enabled) {
        StoredModel row = requireModel(id);
        boolean turnOn = Boolean.TRUE.equals(enabled);
        if (turnOn) {
            ensureLoadBalance(row.getId(), row.getProviderId());
        }
        store.updateLoadBalance(id, turnOn ? 1 : 0);
        return modelView(requireModel(id));
    }

    @Transactional
    public ModelView updateRateLimit(int id, RateLimitBody body) {
        requireModel(id);
        boolean enabled = body != null && Boolean.TRUE.equals(body.enabled());
        Integer rpm = normalizeLimit(body == null ? null : body.rpm());
        if (enabled) {
            ensureRateLimit(rpm);
        }
        store.updateRateLimit(id, enabled ? 1 : 0, rpm, null);
        return modelView(requireModel(id));
    }

    private StoredModel draft(StoredModel current, ModelBody body) {
        if (body == null) {
            throw bad("缺少模型");
        }
        int providerId = body.providerId() == null && current != null ? current.getProviderId() : requiredId(body.providerId());
        requireProvider(providerId);
        StoredModel row = new StoredModel();
        row.setProviderId(providerId);
        row.setModelName(requiredName(body.modelName(), "模型名称"));
        row.setAdapterName(requiredAdapter(body.adapterName()));
        row.setUpstreamModelName(blankToNull(body.upstreamModelName()));
        row.setBaseUrl(requiredUrl(body.baseUrl()));
        row.setEnabled(flag(body.enabled(), current == null ? 1 : current.getEnabled()));
        boolean loadBalance = body.loadBalanceEnabled() == null
                ? current != null && current.getLoadBalanceEnabled() == 1
                : body.loadBalanceEnabled();
        boolean rateLimit = body.rateLimitEnabled() == null
                ? current != null && current.getRateLimitEnabled() == 1
                : body.rateLimitEnabled();
        Integer rpm = body.rpm() == null && current != null ? current.getRpm() : normalizeLimit(body.rpm());
        if (rateLimit) {
            ensureRateLimit(rpm);
        }
        row.setLoadBalanceEnabled(loadBalance ? 1 : 0);
        row.setRateLimitEnabled(rateLimit ? 1 : 0);
        row.setRpm(rpm);
        row.setTpm(null);
        return row;
    }

    private void replaceAccounts(int modelId, int providerId, List<Integer> accountIds) {
        List<Integer> ids = accountIds == null ? List.of() : accountIds;
        for (Integer accountId : ids) {
            if (accountId == null) {
                throw bad("账号不属于该供应商");
            }
            StoredAccount account = store.account(accountId);
            if (account == null || account.getProviderId() != providerId) {
                throw bad("账号不属于该供应商");
            }
        }
        store.replaceAccounts(modelId, ids);
    }

    private void ensureLoadBalance(int modelId, int providerId) {
        if (catalog.findEligibleAccounts(modelId, providerId).size() < 2) {
            throw bad("可用账号不足两个，不能开启负载均衡");
        }
    }

    private static void ensureRateLimit(Integer rpm) {
        if (rpm == null || rpm <= 0) {
            throw bad("开启限流时必须填写正数 RPM");
        }
    }

    private ModelView modelView(StoredModel model) {
        return new ModelView(
                model.getId(),
                model.getProviderId(),
                model.getProviderName(),
                model.getModelName(),
                model.getAdapterName(),
                model.getUpstreamModelName(),
                model.getBaseUrl(),
                model.getEnabled(),
                model.getLoadBalanceEnabled() == 1,
                model.getRateLimitEnabled() == 1,
                model.getRpm(),
                model.getTpm(),
                model.getConsumedTokens(),
                catalog.findEligibleAccounts(model.getId(), model.getProviderId()).size(),
                List.copyOf(store.accountIds(model.getId())));
    }

    private String requiredAdapter(String adapterName) {
        if (!StringUtils.hasText(adapterName)) {
            throw bad("缺少协议适配器");
        }
        ProtocolAdapter adapter = adapters.find(adapterName.trim()).orElse(null);
        if (adapter == null) {
            throw bad("不支持的协议适配器");
        }
        return adapter.name();
    }

    private StoredProvider requireProvider(int id) {
        StoredProvider row = store.provider(id);
        if (row == null) {
            throw new GatewayException(404, ErrorTypes.NOT_FOUND, "供应商不存在");
        }
        return row;
    }

    private StoredAccount requireAccount(int id) {
        StoredAccount row = store.account(id);
        if (row == null) {
            throw new GatewayException(404, ErrorTypes.NOT_FOUND, "账号不存在");
        }
        return row;
    }

    private StoredKey requireKey(int id) {
        StoredKey row = store.key(id);
        if (row == null) {
            throw new GatewayException(404, ErrorTypes.NOT_FOUND, "密钥不存在");
        }
        return row;
    }

    private StoredModel requireModel(int id) {
        StoredModel row = store.model(id);
        if (row == null) {
            throw new GatewayException(404, ErrorTypes.NOT_FOUND, "模型不存在");
        }
        return row;
    }

    private static void write(Runnable action) {
        try {
            action.run();
        } catch (DataIntegrityViolationException ex) {
            throw bad("名称或密钥已存在");
        }
    }

    private static ProviderView providerView(StoredProvider row) {
        return new ProviderView(row.getId(), row.getProviderName());
    }

    private static AccountView accountView(StoredAccount row) {
        return new AccountView(row.getId(), row.getProviderId(), row.getAccountName(), row.getEnabled());
    }

    private static KeyView keyView(StoredKey row) {
        return new KeyView(row.getId(), row.getProviderAccountId(), row.getKeyName(),
                ApiKeys.mask(row.getApiKey()), row.getEnabled());
    }

    private static String requiredName(String value, String label) {
        if (!StringUtils.hasText(value)) {
            throw bad("缺少" + label);
        }
        return value.trim();
    }

    private static String requiredUrl(String value) {
        if (!StringUtils.hasText(value)) {
            throw bad("缺少模型调用 URL");
        }
        return value.trim();
    }

    private static int requiredId(Integer id) {
        if (id == null) {
            throw bad("缺少供应商");
        }
        return id;
    }

    private static int flag(Integer value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value != 0 && value != 1) {
            throw bad("启用状态只能是 0 或 1");
        }
        return value;
    }

    private static Integer normalizeLimit(Integer value) {
        if (value == null || value == 0) {
            return null;
        }
        if (value < 0) {
            throw bad("限流数值必须大于 0");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static GatewayException bad(String message) {
        return new GatewayException(400, ErrorTypes.INVALID_REQUEST, message);
    }
}
