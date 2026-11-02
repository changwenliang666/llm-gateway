## Context

管理页现在是 `src/main/resources/static/admin` 下的 `index.html`、`admin.js`、`admin.css`。它已经调用 `/admin/api/providers`、`/accounts`、`/keys`、`/models` 及其调用配置、负载均衡和限流子路径。令牌放在 `sessionStorage`，请求头是 `X-Admin-Token`。`AdminAuthFilter` 只拦截 `/admin/api/`。

调用记录在 `model_tasks`（`task_id`、`model_name`、`provider_account_id`、`task_status`、`created_time`）。写入只插入或更新状态，没有列表接口。Token 只有 `models.consumed_tokens`，模型列表响应字段是 `consumedTokens`。`.gitignore` 已忽略 `console/node_modules/` 和 `console/dist/`。行为要求见 `specs/admin-console/spec.md`。

## Goals / Non-Goals

**Goals:**

- 在仓库根目录用独立的 `console/` 工程交付六个视图，并建成网关仍能在 `/admin/` 提供的静态资源。
- 配置类页面继续走现有管理接口，密钥只展示 `apiKeyMask`。
- 为任务分页和 14 日汇总增加只读接口；Token 图直接使用模型列表里的累计值。
- 深色科技风覆盖 Element Plus 默认浅色表面。

**Non-Goals:**

- 不新增按次或按日的 Token 流水，图表没有历史 Token 曲线。
- 不改 `/v1/**`、选路、限流、熔断和 `consumed_tokens` 的累加规则。
- 不加第二套登录账号，不加 CORS，不让管理接口在浏览器里跨源直连。
- 不提供任务详情正文，也不在控制台里改任务状态。

## Decisions

### 1. 前端工程放在 `console/`，组件库用 Element Plus

Vue 3 不能使用 Vue 2 的 Element UI。依赖固定为 `vue`、`vue-router`、`pinia`、`element-plus`、`axios`、`sass`、`echarts`。`vue-router` 不在用户列出的名字里，但六个视图需要路由；用 hash 历史，避免服务端为深层路径做转发。

目录：

```text
console/
  index.html
  vite.config.js
  src/main.js
  src/App.vue
  src/api/http.js          # axios 实例与路径常量
  src/stores/session.js    # 令牌
  src/router/index.js
  src/styles/theme.scss
  src/layouts/ConsoleLayout.vue
  src/components/TechChart.vue
  src/views/GateView.vue
  src/views/OverviewView.vue
  src/views/ProvidersView.vue
  src/views/AccountsView.vue
  src/views/ModelsView.vue
  src/views/TasksView.vue
  src/views/UsageView.vue
```

Vite `base` 为 `/admin/`。开发服务器把 `/admin/api` 代理到 `http://localhost:8080`。构建 `outDir` 为 `../src/main/resources/static/admin`，并清空该目录。因此手写的 `admin.js` 不再是源码；源码里的路径常量集中在 `http.js`，供现有「页面调用真实接口」测试改为读取这个文件。

备选是把产物只放在 `console/dist`、运行时再起一个静态服务器。否决：当前入口是同一个网关进程上的 `/admin/`，分开部署会改变 README 里的访问方式。

### 2. 深色科技风只覆盖变量和外壳，不重写组件

`index.html` 的根节点带 `dark`。引入 Element Plus 的 dark css-vars，再用 `theme.scss` 覆盖背景、文字、边框和主色。主背景接近 `#070b14`，正文接近 `#e7f3ff`，强调色为青色 `#3ee0ff`，辅色为蓝紫。页面铺网格和两处径向光，卡片用细描边和弱发光，数字用等宽数字。不从 CDN 拉字体。

`TechChart.vue` 直接调用 `echarts`，背景透明，坐标轴和提示框沿用同一套深色。组件在卸载时 `dispose`，并用 `ResizeObserver` 调整尺寸。概览的四张图是：按模型 Token（柱）、按供应商 Token（柱或环）、按状态任务数（环）、按日任务数（折线或柱）。状态色固定，不随刷新改色。接口失败时清空对应序列，不保留上一轮范围。零数据渲染空轴，不注入示例点。

Element Plus 语言包用中文。表单、表格、分页、消息使用组件库，外壳和图表自己画。

### 3. 令牌与现有接口的调用方式

Pinia `session` 从 `sessionStorage` 键 `adminToken` 读取和写入，与现页相同。axios 拦截器在每个 `/admin/api` 请求上加 `X-Admin-Token`。进入控制台前先请求 `GET /admin/api/adapters`；401 停在令牌页并展示 `error.message`。选择更换令牌时先清掉存储，再回到令牌页。

密钥更新：秘密框为空时提交空字符串；只有操作者新输入时才提交新值。服务端 `ApiKeys.keepStored` 已把空白和当前掩码当成「不更换」。界面只渲染 `apiKeyMask`。

模型页一次保存可以分别调用已有的模型更新、`/invocation`、`/load-balance`、`/rate-limit`。400 时展示 `error.message`，并重新拉取该模型，避免把被拒绝的开关显示成已保存。适配器下拉只用 `GET /admin/api/adapters`。

Token 消耗页和概览上的 Token 图在浏览器里按 `providerName`、`modelName` 聚合 `consumedTokens`。不新增 Token 统计接口。筛选后的合计等于当前展示模型的 `consumedTokens` 之和。保存配置不提交这个字段，模型请求体里也没有它。

### 4. 任务查询放在现有 task 映射，HTTP 仍走管理控制器

`GET /admin/api/tasks` 与 `GET /admin/api/tasks/summary` 加在 `AdminController`。`/tasks/summary` 必须是独立映射，不能写成 `/{taskId}`。校验和组装放在 admin 侧的新查询服务；SQL 加在 `ModelTaskMapper`，因为它已经负责 `model_tasks`。

列表查询连接 `provider_accounts`，带出 `account_name`。筛选是等值：`model_name`、`task_status`、`provider_account_id`。`from`/`to` 含首尾日，条件写成 `created_time >= from 的 00:00:00` 且 `< to 的次日 00:00:00`。排序 `created_time DESC, task_id DESC`。`createdTime` 用 SQL `DATE_FORMAT(..., '%Y-%m-%dT%H:%i:%s')`。分页 `LIMIT/OFFSET`，`page` 从 1 开始，`offset = (page - 1) * size`。另有一条相同 WHERE 的 `COUNT(*)`。

汇总的「今天」用 MySQL `CURDATE()`，与 `created_time` 默认值同一时钟。两端都省略时，`from = CURDATE() - 13 天`，`to = CURDATE()`。只给 `to` 时，`from = to - 13 天`。只给 `from` 时，`to = CURDATE()`。跨度按自然日含首尾计算，超过 90 天返回 400。`byStatus` 是范围内 `GROUP BY task_status`，计数为 0 的状态不返回。`byDay` 先按 `DATE(created_time)` 分组，再在 Java 里按升序补零。状态更新不改 `created_time`，因此查询不用另外的时间列。

非法 `page`、`size`、日期格式、起止颠倒，抛出现有的 `GatewayException`（400，`invalid_request_error`），正文仍是 `error.message`。空结果返回 200 和空 `items`，不是 404。

追加 `sql/model_task_list.sql`，只为 `model_tasks` 增加 `(created_time, task_status)` 索引；脚本可重复执行，不改五张配置表，也不动 `sql/schema.sql` 的删表行为。`schema.sql` 里的建表语句同步带上这个索引，供新库使用。

控制台任务表的筛选、页码和每页条数都作为查询参数发给上述接口，不在浏览器里缓存全表。

### 5. 静态入口

Spring 继续从 `classpath:/static/` 提供文件。增加一个只做转发的映射：`/admin` 和 `/admin/` 都转发到 `/admin/index.html`。`/admin/api/**` 不经过这个转发。hash 路由不再需要把 `/admin/tasks` 转回首页。

## Risks / Trade-offs

- [构建产物进 Git，diff 会很大] → 只提交 `src/main/resources/static/admin` 这一处产物，源码仍以 `console/` 为准；禁止手改产物。
- [JVM 与 MySQL 时区不一致时，默认 14 日窗口会偏] → 默认窗口和按日分组都使用 MySQL 的日期，不在浏览器里重算「今天」。
- [任务表变大后 `OFFSET` 变慢] → 管理页默认 20 条；本次不做游标分页。索引覆盖按日汇总。
- [Element Plus 升级可能冲掉暗色变量] → 暗色以 `theme.scss` 的覆盖为准，不依赖组件默认浅色表面。
- [未归档的 `model-governance` 也写了 `admin-console` 需求] → 本变更只增加控制台和只读查询，不改该变更里的令牌、掩码和写接口语义。归档时按需求名合并，不覆盖那些已有需求。

## Migration Plan

1. 先加只读接口和索引脚本，旧静态页仍可使用。
2. 再加入 `console/` 并构建到 `src/main/resources/static/admin`，这一步替换旧页面。
3. 回滚时恢复旧的三个静态文件，并可以留下新的 GET 接口；它们不被旧页面调用。
4. 已有库执行一次 `sql/model_task_list.sql`。没有该索引时查询仍正确，只是汇总会扫表。
5. 更新 README：开发时在 `console/` 运行 Vite，并同时 `mvn spring-boot:run`；提交前执行构建，使 `/admin/` 在只启动网关时可用。
