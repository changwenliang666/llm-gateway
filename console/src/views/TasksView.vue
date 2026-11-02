<script setup>
import { onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import { errorMessage, http, paths } from "../api/http";

  const filters = reactive({
  modelName: "",
  taskStatus: "",
  accountId: "",
  from: null,
  to: null
});
const page = ref(1);
const size = ref(20);
const total = ref(0);
const items = ref([]);
const loading = ref(false);

function params() {
  const query = { page: page.value, size: size.value };
  if (filters.modelName.trim()) query.modelName = filters.modelName.trim();
  if (filters.taskStatus.trim()) query.taskStatus = filters.taskStatus.trim();
  if (String(filters.accountId).trim()) query.accountId = Number(filters.accountId);
  if (filters.from) query.from = filters.from;
  if (filters.to) query.to = filters.to;
  return query;
}

async function load() {
  loading.value = true;
  try {
    const { data } = await http.get(paths.tasks, { params: params() });
    total.value = data.total;
    page.value = data.page;
    size.value = data.size;
    items.value = data.items;
  } catch (error) {
    items.value = [];
    total.value = 0;
    ElMessage.error(errorMessage(error));
  } finally {
    loading.value = false;
  }
}

async function search() {
  page.value = 1;
  await load();
}

async function onPage(next) {
  page.value = next;
  await load();
}

async function onSize(next) {
  size.value = next;
  page.value = 1;
  await load();
}

onMounted(load);
</script>

<template>
  <div v-loading="loading">
    <form class="panel filters" @submit.prevent="search">
      <label class="field"><span>模型</span><el-input v-model="filters.modelName" placeholder="精确匹配" /></label>
      <label class="field"><span>状态</span><el-input v-model="filters.taskStatus" placeholder="例如 SUCCEEDED" /></label>
      <label class="field"><span>账号 ID</span><el-input v-model="filters.accountId" placeholder="精确匹配" /></label>
      <label class="field">
        <span>从</span>
        <el-date-picker v-model="filters.from" type="date" value-format="YYYY-MM-DD" placeholder="开始日期" />
      </label>
      <label class="field">
        <span>到</span>
        <el-date-picker v-model="filters.to" type="date" value-format="YYYY-MM-DD" placeholder="结束日期" />
      </label>
      <el-button type="primary" native-type="submit">查询</el-button>
    </form>
    <div class="panel">
      <el-table class="tech-table" :data="items" empty-text="这个范围内没有调用任务。">
        <el-table-column prop="taskId" label="任务号" min-width="220" />
        <el-table-column prop="modelName" label="模型" min-width="140" />
        <el-table-column prop="accountName" label="账号" min-width="140" />
        <el-table-column prop="accountId" label="账号 ID" width="100" />
        <el-table-column prop="taskStatus" label="状态" width="140" />
        <el-table-column prop="createdTime" label="创建时间" min-width="180" />
      </el-table>
      <div class="pager">
        <el-pagination
          background
          layout="total, sizes, prev, pager, next"
          :page-sizes="[20, 50, 100]"
          :total="total"
          :page-size="size"
          :current-page="page"
          @current-change="onPage"
          @size-change="onSize"
        />
      </div>
    </div>
  </div>
</template>
