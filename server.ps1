[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$JavaArguments
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$jar = Join-Path $root "tailcat-mesh-server\target\tailcat-mesh-server-0.1.0-SNAPSHOT.jar"

if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
    throw "Server JAR not found: $jar. Run Maven package first."
}

Push-Location $root
try {
    $arguments = @(
        "-jar",
        $jar,
        "--tailcat-mesh.security.require-https=false"
    ) + @($JavaArguments)
    & java @arguments
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
