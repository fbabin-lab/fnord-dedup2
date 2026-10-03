$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$runner = Join-Path $root 'build\install\fnord-dedup2\bin\fnord-dedup2-groovy.bat'
$app = Join-Path $root 'build\install\fnord-dedup2\bin\fnord-dedup2.bat'
& $app --help
if ($LASTEXITCODE -ne 0) { throw 'Installed Windows CLI failed' }
& (Join-Path $root 'bin\fnord-dedup2.bat') --help
if ($LASTEXITCODE -ne 0) { throw 'Repository Windows CLI failed' }
& $runner (Join-Path $PSScriptRoot 'windows-smoke.groovy')
if ($LASTEXITCODE -ne 0) { throw 'Windows process/API smoke tests failed' }
# Test the distribution ZIP, not only Gradle's install directory.
$zip = Get-ChildItem (Join-Path $root 'build\distributions\*.zip') | Select-Object -First 1
$temp = Join-Path $root 'build\windows-smoke\unpacked space'
New-Item -ItemType Directory -Force $temp | Out-Null
Expand-Archive -LiteralPath $zip.FullName -DestinationPath $temp -Force
$unpacked = Get-ChildItem $temp -Directory | Select-Object -First 1
& (Join-Path $unpacked.FullName 'bin\fnord-dedup2.bat') --help
if ($LASTEXITCODE -ne 0) { throw 'ZIP Windows launcher failed' }
