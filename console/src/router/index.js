import { createRouter, createWebHashHistory } from "vue-router";
import { useSessionStore } from "../stores/session";
import { errorMessage, http, paths } from "../api/http";
import ConsoleLayout from "../layouts/ConsoleLayout.vue";
import GateView from "../views/GateView.vue";
import OverviewView from "../views/OverviewView.vue";
import ProvidersView from "../views/ProvidersView.vue";
import AccountsView from "../views/AccountsView.vue";
import ModelsView from "../views/ModelsView.vue";
import TasksView from "../views/TasksView.vue";
import UsageView from "../views/UsageView.vue";

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: "/gate", component: GateView, meta: { public: true } },
    {
      path: "/",
      component: ConsoleLayout,
      children: [
        { path: "", component: OverviewView, meta: { title: "概览" } },
        { path: "providers", component: ProvidersView, meta: { title: "供应商" } },
        { path: "accounts", component: AccountsView, meta: { title: "账号与密钥" } },
        { path: "models", component: ModelsView, meta: { title: "模型" } },
        { path: "tasks", component: TasksView, meta: { title: "调用任务" } },
        { path: "usage", component: UsageView, meta: { title: "Token 消耗" } }
      ]
    }
  ]
});

router.beforeEach(async (to) => {
  const session = useSessionStore();
  if (!session.token) {
    return to.meta.public ? true : { path: "/gate" };
  }
  if (!session.accepted) {
    try {
      await http.get(paths.adapters);
      session.accept();
    } catch (error) {
      if (error?.response?.status !== 401) {
        session.error = errorMessage(error);
      }
      return to.meta.public ? true : { path: "/gate" };
    }
  }
  if (to.meta.public) {
    return { path: "/" };
  }
  return true;
});

export default router;
