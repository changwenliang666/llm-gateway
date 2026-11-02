<script setup>
import { computed, onMounted, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import TechChart from "../components/TechChart.vue";
import { barOption } from "../charts";
import { errorMessage, http, paths } from "../api/http";
import { formatTokens, modelRows, providerRows, sumTokens } from "../stats";

const providers = ref([]);
const models = ref([]);
const providerId = ref("");
const modelId = ref("");
const loading = ref(false);

const providerModels = computed(() => {
  if (!providerId.value) return models.value;
  return models.value.filter((item) => String(item.providerId) === String(providerId.value));
});
const shown = computed(() => {
  if (!modelId.value) return providerModels.value;
  return providerModels.value.filter((item) => String(item.id) === String(modelId.value));
});
const total = computed(() => sumTokens(shown.value));
const modelChart = computed(() => barOption("按模型 Token", modelRows(shown.value)));
const providerChart = computed(() => barOption("按供应商 Token", providerRows(shown.value)));

watch(providerId, () => {
  if (modelId.value && !providerModels.value.some((item) => String(item.id) === String(modelId.value))) {
    modelId.value = "";
  }
});

async function load() {
  loading.value = true;
  const [providerResult, modelResult] = await Promise.allSettled([
    http.get(paths.providers),
    http.get(paths.models)
  ]);
  providers.value = providerResult.status === "fulfilled" ? providerResult.value.data : [];
  models.value = modelResult.status === "fulfilled" ? modelResult.value.data : [];
  const failed = [providerResult, modelResult].find((item) => item.status === "rejected");
  if (failed) ElMessage.error(errorMessage(failed.reason));
  loading.value = false;
}

onMounted(load);
</script>

<template>
  <div v-loading="loading" class="stack">
    <div class="panel filters">
      <label class="field">
        <span>供应商</span>
        <el-select v-model="providerId" clearable placeholder="全部供应商">
          <el-option v-for="item in providers" :key="item.id" :label="item.providerName" :value="String(item.id)" />
        </el-select>
      </label>
      <label class="field">
        <span>模型</span>
        <el-select v-model="modelId" clearable placeholder="全部模型">
          <el-option v-for="item in providerModels" :key="item.id" :label="item.modelName" :value="String(item.id)" />
        </el-select>
      </label>
      <el-button @click="load">刷新</el-button>
    </div>
    <section class="stat-grid">
      <article class="panel stat">
        <span>筛选合计</span>
        <strong class="num">{{ formatTokens(total) }}</strong>
      </article>
    </section>
    <section class="chart-grid">
      <article class="panel"><TechChart :option="providerChart" /></article>
      <article class="panel"><TechChart :option="modelChart" /></article>
    </section>
    <div class="panel">
      <el-table class="tech-table" :data="shown" empty-text="没有匹配的模型。">
        <el-table-column prop="modelName" label="模型" />
        <el-table-column prop="providerName" label="供应商" />
        <el-table-column label="累计 Token">
          <template #default="{ row }"><span class="num">{{ formatTokens(row.consumedTokens) }}</span></template>
        </el-table-column>
      </el-table>
      <p class="note">有 usage.total_tokens 时用真实值，否则用本次预估。失败、限流和熔断不计入。这里没有按次流水。</p>
    </div>
  </div>
</template>
