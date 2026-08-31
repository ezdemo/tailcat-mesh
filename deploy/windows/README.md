# Windows Agent package

The Agent is a foreground Java 21 process in the current milestone. The
included wrapper makes the user-facing command independent of the JAR filename:

```text
tailcat-mesh-agent.bat connect --server https://mesh.example.com --token tm_enroll_xxx --tailcat-binary .\bin\tailcat.exe --data-dir "%ProgramData%\TailcatMesh"
```

The intended package layout is:

```text
TailcatMesh/
├─ tailcat-mesh-agent.bat
├─ tailcat-mesh-agent.jar
├─ bin/
│  └─ tailcat.exe
└─ agent.yml.example
```

Copy `agent.yml.example` to `agent.yml` and edit the Server URL if using the
config-file form. The first `connect` command consumes the one-time token and
writes the Agent credential below the configured data directory. Later starts
can use `run --config agent.yml` without a token.

WinSW or another service wrapper is intentionally deferred. The Java process
remains responsible for terminating its Tailcat child process.
