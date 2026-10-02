@echo off
setlocal
set "APP_HOME=%~dp0.."
if defined JAVA_HOME (
  set "JAVA=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVA=java"
)
"%JAVA%" -Xms64m -Xmx512m %JAVA_OPTS% -cp "%APP_HOME%\lib\*" groovy.ui.GroovyMain %*
exit /b %errorlevel%
