@echo off
setlocal
set "ROOT=%~dp0.."
set "APP=%ROOT%\build\install\fnord-dedup2\bin\fnord-dedup2.bat"
if not exist "%APP%" (
  echo Build first: gradlew.bat windowsCoreTest installDist 1>&2
  exit /b 1
)
call "%APP%" %*
exit /b %errorlevel%
