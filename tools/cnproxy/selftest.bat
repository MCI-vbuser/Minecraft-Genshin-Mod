@echo off
rem Self test: fetch one URL through the local cache proxy.
setlocal
set "HERE=%~dp0"
if "%JAVA_HOME%"=="" (
  echo [ERROR] JAVA_HOME is not set.
  exit /b 1
)
"%JAVA_HOME%\bin\java.exe" -Djavax.net.ssl.trustStore=%HERE%truststore.jks ^
  -Djavax.net.ssl.trustStorePassword=changeit ^
  -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=8899 ^
  -cp "%HERE%classes" SelfTest %*
