# Operations

## Tailcat binary

Install or package the official Tailcat v0.3.x binary separately, or enable
automatic download in the Agent configuration:

```yaml
tailcat:
  version: 0.3.0
  autoDownload: true
```

When `tailcat.binary` is omitted, the Agent selects the matching official
`tailscale/tailcat` v0.3.0 release asset for the host platform and architecture,
verifies its SHA-256, and extracts it atomically into the per-user cache
`~/.tailcat-mesh/tailcat/v0.3.0/` (`%USERPROFILE%\.tailcat-mesh\...` on
Windows). The current release supports Windows amd64/arm64 and Linux
amd64/arm64/armv7; macOS is not included in the v0.3.0 release assets. An
explicit `tailcat.binary` or `--tailcat-binary` overrides the cache location.
Manual discovery remains available through the `tailcat.binary` system
property, the `TAILCAT_BINARY` environment variable, a repository `bin/` path,
or an executable on `PATH`.

The binary is intentionally not committed to this repository. Pin the binary
version and verify its release checksum before deployment.

The repository's Windows `agent1.ps1` and `agent2.ps1` wrappers also prepare
the Virtual LAN sidecars in `~/.tailcat-mesh/virtual-lan/windows/`: the
version-pinned `tun2socks.exe` and the official `wintun.dll`. The files are
downloaded only when missing, verified with SHA-256, and shared by both local
Agents. Because Wintun creates a system adapter, run the wrappers from an
elevated Administrator PowerShell or Git Bash terminal. Before starting, each
wrapper also removes only its own fixed-GUID adapter and orphaned tun2socks
process, recovering from an interrupted previous run.

## Control-plane Agent usage

Build the executable Agent JAR:

```text
mvn -pl tailcat-mesh-agent -am package -DskipTests
```

On Windows, place the JAR next to `deploy/windows/tailcat-mesh-agent.bat` and
the official `tailcat.exe`. A first-time device connects with:

```text
tailcat-mesh-agent.bat connect --server https://mesh.example.com --token tm_enroll_xxx --tailcat-binary .\bin\tailcat.exe --data-dir "%ProgramData%\TailcatMesh"
```

The Agent remains in the foreground. It stores only the issued Agent
credential in `data-dir\identity\agent-state.json`; the one-time enrollment
token is not persisted. It also owns persistent Tailcat keys under
`data-dir\identity`; the Server key is generated with a fixed DERP region so
its ConnBlob survives a restart with the same served-port configuration. An
unapproved device reports `PENDING` and runs with `--allow=none` until an
administrator approves it. A subsequent start uses `run` and the saved state.

When a device is approved or disabled, the Server increments the mesh desired
revision for the network and notifies connected Agents. Each Agent reconciles
the complete allowlist and restarts its Tailcat Server if the list changed.

Use `--once` only for a start/report/stop diagnostic check.

## Publish a TCP service

An administrator can publish a service from the Web control plane's “服务”
page, or use the admin REST API:

```powershell
$service = @{
  deviceId = '<DEVICE_ID>'
  name = 'NAS Web'
  protocol = 'TCP'
  targetHost = '192.168.1.20'
  targetPort = 5000
  enabled = $true
} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/services `
  -Headers @{ Authorization = "Bearer $($login.accessToken)" } `
  -ContentType 'application/json' -Body $service
```

The publishing Agent obtains a dynamic `127.0.0.1` bridge port and starts the
official Tailcat Server with that port in its `--serve` set. The service list
shows `bridgePort` and the latest `READY`, `FAILED`, or `STOPPED` runtime state.
The bridge port is local to the Agent and may change after an Agent restart.
The Java Agent owns all bridge sockets; business code never starts Tailcat
with `ProcessBuilder` directly.

## Server local database

The Server uses an H2 file database by default, so a local installation does
not require PostgreSQL. Start the Server from the project or release
directory and the database is created at:

```text
data/tailcat-mesh.mv.db
```

Flyway applies the schema migrations during startup. The database location and
credentials can be overridden with `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD`.
For PostgreSQL deployments, use the explicit settings in
`deploy/docker-compose.yml`.

The default allows all browser origins, methods, and request headers for the
Web/API surface. For production, set `WEB_ALLOWED_ORIGINS` to the exact
frontend origin, for example `https://admin.example.com`, to restrict it.

## Admin Web UI

The administrator UI is served directly by `tailcat-mesh-server` using
Thymeleaf and a local jQuery WebJar. No independent Node/Vite process is
required. After starting the Server, open `http://localhost:8080/login` and
sign in with `ADMIN_USERNAME` / `ADMIN_PASSWORD`.

## Verification

Run `mvn clean verify` for the repository build. To exercise the real binary,
pass `-Dtailcat.binary=<absolute path>` and run
`TailcatCliEngineIntegrationTest` in the Agent module.

To exercise the whole Java Agent lifecycle with the official binary, run
`AgentRuntimeIntegrationTest` with the same property.
