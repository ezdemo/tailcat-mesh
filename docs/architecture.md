# Architecture

Tailcat Mesh uses a Java 21 Server and a lightweight Java 21 Agent. The Agent
owns the only boundary to the official Tailcat v0.3.x binary:

```text
Server (Spring Boot)
        │ HTTPS / WebSocket
        ▼
Agent (Java 21)
        │ TailcatEngine / TailcatCliEngine
        │ ProcessBuilder with argument lists
        ▼
Official tailcat binary
```

The Server module never depends on Agent implementation classes. Shared wire
models belong in `tailcat-mesh-protocol`; persistence and Spring MVC types stay
inside the Server module.

The current M3 slice adds the secure Tailcat Mesh reconciliation path on top of
the M2 control plane: every Agent owns a persistent Tailcat Server key and
Client key, the Server calculates an explicit per-device allowlist from
approved mesh members, and Desired State changes cause the Agent to restart its
Tailcat Server with the new `--allow` value. The resulting ConnBlob is stored
on the Server as a sensitive capability and is never printed in normal logs.

The M4 Service layer adds a Java-owned TCP bridge on each publishing Agent. A
configured target such as `192.168.1.20:5000` is exposed first as a dynamic
loopback listener, and the Agent passes that listener port to the official
Tailcat Server as `--serve=<bridgePort>`. The Server stores configuration and
runtime projections but still does not carry business traffic. Peer SOCKS and
Local Forward remain later milestones.
