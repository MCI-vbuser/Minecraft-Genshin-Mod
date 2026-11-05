@echo off
rem REPLAY mode: only serve what has already been recorded.
rem Anything not recorded is reset (RST) so Gradle treats the repo as unavailable
rem and falls back to its own cache. This mode can never make a build worse.
setlocal
set "HERE=%~dp0"

if "%JAVA_HOME%"=="" (
  echo [ERROR] JAVA_HOME is not set.
  exit /b 1
)
if not exist "%HERE%classes\Proxy.class" (
  echo [ERROR] run setup.bat first.
  exit /b 1
)

netstat -ano | findstr /r /c:"127.0.0.1:8899 .*LISTENING" >nul 2>nul
if not errorlevel 1 (
  echo [INFO] proxy already listening on 127.0.0.1:8899
  exit /b 0
)

echo starting local cache proxy (replay mode) on 127.0.0.1:8899 ...
start "mdk-cnproxy" /min "%JAVA_HOME%\bin\java.exe" -cp "%HERE%classes" Proxy ^
  --port 8899 --cache "%HERE%cache" --keystore "%HERE%proxy.p12" --storepass changeit --replay

echo proxy started in a separate window (close it to stop).
echo log: HIT = replayed from cache, MISS = not recorded (skipped safely).
