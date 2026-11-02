<script setup>
import { computed, onMounted, reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { errorMessage, http, paths } from "../api/http";
import { formatTokens } from "../stats";

const providers = ref([]);
const accounts = ref([]);
const adapters = ref([]);
const models = ref([]);
const loading = ref(false);
const query = ref("");
const providerFilter = ref("");
const selectedId = ref(null);
const creating = ref(false);
const tab = ref("call");
const checkedAccounts = ref([]);
const balanceEnabled = ref(false);

const createForm = reactive({
  modelName: "",
  providerId: "",
  baseUrl: "",
  adapterName: "",
  upstreamModelName: ""
});
const callForm = reactive({ adapterName: "", upstreamModelName: "", baseUrl: "" });
const limitForm = reactive({ enabled: false, rpm: "" });

const visible = computed(() => models.value.filter((item) => {
  const providerOk = !providerFilter.value || String(item.providerId) === providerFilter.value;
  const queryOk = !query.value || item.modelName.toLowerCase().includes(query.value.trim().toLowerCase());
  return providerOk && queryOk;
}));
const selected = computed(() => models.value.find((item) => item.id === selectedId.value) || null);
const relatedAccounts = computed(() => accounts.value.filter((item) => selected.value && item.providerId === selected.value.providerId));

function payload(model, patch = {}) {
  return {
    providerId: model.providerId,
    modelName: model.modelName,
    adapterName: model.adapterName,
    upstreamModelName: model.upstreamModelName,
    baseUrl: model.baseUrl,
    enabled: model.enabled,
    loadBalanceEnabled: model.loadBalanceEnabled,
    rateLimitEnabled: model.rateLimitEnabled,
    rpm: model.rpm,
    ...patch
  };
}

function sync(model) {
  if (!model) return;
  callForm.adapterName = model.adapterName;
  callForm.upstreamModelName = model.upstreamModelName || "";
  callForm.baseUrl = model.baseUrl;
  checkedAccounts.value = [...(model.accountIds || [])];
  balanceEnabled.value = model.loadBalanceEnabled;
  limitForm.enabled = model.rateLimitEnabled;
  limitForm.rpm = model.rpm ?? "";
}

watch(selected, (model) => sync(model), { immediate: true });

async function load() {
  loading.value = true;
  try {
    const [providerResult, accountResult, adapterResult, modelResult] = await Promise.all([
      http.get(paths.providers),
      http.get(paths.accounts),
      http.get(paths.adapters),
      http.get(paths.models)
    ]);
    providers.value = providerResult.data;
    accounts.value = accountResult.data;
    adapters.value = adapterResult.data;
    models.value = modelResult.data;
    if (!createForm.providerId && providers.value[0]) createForm.providerId = String(providers.value[0].id);
    if (!createForm.adapterName && adapters.value[0]) createForm.adapterName = adapters.value[0].name;
    if (selectedId.value && !models.value.some((item) => item.id === selectedId.value)) selectedId.value = null;
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    loading.value = false;
  }
}

async function finish(action, success) {
  try {
    const result = await action();
    if (success) ElMessage.success(success);
    await load();
    return result;
  } catch (error) {
    ElMessage.error(errorMessage(error));
    await load();
    return null;
  }
}

function numberOrNull(value) {
  if (value === "" || value === null || value === undefined) return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

async function createModel() {
  const created = await finish(() => http.post(paths.models, {
    providerId: Number(createForm.providerId),
    modelName: createForm.modelName.trim(),
    baseUrl: createForm.baseUrl.trim(),
    adapterName: createForm.adapterName,
    upstreamModelName: createForm.upstreamModelName.trim(),
    enabled: 1,
    loadBalanceEnabled: false,
    rateLimitEnabled: false,
    rpm: null,
    accountIds: []
  }), "已创建模型");
  if (created) {
    selectedId.value = created.data.id;
    creating.value = false;
    tab.value = "schedule";
    createForm.modelName = "";
    createForm.baseUrl = "";
    createForm.upstreamModelName = "";
  }
}

function toggleEnabled() {
  const model = selected.value;
  return finish(() => http.put(paths.model(model.id), payload(model, { enabled: model.enabled === 1 ? 0 : 1 })));
}

function saveInvocation() {
  const model = selected.value;
  return finish(() => http.put(paths.invocation(model.id), {
    adapterName: callForm.adapterName,
    upstreamModelName: callForm.upstreamModelName.trim(),
    baseUrl: callForm.baseUrl.trim()
  }), "已保存调用配置");
}

function saveSchedule() {
  const model = selected.value;
  const enabled = balanceEnabled.value;
  return finish(async () => {
    await http.put(paths.model(model.id), payload(model, {
      loadBalanceEnabled: false,
      accountIds: checkedAccounts.value
    }));
    await http.put(paths.loadBalance(model.id), { enabled });
  }, "已保存调度");
}

function saveLimit() {
  const model = selected.value;
  return finish(() => http.put(paths.rateLimit(model.id), {
    enabled: limitForm.enabled,
    rpm: numberOrNull(limitForm.rpm)
  }), "已保存限流");
}

onMounted(load);
</script>

<template>
  <div v-loading="loading" class="split">
    <div class="stack">
      <div class="panel stack">
        <label class="field">
          <span>搜索</span>
          <el-input v-model="query" placeholder="模型名" />
        </label>
        <label class="field">
          <span>供应商</span>
          <el-select v-model="providerFilter" clearable placeholder="全部供应商">
            <el-option v-for="item in providers" :key="item.id" :label="item.providerName" :value="String(item.id)" />
          </el-select>
        </label>
        <el-button type="primary" @click="creating = true; selectedId = null">新建模型</el-button>
        <el-button @click="load">刷新</el-button>
      </div>
      <div class="panel stack">
        <button
          v-for="item in visible"
          :key="item.id"
          type="button"
          class="list-button"
          :class="{ active: item.id === selectedId && !creating }"
          @click="creating = false; selectedId = item.id; tab = 'call'"
        >
          {{ item.modelName }}
          <small>{{ item.providerName }} · {{ formatTokens(item.consumedTokens) }} Token</small>
        </button>
        <p v-if="!visible.length" class="note">没有匹配的模型。</p>
      </div>
    </div>
    <div class="panel">
      <form v-if="creating" class="stack" @submit.prevent="createModel">
        <h2>新建模型</h2>
        <label class="field"><span>模型名称</span><el-input v-model="createForm.modelName" /></label>
        <label class="field">
          <span>供应商</span>
          <el-select v-model="createForm.providerId">
            <el-option v-for="item in providers" :key="item.id" :label="item.providerName" :value="String(item.id)" />
          </el-select>
        </label>
        <label class="field"><span>调用 URL</span><el-input v-model="createForm.baseUrl" placeholder="https://..." /></label>
        <label class="field">
          <span>协议</span>
          <el-select v-model="createForm.adapterName">
            <el-option v-for="item in adapters" :key="item.name" :label="item.label || item.name" :value="item.name" />
          </el-select>
        </label>
        <label class="field"><span>上游模型名</span><el-input v-model="createForm.upstreamModelName" placeholder="空则与模型名相同" /></label>
        <el-button type="primary" native-type="submit">创建</el-button>
        <p class="note">创建后到「调度」里勾选账号。账号不足两个时不能打开负载均衡。</p>
      </form>
      <p v-else-if="!selected" class="note">从左侧选择一个模型，或新建一个。</p>
      <div v-else class="stack">
        <div class="toolbar">
          <div>
            <strong>{{ selected.modelName }}</strong>
            <p class="note">
              {{ selected.enabled === 1 ? "启用" : "停用" }}
              · {{ selected.providerName }}
              · {{ selected.adapterName }}
              · {{ formatTokens(selected.consumedTokens) }} Token
            </p>
          </div>
          <el-button :type="selected.enabled === 1 ? 'danger' : 'primary'" @click="toggleEnabled">
            {{ selected.enabled === 1 ? "停用" : "启用" }}
          </el-button>
        </div>
        <el-tabs v-model="tab">
          <el-tab-pane label="调用" name="call">
            <form class="stack" @submit.prevent="saveInvocation">
              <label class="field">
                <span>协议</span>
                <el-select v-model="callForm.adapterName">
                  <el-option v-for="item in adapters" :key="item.name" :label="item.label || item.name" :value="item.name" />
                </el-select>
              </label>
              <label class="field"><span>上游模型名</span><el-input v-model="callForm.upstreamModelName" placeholder="空则与模型名相同" /></label>
              <label class="field"><span>调用 URL</span><el-input v-model="callForm.baseUrl" /></label>
              <el-button type="primary" native-type="submit">保存调用配置</el-button>
              <p class="note">请求会原样发到这个 URL。协议列表来自当前进程已注册的适配器。</p>
            </form>
          </el-tab-pane>
          <el-tab-pane label="调度" name="schedule">
            <form class="stack" @submit.prevent="saveSchedule">
              <p class="note">当前可调度账号 {{ selected.eligibleAccounts }} 个。少于两个时不能打开轮询。</p>
              <el-checkbox-group v-model="checkedAccounts">
                <el-checkbox v-for="item in relatedAccounts" :key="item.id" :value="item.id">
                  {{ item.accountName }}{{ item.enabled === 1 ? "" : "（账号已停用）" }}
                </el-checkbox>
              </el-checkbox-group>
              <p v-if="!relatedAccounts.length" class="note">这个供应商还没有账号。</p>
              <label class="field">
                <span>负载均衡</span>
                <el-switch v-model="balanceEnabled" active-text="在账号间轮询" inactive-text="固定最小账号" />
              </label>
              <el-button type="primary" native-type="submit">保存调度</el-button>
            </form>
          </el-tab-pane>
          <el-tab-pane label="限流" name="limit">
            <form class="stack" @submit.prevent="saveLimit">
              <label class="field">
                <span>开关</span>
                <el-switch v-model="limitForm.enabled" active-text="开启" inactive-text="关闭" />
              </label>
              <label class="field"><span>RPM，每分钟请求数</span><el-input v-model="limitForm.rpm" placeholder="开启时填写正数" /></label>
              <el-button type="primary" native-type="submit">保存限流</el-button>
              <p class="note">只限制这一个模型最近 60 秒的请求数。开启时必须填写正数 RPM。超限返回 429，不换账号，也不记入熔断。供应商的 TPM 错误按上游正文返回。</p>
            </form>
          </el-tab-pane>
        </el-tabs>
      </div>
    </div>
  </div>
</template>
