import { defineStore } from "pinia";
import { ref } from "vue";

const KEY = "adminToken";
let onUnauthorized = () => {};

export function setUnauthorizedHandler(handler) {
  onUnauthorized = handler;
}

export const useSessionStore = defineStore("session", () => {
  const token = ref(sessionStorage.getItem(KEY) || "");
  const accepted = ref(false);
  const error = ref("");

  function setToken(value) {
    token.value = String(value || "").trim();
    accepted.value = false;
    error.value = "";
    if (token.value) {
      sessionStorage.setItem(KEY, token.value);
    } else {
      sessionStorage.removeItem(KEY);
    }
  }

  function accept() {
    accepted.value = true;
    error.value = "";
  }

  function clearToken() {
    token.value = "";
    accepted.value = false;
    error.value = "";
    sessionStorage.removeItem(KEY);
  }

  function fail(message) {
    token.value = "";
    accepted.value = false;
    sessionStorage.removeItem(KEY);
    error.value = message || "管理令牌不正确";
    onUnauthorized();
  }

  return { token, accepted, error, setToken, accept, clearToken, fail };
});
