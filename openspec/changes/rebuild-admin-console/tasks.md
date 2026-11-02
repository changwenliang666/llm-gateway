## 1. 任务只读查询

- [x] 1.1 在 `ModelTaskMapper` 增加任务分页、计数、按状态汇总、按日汇总和 `CURDATE()`。新增可重复执行的 `sql/model_task_list.sql`，并为 `schema.sql` 里的 `model_tasks` 加上 `(created_time, task_status)` 索引。验证：映射含相同筛选条件的列表与计数；索引脚本不修改五张配置表
- [x] 1.2 增加管理侧查询服务：校验 `page`、`size` 和日期，默认窗口用 MySQL 的今天，跨度超过 90 天或起止颠倒返回 400，并按日期升序把 `byDay` 补零。验证：用假映射的单元测试覆盖 400、空结果和补零
- [x] 1.3 在 `live.db=true` 时用真实库验证连接账号名、状态筛选、创建时间倒序，以及更新状态后任务仍算在原来的创建日。验证：该测试默认跳过，开启 `live.db` 后通过，事务回滚

## 2. 管理接口与入口

- [x] 2.1 在 `AdminController` 增加 `GET /admin/api/tasks` 和独立的 `GET /admin/api/tasks/summary`。响应字段与 `specs/admin-console/spec.md` 一致，不返回调用正文，也不提供写接口。验证：MockMvc 在无令牌时 401，`size` 大于 100 时 400，合法请求返回 `total/page/size/items`
- [x] 2.2 让 `/admin` 和 `/admin/` 转发到 `/admin/index.html`，且不转发 `/admin/api/**`。验证：MockMvc 能看到这两处转发，管理接口仍进入原来的控制器

## 3. Vue 控制台

- [x] 3.1 创建 `console/`：Vue 3、Vue Router（hash）、Pinia、Vite、Element Plus、Axios、Sass、ECharts。`base` 为 `/admin/`，开发代理 `/admin/api` 到 `localhost:8080`，构建输出到 `src/main/resources/static/admin` 并清空该目录。验证：`npm install` 成功，配置文件包含上述三项
- [x] 3.2 实现令牌门、axios 拦截器、六个路由和深色外壳。令牌使用 `sessionStorage` 的 `adminToken` 与请求头 `X-Admin-Token`；401 展示 `error.message` 并停在门外；更换令牌时先清除旧值。验证：`console/src/api/http.js` 含现有管理路径和该请求头；`theme.scss` 把主背景设为深色、正文设为浅色
- [x] 3.3 实现供应商、账号与密钥、模型三个视图，覆盖创建和更新、适配器下拉、调用配置、负载均衡、限流和调度账号。密钥只显示 `apiKeyMask`，空白秘密框提交空字符串；400 时展示 `error.message` 并重新拉取。验证：这三个视图的请求都来自 `http.js` 的路径常量，密钥视图没有明文展示字段
- [x] 3.4 实现调用任务视图：筛选和分页都发给 `GET /admin/api/tasks`，表格只渲染当前页。验证：翻页会带上新的 `page`，页面代码没有把全量任务一次拉进本地数组
- [x] 3.5 实现概览和 Token 消耗。概览绘制按模型 Token、按供应商 Token、按状态任务数、按日任务数；Token 页按供应商再按模型筛选，合计等于所展示模型的 `consumedTokens` 之和。失败时清空图表，零数据不使用示例序列。验证：图表数据只来自 `/admin/api/models` 和 `/admin/api/tasks/summary`，源码中没有写死的统计序列

## 4. 接入与回归

- [x] 4.1 执行 `console` 构建，用产物替换手写管理页。把 `AdminConsoleTest` 对 `admin.js` 的读取改为 `console/src/api/http.js`。验证：`src/main/resources/static/admin/index.html` 存在，相关单元测试通过，旧的手写 `admin.js` 不再作为源文件留下
- [x] 4.2 更新 README 的管理页一节：`console/` 的开发与构建、`/admin/` 入口、两个任务接口，以及已有库执行 `sql/model_task_list.sql`。验证：文档能按步骤打开控制台，并写明 Token 图使用累计值、没有按次流水
- [x] 4.3 跑 `mvn test`。验证：测试通过；`live.db` 未开启时任务库测试被跳过而不是失败
- [x] 4.4 在浏览器走通令牌门、六个视图、任务翻页和图表筛选；错误令牌不显示数据，深色导航在各视图保持可见。验证：记录实际操作过的路径；若当时没有浏览器工具，改用接口请求说明未覆盖的界面部分
