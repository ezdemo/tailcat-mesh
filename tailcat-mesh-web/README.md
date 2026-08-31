# Tailcat Mesh Web

Tailcat Mesh 的管理员控制面前端，使用 React、TypeScript、Vite 和 Tailwind
CSS。界面采用 Tailwind UI 应用程序 UI 常见的侧边栏、统计卡片、表格、表单、
状态徽章和模态框模式。

## 本地开发

先从项目根目录启动 Server（本地 HTTP 测试需要关闭 HTTPS 强制校验）：

```powershell
java -jar .\tailcat-mesh-server\target\tailcat-mesh-server-0.1.0-SNAPSHOT.jar --tailcat-mesh.security.require-https=false
```

再启动前端：

```powershell
cd .\tailcat-mesh-web
npm install
npm run dev
```

打开 `http://localhost:5173`。开发服务器会把 `/api` 请求代理到
`http://localhost:8080`，因此登录页的“控制面地址”可以留空。

如果前端和 Server 不在同一台机器，可以复制 `.env.example` 为 `.env`，设置
`VITE_API_BASE_URL`。Server 默认允许所有跨域来源；生产环境建议额外设置
`WEB_ALLOWED_ORIGINS` 为前端的精确来源：

```text
VITE_API_BASE_URL=https://mesh.example.com
```

```text
WEB_ALLOWED_ORIGINS=https://admin.example.com
```

## 当前已接入功能

- 管理员登录、退出登录和会话过期处理；
- 控制面总览与设备统计；
- 设备列表、搜索、状态筛选和详情；
- PENDING 设备审批；
- 设备禁用；
- Enrollment Token 创建、列表、复制和禁用；
- M7.1 Virtual Network 创建、启停、删除、成员添加/移除和稳定 Virtual IPv4 查看；
- TCP 服务创建、修改、删除，以及 Agent bridge 运行态查看。
- Local Forward 创建、修改、删除，以及本地监听运行态查看；用户在源设备上访问 `127.0.0.1:<localPort>` 即可使用远端 TCP 服务。
- Peer 连接的 ONLINE/DEGRADED/OFFLINE 状态、Direct/DERP 路径、延迟和最近错误查看。

Java Agent 的 `/api/v1/agent/*` REST/WebSocket 是机器间控制通道，由 Agent
自身调用，不在管理员浏览器中暴露 credential 输入框。

## 构建

```powershell
npm run build
```
