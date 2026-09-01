The packaged build stages the shaded tailcat-mesh-agent JAR here as
tailcat-mesh-agent.jar. `npm run dist` builds the current Java Agent module and
copies the resulting versioned artifact here before Electron Builder creates
the installer. The generated JAR is intentionally not committed.
