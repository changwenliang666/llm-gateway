<script setup>
import { ref } from "vue";
import { useRouter } from "vue-router";
import { errorMessage, http, paths } from "../api/http";
import { useSessionStore } from "../stores/session";

const router = useRouter();
const session = useSessionStore();
const draft = ref(session.token);
const pending = ref(false);

async function enter() {
  pending.value = true;
  session.setToken(draft.value);
  try {
    await http.get(paths.adapters);
    session.accept();
    await router.replace("/");
  } catch (error) {
    if (error?.response?.status !== 401) {
      session.error = errorMessage(error);
    }
  } finally {
    pending.value = false;
  }
}
</script>

<template>
  <section class="gate">
    <form class="panel gate-card" @submit.prevent="enter">
      <span class="scan"></span>
      <p class="eyebrow">LLM GATEWAY</p>
      <h1>进入控制台</h1>
      <p class="note">填写管理令牌。令牌只放在本次浏览器会话里，每次请求带给管理接口。</p>
      <label class="field">
        <span>管理令牌</span>
        <el-input v-model="draft" type="password" show-password autocomplete="current-password" />
      </label>
      <div class="actions" style="margin-top: 16px">
        <el-button type="primary" native-type="submit" :loading="pending">进入</el-button>
      </div>
      <p class="error">{{ session.error }}</p>
    </form>
  </section>
</template>
