<script setup>
import { computed, onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { errorMessage, http, paths } from "../api/http";

const providers = ref([]);
const accounts = ref([]);
const providerId = ref("");
const accountName = ref("");
const selectedId = ref(null);
const keys = ref([]);
const loading = ref(false);

const visibleAccounts = computed(() => accounts.value.filter((item) => String(item.providerId) === String(providerId.value)));
const selected = computed(() => accounts.value.find((item) => item.id === selectedId.value) || null);

async function loadAccounts() {
  const [providerResult, accountResult] = await Promise.all([
    http.get(paths.providers),
    http.get(paths.accounts)
  ]);
  providers.value = providerResult.data;
  accounts.value = accountResult.data;
  if (!providerId.value && providers.value[0]) providerId.value = String(providers.value[0].id);
  if (selected.value && String(selected.value.providerId) !== String(providerId.value)) {
    selectedId.value = null;
    keys.value = [];
  }
}

async function load() {
  loading.value = true;
  try {
    await loadAccounts();
    if (selectedId.value) await loadKeys(selectedId.value);
  } catch (error) {
    providers.value = [];
    accounts.value = [];
    keys.value = [];
    ElMessage.error(errorMessage(error));
  } finally {
    loading.value = false;
  }
}

async function loadKeys(accountId) {
  try {
    const { data } = await http.get(paths.keys(accountId));
    keys.value = data.map((item) => ({ ...item, secret: "" }));
  } catch (error) {
    keys.value = [];
    ElMessage.error(errorMessage(error));
  }
}

async function selectAccount(id) {
  selectedId.value = id;
  await loadKeys(id);
}

async function createAccount() {
  try {
    await http.post(paths.accounts, {
      providerId: Number(providerId.value),
      accountName: accountName.value.trim(),
      enabled: 1
    });
    accountName.value = "";
    ElMessage.success("已添加账号");
  } catch (error) {
    ElMessage.error(errorMessage(error));
  }
  await load();
}

async function toggleAccount() {
  const account = selected.value;
  if (!account) return;
  try {
    await http.put(paths.account(account.id), {
      providerId: account.providerId,
      accountName: account.accountName,
      enabled: account.enabled === 1 ? 0 : 1
    });
  } catch (error) {
    ElMessage.error(errorMessage(error));
  }
  await load();
}

async function saveKey(key) {
  try {
    await http.put(paths.key(key.id), {
      keyName: key.keyName,
      apiKey: key.secret || "",
      enabled: key.enabled
    });
    ElMessage.success("已保存密钥");
  } catch (error) {
    ElMessage.error(errorMessage(error));
  }
  await loadKeys(selectedId.value);
}

const newKey = ref({ keyName: "", apiKey: "" });

async function createKey() {
  try {
    await http.post(paths.keys(selectedId.value), {
      keyName: newKey.value.keyName.trim(),
      apiKey: newKey.value.apiKey,
      enabled: 1
    });
    newKey.value = { keyName: "", apiKey: "" };
    ElMessage.success("已添加密钥");
  } catch (error) {
    ElMessage.error(errorMessage(error));
  }
  await loadKeys(selectedId.value);
}

onMounted(load);
</script>

<template>
  <div v-loading="loading" class="stack">
    <div class="panel filters">
      <label class="field">
        <span>供应商</span>
        <el-select v-model="providerId" @change="selectedId = null; keys = []">
          <el-option v-for="item in providers" :key="item.id" :label="item.providerName" :value="String(item.id)" />
        </el-select>
      </label>
      <el-button @click="load">刷新</el-button>
    </div>
    <div class="split">
      <div class="stack">
        <form class="panel stack" @submit.prevent="createAccount">
          <label class="field">
            <span>新账号名称</span>
            <el-input v-model="accountName" />
          </label>
          <el-button type="primary" native-type="submit">新增账号</el-button>
        </form>
        <div class="panel stack">
          <button
            v-for="item in visibleAccounts"
            :key="item.id"
            type="button"
            class="list-button"
            :class="{ active: item.id === selectedId }"
            @click="selectAccount(item.id)"
          >
            {{ item.accountName }}
            <small>{{ item.enabled === 1 ? "启用" : "停用" }} · #{{ item.id }}</small>
          </button>
          <p v-if="!visibleAccounts.length" class="note">这个供应商还没有账号。</p>
        </div>
      </div>
      <div class="panel">
        <p v-if="!selected" class="note">从左侧选择一个账号，查看和新增密钥。</p>
        <template v-else>
          <div class="toolbar">
            <div>
              <strong>{{ selected.accountName }}</strong>
              <p class="note">{{ selected.enabled === 1 ? "账号启用" : "账号停用" }}</p>
            </div>
            <el-button :type="selected.enabled === 1 ? 'danger' : 'primary'" @click="toggleAccount">
              {{ selected.enabled === 1 ? "停用账号" : "启用账号" }}
            </el-button>
          </div>
          <el-table class="tech-table" :data="keys" empty-text="还没有密钥。没有启用的密钥时，这个账号不会被选中。">
            <el-table-column label="名称" min-width="140">
              <template #default="{ row }">
                <el-input v-model="row.keyName" />
              </template>
            </el-table-column>
            <el-table-column prop="apiKeyMask" label="密钥" min-width="140" />
            <el-table-column label="更换密钥" min-width="180">
              <template #default="{ row }">
                <el-input v-model="row.secret" type="password" show-password placeholder="留空则不更换" autocomplete="new-password" />
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-switch v-model="row.enabled" :active-value="1" :inactive-value="0" />
              </template>
            </el-table-column>
            <el-table-column width="100">
              <template #default="{ row }">
                <el-button type="primary" @click="saveKey(row)">保存</el-button>
              </template>
            </el-table-column>
          </el-table>
          <form class="filters" style="margin-top: 16px" @submit.prevent="createKey">
            <label class="field">
              <span>密钥名称</span>
              <el-input v-model="newKey.keyName" />
            </label>
            <label class="field">
              <span>新密钥</span>
              <el-input v-model="newKey.apiKey" type="password" show-password autocomplete="new-password" />
            </label>
            <el-button type="primary" native-type="submit">新增密钥</el-button>
          </form>
          <p class="note">密钥只在新增或更换时提交。列表里只显示掩码。</p>
        </template>
      </div>
    </div>
  </div>
</template>
