<script setup>
import { useRoute, useRouter } from "vue-router";
import { useSessionStore } from "../stores/session";

const route = useRoute();
const router = useRouter();
const session = useSessionStore();

const links = [
  { to: "/", label: "概览", code: "01" },
  { to: "/providers", label: "供应商", code: "02" },
  { to: "/accounts", label: "账号与密钥", code: "03" },
  { to: "/models", label: "模型", code: "04" },
  { to: "/tasks", label: "调用任务", code: "05" },
  { to: "/usage", label: "Token 消耗", code: "06" }
];

function signOut() {
  session.clearToken();
  router.replace("/gate");
}
</script>

<template>
  <div class="shell">
    <aside class="rail">
      <div class="brand">
        <span class="mark"></span>
        <div>
          <strong>LLM GATEWAY</strong>
          <small>CONTROL DECK</small>
        </div>
      </div>
      <nav>
        <router-link v-for="link in links" :key="link.to" :to="link.to">
          <span>{{ link.label }}</span>
          <span class="code">{{ link.code }}</span>
        </router-link>
      </nav>
      <el-button class="ghost" @click="signOut">更换令牌</el-button>
    </aside>
    <main class="workspace">
      <header>
        <div>
          <p class="eyebrow">OPERATIONS</p>
          <h1>{{ route.meta.title }}</h1>
        </div>
      </header>
      <router-view />
    </main>
  </div>
</template>
