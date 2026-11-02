import { createApp } from "vue";
import { createPinia } from "pinia";
import ElementPlus from "element-plus";
import zhCn from "element-plus/es/locale/lang/zh-cn";
import "element-plus/dist/index.css";
import "element-plus/theme-chalk/dark/css-vars.css";
import App from "./App.vue";
import router from "./router";
import { setUnauthorizedHandler } from "./stores/session";
import "./styles/theme.scss";

const app = createApp(App);
app.use(createPinia());
setUnauthorizedHandler(() => {
  if (router.currentRoute.value.path !== "/gate") {
    router.replace("/gate");
  }
});
app.use(router);
app.use(ElementPlus, { locale: zhCn });
app.mount("#app");
