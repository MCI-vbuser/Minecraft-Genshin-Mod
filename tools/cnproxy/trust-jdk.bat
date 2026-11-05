@echo off
rem OPTIONAL - machine wide change, read tools\cnproxy\README.md first.
rem
rem Gradle 4.9's dependency resolver ignores -Djavax.net.ssl.trustStore, but JSSE
rem prefers  %JAVA_HOME%\jre\lib\security\jssecacerts  over cacerts when no property
rem is set. Copying our truststore there makes EVERY JVM of this JDK trust the
rem local proxy certificate, which is what makes the cache proxy usable for
rem Gradle's own resolution.
rem
rem Undo with:  del "%JAVA_HOME%\jre\lib\security\jssecacerts"
setlocal
set "HERE=%~dp0"

if "%JAVA_HOME%"=="" (
  echo [ERROR] JAVA_HOME is not set.
  exit /b 1
)
if not exist "%HERE%truststore.jks" (
  echo [ERROR] run setup.bat first.
  exit /b 1
)

set "SEC=%JAVA_HOME%\jre\lib\security"
if not exist "%SEC%" set "SEC=%JAVA_HOME%\lib\security"

if exist "%SEC%\jssecacerts" (
  echo [WARN] %SEC%\jssecacerts already exists - backing it up to jssecacerts.bak
  move /y "%SEC%\jssecacerts" "%SEC%\jssecacerts.bak" >nul
)

copy /y "%HERE%truststore.jks" "%SEC%\jssecacerts" >nul
if errorlevel 1 (
  echo [ERROR] could not write %SEC%\jssecacerts  ^(need admin rights^)
  exit /b 1
)

echo.
echo Installed: %SEC%\jssecacerts
echo Remove it again with:  del "%SEC%\jssecacerts"
