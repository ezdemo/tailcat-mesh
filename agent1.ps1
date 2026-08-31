[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$Token
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$jar = Join-Path $root "tailcat-mesh-agent\target\tailcat-mesh-agent-0.1.0-SNAPSHOT.jar"
$config = Join-Path $root "agent1.yml"
$state = Join-Path $root "data\agent\identity\agent-state.json"

if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
    throw "Agent JAR not found: $jar. Run Maven package first."
}
if (-not (Test-Path -LiteralPath $config -PathType Leaf)) {
    throw "Agent config not found: $config"
}

$javaArguments = @("-jar", $jar)
if (Test-Path -LiteralPath $state -PathType Leaf) {
    $javaArguments += @("run", "--config", $config)
} else {
    if ([string]::IsNullOrWhiteSpace($Token)) {
        Write-Error "Agent 1 is not enrolled. Run: .\agent1.ps1 -Token '<one-time-token>'"
        exit 2
    }
    $javaArguments += @("connect", "--config", $config, "--token", $Token)
}

Push-Location $root
try {
    & java @javaArguments
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
