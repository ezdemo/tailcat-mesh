# Tailcat Mesh Desktop UI Implementation Audit

审计范围：`tailcat-mesh-desktop` Electron 工程，以及《Tailcat Mesh Desktop UI 规格说明书》定义的 v0.1 Desktop UI 边界。

## 1. 当前桌面工程情况

- 已存在 Electron + TypeScript 工程，入口为 `src/main/main.ts`，Preload 为 `src/preload/preload.cts`。
- Electron Main 已拥有 Java Agent 的受控启动、状态轮询、自动重启、配置存储和系统托盘能力。
- Renderer 原来是单页、单文件连接/概览界面，使用了偏展示型卡片、中文旧导航和本地代理入口，尚未覆盖 Networks Master-Detail、Activity、Logs、Settings/About、完整连接状态和统一主题模型。
- Java Agent / Tailcat / tun2socks / Wintun 的实际生命周期仍由 Main → AgentSupervisor → Java Agent 管理；本次 UI 实施不重写网络核心。
- 工作区在开始前已有 Agent/Main 相关未提交修改；UI 实施避开这些 Java 核心变更，只调整 Desktop UI 边界所需的共享设置、IPC 和窗口/托盘行为。

## 2. 推荐的 Renderer 技术方案

继续使用 Electron 原生 Renderer + TypeScript + HTML/CSS，不引入重量级 Web Admin 框架或运行时依赖。页面使用明确的 `pages/` 与 `components/` 模块，统一由 `renderer.ts` 管理路由、结构化 UI model、Mock scenario、IPC action 和渲染状态。

Renderer 只接触 `window.tailcatMesh` 受控 Preload API；`nodeIntegration=false`、`contextIsolation=true`、`sandbox=true` 保持不变。所有进程、文件夹、导出和外部链接操作继续落在 Electron Main 的 IPC handler 中。

## 3. AppShell 结构

```text
AppFrame
├── TitleBar (48px, native Windows overlay controls + drag region)
├── DesktopBody
│   ├── Sidebar (200px; <= 980px 收缩至 icon-only 64px)
│   └── MainContent (可滚动)
└── StatusBar (28px)

EnrollmentFrame
├── TitleBar
├── EnrollmentContent (最大宽度 420px)
└── StatusBar
```

已实现的页面层级：

```text
AppShell
├── TitleBar
├── Sidebar
├── StatusBar
├── OverviewPage
│   ├── ConnectionStatus
│   ├── NetworkTable
│   └── RuntimeStatus
├── NetworksPage
│   ├── NetworkSidebar
│   └── NetworkDetail / Members
├── ActivityPage
├── LogsPage
└── SettingsPage
    ├── General
    ├── Appearance
    ├── Startup
    ├── Advanced
    └── About
```

## 4. Design Token 实现方式

`src/renderer/styles.css` 使用单一 CSS custom property token 层。Light、Dark 和 System 主题只切换 token，不由页面局部重定义颜色、圆角或间距。

- 颜色 token 对齐规格书的 App Background / Surface / Border / Text / Primary / Success / Warning / Danger / Offline。
- 字体优先 `Segoe UI Variable` / `Segoe UI`。
- 间距 token 为 `4 / 8 / 12 / 16 / 20 / 24 / 32px`。
- 圆角 token 为 button/input 7px、panel 10px、dialog 12px、tooltip 6px。
- 状态使用文字 + 图标/色彩，保证颜色不是唯一信息。

## 5. 页面和组件目录

```text
src/renderer/
├── components/
│   ├── icons.ts
│   └── status.ts
├── pages/
│   ├── activity.ts
│   ├── enrollment.ts
│   ├── logs.ts
│   ├── networks.ts
│   ├── overview.ts
│   └── settings.ts
├── mock-data.ts
├── model.ts
├── view.ts
├── renderer.ts
├── index.html
└── styles.css
```

共享结构化 UI 状态模型位于 `src/shared/ui-types.ts`，Electron/Agent 边界类型位于 `src/shared/types.ts`。

## 6. 是否已有可复用组件

原工程没有可复用的通用 UI 组件库；只有旧 Renderer 的 DOM 查询和 CSS class。保留可复用的 Main/Preload/AgentSupervisor IPC 边界，并新增轻量的 icon、status、page renderer 模块。图标统一为 Lucide 风格的内置 SVG path，不混用其它图标库，也不依赖网络 CDN。

## 7. 准备新增/修改的文件

新增：

- `UI_IMPLEMENTATION_AUDIT.md`
- `src/shared/ui-types.ts`
- `src/renderer/components/icons.ts`
- `src/renderer/components/status.ts`
- `src/renderer/pages/*.ts`
- `src/renderer/mock-data.ts`
- `src/renderer/model.ts`
- `src/renderer/view.ts`

修改：

- `src/shared/types.ts`
- `src/main/paths.ts`
- `src/main/config-store.ts`
- `src/main/agent-supervisor.ts`
- `src/main/ipc.ts`
- `src/main/main.ts`
- `src/main/tray.ts`
- `src/preload/preload.cts`
- `src/renderer/renderer.ts`
- `src/renderer/index.html`
- `src/renderer/styles.css`
- `test/desktop-shell.test.mjs`

不修改：Tailcat Mesh Java Agent、M1-M7 网络核心和网络协议实现。

## 8. 实施顺序

1. UI.1 Design Tokens / Theme / AppShell
2. UI.2 Title Bar / Sidebar / Status Bar
3. UI.3–UI.4 Enrollment、Connecting、Success、Error
4. UI.5 Overview
5. UI.6 Networks Master-Detail
6. UI.7 Activity
7. UI.8 Logs filters / copy / export
8. UI.9 Settings / About / Advanced / Danger Zone
9. UI.10 Tray / close-to-tray / window sizing
10. UI.11 Global loading / empty / reconnecting / agent error states
11. UI.12 Dark Theme
12. UI.13 Mock Data E2E scenarios
13. UI.14 Main / Preload controlled actual-state integration
14. Build, launch Electron and verify 1120×760 plus 920×620 minimum layout.

## 9. Implementation and validation result

- UI.1–UI.14 已完成：Renderer 采用 Desktop-first AppShell，Main/Preload 使用受控 API，真实 Agent 状态映射保留结构化模型；当前 E2E 使用 `--mock-ui` 场景，不启动 Java Agent。
- `npm run typecheck` 通过，`npm run build` 通过，`npm test` 通过（6/6）。
- 已实际启动 Electron 并检查 `1120×760` 与 `920×620`：Title Bar、Sidebar、Master/Detail、表格、状态栏和可滚动内容保持桌面客户端布局。
- 已检查 Connected、Connecting、Success、Enrollment、Offline、Reconnecting、Agent Error、Activity、Logs、Settings、Dark Theme 等 Mock 场景。
- 已验证 Close 隐藏到 Tray，Electron 主进程继续运行；再次启动同一实例可重新显示窗口。
- 当前剩余边界：真实 Server / Java Agent / Tailcat 联调需要接入实际运行环境，未用 Mock 验证替代该联调。
