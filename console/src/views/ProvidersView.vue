<script setup>
import { onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { errorMessage, http, paths } from "../api/http";

const providers = ref([]);
const name = ref("");
const loading = ref(false);
const drafts = ref({});

async function load() {
  loading.value = true;
  try {
    const { data } = await http.get(paths.providers);
    providers.value = data;
    drafts.value = Object.fromEntries(data.map((item) => [item.id, item.providerName]));
  } catch (error) {
    providers.value = [];
    ElMessage.error(errorMessage(error));
  } finally {
    loading.value = false;
  }
}

async function createProvider() {
  try {
    await http.post(paths.providers, { providerName: name.value.trim() });
    name.value = "";
    ElMessage.success("已添加供应商");
    await load();
  } catch (error) {
    ElMessage.error(errorMessage(error));
    await load();
  }
}

async function save(row) {
  try {
    await http.put(paths.provider(row.id), { providerName: String(drafts.value[row.id] || "").trim() });
    ElMessage.success("已保存");
    await load();
  } catch (error) {
    ElMessage.error(errorMessage(error));
    await load();
  }
}

onMounted(load);
</script>

<template>
  <div v-loading="loading" class="stack">
    <form class="panel filters" @submit.prevent="createProvider">
      <label class="field">
        <span>名称</span>
        <el-input v-model="name" placeholder="例如 dashscope" />
      </label>
      <el-button type="primary" native-type="submit">新增供应商</el-button>
      <el-button @click="load">刷新</el-button>
    </form>
    <div class="panel">
      <el-table class="tech-table" :data="providers" empty-text="还没有供应商。">
        <el-table-column prop="id" label="ID" width="90" />
        <el-table-column label="名称">
          <template #default="{ row }">
            <el-input v-model="drafts[row.id]" />
          </template>
        </el-table-column>
        <el-table-column label="" width="120">
          <template #default="{ row }">
            <el-button type="primary" @click="save(row)">保存</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>
