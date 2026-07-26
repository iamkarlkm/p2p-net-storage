param(
  [string]$OutDir = "",
  [switch]$SkipBuild = $false
)

$ErrorActionPreference = "Stop"

function Invoke-Step([string]$WorkingDir, [string[]]$Command) {
  $p = Start-Process -FilePath $Command[0] -ArgumentList $Command[1..($Command.Length - 1)] -WorkingDirectory $WorkingDir -NoNewWindow -PassThru -Wait
  if ($p.ExitCode -ne 0) { throw ("命令失败: " + ($Command -join " ")) }
}

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\\..")).Path
$Version = (& mvn -pl p2p-sync -q -DforceStdout help:evaluate -Dexpression=project.version).Trim()
if ([string]::IsNullOrWhiteSpace($Version)) { throw "无法解析 p2p-sync 版本号" }

if (-not $SkipBuild) {
  Invoke-Step $RepoRoot @("mvn", "-pl", "p2p-core,p2p-transfer,p2p-db", "-DskipTests=true", "install")
  Invoke-Step $RepoRoot @("mvn", "-pl", "p2p-sync", "-DskipTests=true", "package")
  Invoke-Step $RepoRoot @("mvn", "-pl", "p2p-sync", "-DskipTests=true", "dependency:copy-dependencies", "-DincludeScope=runtime", "-DoutputDirectory=p2p-sync/target/dependency")
}

$Jar = Join-Path $RepoRoot ("p2p-sync\\target\\p2p-sync-" + $Version + ".jar")
$DepsDir = Join-Path $RepoRoot "p2p-sync\\target\\dependency"
$YamlExample = Join-Path $RepoRoot "p2p-sync\\src\\main\\resources\\p2p-sync.yaml.example"
$RunPs1 = Join-Path $RepoRoot "p2p-sync\\deploy\\run.ps1"
$RunSh = Join-Path $RepoRoot "p2p-sync\\deploy\\run.sh"

if (-not (Test-Path -Path $Jar)) { throw ("找不到产物: " + $Jar) }
if (-not (Test-Path -Path $DepsDir)) { throw ("找不到依赖目录: " + $DepsDir) }
if (-not (Test-Path -Path $YamlExample)) { throw ("找不到示例配置: " + $YamlExample) }

if ([string]::IsNullOrWhiteSpace($OutDir)) {
  $OutDir = (Join-Path $RepoRoot "dist")
}

$Stamp = (Get-Date -Format "yyyyMMddHHmmss")
$DistRoot = Join-Path $OutDir ("p2p-sync-" + $Version + "-" + $Stamp)

New-Item -ItemType Directory -Force -Path (Join-Path $DistRoot "app") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $DistRoot "lib") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $DistRoot "conf") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $DistRoot "bin") | Out-Null

Copy-Item -Force $Jar (Join-Path $DistRoot "app\\p2p-sync.jar")
Copy-Item -Force (Join-Path $DepsDir "*") (Join-Path $DistRoot "lib")
Copy-Item -Force $YamlExample (Join-Path $DistRoot "conf\\p2p-sync.yaml.example")

if (Test-Path -Path $RunPs1) { Copy-Item -Force $RunPs1 (Join-Path $DistRoot "bin\\run.ps1") }
if (Test-Path -Path $RunSh) { Copy-Item -Force $RunSh (Join-Path $DistRoot "bin\\run.sh") }

Write-Host $DistRoot
