# Tailcat Mesh Desktop

The Desktop app is a thin Windows Electron shell around the shaded Java Agent.
It owns the first-connection form, tray, login startup, status display, and
Agent process supervision. The Electron main process resolves Java 21 and
starts the Agent directly with `java -jar`; it does not invoke PowerShell or
any other launcher script. It also does not start `tailcat.exe`,
`tun2socks.exe`, or Wintun directly: those remain Java Agent responsibilities.

## Local runtime contract

The app reuses the Java Agent's convention-based per-user directory:

```text
%USERPROFILE%\.tailcat-mesh\
├─ config\agent.yml
├─ data\agent\identity\
├─ logs\desktop-agent.log
├─ tailcat\v0.3.0\
├─ virtual-lan\windows\
└─ runtime\java\
```

The one-time enrollment token is passed directly to the Java Agent child and is
never written to Desktop settings or Agent configuration. The Java Agent stores
only its control-plane credential and exposes a token-protected, loopback-only
local status channel for the Desktop shell.

The desktop UI intentionally keeps runtime implementation details out of the
normal user surface. General settings contain only the Server URL and Device
Name; appearance, startup, troubleshooting, and About are separate desktop
settings sections. Existing proxy values, when present in the local settings
file, remain an Agent-owned implementation detail and are not exposed by the
ordinary UI.

## Build

Install the Desktop dependencies and create an NSIS installer. The packaging
command builds the current Java Agent, stages it under `resources/agent`, and
puts it into the installer as `resources/agent/tailcat-mesh-agent.jar`:

```powershell
cd tailcat-mesh-desktop
npm install
npm run dist
```

The Tailcat, tun2socks, Wintun, and Java runtime assets remain on-demand, versioned
runtime dependencies managed by the Java Agent; the installer contains no
PowerShell Bootstrap directory.

For a local UI build without packaging, run `npm run build`; `npm run dev`
opens the Electron shell. To preview the complete UI with deterministic mock
data without starting the Java Agent, run `npm run build` followed by
`electron . --mock-ui=1`. Other mock scenarios include `offline`,
`reconnecting`, `error`, `empty`, `connecting`, and `enrollment`.

The Desktop main process starts Java only after a
saved enrollment exists or the user submits Connect. On a Windows machine,
the packaged executable requests administrator privileges once at launch so
the Java child can create Wintun adapters and routes when the Server enables a
Virtual LAN. Development Electron runs should be started from an elevated
terminal when testing that capability. The TUN and tun2socks processes remain
demand-driven by Server desired state.
