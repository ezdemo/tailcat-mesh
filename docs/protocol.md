# Protocol boundary

`tailcat-mesh-protocol` contains only transport-neutral models shared by the
Server and Agent. It does not contain Spring configuration, persistence
annotations, or Tailcat process-launching code.

`ProtocolEnvelope` establishes the immutable WebSocket envelope shape from the
v0.1 specification. The `com.tailcatmesh.protocol.agent` package now contains
the shared registration, heartbeat, runtime-server, and desired-state DTOs.
Server persistence records and Agent local state remain outside this module.

The current WebSocket flow is:

```text
Agent -> HELLO
Server -> SYNC_DESIRED_STATE
Agent -> periodic HTTP HEARTBEAT
```

The M3 desired-state payload includes the complete membership-derived
`allowedClientPublicKeys` list and a monotonic revision. The M4 payload also
contains typed `services` entries. The Agent creates or stops a local bridge
for each enabled entry, includes ready bridge ports in the Tailcat Server
configuration, and reports a complete `AgentServiceRuntimeReport` over the
authenticated Agent REST channel. An empty allowlist is serialized by the CLI
boundary as `--allow=none`; forwards and peer commands remain later milestones.
