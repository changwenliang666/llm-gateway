<script setup>
import { computed, onMounted, ref } from "vue";
import TechChart from "../components/TechChart.vue";
import { barOption, lineOption, statusOption } from "../charts";
import { errorMessage, http, paths } from "../api/http";
import { formatTokens, modelRows, providerRows, sumTokens } from "../stats";
import { ElMessage } from "element-plus";

const providers = ref([]);
const accounts = ref([]);
const models = ref([]);
const summary = ref({ from: "", to: "", byStatus: [], byDay: [] });
const loading = ref(false);

const enabledModels = computed(() => models.value.filter((item) => item.enabled === 1).length);
const tokenTotal = computed(() => sumTokens(models.value));
const modelChart = computed(() => barOption("按模型 Token", modelRows(models.value)));
const providerChart = computed(() => barOption("按供应商 Token", providerRows(models.value)));
const statusChart = computed(() => statusOption("任务状态", (summary.value.byStatus || []).map((item) => ({
  name: item.status,
  value: item.count
}))));
const dayChart = computed(() => lineOption("每日任务", (summary.value.byDay || []).map((item) => ({
  name: item.date,
  value: item.count
}))));

async function load() {
  loading.value = true;
  const [modelResult, summaryResult, providerResult, accountResult] = await Promise.allSettled([
    http.get(paths.models),
    http.get(paths.taskSummary),
    http.get(paths.providers),
    http.get(paths.accounts)
  ]);
  models.value = modelResult.status === "fulfilled" ? modelResult.value.data : [];
  summary.value = summaryResult.status === "fulfilled"
    ? summaryResult.value.data
    : { from: "", to: "", byStatus: [], byDay: [] };
  providers.value = providerResult.status === "fulfilled" ? providerResult.value.data : [];
  accounts.value = accountResult.status === "fulfilled" ? accountResult.value.data : [];
  const failed = [modelResult, summaryResult, providerResult, accountResult].find((item) => item.status === "rejected");
  if (failed) ElMessage.error(errorMessage(failed.reason));
  loading.value = false;
}

onMounted(load);
</script>

<template>
  <div v-loading="loading">
    <div class="toolbar">
      <el-button @click="load">刷新</el-button>
      <span v-if="summary.from" class="note">任务窗口 {{ summary.from }} 至 {{ summary.to }}</span>
    </div>
    <section class="stat-grid">
      <article class="panel stat"><span>供应商</span><strong class="num">{{ providers.length }}</strong></article>
      <article class="panel stat"><span>账号</span><strong class="num">{{ accounts.length }}</strong></article>
      <article class="panel stat"><span>启用中的模型</span><strong class="num">{{ enabledModels }}</strong></article>
      <article class="panel stat"><span>累计 Token</span><strong class="num">{{ formatTokens(tokenTotal) }}</strong></article>
    </section>
    <section class="chart-grid">
      <article class="panel"><TechChart :option="modelChart" /></article>
      <article class="panel"><TechChart :option="providerChart" /></article>
      <article class="panel"><TechChart :option="statusChart" /></article>
      <article class="panel"><TechChart :option="dayChart" /></article>
    </section>
  </div>
</template>
