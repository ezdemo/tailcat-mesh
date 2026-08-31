# Security notes

- Tailcat private keys remain in the Agent data directory.
- The Server-facing protocol must carry public metadata only; it must never
  carry a private key or a full Agent credential in logs.
- ConnBlobs are connection capabilities. Future control-plane code must mask
  them in UI and logs and should use a hash for operational correlation.
- Tailcat Server commands always include an explicit `--allow` value. An empty
  or unavailable allowlist is represented as `--allow=none`.
- Tailcat command arguments are passed as a `List<String>` to `ProcessBuilder`;
  no shell command string is constructed.
- Agent credentials and enrollment tokens are stored only as hashes on the
  Server. The plaintext Agent credential is returned once at enrollment and is
  persisted only in the Agent's local identity state.
- The Server rejects control-plane API traffic over plain HTTP when
  `tailcat-mesh.security.require-https=true`. Local tests can explicitly turn
  this off.
- The Agent never puts its credential in a WebSocket URL query; it uses the
  `Authorization: Bearer` header.
