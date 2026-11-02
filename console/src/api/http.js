import axios from "axios";
import { useSessionStore } from "../stores/session";

export const ADMIN_HEADER = "X-Admin-Token";

export const paths = {
  adapters: "/admin/api/adapters",
  providers: "/admin/api/providers",
  accounts: "/admin/api/accounts",
  models: "/admin/api/models",
  tasks: "/admin/api/tasks",
  taskSummary: "/admin/api/tasks/summary",
  provider: (id) => `/admin/api/providers/${id}`,
  account: (id) => `/admin/api/accounts/${id}`,
  keys: (accountId) => `/admin/api/accounts/${accountId}/keys`,
  key: (id) => `/admin/api/keys/${id}`,
  model: (id) => `/admin/api/models/${id}`,
  invocation: (id) => `/admin/api/models/${id}/invocation`,
  loadBalance: (id) => `/admin/api/models/${id}/load-balance`,
  rateLimit: (id) => `/admin/api/models/${id}/rate-limit`
};

export const http = axios.create();

export function errorMessage(error) {
  return error?.response?.data?.error?.message || error?.message || "请求失败";
}

http.interceptors.request.use((config) => {
  const session = useSessionStore();
  if (session.token) {
    config.headers[ADMIN_HEADER] = session.token;
  }
  return config;
});

http.interceptors.response.use((response) => response, (error) => {
  if (error?.response?.status === 401) {
    useSessionStore().fail(errorMessage(error));
  }
  return Promise.reject(error);
});
