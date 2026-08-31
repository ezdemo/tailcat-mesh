# Tailcat Mesh v0.1 开发规格说明书（Java-First / CLI Engine 版）

> **用途**：本文件可直接交给 Codex、Claude Code、GPT 等编码 AI 作为项目实施规格。  
> **项目名称**：Tailcat Mesh  
> **目标版本**：v0.1 MVP  
> **Tailcat 基线**：`tailscale/tailcat v0.3.0`  
> **主语言**：Java 21  
> **文档日期**：2026-08-31  
> **项目性质**：独立社区项目，不属于 Tailscale 官方产品。

---

# 0. 给编码 AI 的总指令

你正在实现一个名为 **Tailcat Mesh** 的自托管节点与服务 Mesh 管理平台。

项目的核心原则是：

- **Tailcat 官方二进制负责底层 WireGuard / magicsock / DERP / NAT traversal。**
- **Tailcat Mesh 自己的业务代码以 Java 为主。**
- **不自行用 Java 重写 Tailcat。**
- **不引入自研 Go Agent。**
- **不把 Tailcat CLI 的人类日志文本直接扩散到业务层。**
- 所有 Tailcat CLI 交互必须隔离在 Java Agent 的 `TailcatEngine` / `TailcatCliEngine` 模块。

本项目 v0.1 使用以下架构：

```text
Tailcat Mesh Server
Java 21 + Spring Boot
        │
        │ HTTPS / WebSocket
        │
        ▼
Tailcat Mesh Agent
Java 21
        │
        │ ProcessBuilder
        ▼
官方 tailcat v0.3.0 二进制
        │
        ▼
WireGuard / magicsock / DERP / P2P
```

Web 管理界面第一版不使用独立 Vue / React 工程，优先使用：

```text
Spring Boot
+ Thymeleaf
+ HTMX
+ Bootstrap 5
```

从而避免额外维护 Node.js / TypeScript 前端工程。

---

# 1. 项目一句话定义

**Tailcat Mesh 是一个基于 Tailcat 数据平面的、自托管的设备与 TCP 服务 Mesh 管理平台。**

用户安装 Tailcat Mesh Agent 后，通过统一 Web 管理面板管理多台设备，系统自动完成：

- Agent 注册
- 设备审批
- 在线状态
- Tailcat Server 启动与守护
- 稳定 Tailcat 身份管理
- ConnBlob 自动登记与分发
- Client 公钥授权
- NAT traversal
- DERP fallback
- Direct / DERP 状态检测
- TCP 服务发布
- 本地端口映射
- Tailcat 子进程自动重启

用户不再需要手工复制长 `tc...` token。

---

# 2. 为什么采用 Java-First 架构

Tailcat 官方库本身是 Go Library，但官方同时提供完整 CLI。

本项目 v0.1 不直接调用 Go Library，而把官方 CLI 当作一个稳定边界较清晰的 **网络引擎进程**。

这样项目自身只需要重点维护 Java：

```text
Java Server
Java Agent
Java Shared Protocol
HTML / Thymeleaf / HTMX
官方 tailcat binary（外部依赖）
```

而不是：

```text
Java Server
Go Agent
Vue / TypeScript
Tailcat Go Library
```

v0.1 的优先目标不是做到理论上最优，而是：

> **以最少技术栈，先做出稳定可用的 Tailcat Mesh 产品闭环。**

如果未来 CLI 能力成为瓶颈，再在不修改 Server 协议的前提下新增 Native Engine。

---

# 3. 已确认的 Tailcat v0.3.0 CLI 能力

编码前必须以 `tailscale/tailcat v0.3.0` 为基线，不得使用网上旧版本参数猜测。

已确认 v0.3.0 CLI 支持：

```text
tailcat --version

tailcat --serve=22,80,443

tailcat --allow=<publicKey1>,<publicKey2>
tailcat --allow=none

tailcat --key=<name-or-private-json-path>

tailcat --full-address

tailcat --json

tailcat --derpmap-url=<url>

tailcat ping <token>
tailcat ping --until-direct --timeout=10s <token>

tailcat socks --listen=127.0.0.1:<port> <token>

tailcat parse <token>
tailcat resolve <token>

tailcat printpub

tailcat genkey
tailcat genkey --client
tailcat genkey --key=<name>
tailcat genkey --delete --key=<name>
tailcat genkey --list
```

关键事实：

1. Tailcat 不依赖 Tailscale control plane。
2. Tailcat 的连接 metadata 可以通过任何带外方式交换。
3. Tailcat 使用 userspace WireGuard、magicsock 和 gVisor Netstack。
4. Tailcat 自身不创建系统 TUN/TAP，也不修改系统路由和 DNS。
5. M0-M6 继续保持不修改系统路由；M7 的 Virtual LAN Overlay 由 Tailcat Mesh 额外创建 TUN 与虚拟路由。
6. 普通 Tailcat tunnel 不要求管理员/root；M7 创建 TUN/修改路由时需要管理员/root 权限。
7. 初始连接通过 DERP bootstrap，之后尝试升级为 Direct UDP P2P。
8. 穿透失败时继续使用 DERP。
9. `--allow` 为空意味着允许任意持有 token 的 client。
10. `--allow=none` 可明确拒绝所有 client。
11. `--allow` 支持逗号分隔多个 client public key。
12. `--json` 在 Server 模式可输出结构化的 `listenAddr`。
13. `tailcat socks` 可启动持久 SOCKS5 proxy，并可指定固定 Tailcat Server token。
14. SOCKS 中的特殊主机名 `server.tailcat` 表示当前绑定的 Tailcat Server。
15. `tailcat ping` 输出可区分 `DERP(region)` 或 direct endpoint。

官方来源：

- https://github.com/tailscale/tailcat/tree/v0.3.0
- https://github.com/tailscale/tailcat/blob/v0.3.0/README.md
- https://github.com/tailscale/tailcat/blob/v0.3.0/cmd/tailcat/tailcat.go

---

# 4. v0.1 的产品边界

## 4.1 v0.1 必须实现

### Server

- 管理员登录
- Mesh 网络
- 注册 Token
- 设备注册
- 设备审批
- 设备删除 / 禁用
- Agent 在线 / 离线状态
- Agent 心跳
- Agent WebSocket 控制通道
- 设备 Tailcat Server token 登记
- 设备 Tailcat Client public key 登记
- Mesh 成员授权计算
- Service 管理
- Local Forward 管理
- Peer 状态
- Direct / DERP 状态
- 简单审计日志
- 系统设置
- DERP 配置记录

### Agent

- Java 21 单机 Agent
- 首次注册
- Agent credential 持久化
- Tailcat 二进制探测
- Tailcat 版本校验
- Tailcat Server key 初始化
- Tailcat Client key 初始化
- Tailcat Server process supervisor
- Tailcat SOCKS process supervisor
- Tailcat ping runner
- Tailcat parse runner
- Service Bridge
- Local Forward
- SOCKS5 CONNECT client
- WebSocket command client
- 心跳
- 配置同步
- 子进程异常恢复
- 本地日志

### Web

- Login
- Dashboard
- Devices
- Device Detail
- Services
- Forwards
- Connections
- Settings
- Audit Logs

---

## 4.2 v0.1 明确不做

以下能力不要在第一阶段实现：

- 二层以太网桥接（Layer 2 Ethernet）
- ARP 广播透传
- UDP Virtual LAN 数据面
- mDNS / SSDP / NetBIOS 广播发现
- 原生 ICMP over Tailcat 数据面
- 虚拟 IPv6 地址池
- MagicDNS
- UDP Service Forward
- Exit Node 产品化
- Subnet Router 产品化
- 手机客户端
- OIDC / SSO
- SaaS 多租户计费
- Kubernetes
- 自研 DERP Server
- Java 重写 WireGuard / magicsock / Tailcat 协议

v0.1 扩展目标是：

> **Device Management + TCP Service Mesh + TCP Virtual LAN Overlay**

其中 M7 的“Virtual LAN”是 **TCP-first 的三层虚拟网络体验**：设备有稳定虚拟 IPv4，可直接访问同一 Mesh Network 内其他设备的 TCP 端口；它不是完整二层 LAN，也不承诺 UDP/广播/ICMP。

---

# 5. 总体架构

```text
┌──────────────────────────────────────────────────────┐
│                Tailcat Mesh Server                   │
│                                                      │
│ Java 21 + Spring Boot                                │
│                                                      │
│ Auth / Device / Mesh / Service / Forward            │
│ Agent WS / Peer State / Audit / DERP Config         │
│ PostgreSQL                                           │
│ Thymeleaf + HTMX Web UI                              │
└──────────────────────┬───────────────────────────────┘
                       │
                 HTTPS / WSS
                       │
          ┌────────────┴────────────┐
          │                         │
          ▼                         ▼
┌─────────────────────┐   ┌─────────────────────┐
│ Mesh Agent A        │   │ Mesh Agent B        │
│ Java 21             │   │ Java 21             │
│                     │   │                     │
│ Agent Core          │   │ Agent Core          │
│ TailcatCliEngine    │   │ TailcatCliEngine    │
│ ProcessSupervisor   │   │ ProcessSupervisor   │
│ ServiceBridge       │   │ ServiceBridge       │
│ LocalForward        │   │ LocalForward        │
│ SOCKS Client        │   │ SOCKS Client        │
└─────────┬───────────┘   └──────────┬──────────┘
          │ ProcessBuilder           │ ProcessBuilder
          ▼                          ▼
┌─────────────────────┐   ┌─────────────────────┐
│ Official tailcat    │   │ Official tailcat    │
│ v0.3.0 binary       │   │ v0.3.0 binary       │
└─────────┬───────────┘   └──────────┬──────────┘
          │                          │
          └── WireGuard / DERP / P2P┘
```

**Tailcat Mesh Server 永远不转发业务流量。**

业务流量必须直接在 Agent 之间通过 Tailcat 数据面传输。

---

# 6. 技术栈

## 6.1 Server

```text
Java 21 LTS
Spring Boot 4.x
Spring MVC
Spring Security
Spring WebSocket
Spring Data JPA
PostgreSQL 16+
Flyway
Jackson
Thymeleaf
HTMX
Bootstrap 5
Micrometer / Actuator
JUnit 5
Testcontainers
Maven
```

Redis v0.1 不强制。

单实例 Server 可在 JVM 内维护 Agent WebSocket session registry。

---

## 6.2 Agent

Agent 不需要 Spring Boot。

推荐：

```text
Java 21
Maven
Jackson
SLF4J + Logback
JDK java.net.http.HttpClient
JDK java.net.http.WebSocket
ExecutorService / Virtual Threads
JUnit 5
```

Agent 应尽量轻量化。

可使用 Java 21 Virtual Threads 处理：

- TCP bridge
- Local Forward
- stdout/stderr drain
- WebSocket task

---

## 6.3 分发

建议最终包：

### Windows

```text
TailcatMesh/
├─ tailcat-mesh-agent.exe 或 bat launcher
├─ runtime/                 # jlink runtime，可选
├─ lib/
├─ bin/
│  └─ tailcat.exe
└─ config/
```

### Linux

```text
/opt/tailcat-mesh/
├─ agent.jar
├─ bin/tailcat
└─ config/

/etc/tailcat-mesh/agent.yml
/var/lib/tailcat-mesh/
/var/log/tailcat-mesh/
```

Tailcat binary 作为第三方运行时依赖随 Agent 打包或由管理员指定路径。

不得要求目标机器安装 Go toolchain。

---

# 7. Maven 仓库结构

使用 Maven multi-module mono-repo：

```text
tailcat-mesh/
│
├─ pom.xml
│
├─ tailcat-mesh-protocol/
│  ├─ pom.xml
│  └─ src/main/java/
│     └─ .../protocol/
│
├─ tailcat-mesh-server/
│  ├─ pom.xml
│  └─ src/
│     ├─ main/java/
│     ├─ main/resources/
│     │  ├─ templates/
│     │  ├─ static/
│     │  ├─ application.yml
│     │  └─ db/migration/
│     └─ test/
│
├─ tailcat-mesh-agent/
│  ├─ pom.xml
│  └─ src/
│     ├─ main/java/
│     ├─ main/resources/
│     └─ test/
│
├─ deploy/
│  ├─ docker-compose.yml
│  ├─ systemd/
│  └─ windows/
│
└─ docs/
   ├─ architecture.md
   ├─ protocol.md
   ├─ security.md
   └─ operations.md
```

---

# 8. 模块职责

## 8.1 `tailcat-mesh-protocol`

只放 Server 与 Agent 共用的协议模型：

- WebSocket Envelope
- Command DTO
- Event DTO
- Registration DTO
- Heartbeat DTO
- Device Capability DTO
- Tailcat Runtime DTO
- Service DTO
- Forward DTO

禁止放 Spring Data Entity。

禁止让 Agent 依赖 Server module。

---

## 8.2 Server package 建议

```text
com.tailcatmesh.server
├─ auth
├─ user
├─ mesh
├─ device
├─ enrollment
├─ agentws
├─ service
├─ forward
├─ peer
├─ derp
├─ audit
├─ web
└─ common
```

---

## 8.3 Agent package 建议

```text
com.tailcatmesh.agent
├─ bootstrap
├─ config
├─ identity
├─ control
├─ command
├─ tailcat
│  ├─ TailcatEngine.java
│  ├─ TailcatCliEngine.java
│  ├─ TailcatCommandFactory.java
│  ├─ TailcatCliParser.java
│  ├─ TailcatProcessSupervisor.java
│  └─ model/
├─ service
├─ forward
├─ socks
├─ status
└─ util
```

---

# 9. TailcatEngine 抽象层

这是整个 Java Agent 最重要的边界。

业务层禁止直接：

```java
new ProcessBuilder("tailcat", ...)
```

只能依赖：

```java
public interface TailcatEngine {
    TailcatVersion getVersion();

    TailcatIdentity ensureIdentity(TailcatIdentityConfig config);

    TailcatServerHandle startServer(TailcatServerConfig config);

    void stopServer();

    void restartServer(TailcatServerConfig config);

    TailcatPeerProxyHandle startPeerProxy(
        UUID peerDeviceId,
        String connBlob,
        TailcatPeerProxyConfig config
    );

    void stopPeerProxy(UUID peerDeviceId);

    TailcatPingResult ping(String connBlob, Duration timeout);

    TailcatTokenInfo parseToken(String connBlob);

    TailcatRuntimeStatus getRuntimeStatus();

    void shutdown();
}
```

v0.1 只有：

```text
TailcatEngine
└─ TailcatCliEngine
```

未来允许新增：

```text
TailcatEngine
├─ TailcatCliEngine
└─ TailcatNativeEngine
```

Native Engine 可以以后通过：

- JNI
- sidecar RPC
- Go native helper

实现，但不能影响 Server API。

---

# 10. Tailcat CLI 调用原则

## 10.1 禁止 Shell 拼接

必须使用：

```java
new ProcessBuilder(List.of(...))
```

禁止：

```text
cmd /c "tailcat ..."
sh -c "tailcat ..."
```

除非平台安装脚本确实需要。

所有用户输入不得未经校验进入命令参数。

---

## 10.2 优先结构化输出

Tailcat Server 启动必须使用：

```text
--json
```

读取：

```json
{"listenAddr":"tc..."}
```

禁止从以下人类日志中正则提取 token：

```text
🐈 Server listening ...
```

除非未来兼容旧版 Tailcat 且明确放在 compatibility parser。

---

## 10.3 CLI 文本解析必须集中

目前 `tailcat ping` 没有 JSON 输出，因此 v0.1 可以解析官方格式：

```text
pong in 42.1ms via DERP(sfo)
```

或：

```text
pong in 1.2ms via 203.0.113.7:41641
```

但解析代码只能存在于：

```text
TailcatCliParser
```

不得在 DeviceService / ForwardService 等业务代码出现正则。

解析失败必须返回：

```text
UNKNOWN
```

不能导致 Agent 崩溃。

---

# 11. Tailcat 二进制版本策略

v0.1 默认严格支持：

```text
0.3.x
```

Agent 启动：

```text
tailcat --version
```

解析版本。

定义：

```java
enum TailcatCompatibility {
    SUPPORTED,
    UNSUPPORTED_OLDER,
    UNSUPPORTED_NEWER,
    UNKNOWN
}
```

推荐策略：

- `0.3.x`：允许启动
- `<0.3.0`：拒绝并提示升级
- `>=0.4.0`：默认警告并拒绝，除非 `allowUnsupportedTailcat=true`

原因：Tailcat 官方明确说明 CLI / API / wire format 当前不保证稳定。

---

# 12. Agent 身份设计

必须区分三种身份。

## 12.1 Control Plane Device Identity

Tailcat Mesh 自己的设备身份：

```text
device_id UUID
agent_credential random secret
```

用于：

- REST
- WebSocket
- Server 对 Agent 的认证

与 Tailcat WireGuard key 无关。

---

## 12.2 Tailcat Server Identity

每个 Agent 有一套持久 server key。

建议命名：

```text
tailcat-mesh-server
```

用于：

```text
其他 Agent -> 本机 Tailcat Server
```

server private key 永远留在本机。

Server 只上传：

- Tailcat ConnBlob
- 可由 token 解析得到的 Server Public metadata

---

## 12.3 Tailcat Client Identity

每个 Agent 再有一套持久 client key。

建议命名：

```text
tailcat-mesh-client
```

用于本机连接其他 Agent。

Server 只登记：

```text
client_public_key
```

client private key 永远不上报。

---

# 13. 为什么 v0.1 使用“每台设备一个稳定 Client Key”

Go Library 版本可以动态调用 `AddAllowedClient`，但 CLI 模式下 `--allow` 是 Server process 启动参数。

因此 v0.1 不采用“每次连接生成临时 Client Key”。

改为：

```text
Device A
Client Key A（稳定）

Device B Server
--allow=<Client Public Key A>
```

Mesh 成员允许关系改变时，由 Java Agent 重启 Tailcat Server process 并更新 `--allow`。

这是 CLI Engine 模式下的重要取舍。

---

# 14. 默认安全策略：deny by default

Tailcat v0.3.0：

```text
--allow 为空 = 所有 client 可连接
```

因此 Tailcat Mesh **绝对不能**在生产默认配置里省略 `--allow`。

当没有已批准 Peer 时：

```text
--allow=none
```

存在 Peer 时：

```text
--allow=nodekey:A,nodekey:B,nodekey:C
```

Server 负责根据 Mesh 成员关系计算目标 Agent 应允许的 client public key 集合。

---

# 15. Tailcat Server Process 模型

每台 Agent 长期维护一个 Tailcat Server process。

示意：

```text
tailcat
  --key=tailcat-mesh-server
  --serve=45101,45102,45103
  --allow=nodekey:A,nodekey:B
  --full-address
  --json
```

如果配置了自建 DERP Map：

```text
--derpmap-url=https://mesh.example.com/derpmap.json
```

注意：

- Server key 必须稳定。
- 允许列表必须显式传入。
- `--json` 用于可靠读取 ConnBlob。
- `--full-address` 推荐启用，减少客户端额外获取 DERP map 的需要。

---

# 16. Tailcat Server 重启策略

以下配置改变，需要重启 Tailcat Server process：

- allowed client list
- served port list
- Tailcat server key
- DERP region / DERP map 相关关键配置

v0.1 接受：

> 服务配置或 Mesh 成员变化时，现有 Tailcat TCP 连接可能短暂断开。

Agent 必须：

1. debounce 2 秒聚合配置变化；
2. 生成完整新配置；
3. 停止旧 Tailcat Server；
4. 启动新 Tailcat Server；
5. 读取新的 `listenAddr`；
6. 上报 Server；
7. 通知本地状态恢复 ONLINE。

同一稳定 server key + 同一 relay region 下 token 应保持稳定；实现中仍必须比较新旧 token，如果变化则立即上报。

---

# 17. Service Bridge 设计

这是 Java CLI 方案解决“Tailcat --serve 只能转发 localhost 同端口”的关键设计。

假设用户发布服务：

```text
Name: NAS Web
Target: 192.168.1.20:5000
```

Java Agent 不要求 Tailcat 直接连接这个地址。

Agent 创建一个 loopback bridge：

```text
127.0.0.1:45123
      │
      ▼
192.168.1.20:5000
```

然后 Tailcat Server：

```text
--serve=45123
```

远端通过 Tailcat 连接：

```text
server.tailcat:45123
```

最终链路：

```text
Remote User App
      │
Local Forward
      │
Tailcat SOCKS
      │
WireGuard / DERP
      │
Remote Tailcat Server :45123
      │
Java ServiceBridge :45123
      │
192.168.1.20:5000
```

---

# 18. ServiceBridge 实现

定义：

```java
public interface ServiceBridge {
    ServiceBridgeHandle start(ServiceRuntimeConfig config);
    void stop(UUID serviceId);
}
```

配置：

```text
serviceId
bindHost = 127.0.0.1
bridgePort
upstreamHost
upstreamPort
connectTimeout
idleTimeout
```

bridgePort 分配：

- 只绑定 `127.0.0.1`
- 可以使用系统可用端口动态分配
- Agent 运行期间保持稳定
- Agent 重启后允许改变
- 变化后必须上报 Server

推荐用 Java Virtual Thread 为每条连接执行双向 copy。

---

# 19. Peer SOCKS Process 模型

不要为每个 TCP connection 启动一个 `tailcat` 进程。

v0.1 推荐：

> **每个远端 Peer 维护一个长期 `tailcat socks` process。**

例如 Agent A 连接 Device B：

```text
tailcat
  --key=tailcat-mesh-client
  socks
  --listen=127.0.0.1:46101
  <B_CONN_BLOB>
```

该 SOCKS process 可以复用多个业务 TCP connection。

Java Agent 内维护：

```text
PeerDeviceId -> TailcatPeerProxyHandle
```

Handle 包含：

```text
process
localSocksHost
localSocksPort
connBlob
startedAt
restartCount
status
```

---

# 20. Local Forward 设计

用户配置：

```text
Remote Device: NAS-B
Remote Service: WebUI
Local Bind: 127.0.0.1:10080
```

Java Agent A：

1. 确保 NAS-B 的 Peer SOCKS process 已运行。
2. 在本地监听 `127.0.0.1:10080`。
3. 每来一条连接，连接 Peer SOCKS 地址。
4. 执行 SOCKS5 `CONNECT`：

```text
server.tailcat:<remoteBridgePort>
```

5. 成功后双向 copy。

链路：

```text
Browser
  │
127.0.0.1:10080
  │
Java LocalForward
  │
127.0.0.1:46101 SOCKS5
  │
tailcat socks process
  │
WireGuard / DERP
  │
NAS-B tailcat server
  │
server.tailcat:<bridgePort>
  │
NAS-B Java ServiceBridge
  │
真实服务
```

---

# 21. SOCKS5 客户端

为减少第三方依赖，v0.1 可以自行实现最小 SOCKS5 CONNECT client。

只需支持：

```text
VER = 0x05
METHOD = NO AUTH (0x00)
CMD = CONNECT (0x01)
ATYP = DOMAIN / IPv4
```

不需要：

- SOCKS authentication
- UDP ASSOCIATE
- BIND

必须有单元测试覆盖握手字节序列和错误响应。

如选择成熟、体积小的 Java SOCKS 库，也可以，但不得引入庞大网络框架只为 SOCKS。

---

# 22. Peer Path 状态检测

每个在线 Peer 每隔建议 30 秒执行：

```text
tailcat --key=tailcat-mesh-client ping --timeout=5s <connBlob>
```

注意实际全局 flag 必须放在 CLI 能正确解析的位置，CommandFactory 根据 v0.3.0 集成测试固定生成方式。

解析：

```text
pong in 42.1ms via DERP(sfo)
```

映射：

```text
pathType = DERP
latencyMs = 42.1
derpRegion = sfo
```

解析：

```text
pong in 1.2ms via 203.0.113.7:41641
```

映射：

```text
pathType = DIRECT
latencyMs = 1.2
endpoint = 203.0.113.7:41641
```

错误：

```text
pathType = OFFLINE / UNKNOWN
```

不要因为 Direct 不成功就认为 Peer 不可用。

DERP 是合法工作状态。

---

# 23. Agent 首次启动流程

```text
START
 │
 ├─ 读取 agent.yml
 │
 ├─ 找到 tailcat binary
 │
 ├─ tailcat --version
 │
 ├─ 检查兼容性
 │
 ├─ 读取 local agent identity
 │
 ├─ 若未注册：使用 enrollment token 注册
 │
 ├─ 初始化 Tailcat server key
 │
 ├─ 初始化 Tailcat client key
 │
 ├─ 获取 client public key
 │
 ├─ 上报设备信息
 │
 ├─ 获取当前 Desired State
 │
 ├─ 创建 Service Bridges
 │
 ├─ 启动 Tailcat Server
 │
 ├─ 上报 ConnBlob
 │
 ├─ 建立 WebSocket
 │
 ├─ 启动 heartbeat
 │
 ├─ 根据 Forwards 启动 Peer SOCKS
 │
 └─ RUNNING
```

---

# 24. Enrollment 注册流程

管理员在 Web 创建：

```text
Enrollment Token
```

属性：

```text
id
network_id
token_hash
expires_at
max_uses
used_count
enabled
created_at
```

Agent 初次注册：

```http
POST /api/v1/agent/enroll
```

请求：

```json
{
  "enrollmentToken": "tm_enroll_xxx",
  "hostname": "DESKTOP-A",
  "os": "windows",
  "arch": "amd64",
  "agentVersion": "0.1.0",
  "tailcatVersion": "0.3.0",
  "clientPublicKey": "nodekey:..."
}
```

返回：

```json
{
  "deviceId": "uuid",
  "agentCredential": "tm_agent_xxx",
  "status": "PENDING"
}
```

`agentCredential` 只在创建时返回一次明文。

数据库只存 hash。

---

# 25. Agent 控制认证

后续 Agent 请求使用：

```http
Authorization: Bearer <agentCredential>
```

WebSocket：

```text
wss://server/api/v1/agent/ws
```

通过 Authorization header 或标准握手 token 认证。

生产必须 HTTPS/WSS。

禁止在 URL query 中长期暴露 Agent credential。

---

# 26. WebSocket 协议

统一 Envelope：

```json
{
  "id": "uuid",
  "type": "DESIRED_STATE_CHANGED",
  "timestamp": "2026-08-31T10:00:00Z",
  "payload": {}
}
```

Server -> Agent 至少支持：

```text
SYNC_DESIRED_STATE
RESTART_TAILCAT_SERVER
REFRESH_PEER
STOP_PEER
PING_NOW
RELOAD_CONFIG
```

Agent -> Server：

```text
HELLO
HEARTBEAT
TAILCAT_SERVER_READY
TAILCAT_SERVER_FAILED
PEER_STATUS
SERVICE_STATUS
FORWARD_STATUS
AGENT_ERROR
```

所有 command 必须幂等或携带 desired-state version。

---

# 27. Desired State 模型

不要让 Server 发送一串“执行步骤”作为唯一事实源。

Agent 应定期获取完整 Desired State：

```json
{
  "revision": 25,
  "allowedClientPublicKeys": [
    "nodekey:..."
  ],
  "services": [],
  "forwards": [],
  "derp": {},
  "settings": {}
}
```

Agent 本地执行：

```text
Current State
vs
Desired State
```

并自行 reconcile。

WebSocket 主要负责通知“有变化”，而不是成为唯一状态存储。

---

# 28. Heartbeat

默认：

```text
interval = 15 seconds
```

Heartbeat：

```json
{
  "deviceId": "uuid",
  "agentVersion": "0.1.0",
  "tailcatVersion": "0.3.0",
  "desiredRevision": 25,
  "tailcatServerRunning": true,
  "serverConnBlobHash": "sha256:...",
  "servicesUp": 3,
  "forwardsUp": 2,
  "timestamp": "..."
}
```

Server：

```text
> 45 sec no heartbeat => OFFLINE
```

ConnBlob 本身不需要每次 heartbeat 全量上传。

---

# 29. 核心数据库表

## 29.1 `users`

```text
id UUID PK
username VARCHAR UNIQUE
password_hash VARCHAR
role VARCHAR
created_at TIMESTAMP
updated_at TIMESTAMP
```

---

## 29.2 `mesh_networks`

```text
id UUID PK
name VARCHAR
slug VARCHAR UNIQUE
created_at
updated_at
```

v0.1 默认创建：

```text
Default Mesh
```

---

## 29.3 `devices`

```text
id UUID PK
network_id UUID FK
name VARCHAR
hostname VARCHAR
os VARCHAR
arch VARCHAR
status VARCHAR
agent_version VARCHAR
tailcat_version VARCHAR
client_public_key TEXT
server_conn_blob TEXT
server_conn_blob_hash VARCHAR
last_seen_at TIMESTAMP
desired_revision BIGINT
created_at
updated_at
```

status：

```text
PENDING
ONLINE
OFFLINE
DISABLED
```

---

## 29.4 `agent_credentials`

```text
id UUID PK
device_id UUID FK
secret_hash VARCHAR
created_at
last_used_at
revoked_at
```

---

## 29.5 `enrollment_tokens`

```text
id UUID PK
network_id UUID FK
token_hash VARCHAR
expires_at TIMESTAMP
max_uses INT
used_count INT
enabled BOOLEAN
created_at
```

---

## 29.6 `services`

```text
id UUID PK
device_id UUID FK
name VARCHAR
protocol VARCHAR DEFAULT TCP
target_host VARCHAR
target_port INT
enabled BOOLEAN
created_at
updated_at
```

运行时 bridge port 不属于静态配置，可独立保存。

---

## 29.7 `service_runtime`

```text
service_id UUID PK
bridge_port INT
status VARCHAR
last_error TEXT
updated_at
```

---

## 29.8 `forwards`

```text
id UUID PK
source_device_id UUID FK
remote_service_id UUID FK
name VARCHAR
local_bind_host VARCHAR
local_bind_port INT
enabled BOOLEAN
created_at
updated_at
```

默认：

```text
local_bind_host = 127.0.0.1
```

除非管理员明确允许，否则 Web 不提供 `0.0.0.0`。

---

## 29.9 `peer_status`

```text
source_device_id UUID
peer_device_id UUID
status VARCHAR
path_type VARCHAR
latency_ms DOUBLE PRECISION
derp_region VARCHAR
direct_endpoint VARCHAR
last_check_at TIMESTAMP
last_error TEXT
PRIMARY KEY(source_device_id, peer_device_id)
```

---

## 29.10 `audit_logs`

```text
id UUID PK
actor_type VARCHAR
actor_id VARCHAR
action VARCHAR
resource_type VARCHAR
resource_id VARCHAR
detail_json JSONB
created_at TIMESTAMP
```

---

# 30. REST API 草案

## Admin / Web

```text
POST   /api/v1/auth/login
POST   /api/v1/auth/logout

GET    /api/v1/devices
GET    /api/v1/devices/{id}
POST   /api/v1/devices/{id}/approve
POST   /api/v1/devices/{id}/disable
DELETE /api/v1/devices/{id}

POST   /api/v1/enrollment-tokens
GET    /api/v1/enrollment-tokens
DELETE /api/v1/enrollment-tokens/{id}

GET    /api/v1/services
POST   /api/v1/services
PUT    /api/v1/services/{id}
DELETE /api/v1/services/{id}

GET    /api/v1/forwards
POST   /api/v1/forwards
PUT    /api/v1/forwards/{id}
DELETE /api/v1/forwards/{id}

GET    /api/v1/connections
GET    /api/v1/audit-logs
```

## Agent

```text
POST /api/v1/agent/enroll
POST /api/v1/agent/heartbeat
GET  /api/v1/agent/desired-state
POST /api/v1/agent/runtime/server
POST /api/v1/agent/runtime/services
POST /api/v1/agent/runtime/forwards
POST /api/v1/agent/runtime/peers
GET  /api/v1/agent/ws
```

---

# 31. 服务发布流程

管理员新增：

```text
Device B
Service: SSH
Target: 127.0.0.1:22
```

流程：

```text
Web
 ↓
Server DB
 ↓
increment desired_revision(B)
 ↓
WebSocket notify B
 ↓
Agent B GET desired state
 ↓
创建 ServiceBridge
127.0.0.1:<bridgePort> -> 127.0.0.1:22
 ↓
Tailcat Server served ports 改变
 ↓
restart Tailcat Server
 ↓
上报 ServiceRuntime bridgePort
 ↓
READY
```

---

# 32. Local Forward 创建流程

管理员：

```text
Source: Device A
Remote: Device B / SSH
Local: 127.0.0.1:10022
```

流程：

```text
Server 保存 Forward
 ↓
increment desired_revision(A)
 ↓
通知 Agent A
 ↓
Agent A 获取 B ConnBlob + SSH bridgePort
 ↓
确保 Peer B SOCKS process
 ↓
监听 127.0.0.1:10022
 ↓
用户 ssh root@127.0.0.1 -p 10022
 ↓
LocalForward -> SOCKS CONNECT server.tailcat:<bridgePort>
 ↓
Tailcat tunnel
 ↓
B ServiceBridge
 ↓
127.0.0.1:22
```

---

# 33. Tailcat Server allowlist 计算

v0.1 默认策略：

> 同一 Mesh Network 中所有 `APPROVED + 非 DISABLED` Device 可以互联。

因此 Device B 的 allowed client list：

```text
网络中除 B 自己外，所有批准设备的 client_public_key
```

如果列表为空：

```text
--allow=none
```

未来 v0.2 再加入 ACL。

---

# 34. Process Supervisor

所有 Tailcat 长期进程必须由统一 supervisor 管理。

定义：

```java
public interface ManagedProcess {
    ProcessState state();
    long pid();
    Instant startedAt();
    int restartCount();
    void stop(Duration timeout);
}
```

必须：

- 同时 drain stdout 和 stderr，避免 pipe buffer 卡死
- 有限长度 ring buffer 保存最近日志
- process exit 异常触发重启
- exponential backoff
- 防止 crash loop
- Agent shutdown 时优雅结束 child process

重启建议：

```text
1s
2s
5s
10s
30s
60s max
```

稳定运行 5 分钟后 reset restart counter。

---

# 35. Tailcat Server crash 行为

如果 Server process 退出：

Agent：

1. 标记 Tailcat Server DEGRADED；
2. 保存 exit code；
3. 保存 stderr tail；
4. 根据 backoff 重启；
5. 成功后读取 listenAddr；
6. 比较 token；
7. 上报 Server。

不能让整个 Java Agent 一起退出。

---

# 36. Peer SOCKS crash 行为

如果 Device B SOCKS process 退出：

- Device B peer status = DEGRADED
- 已有 Local Forward listener 保持运行
- 新连接返回明确失败
- Supervisor 尝试重启 SOCKS process
- 恢复后 Forward 自动恢复

不要要求用户重启 Agent。

---

# 37. Agent 与 Tailcat 配置目录隔离

必须避免和用户手动使用 Tailcat 的默认 key 冲突。

优先方案：

- 使用 `--key=<明确路径或专用 key name>`；
- 所有 Tailcat Mesh key 使用 `tailcat-mesh-*` 命名；
- Agent data directory 单独管理；
- 不删除用户已有的 `default` / `client-default` key。

编码 AI 必须先通过 v0.3.0 集成测试验证自定义 key 名称 / 路径行为，再固化实现。

禁止执行：

```text
tailcat genkey --delete --key=default
```

除非用户主动要求。

---

# 38. 配置文件

示例 `agent.yml`：

```yaml
server:
  url: https://mesh.example.com

tailcat:
  binary: ./bin/tailcat.exe
  supportedVersion: 0.3.x
  serverKey: tailcat-mesh-server
  clientKey: tailcat-mesh-client
  fullAddress: true
  derpMapUrl: ""

agent:
  dataDir: ./data
  heartbeatSeconds: 15
  peerPingSeconds: 30

forward:
  defaultBindHost: 127.0.0.1

logging:
  level: INFO
```

敏感 Agent credential 不建议直接明文写 YAML。

应保存到：

```text
data/identity/credential
```

并限制权限。

---

# 39. Server 配置

`application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://postgres:5432/tailcat_mesh
    username: tailcat_mesh
    password: ${DB_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: validate

server:
  forward-headers-strategy: framework

tailcat-mesh:
  security:
    require-https: true
  agent:
    heartbeat-timeout-seconds: 45
  enrollment:
    default-expire-hours: 24
```

生产密码只允许环境变量 / secret store。

---

# 40. Web UI 信息架构

## Dashboard

显示：

```text
Devices Online
Devices Offline
Services
Active Forwards
Direct Peers
DERP Peers
```

---

## Devices

```text
Name
OS
Agent Version
Tailcat Version
Online
Server State
Last Seen
```

---

## Device Detail

```text
Device Info
Tailcat Runtime
Client Public Key
ConnBlob（默认隐藏，可复制）
Services
Forwards
Peer Connections
Recent Errors
```

ConnBlob 属于敏感 capability，不要在列表页直接显示完整内容。

---

## Services

```text
Name
Device
Target
Bridge Port
Status
```

---

## Forwards

```text
Name
Source Device
Remote Service
Local Address
Status
```

---

## Connections

```text
Source
Target
DIRECT / DERP / OFFLINE
Latency
DERP Region / Endpoint
Last Checked
```

---

# 41. 安全要求

## 41.1 Tailcat private key

绝不上传：

- Server private key
- Client private key

控制面只保存：

- client public key
- ConnBlob

---

## 41.2 ConnBlob

ConnBlob 相当于连接 capability，必须按敏感数据处理。

要求：

- API 不在普通日志输出完整 token
- UI 默认掩码
- Audit 不记录完整 token
- Exception 不包含完整 token

建议日志只写：

```text
connBlobHash = SHA-256(...)
```

---

## 41.3 Agent credential

- 至少 256-bit 随机值
- Server 只存 hash
- 支持 revoke
- HTTPS only
- 不写普通日志

---

## 41.4 `--allow`

禁止因计算异常回退为“无 --allow 参数”。

如果 allowlist 计算失败：

```text
fail closed
```

即启动：

```text
--allow=none
```

---

## 41.5 Local Forward

默认只能绑定：

```text
127.0.0.1
::1
```

`0.0.0.0` / 外部地址需要管理员显式打开高级选项，并在 UI 警告。

---

# 42. 自建 DERP 支持

v0.1 不负责自动部署 DERP Server，但要预留配置。

支持两种思路：

## A. 自定义 DERP hostname 嵌入 token

官方 Tailcat 支持以自建 DERP hostname 生成 server key，例如：

```text
tailcat genkey --region=derp.example.com
```

生成的 token 会包含自建 relay hostname。

## B. 自定义 DERP Map

```text
--derpmap-url=https://mesh.example.com/derpmap.json
```

Tailcat Mesh 设置页保存：

```text
mode = DEFAULT | CUSTOM_MAP | CUSTOM_HOST
```

v0.1 可以先完成配置模型和 Agent 透传，不要求 Web 自动创建 DERP。

---

# 43. 完全脱离 Tailscale 控制面的目标

Tailcat 本身不需要 Tailscale control plane。

Tailcat Mesh Server 自己负责：

- 用户
- 设备
- enrollment
- metadata
- Peer discovery
- allowlist
- service config

如果同时使用自建 DERP，则数据面可以不依赖 Tailscale 官方登录 / 管理服务。

项目 README 必须避免写成“官方 Tailscale 替代产品”。

---

# 44. 日志规范

Agent 日志字段至少包含：

```text
time
level
component
deviceId
peerDeviceId optional
serviceId optional
forwardId optional
event
message
```

组件：

```text
CONTROL
TAILCAT_SERVER
TAILCAT_SOCKS
SERVICE_BRIDGE
LOCAL_FORWARD
HEARTBEAT
RECONCILER
```

禁止输出：

- private key
- agentCredential
- 完整 ConnBlob
- 用户密码

---

# 45. 审计日志

至少记录：

```text
LOGIN_SUCCESS
LOGIN_FAILED
DEVICE_ENROLLED
DEVICE_APPROVED
DEVICE_DISABLED
DEVICE_DELETED
SERVICE_CREATED
SERVICE_UPDATED
SERVICE_DELETED
FORWARD_CREATED
FORWARD_UPDATED
FORWARD_DELETED
DERP_CONFIG_UPDATED
```

---

# 46. 错误码

统一错误码，例如：

```text
TM-AGENT-001 Tailcat binary not found
TM-AGENT-002 Tailcat version unsupported
TM-AGENT-003 Tailcat server failed to start
TM-AGENT-004 Tailcat server JSON invalid
TM-AGENT-005 Peer SOCKS failed
TM-AGENT-006 SOCKS handshake failed
TM-AGENT-007 Local port occupied
TM-AGENT-008 Service upstream unavailable
TM-CTRL-001 Authentication failed
TM-CTRL-002 Enrollment token invalid
TM-CTRL-003 Device disabled
TM-CTRL-004 Desired state invalid
```

---

# 47. 测试要求

## Unit Test

至少覆盖：

- Tailcat CLI command building
- Tailcat version parser
- Tailcat JSON listenAddr parser
- ping parser Direct
- ping parser DERP
- ping parser unexpected output
- SOCKS5 handshake
- desired-state diff
- allowlist generation
- credential hashing
- Service Bridge copy

---

## Integration Test

需要真正使用官方 Tailcat v0.3.0 binary。

至少覆盖：

### IT-01 Tailcat server start

Java Agent 能启动：

```text
tailcat --json ...
```

并获得有效 `tc...`。

### IT-02 allow none

```text
--allow=none
```

未授权 client 无法访问。

### IT-03 allowed stable client

生成 stable client key，server 加入 public key 后可以连接。

### IT-04 SOCKS

Java 启动长期 `tailcat socks`，Java SOCKS client 能访问 `server.tailcat:<port>`。

### IT-05 Service Bridge

远端通过 Tailcat 能访问 Agent bridge 后的真实本地 TCP 服务。

### IT-06 Process restart

杀掉 tailcat child process，Agent 自动恢复。

---

# 48. E2E 验收场景

准备两台机器：

```text
A = Windows
B = Linux 或 Windows
```

Server：

```text
Java Spring Boot + PostgreSQL
```

步骤：

1. Web 创建 Enrollment Token。
2. A 安装 Agent 并注册。
3. B 安装 Agent 并注册。
4. Web 审批 A、B。
5. 两台设备显示 Online。
6. B 发布：

```text
Web Service
127.0.0.1:8080
```

7. A 创建：

```text
127.0.0.1:18080
-> B / Web Service
```

8. A 浏览器访问：

```text
http://127.0.0.1:18080
```

9. 能正常看到 B 的 Web 内容。
10. Web Connections 页面能显示：

```text
A -> B
DIRECT
```

或：

```text
A -> B
DERP(...)
```

两者都算成功。

11. 杀死 B 的 tailcat process。
12. Agent 自动重启。
13. Forward 自动恢复。

以上全部通过才算 v0.1 核心闭环完成。

---

# 49. 非功能要求

## 49.1 Agent CPU

空闲：

```text
目标 < 2%
```

不允许高频轮询。

## 49.2 Agent Memory

Java Agent 自身目标：

```text
< 150 MB idle
```

不含 tailcat child processes。

## 49.3 控制面不可达

如果 Tailcat Mesh Server 暂时离线：

- Agent 不立即杀死已有 Tailcat Server
- 已建立的本地静态 Forward 尽可能继续工作
- 使用最后一次 Desired State
- 控制连接后台重试

说明：如果 peer SOCKS 本身仍在，则数据连接可继续由 Tailcat 数据面维持。

---

# 50. Reconciler

Agent 不应到处写“收到 command 就直接改进程”的 spaghetti code。

核心组件：

```java
public interface AgentReconciler {
    ReconcileResult reconcile(
        DesiredState desired,
        CurrentRuntimeState current
    );
}
```

Reconcile 顺序：

```text
Identity
Service Bridges
Tailcat Server config
Peer SOCKS
Local Forwards
Runtime report
```

相同 Desired State 重复下发必须安全。

---

# 51. 并发模型

推荐 Java 21 Virtual Threads：

```java
Executors.newVirtualThreadPerTaskExecutor()
```

适合：

- TCP copy
- bridge connection
- local forward connection
- stdout/stderr reader

长期状态仍通过清晰的 manager / synchronized / lock 保护。

不要因为用了 Virtual Thread 就允许并发修改同一个 process state。

---

# 52. 端口冲突

Local Forward 若端口被占用：

```text
status = ERROR
code = TM-AGENT-007
```

不能偷偷换端口，因为用户配置的就是该地址。

Service Bridge 的内部端口可以重新分配。

---

# 53. Web UI 风格

v0.1 目标：

- 清晰
- 简洁
- 工具型
- 深色模式可后做
- 不追求动画

连接状态使用：

```text
● Direct
● DERP
● Offline
● Unknown
```

Device Detail 可以展示拓扑关系，但 v0.1 不做复杂可视化拓扑图。

---

# 54. Docker Compose

Server 提供：

```text
server
postgres
```

示意：

```yaml
services:
  postgres:
    image: postgres:16

  server:
    image: tailcat-mesh-server:0.1.0
    depends_on:
      - postgres
```

Agent 不建议跑在 Docker 作为第一优先体验，因为用户通常需要访问宿主机本地服务。

先提供 Windows Service / systemd。

---

# 55. Windows Agent

v0.1 Windows 优先级最高。

要求：

- Windows 10/11 amd64
- Agent 可前台运行调试
- 最终可安装为 Windows Service
- child tailcat.exe 随 Agent 生命周期管理
- 停服务时终止 Tailcat child process

可使用：

- WinSW 包装 Agent
- 或后续自研 service launcher

v0.1 不要求 MSI。

---

# 56. Linux Agent

提供 systemd：

```ini
[Unit]
Description=Tailcat Mesh Agent
After=network-online.target

[Service]
ExecStart=/usr/bin/java -jar /opt/tailcat-mesh/agent.jar
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

最终配置需考虑专用 service user 和 key 文件权限。

---

# 57. v0.1 开发 Milestones

## M0 — Skeleton

完成：

- Maven multi-module
- protocol
- server starts
- agent starts
- Flyway
- basic CI

验收：

```text
mvn clean verify
```

全绿。

---

## M1 — Tailcat CLI Engine

完成：

- binary discover
- version check
- command factory
- genkey wrapper
- printpub wrapper
- server `--json`
- process supervisor
- ping parser
- parse wrapper

验收：

Java integration test 能启动真实 tailcat v0.3.0。

---

## M2 — Control Plane Registration

完成：

- user login
- enrollment token
- agent enroll
- device list
- approve
- heartbeat
- WebSocket

验收：

两台 Agent 在 Web 显示 Online。

---

## M3 — Secure Tailcat Mesh

完成：

- stable client key
- client public key upload
- allowlist generation
- `--allow=none`
- `--allow=A,B`
- stable server process
- ConnBlob upload

验收：

仅 Mesh 已批准 Agent 可以连接目标 Tailcat Server。

---

## M4 — Services

完成：

- Service CRUD
- ServiceBridge
- dynamic bridge port
- Tailcat served-port reconcile
- runtime status

验收：

Remote Tailcat 能访问 B 发布的非同端口 upstream。

---

## M5 — Peer SOCKS

完成：

- persistent SOCKS process per peer
- peer process lifecycle
- ping path state
- Direct / DERP UI

验收：

A 能通过 SOCKS 访问 B service bridge。

---

## M6 — Local Forward

完成：

- Forward CRUD
- local TCP listener
- SOCKS5 CONNECT
- bidirectional copy
- status/error handling

验收：

```text
127.0.0.1:<localPort>
```

可以像普通本地 TCP 服务一样使用。

---

## M7 — Virtual LAN Overlay（TCP-first）

目标：

让用户可以在 Web 中创建一个 Mesh Network，选择若干已批准设备加入，并给每台设备分配稳定虚拟 IPv4。加入同一 Network 的设备应获得“像在同一个虚拟局域网里”的 TCP 使用体验。

示例：

```text
Mesh Network: home
CIDR: 10.77.0.0/24

DESKTOP-A   10.77.0.2
NAS-B       10.77.0.3
VPS-C       10.77.0.4
```

用户在 DESKTOP-A 上可以直接：

```text
ssh 10.77.0.3
curl http://10.77.0.4:8080
访问 \\10.77.0.3\share   # 仅依赖 TCP 的场景
```

### M7.1 Network 数据模型

新增：

```text
MeshNetwork
- id
- name
- cidr
- enabled
- createdAt

MeshNetworkMember
- id
- networkId
- deviceId
- virtualIpv4
- joinedAt
- enabled
```

要求：

- 一个 Network 至少 2 台设备才有组网意义；
- Device 可加入多个 Network；
- Network CIDR 之间不得重叠；
- virtualIpv4 在 Network 内唯一；
- virtualIpv4 对同一成员稳定，不因 Agent 重启改变；
- 默认 CIDR 可从 `10.77.0.0/16` 中创建子网，但创建时必须做本机/现有 Mesh CIDR 冲突检查；
- 删除成员时必须同步撤销 Peer allowlist、路由和运行时配置。

### M7.2 Network 隔离模型

M7 不复用 M3 的单一 Device Tailcat Server 来承载所有虚拟网络。

每个 `Device × MeshNetwork` 必须拥有独立的 Virtual Network Tailcat Server runtime：

```text
VirtualNetworkRuntime
  ├─ network-specific server key
  ├─ network-specific ConnBlob
  ├─ --serve=all
  └─ --allow=<同 Network 其他成员 client public keys>
```

原因：

- `--serve=all` 会暴露本机全部 TCP 端口；
- 使用 Network 独立 Server Key / ConnBlob / allowlist，才能保证不同 Network 之间逻辑隔离；
- 不允许简单把所有 Network Peer public key 合并到一个全局 `--allow`。

Network 私钥仍只保存在 Agent 本地，禁止上传 Server。

### M7.3 Virtual IP 数据路径

推荐实现：

```text
Application
   │ connect 10.77.0.3:445
   ▼
OS Route
   ▼
Tailcat Mesh TUN
   ▼
tun2socks sidecar
   ▼
Java MeshSocksRouter
   │ lookup 10.77.0.3 -> NAS-B / Network=home
   ▼
Peer Tailcat SOCKS process
   │ CONNECT server.tailcat:445
   ▼
Tailcat P2P / DERP
   ▼
NAS-B VirtualNetworkRuntime --serve=all
   ▼
127.0.0.1:445
```

说明：

- Tailcat 仍然只作为官方二进制网络引擎；
- 我们自己的业务代码仍全部使用 Java；
- `tun2socks` 是可替换的第三方 sidecar，不进入业务语言栈；
- Windows 初版使用 Wintun；Linux 使用系统 TUN；
- Java Agent 负责启动、停止、健康检查和回收 tun2socks/Wintun/TUN 运行时；
- 不允许 Java 自己重写 TCP/IP stack。

### M7.4 MeshSocksRouter

Agent 新增：

```text
MeshSocksRouter
VirtualIpRouteTable
VirtualNetworkManager
VirtualNetworkRuntimeSupervisor
TunRuntime
Tun2SocksSupervisor
OsRouteManager
```

`MeshSocksRouter` 接收来自 tun2socks 的 SOCKS5 CONNECT：

```text
10.77.0.3:8080
```

先根据 `10.77.0.3` 找到目标 Device，再连接该 Device 在当前 Network 的 Tailcat SOCKS runtime，并把最终目标转换成：

```text
server.tailcat:8080
```

禁止直接把 `10.77.0.3` 当成远端真实 LAN IP 交给 Tailcat exit-node 逻辑。

### M7.5 TUN 与系统路由

M7 是第一个允许修改本机网络配置的阶段。

Windows：

- 使用 Wintun/TUN；
- 仅添加 Mesh CIDR 路由，不改默认路由；
- Agent 安装/启用 Virtual LAN 功能时需要管理员权限；
- 使用固定 adapter GUID/name，避免每次启动创建新网卡；
- Agent 崩溃或卸载时要尽力清理遗留 route。

Linux：

- 使用 `/dev/net/tun`；
- 仅添加 Mesh CIDR 路由；
- systemd service 需要相应 CAP_NET_ADMIN/root 能力；
- 不修改默认路由。

M7 不做全局 VPN，不允许把 `0.0.0.0/0` 指向 Tailcat Mesh。

### M7.6 Web UI

新增一级菜单：

```text
Networks
```

支持：

- 创建 Network；
- 设置名称与 CIDR；
- 从已批准设备中勾选成员；
- 自动/手动分配虚拟 IPv4；
- 展示成员 Online/Offline；
- 展示成员 Virtual IP；
- 展示 Peer 当前 DIRECT / DERP；
- Add Device / Remove Device；
- Enable / Disable Network；
- 删除 Network。

设备详情页增加：

```text
Virtual Networks
- home      10.77.0.2
- dev       10.78.0.7
```

### M7.7 TCP 能力边界

Tailcat v0.3.0 当前应用层 API/CLI 主要承载 TCP。

因此 M7 验收只承诺：

- TCP；
- 虚拟 IPv4；
- 同 Network 设备间按虚拟 IP 访问；
- Direct / DERP 均可作为底层路径。

M7 **不承诺**：

- UDP；
- `ping`/ICMP 一定可用；
- mDNS；
- SSDP；
- NetBIOS 广播；
- 游戏局域网广播发现；
- Layer-2 Ethernet broadcast/multicast。

如果后续 Tailcat 官方暴露稳定 UDP API，再在 M9+ 扩展真正 Full L3 Virtual LAN。

### M7.8 运行时与重连

- Control Server 临时断线时，已有 Virtual LAN runtime 尽可能继续工作；
- Peer ConnBlob 更新后仅重建对应 Peer runtime，不重启整个 Agent；
- Network membership 变更后增量 reconcile；
- Virtual Network Tailcat child process 崩溃后自动 supervisor restart；
- tun2socks 崩溃后自动重启；
- route reconcile 必须幂等；
- Agent 启动时检查并修复上一次异常退出留下的网卡/route 状态。

### M7.9 安全要求

- Virtual Network Server 必须显式 `--allow`；
- 只有同一 Network 成员可进入 allowlist；
- Network 无其他 Peer 时使用 `--allow=none`；
- 每 Network/Device 独立 Server key；
- 私钥永不上传；
- Web 不展示 ConnBlob/private key；
- Network 删除或成员移除后必须撤销访问；
- 不使用 `--serve=exit-node` 实现 M7；
- 不向 Mesh Network 发布默认路由。

### M7.10 验收

至少 3 台设备：A、B、C。

创建：

```text
Network home: A + B
Network dev : A + C
```

验收：

- A/B 获得 home 内稳定虚拟 IP；
- A/C 获得 dev 内稳定虚拟 IP；
- A 能使用 B 的 home 虚拟 IP 访问 B TCP 服务；
- A 能使用 C 的 dev 虚拟 IP 访问 C TCP 服务；
- B 不能因为 A 同时加入 dev 而访问 C；
- Agent 重启后虚拟 IP 不变；
- Direct 路径可工作；
- DERP fallback 也可工作；
- 从 Network 移除 B 后，A 无法再通过该 Network Virtual IP 访问 B；
- Windows E2E 通过；
- Linux E2E 通过；
- 不把 `ping`、UDP、广播发现作为 M7 成功条件。

---

## M8 — Packaging

完成：

- Docker Server
- Windows Agent package
- Windows Virtual LAN dependency package（按许可证要求分发 Wintun/tun2socks 或提供安装器获取机制）
- Linux systemd
- README
- basic release workflow

---

# 58. 编码顺序（非常重要）

编码 AI 不得一次性写完整系统。

严格顺序：

```text
1. Maven skeleton
2. Tailcat CLI Engine
3. Tailcat CLI integration tests
4. Server DB + enrollment
5. Agent control channel
6. Device approval
7. stable keys + allowlist
8. Tailcat server runtime
9. Service Bridge
10. Peer SOCKS
11. SOCKS5 Java client
12. Local Forward
13. Direct/DERP status
14. M7 MeshNetwork / Member / IPAM
15. M7 network-specific Tailcat runtime (`--serve=all` + per-network allowlist)
16. M7 MeshSocksRouter
17. M7 TUN + tun2socks + OS route manager
18. M7 Virtual LAN Web UI
19. M7 Windows/Linux E2E
20. Web UI polish
21. M8 packaging
```

**第 2~3 步未跑通，不允许开始大规模业务 UI。**

---

# 59. 给编码 AI 的第一轮任务

第一次把本规格交给编码 AI 时，只要求完成以下内容：

## Task 1

创建 Maven multi-module：

```text
tailcat-mesh-protocol
tailcat-mesh-server
tailcat-mesh-agent
```

## Task 2

在 Agent 实现：

```text
TailcatEngine
TailcatCliEngine
TailcatCommandFactory
TailcatCliParser
TailcatProcessSupervisor
```

## Task 3

实现：

```text
tailcat --version
```

并校验 0.3.x。

## Task 4

实现一个真实 integration test：

启动：

```text
tailcat --key=<test-server-key> --serve=<testPort> --allow=none --full-address --json
```

读取 JSON：

```json
{"listenAddr":"tc..."}
```

随后干净关闭子进程。

## Task 5

实现 `tailcat parse <token>` JSON wrapper。

第一轮完成后停止，不要提前写 Service / Forward。

---

# 60. AI 硬性约束

编码 AI 必须遵守：

1. 不引入 Go Agent。
2. 不 fork Tailcat 源码作为第一版方案。
3. 不重写 WireGuard。
4. 不自行实现 DERP protocol。
5. Tailcat CLI 只允许通过 `TailcatCliEngine` 调用。
6. CLI command 只允许通过 `TailcatCommandFactory` 生成。
7. 不用 shell string 拼接执行命令。
8. 私钥禁止上传 Server。
9. ConnBlob 禁止普通日志明文输出。
10. `--allow` 配置失败必须 fail closed 为 `none`。
11. 不因为 Direct 失败而判定连接失败；DERP 是合法路径。
12. M0-M6 不实现 TUN/TAP；只有 M7 Virtual LAN Overlay 可以引入 TUN。
13. 虚拟 IP 只能由 M7 的 MeshNetwork/IPAM 模块分配，业务模块不得自行生成。
14. M7 的 TUN/tun2socks 必须通过独立 Adapter/Supervisor 封装，不得散落 ProcessBuilder/系统命令到业务代码。
15. M7 不得宣称支持 UDP、广播、mDNS 或完整 Layer-2 LAN。
16. v0.1 不引入 Vue/React，除非用户后续明确决定改变方案。
17. 数据库变更全部使用 Flyway。
18. Server Entity 不得直接作为 Agent 协议 DTO。
19. WebSocket 断开不等于停止 Tailcat 数据面。
20. Agent 必须能独立 supervisor Tailcat child process。
21. 所有长期 child process 必须同时 drain stdout/stderr。
22. 所有 Tailcat 行为必须有 v0.3.0 integration tests 做事实依据。
23. M7 不允许使用 `--serve=exit-node` 偷代替 Virtual LAN。
24. M7 Network 隔离必须使用 network-specific runtime/identity/allowlist，不允许全局 peer allowlist 合并。

---

# 61. 已知 v0.1 限制

必须在 README 明确写出：

### 限制 1

M0-M6 不是系统级 VPN；M7 增加 TCP-first Virtual LAN Overlay 和虚拟 IPv4，但仍不是完整二层 LAN。

### 限制 2

Tailcat v0.3.0 的应用层能力以 TCP 为主。M7 的虚拟 IP 访问只承诺 TCP；UDP、ICMP、广播发现不在当前承诺范围。

### 限制 3

修改 allowlist / served port 时 Tailcat Server 需要重启，可能短暂断开已有连接。

### 限制 4

Tailcat CLI 输出格式未来可能改变。

### 限制 5

Tailcat v0.x 自身仍在快速演进。

### 限制 6

Public Tailcat DERP 可能限速；生产推荐自建 DERP。

---

# 62. v0.2 路线

完成 v0.1 后考虑：

- ACL
- Service permission
- Network policy / group policy
- MagicDNS / Device Name Resolver
- 自建 DERP 管理页
- DERP health check
- Agent auto update
- SOCKS pool 优化
- Metrics
- Prometheus
- API Token
- 更完整拓扑

---

# 63. v1.0 路线

如果项目验证成功，再评估：

```text
Full L3 Virtual LAN (TCP + UDP + ICMP)
Subnet Router
Exit Node
Magic DNS
UDP Service Mesh
Broadcast/Discovery compatibility where feasible
IPv6 Virtual Network
```

这时需要重新评估 CLI Engine 是否足够。

可能升级为：

```text
Java Control / Agent Core
        │
TailcatEngine
        ├─ CLI Engine
        └─ Native Sidecar Engine
```

但 Server 控制协议应保持兼容。

---

# 64. 项目命名与声明

项目名称：

```text
Tailcat Mesh
```

推荐描述：

> A self-hosted TCP service mesh and device management layer powered by Tailcat.

README 必须添加类似声明：

> Tailcat Mesh is an independent community project. It is not affiliated with, sponsored by, or endorsed by Tailscale Inc.

Tailcat、Tailscale 等名称及商标归其各自权利人所有。

---

# 65. 最终 v0.1 Definition of Done

只有同时满足以下条件才可以标记 `v0.1.0`：

- [ ] Server 一条命令可启动
- [ ] PostgreSQL migration 正常
- [ ] Admin 可登录
- [ ] 可生成 Enrollment Token
- [ ] Windows Agent 可注册
- [ ] Linux Agent 可注册
- [ ] Agent 可自动找到官方 Tailcat binary
- [ ] Agent 能检测 Tailcat v0.3.x
- [ ] 每个 Device 有独立稳定 Server Key
- [ ] 每个 Device 有独立稳定 Client Key
- [ ] Server private key 不上传
- [ ] Client private key 不上传
- [ ] Tailcat Server 使用显式 allowlist
- [ ] 零 Peer 时 `--allow=none`
- [ ] ConnBlob 自动上传
- [ ] 两台设备自动获得 Peer metadata
- [ ] ServiceBridge 可工作
- [ ] Peer SOCKS process 可长期运行
- [ ] Local Forward 可工作
- [ ] SSH 可通过 Local Forward 使用
- [ ] HTTP 可通过 Local Forward 使用
- [ ] 可创建 Mesh Network 并选择设备加入
- [ ] Mesh Network 可分配稳定且唯一的 Virtual IPv4
- [ ] Network CIDR 冲突可检测并阻止
- [ ] 同 Network 设备可直接使用 Virtual IPv4 访问 TCP 服务
- [ ] 不同 Network 成员隔离有效
- [ ] Network 成员移除后访问权限被撤销
- [ ] M7 network-specific Tailcat runtime 使用 `--serve=all` + 显式 allowlist
- [ ] Windows TUN/tun2socks/route E2E 通过
- [ ] Linux TUN/tun2socks/route E2E 通过
- [ ] 可区分 DIRECT / DERP
- [ ] Tailcat child process 崩溃后自动恢复
- [ ] Control Server 短暂断线时已有数据面尽可能继续工作
- [ ] UI 不显示 private key
- [ ] 日志不泄露 credential / ConnBlob
- [ ] Windows 基本 E2E 通过
- [ ] Linux 基本 E2E 通过
- [ ] `mvn clean verify` 全绿

---

# 66. 给项目开发者的最终原则

Tailcat Mesh v0.1 不追求一次性成为第二个 Tailscale。

M0-M7 先做好五件事：

```text
1. 设备自动加入
2. Tailcat 自动运行
3. Peer 自动授权和发现
4. TCP 服务一键映射
5. 选择设备组建 TCP-first Virtual LAN
```

最终用户体验应当同时支持两条路径：

```text
路径 A：Service / Forward
装 Agent → 审批 → 发布 Service → 创建 Forward → 访问 127.0.0.1:端口

路径 B：Virtual LAN
装 Agent → 审批 → 创建 Network → 勾选设备 → 自动分配虚拟 IP → 直接访问 10.77.x.x:端口
```

用户不应该需要理解：

```text
ConnBlob
WireGuard public key
DERP region
SOCKS5
magicsock
```

这些都应该由 Tailcat Mesh 自动处理。

> **Tailcat 是网络引擎。Java Agent 是驾驶舱。Java Server 是调度中心。**

这就是 Tailcat Mesh v0.1。
