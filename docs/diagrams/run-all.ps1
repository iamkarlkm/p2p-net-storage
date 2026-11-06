$ErrorActionPreference = "Continue"
$diagramsRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$runner = Join-Path $diagramsRoot "_run_all.js"

Push-Location $diagramsRoot
try {
    Write-Host "Invoking Node.js runner: $runner" -ForegroundColor Cyan
    & node $runner
    $exitCode = $LASTEXITCODE
    Write-Host "Runner exit code: $exitCode" -ForegroundColor $(if ($exitCode -eq 0) { "Green" } else { "Yellow" })
    exit $exitCode
} finally {
    Pop-Location
}
