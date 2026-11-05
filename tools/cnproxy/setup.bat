@echo off
setlocal enabledelayedexpansion
set "HERE=%~dp0"
set "PASS=changeit"

if "%JAVA_HOME%"=="" (
  echo [ERROR] JAVA_HOME is not set.
  exit /b 1
)
set "KEYTOOL=%JAVA_HOME%\bin\keytool.exe"
set "JAVAC=%JAVA_HOME%\bin\javac.exe"

if not exist "%KEYTOOL%" (
  echo [ERROR] keytool not found: %KEYTOOL%
  exit /b 1
)

set "SAN=dns:files.minecraftforge.net,dns:maven.minecraftforge.net,dns:launchermeta.mojang.com,dns:launcher.mojang.com,dns:libraries.minecraft.net,dns:resources.download.minecraft.net,dns:repo.maven.apache.org,dns:repo1.maven.org"

if not exist "%HERE%proxy.p12" (
  echo [1/4] generating self-signed certificate ...
  "%KEYTOOL%" -genkeypair -alias mdkproxy -keyalg RSA -keysize 2048 -validity 3650 ^
    -dname "CN=mdk-local-proxy,O=local,C=CN" -ext "SAN=%SAN%" ^
    -keystore "%HERE%proxy.p12" -storetype PKCS12 -storepass %PASS% -keypass %PASS%
  if errorlevel 1 exit /b 1
) else (
  echo [1/4] certificate exists, skip
)

echo [2/4] exporting certificate ...
"%KEYTOOL%" -exportcert -alias mdkproxy -keystore "%HERE%proxy.p12" -storepass %PASS% -file "%HERE%proxy.crt" >nul
if errorlevel 1 exit /b 1

echo [3/4] building truststore (JDK cacerts + our cert) ...
copy /y "%JAVA_HOME%\jre\lib\security\cacerts" "%HERE%truststore.jks" >nul 2>nul
if not exist "%HERE%truststore.jks" (
  copy /y "%JAVA_HOME%\lib\security\cacerts" "%HERE%truststore.jks" >nul
)
"%KEYTOOL%" -importcert -noprompt -alias mdkproxy -file "%HERE%proxy.crt" ^
  -keystore "%HERE%truststore.jks" -storepass changeit >nul
if errorlevel 1 exit /b 1

echo [4/4] compiling Proxy.java ...
if not exist "%HERE%classes" mkdir "%HERE%classes"
"%JAVAC%" -encoding UTF-8 -d "%HERE%classes" "%HERE%Proxy.java"
if errorlevel 1 exit /b 1

set "TRUST=%HERE:\=/%"
echo.
echo Done. Nothing was changed in gradle.properties on purpose.
echo.
echo Read tools\cnproxy\README.md first - there is a known limitation with
echo Gradle's own resolver. If you still want to try it, add these lines to
echo gradle.properties and uncomment them while the proxy is running:
echo.
echo   systemProp.http.proxyHost=127.0.0.1
echo   systemProp.http.proxyPort=8899
echo   systemProp.https.proxyHost=127.0.0.1
echo   systemProp.https.proxyPort=8899
echo   systemProp.javax.net.ssl.trustStore=%TRUST%truststore.jks
echo   systemProp.javax.net.ssl.trustStorePassword=changeit
echo   systemProp.net.minecraftforge.gradle.test_certs=false
echo.
