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
import com.llmgateway.admin.AdminPayloads.SwitchBody;
import com.llmgateway.admin.TaskPayloads.TaskPage;
import com.llmgateway.admin.TaskPayloads.TaskSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理接口。令牌在过滤器里检查，这里只做业务。
 * 配置和任务查询都打到这些路径，不在浏览器里放一份假数据。
 */
@RestController
public class AdminController {

    private final AdminService admin;
    private final TaskQuery taskQuery;

    public AdminController(AdminService admin, TaskQuery taskQuery) {
        this.admin = admin;
        this.taskQuery = taskQuery;
    }

    @GetMapping("/admin/api/adapters")
    public List<AdapterView> adapters() {
        return admin.adapters();
    }

    @GetMapping("/admin/api/providers")
    public List<ProviderView> providers() {
        return admin.providers();
    }

    @PostMapping("/admin/api/providers")
    public ProviderView createProvider(@RequestBody ProviderBody body) {
        return admin.createProvider(body);
    }

    @PutMapping("/admin/api/providers/{id}")
    public ProviderView updateProvider(@PathVariable("id") int id, @RequestBody ProviderBody body) {
        return admin.updateProvider(id, body);
    }

    @GetMapping("/admin/api/accounts")
    public List<AccountView> accounts(@RequestParam(name = "providerId", required = false) Integer providerId) {
        return admin.accounts(providerId);
    }

    @PostMapping("/admin/api/accounts")
    public AccountView createAccount(@RequestBody AccountBody body) {
        return admin.createAccount(body);
    }

    @PutMapping("/admin/api/accounts/{id}")
    public AccountView updateAccount(@PathVariable("id") int id, @RequestBody AccountBody body) {
        return admin.updateAccount(id, body);
    }

    @GetMapping("/admin/api/accounts/{accountId}/keys")
    public List<KeyView> keys(@PathVariable("accountId") int accountId) {
        return admin.keys(accountId);
    }

    @PostMapping("/admin/api/accounts/{accountId}/keys")
    public KeyView createKey(@PathVariable("accountId") int accountId, @RequestBody KeyBody body) {
        return admin.createKey(accountId, body);
    }

    @PutMapping("/admin/api/keys/{id}")
    public KeyView updateKey(@PathVariable("id") int id, @RequestBody KeyBody body) {
        return admin.updateKey(id, body);
    }

    @GetMapping("/admin/api/models")
    public List<ModelView> models() {
        return admin.models();
    }

    @PostMapping("/admin/api/models")
    public ModelView createModel(@RequestBody ModelBody body) {
        return admin.createModel(body);
    }

    @PutMapping("/admin/api/models/{id}")
    public ModelView updateModel(@PathVariable("id") int id, @RequestBody ModelBody body) {
        return admin.updateModel(id, body);
    }

    @PutMapping("/admin/api/models/{id}/invocation")
    public ModelView updateInvocation(@PathVariable("id") int id, @RequestBody InvocationBody body) {
        return admin.updateInvocation(id, body);
    }

    @PutMapping("/admin/api/models/{id}/load-balance")
    public ModelView updateLoadBalance(@PathVariable("id") int id, @RequestBody SwitchBody body) {
        return admin.updateLoadBalance(id, body == null ? null : body.enabled());
    }

    @PutMapping("/admin/api/models/{id}/rate-limit")
    public ModelView updateRateLimit(@PathVariable("id") int id, @RequestBody RateLimitBody body) {
        return admin.updateRateLimit(id, body);
    }

    /**
     * 和 /tasks/summary 分成两个映射。不能写成 /tasks/{taskId}，否则 summary 会被当成任务号。
     */
    @GetMapping("/admin/api/tasks")
    public TaskPage tasks(@RequestParam(name = "modelName", required = false) String modelName,
                          @RequestParam(name = "taskStatus", required = false) String taskStatus,
                          @RequestParam(name = "accountId", required = false) Integer accountId,
                          @RequestParam(name = "from", required = false) String from,
                          @RequestParam(name = "to", required = false) String to,
                          @RequestParam(name = "page", required = false) Integer page,
                          @RequestParam(name = "size", required = false) Integer size) {
        return taskQuery.page(modelName, taskStatus, accountId, from, to, page, size);
    }

    @GetMapping("/admin/api/tasks/summary")
    public TaskSummary taskSummary(@RequestParam(name = "from", required = false) String from,
                                   @RequestParam(name = "to", required = false) String to) {
        return taskQuery.summary(from, to);
    }
}
