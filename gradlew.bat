@echo off
setlocal EnableExtensions
set "FNORD_GRADLE_VERSION=8.14.5"
set "FNORD_GRADLE_SHA256=6f74b601422d6d6fc4e1f9a1ab6522f642c2fdcbc15ae33ebd30ba3d7198e854"
if defined GRADLE_USER_HOME (
  set "FNORD_GRADLE_CACHE=%GRADLE_USER_HOME%\fnord-bootstrap"
) else (
  set "FNORD_GRADLE_CACHE=%USERPROFILE%\.gradle\fnord-bootstrap"
)
set "DIST=%FNORD_GRADLE_CACHE%\gradle-%FNORD_GRADLE_VERSION%"
if not exist "%DIST%\bin\gradle.bat" (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; $cache=$env:FNORD_GRADLE_CACHE; $version=$env:FNORD_GRADLE_VERSION; $expected=$env:FNORD_GRADLE_SHA256.ToLowerInvariant(); [IO.Directory]::CreateDirectory($cache) | Out-Null; $lockPath=Join-Path $cache 'install.lock'; $lock=[IO.File]::Open($lockPath,[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None); $tmp=$null; try { $dist=Join-Path $cache ('gradle-'+$version); $gradle=Join-Path $dist 'bin\gradle.bat'; if (!(Test-Path -LiteralPath $gradle)) { $tmp=Join-Path $cache ('.download.'+[Guid]::NewGuid().ToString('N')); [IO.Directory]::CreateDirectory($tmp) | Out-Null; $zip=Join-Path $tmp 'gradle.zip'; Invoke-WebRequest -UseBasicParsing -Uri ('https://services.gradle.org/distributions/gradle-'+$version+'-bin.zip') -OutFile $zip; $stream=[IO.File]::OpenRead($zip); try { $sha=[Security.Cryptography.SHA256]::Create(); try { $actual=([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-','').ToLowerInvariant() } finally { $sha.Dispose() } } finally { $stream.Dispose() }; if ($actual -ne $expected) { throw ('Gradle SHA-256 mismatch: '+$actual) }; Expand-Archive -LiteralPath $zip -DestinationPath $tmp; Move-Item -LiteralPath (Join-Path $tmp ('gradle-'+$version)) -Destination $dist } } finally { if ($lock) { $lock.Dispose() }; if ($tmp -and (Test-Path -LiteralPath $tmp)) { Remove-Item -LiteralPath $tmp -Recurse -Force } }"
  if errorlevel 1 exit /b 1
)
call "%DIST%\bin\gradle.bat" %*
exit /b %errorlevel%
