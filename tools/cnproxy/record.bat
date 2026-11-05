@echo off
rem RECORD mode: fetch from the real servers and store every successful (2xx) response.
rem Run this once while your network is working, then use start.bat (replay mode).
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
  echo [ERROR] a proxy is already running on 8899, stop it first (stop.bat)
  exit /b 1
)
echo starting local cache proxy (record mode) on 127.0.0.1:8899 ...
start "mdk-cnproxy-record" /min "%JAVA_HOME%\bin\java.exe" -cp "%HERE%classes" Proxy ^
  --port 8899 --cache "%HERE%cache" --keystore "%HERE%proxy.p12" --storepass changeit
echo now run:  gradlew --console=plain compileJava
echo the proxy window prints MISS / SAVE for everything it records.
