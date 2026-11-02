package com.llmgateway;

import com.llmgateway.routing.AccountCandidate;
import com.llmgateway.routing.ModelCatalogMapper;
import com.llmgateway.routing.ModelRow;
import com.llmgateway.upstream.wan.TaskBindingStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对着真实库验证联表过滤。整个方法在事务里回滚，不留下夹具数据。
 * 默认不跑，避免普通测试依赖外部数据库。
 */
@SpringBootTest
@Transactional
@Rollback
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "live.db", matches = "true")
class CatalogSqlTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ModelCatalogMapper mapper;

    @Autowired
    private TaskBindingStore tasks;

    @Test
    void filtersDisabledLinksAccountsKeysAndOtherProviders() {
        String suffix = Long.toString(System.nanoTime());
        int providerA = insertProvider("it_a_" + suffix);
        int providerB = insertProvider("it_b_" + suffix);
        int enabledAccount = insertAccount(providerA, "it_ok_" + suffix, 1);
        int disabledAccount = insertAccount(providerA, "it_off_account_" + suffix, 0);
        int noKeyAccount = insertAccount(providerA, "it_nokey_" + suffix, 1);
        int otherProviderAccount = insertAccount(providerB, "it_other_" + suffix, 1);
        int unlinked = insertAccount(providerA, "it_unlinked_" + suffix, 1);
        int enabledKey = insertKey(enabledAccount, "it_key_on_" + suffix, 1);
        insertKey(enabledAccount, "it_key_off_" + suffix, 0);
        insertKey(disabledAccount, "it_key_disabled_account_" + suffix, 1);
        insertKey(noKeyAccount, "it_key_all_off_" + suffix, 0);
        insertKey(otherProviderAccount, "it_key_other_" + suffix, 1);
        insertKey(unlinked, "it_key_unlinked_" + suffix, 1);

        int modelId = insertModel(providerA, "it_model_" + suffix, "openai", "upstream-name", 1);
        int disabledModel = insertModel(providerA, "it_disabled_" + suffix, "openai", null, 0);
        int emptyModel = insertModel(providerA, "it_empty_" + suffix, "openai", "   ", 1);
        link(modelId, enabledAccount, 1);
        link(modelId, disabledAccount, 1);
        link(modelId, noKeyAccount, 1);
        link(modelId, otherProviderAccount, 1);
        link(modelId, unlinked, 0);
        link(disabledModel, enabledAccount, 1);

        ModelRow found = mapper.selectByName("it_model_" + suffix);
        assertEquals(1, found.getEnabled());
        assertEquals("upstream-name", found.getUpstreamModelName());
        List<AccountCandidate> pool = com.llmgateway.routing.AccountPool.group(
                mapper.selectCandidates(found.getId(), found.getProviderId()));
        assertEquals(1, pool.size());
        assertEquals(enabledAccount, pool.get(0).accountId());
        assertEquals(List.of(enabledKey), pool.get(0).keys().stream().map(key -> key.keyId()).toList());

        ModelRow disabled = mapper.selectByName("it_disabled_" + suffix);
        assertEquals(0, disabled.getEnabled());
        assertNull(mapper.selectByName("it_missing_" + suffix));
        ModelRow empty = mapper.selectByName("it_empty_" + suffix);
        assertTrue(mapper.selectCandidates(empty.getId(), empty.getProviderId()).isEmpty());
        assertTrue(empty.getUpstreamModelName() == null || empty.getUpstreamModelName().isBlank());
    }

    @Test
    void createdTaskStaysOnTheFirstAccount() {
        String suffix = Long.toString(System.nanoTime());
        int providerId = insertProvider("it_task_provider_" + suffix);
        int first = insertAccount(providerId, "it_task_a_" + suffix, 1);
        int second = insertAccount(providerId, "it_task_b_" + suffix, 1);
        String taskId = "it_task_" + suffix;

        tasks.record(taskId, "wan2.0", first, "PENDING");
        tasks.record(taskId, "wan3.0", second, "RUNNING");
        tasks.record("it_chat_" + suffix, "qwen-max", first, "SUCCEEDED");

        assertEquals(Optional.of(first), tasks.findAccountId(taskId));
        assertEquals("wan2.0", jdbc.queryForObject(
                "SELECT model_name FROM model_tasks WHERE task_id = ?", String.class, taskId));
        assertEquals("RUNNING", jdbc.queryForObject(
                "SELECT task_status FROM model_tasks WHERE task_id = ?", String.class, taskId));
        assertEquals("SUCCEEDED", jdbc.queryForObject(
                "SELECT task_status FROM model_tasks WHERE task_id = ?", String.class, "it_chat_" + suffix));
        assertEquals(Optional.empty(), tasks.findAccountId("it_missing_" + suffix));
    }

    private int insertProvider(String name) {
        jdbc.update("INSERT INTO providers (provider_name) VALUES (?)", name);
        return jdbc.queryForObject("SELECT id FROM providers WHERE provider_name = ?", Integer.class, name);
    }

    private int insertAccount(int providerId, String name, int enabled) {
        jdbc.update("INSERT INTO provider_accounts (provider_id, account_name, enabled) VALUES (?, ?, ?)",
                providerId, name, enabled);
        return jdbc.queryForObject(
                "SELECT id FROM provider_accounts WHERE provider_id = ? AND account_name = ?",
                Integer.class, providerId, name);
    }

    private int insertKey(int accountId, String name, int enabled) {
        jdbc.update("INSERT INTO account_keys (provider_account_id, key_name, api_key, enabled) VALUES (?, ?, ?, ?)",
                accountId, name, "secret-" + name, enabled);
        return jdbc.queryForObject(
                "SELECT id FROM account_keys WHERE provider_account_id = ? AND key_name = ?",
                Integer.class, accountId, name);
    }

    private int insertModel(int providerId, String name, String adapter, String upstream, int enabled) {
        jdbc.update("""
                INSERT INTO models (provider_id, model_name, adapter_name, upstream_model_name, base_url, enabled)
                VALUES (?, ?, ?, ?, ?, ?)
                """, providerId, name, adapter, upstream, "http://fixture.invalid/upstream", enabled);
        return jdbc.queryForObject("SELECT id FROM models WHERE model_name = ?", Integer.class, name);
    }

    private void link(int modelId, int accountId, int enabled) {
        jdbc.update("INSERT INTO model_accounts (model_id, provider_account_id, enabled) VALUES (?, ?, ?)",
                modelId, accountId, enabled);
    }
}
