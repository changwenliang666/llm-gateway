## Why

现有管理页是 `src/main/resources/static/admin` 里的单页脚本。供应商、账号和模型已经能配，但任务写在 `model_tasks` 里却不能查看，Token 只有按模型累计的数字，没有图表。继续在这个脚本上加页面和统计会难以维护，也做不出科技风控制台。

## What Changes

- 在仓库根目录新增独立前端工程 `console/`。技术栈为 Vue 3、Pinia、Vite、Element Plus、Axios、Sass，图表使用 ECharts。用户提到的 Element UI 是 Vue 2 组件库，本变更使用它的 Vue 3 对应库 Element Plus。
- 用该工程替换手写的管理页。开发时由 Vite 代理 `/admin/api`；构建产物放到网关静态目录，访问地址仍是 `/admin/`。
- 保留现有管理能力：供应商、账号与密钥、模型的调用配置、负载均衡和限流。密钥仍只显示掩码，请求仍带 `X-Admin-Token`。
- 新增只读的调用任务列表，以及按状态、按日汇总的任务数量，供表格和图表使用。
- Token 图表按供应商和模型展示已有的 `models.consumed_tokens`。成功才累计、失败和限流不计入的规则不变。本变更不新增按次 Token 流水。
- 界面为深色科技风，不使用 Element Plus 默认浅色主题。

## Capabilities

### New Capabilities

- `admin-console`: 用 Vue 控制台替换静态管理页，并增加调用任务列表与 Token / 任务统计图表。主规格目录 `openspec/specs/` 里还没有该能力，因此本变更以新增需求写入；行为上延续未归档变更 `model-governance` 里已经约定的管理接口、令牌和密钥掩码。

### Modified Capabilities

## Impact

- 新增 `console/` 工程。`.gitignore` 已忽略 `console/node_modules/` 与 `console/dist/`。
- 删除手写的 `src/main/resources/static/admin` 源文件，改为前端构建产物。`AdminConsoleTest` 里直接读取 `admin.js` 的断言需要改到前端源码或接口测试。
- 新增只读管理接口，查询 `model_tasks` 并做分页和汇总。现有 `/admin/api/providers|accounts|keys|models` 的路径和字段保持不变。
- `/v1/**`、选路、限流、熔断和 Token 累计规则不受影响。不修改五张配置表，也不重跑会删表的 `sql/schema.sql`。
- README 的管理页一节需要改成新工程的启动和构建方式。
