# Tailcat Mesh Desktop UI 规格说明书

> 产品名称：Tailcat Mesh Desktop  
> 目标版本：v0.1  
> 客户端技术：Electron  
> 设计方向：Modern / Minimal / Desktop-first  
> 目标平台：Windows 11 优先  
> 核心原则：约定大于配置、低认知负担、桌面软件感、避免 Web Admin 套壳感

---

# 1. 产品定位

Tailcat Mesh Desktop 是 Tailcat Mesh 的最终用户桌面客户端。

用户安装客户端后，原则上只需要：

```text
Server URL
Enrollment Token
```

即可完成设备注册和加入 Mesh。

复杂管理继续由 Tailcat Mesh Server Web Console 完成，例如：

- 创建 Mesh Network
- 添加/移除成员
- 设备审批
- 高级 Service 配置
- 管理员设置

Desktop 主要负责：

- 连接
- 状态展示
- 网络查看
- 运行状态
- 重连
- 日志
- 基础设置
- 系统托盘

产品体验目标：

> 用户可以不知道 Tailcat、WireGuard、DERP、tun2socks、Wintun、TUN、SOCKS、ConnBlob 是什么。

---

# 2. 设计原则

## 2.1 Desktop First

界面必须像真正的 Windows 桌面软件，而不是网页后台放进 Electron。

优先采用：

```text
Sidebar
Toolbar
List
Detail Panel
Status Bar
Context Menu
Tray
Native Dialog
```

避免：

- 满屏 Dashboard Card
- 超大 Hero Banner
- 移动端式大按钮
- 一页无限向下滚动
- 后台管理系统式数据看板
- 玻璃拟态 / 霓虹 / 赛博朋克风格

---

# 3. 整体窗口

默认尺寸：

```text
Width: 1120px
Height: 760px
```

最小尺寸：

```text
min-width: 920px
min-height: 620px
```

窗口必须可自由缩放。

---

# 4. 主框架

```text
┌─────────────────────────────────────────────────────────────┐
│ Tailcat Mesh                               ● Connected   ─ □ × │
├───────────────┬─────────────────────────────────────────────┤
│               │                                             │
│  Overview     │                                             │
│  Networks     │                Main Content                 │
│  Activity     │                                             │
│  Logs         │                                             │
│               │                                             │
│               │                                             │
│  Settings     │                                             │
├───────────────┴─────────────────────────────────────────────┤
│ Connected · mesh.example.com · Agent Running · v0.1.0      │
└─────────────────────────────────────────────────────────────┘
```

整体由：Title Bar、Sidebar、Main Content、Status Bar 组成。

---

# 5. Title Bar

建议高度：`48px`。

左侧：
- Tailcat Logo
- Tailcat Mesh

右侧：
- 当前状态，例如 `● Connected`
- Minimize
- Maximize
- Close

如使用自定义 Electron Title Bar，必须保留 Windows 11 原生行为：
- 双击标题栏最大化
- 拖动窗口
- Snap Layout
- 最大化 / 还原

---

# 6. Sidebar

推荐宽度：`200px`，小窗口可收缩到 `64px icon-only`。

导航：

```text
Overview
Networks
Activity
Logs

Settings
```

Settings 固定靠近底部。

每项：

```text
height: 40px
icon: 18px
font-size: 14px
border-radius: 6px
```

选中状态：轻微主色背景、主色图标、高对比文字。

不要做成巨大胶囊按钮。

---

# 7. Status Bar

建议高度：`28px`。

正常：

```text
● Connected · mesh.example.com · Agent Running · Tailcat v0.3.0
```

重连：

```text
● Reconnecting · Retrying in 4s...
```

离线：

```text
● Offline · Unable to reach server
```

Status Bar 只承载轻量信息，不放主要操作。

---

# 8. Design Tokens

## Light Theme

```text
App Background       #F6F7F9
Surface              #FFFFFF
Secondary Surface    #F1F3F5
Border               #E4E7EB

Primary Text         #1F2328
Secondary Text       #667085
Muted Text           #98A2B3

Primary              #3B82F6
Primary Hover        #2563EB

Success              #22A06B
Warning              #E69A17
Danger               #D92D20
Offline              #98A2B3
```

## Dark Theme

```text
Background           #111418
Surface              #181C21
Secondary Surface    #20252B
Border               #2B3138

Primary Text         #F4F6F8
Secondary Text       #AAB2BD
Muted Text           #77808C
```

不要使用纯黑背景。

---

# 9. 字体

Windows 优先：

```css
font-family:
  "Segoe UI Variable",
  "Segoe UI",
  Inter,
  system-ui,
  sans-serif;
```

字号：

```text
Page Title       22px / 600
Section Title    16px / 600
Body             14px / 400
Small            13px / 400
Caption          12px / 400
Code/IP          13px / monospace
```

IP、版本号、端口可以使用 Cascadia Mono / Consolas / monospace。

---

# 10. 圆角与间距

圆角：

```text
Button        7px
Input         7px
Panel         10px
Dialog        12px
Tooltip       6px
```

间距体系：`4 / 8 / 12 / 16 / 20 / 24 / 32`。

页面 Padding：`24px`。

禁止大量使用 20px+ 大圆角。

---

# 11. Button

只需要三种：

## Primary
- Connect
- Retry
- Save

## Secondary
- Reconnect
- Restart Agent
- Open Logs

## Ghost
- Cancel
- Advanced
- More

推荐高度：`32px / 36px`。

---

# 12. 首次启动页

视觉目标：

> 简单到用户不会怀疑自己是不是漏配置了东西。

布局：

```text
               Tailcat Mesh

       Connect this device to your mesh

Server URL
┌────────────────────────────────┐
│ https://mesh.example.com       │
└────────────────────────────────┘

Enrollment Token
┌────────────────────────────────┐
│ •••••••••••••••••••••••       │
└────────────────────────────────┘

Device Name                 Optional
┌────────────────────────────────┐
│ DESKTOP-A                      │
└────────────────────────────────┘

                        Connect
```

最大宽度：`420px`。

首次页面禁止出现：Tailcat path、Java path、DERP、SOCKS、Wintun、Network CIDR、Virtual IP。

---

# 13. Connect 状态页

点击 Connect 后展示阶段进度：

```text
Connecting to Tailcat Mesh

✓ Server reachable
✓ Preparing runtime
✓ Preparing Tailcat
● Enrolling device
○ Starting mesh
```

成功：

```text
✓ Connected

Your device has joined Tailcat Mesh.

Continue
```

失败：

```text
Couldn't connect

The server could not be reached.

Check the server address and try again.

Retry
View details
```

技术错误默认隐藏在 `View details`。

---

# 14. Overview 页面

首页必须让用户一眼判断：是否已连接、当前设备是谁、连接哪个 Server、加入哪些 Networks、Virtual IP、Direct / DERP、Agent 是否运行。

推荐结构：

```text
Overview

● Connected
DESKTOP-A
Connected to mesh.example.com

[ Reconnect ] [ Restart Agent ] [ Open Logs ]

Networks
------------------------------------------------
home       10.77.0.2       Direct       Active
dev        10.78.0.4       DERP         Active

Runtime
------------------------------------------------
Agent            Running
Tailcat          v0.3.0
Virtual LAN      Ready
Control Server   Connected
```

不要做巨大彩色 Hero Banner。

---

# 15. Networks 页面

采用 Desktop Master-Detail：

```text
┌────────────────┬────────────────────────────────────────┐
│ Networks       │ home                                   │
│                │                                        │
│ home           │ 10.77.0.0/24                           │
│ dev            │                                        │
│ work           │ This device                            │
│                │ 10.77.0.2                              │
│                │                                        │
│                │ Members                                │
│                │                                        │
│                │ DESKTOP-A  10.77.0.2  This device      │
│                │ NAS-B      10.77.0.3  Direct           │
│                │ VPS-C      10.77.0.4  DERP             │
│                │                                        │
│                │              Open Web Console          │
└────────────────┴────────────────────────────────────────┘
```

左侧 Network List：`240px`，右侧详情自动占据剩余空间。

v0.1 Desktop 默认不做：Create Network、Delete Network、Add Member、Remove Member、CIDR management、ACL。

统一提供：

```text
Manage networks in Web Console
[ Open Web Console ]
```

---

# 16. Direct / DERP 展示

Direct：绿色小 Badge，文案 `Direct`。

DERP：中性或橙色 Badge，文案 `DERP`。

DERP 不是错误状态，不要使用红色。

---

# 17. Activity 页面

Activity 是“人类可读日志”。

```text
Today

10:42
Connected to mesh.example.com

10:41
Virtual network "home" restored

10:41
Agent started

Yesterday

22:17
Connection to NAS-B switched to Direct

22:15
Server connection restored
```

可使用轻量时间线。

---

# 18. Logs 页面

顶部：

```text
Search logs...

All Levels ▼
All Components ▼

Open Folder
```

主体：

```text
TIME       LEVEL  COMPONENT      MESSAGE

10:42:01   INFO   Agent          Connected to server
10:42:03   INFO   Tailcat        Server runtime started
10:42:05   INFO   VirtualLAN     Network home restored
10:42:06   WARN   Peer           DERP fallback: NAS-B
```

支持 Search、Level filter、Component filter、Open Log Folder、Copy、Export。

默认不展开巨大 Stack Trace。

---

# 19. Settings

分类：

```text
General
Appearance
Startup
Advanced
About
```

## General
- Server URL
- Device Name

修改 Server URL 时提示：`Changing server requires reconnecting.`

## Appearance

```text
Theme
● System
○ Light
○ Dark
```

默认 `System`。

## Startup

```text
Launch Tailcat Mesh when I sign in        ON
Start minimized                            ON
```

## Advanced

默认折叠：
- Restart Agent
- Reconnect
- Open Data Folder
- Open Log Folder
- Run Diagnostics
- Reset Runtime Cache
- Re-enroll Device

---

# 20. Danger Zone

例如：

```text
Reset this device

Removes the local Tailcat Mesh identity.
The device must be enrolled again.

[ Reset Device ]
```

危险操作必须二次确认。

---

# 21. About

显示：

```text
Tailcat Mesh
0.1.0

Agent
0.1.0

Tailcat
v0.3.0

Protocol
1
```

提供 GitHub、Licenses、Open Source Notices。

声明：

```text
Tailcat Mesh is an independent community project.
It is not affiliated with or endorsed by Tailscale Inc.
```

---

# 22. Offline / Reconnecting 状态

Control Server Offline 时不要直接宣称 Mesh 已断。

必须区分：

```text
Control Server
Offline

Mesh Runtime
Running
```

如果现有 Peer 数据面仍然工作，UI 应保留 Network 状态。

---

# 23. Agent Error

Agent 意外退出：

```text
Agent stopped unexpectedly

Tailcat Mesh will try to restart it automatically.

Restart now
View logs
```

多次失败：

```text
Agent could not be started.

Retry
Open logs
Run diagnostics
```

---

# 24. Dependency Preparing

首次准备 Tailcat 等依赖：

```text
Preparing Tailcat
Downloading v0.3.0...

████████████████──── 78%
```

Virtual LAN：

```text
Preparing Virtual LAN...
```

普通模式不要展示 tun2socks / wintun 具体文件名，详细信息放 `Details`。

---

# 25. Tray

状态：
- Connected：normal icon
- Offline：gray
- Reconnecting：orange indicator
- Error：red indicator

菜单：

```text
Tailcat Mesh
Connected

Open Tailcat Mesh
──────────────
Reconnect
Restart Agent
Open Logs
──────────────
Launch at Startup ✓
──────────────
Quit
```

关闭窗口默认 `Hide to Tray`，真正退出使用 `Tray → Quit`。

---

# 26. Toast / Notification

应用内 Toast：

```text
Connected
Tailcat Mesh is online.
```

```text
Network restored
home is available again.
```

```text
Using DERP
Direct connection to NAS-B is currently unavailable.
```

只在真正严重故障时使用 Windows 系统通知。

---

# 27. 图标

整套应用只使用一套图标系统，推荐 Lucide。

导航：

```text
Overview    Gauge / House
Networks    Network
Activity    Activity
Logs        ScrollText
Settings    Settings
```

---

# 28. 空状态

无 Network：

```text
No mesh networks

This device is connected but hasn't been added
to a virtual network yet.

Open the Web Console to manage networks.

Open Web Console
```

不使用大型插画。

---

# 29. Loading

优先 Skeleton，不使用整页大 Spinner。

首次启动允许：

```text
Tailcat Mesh
Starting...
```

---

# 30. Accessibility

至少满足：
- 颜色不是唯一状态标识
- 键盘 Focus 清晰
- 文本对比度 WCAG AA
- Input 有真实 Label
- 错误信息与输入框关联

必须展示 `● Connected`，而不是只有一个绿色点。

---

# 31. Electron UI 安全边界

Renderer：

```text
nodeIntegration = false
contextIsolation = true
sandbox = true（可行时）
```

Renderer 禁止：spawn、exec、unrestricted fs、直接读取 Agent credentials、直接启动 Java / Tailcat / tun2socks。

架构：

```text
Renderer
   ↓
Preload API
   ↓
Electron Main
   ↓
AgentSupervisor
   ↓
Bootstrap
   ↓
Java Agent
```

---

# 32. Renderer 推荐组件划分

```text
AppShell
├── TitleBar
├── Sidebar
├── StatusBar
│
├── OverviewPage
│   ├── ConnectionStatus
│   ├── NetworkList
│   └── RuntimeStatus
│
├── NetworksPage
│   ├── NetworkSidebar
│   └── NetworkDetails
│
├── ActivityPage
├── LogsPage
└── SettingsPage
```

---

# 33. UI 状态枚举

Connection：

```text
STARTING
CONNECTING
CONNECTED
RECONNECTING
OFFLINE
ERROR
```

Agent：

```text
STOPPED
STARTING
RUNNING
RESTARTING
FAILED
```

Network：

```text
STARTING
ACTIVE
DEGRADED
OFFLINE
ERROR
```

Peer Path：

```text
DIRECT
DERP
UNKNOWN
OFFLINE
```

UI 禁止从日志字符串猜状态。

---

# 34. 动效

克制：

```text
Sidebar selection      120ms
Hover                  100ms
Page fade              120-160ms
Dialog                 150ms
Toast                  180ms
```

禁止 bounce、大幅 slide、spring animation、无意义动态背景。

---

# 35. v0.1 页面清单

必须完成：

```text
Connect / Enrollment
Connecting
Overview
Networks
Network Detail
Activity
Logs
Settings
About
Error States
Tray
Dialogs
Toasts
```

不要求：

```text
Server Admin
Network Creator
ACL Editor
Account Center
Full Device Management
```

---

# 36. UI Definition of Done

只有全部满足，Desktop UI 才算完成：

- [ ] 第一眼像桌面软件，不像 Web Admin
- [ ] Windows 11 下视觉正常
- [ ] Light Theme 完成
- [ ] Dark Theme 可用
- [ ] 窗口可缩放
- [ ] Sidebar / Title Bar / Status Bar 完整
- [ ] 首次连接只暴露 Server URL + Token
- [ ] Device Name 可选
- [ ] Overview 一眼可判断运行状态
- [ ] Network 列表显示 Virtual IP
- [ ] 可区分 Direct / DERP
- [ ] Control Server Offline 与 Mesh Runtime 状态不混淆
- [ ] Activity 人类可读
- [ ] Logs 面向排障
- [ ] Settings 保持精简
- [ ] Advanced 默认隐藏
- [ ] Tray 完成
- [ ] Close 默认隐藏到 Tray
- [ ] 错误不直接展示大段技术堆栈
- [ ] Renderer 不获得敏感 Agent Credential
- [ ] Renderer 不直接操作系统进程
- [ ] 关键操作有 Loading / Success / Error 状态
- [ ] 没有大量超大 Dashboard Cards
- [ ] 没有无意义渐变 / 霓虹 / 玻璃拟态
- [ ] UI 风格统一

---

# 37. 最终视觉目标

Tailcat Mesh Desktop 应表现为：

> 一个现代、轻量、专业、可信赖的 Windows 网络工具。

最终视觉关键词：

```text
Modern
Minimal
Desktop-first
Calm
Clean
Professional
Low cognitive load
```
