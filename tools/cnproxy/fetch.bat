@echo off
rem Fetch everything listed in cache\miss.log through the USB-connected phone and
rem store it in the local proxy cache. Run this when the PC's network to Mojang/Forge
rem is bad but the phone's is fine.
setlocal
set "HERE=%~dp0"
set "ADB=%~1"

if not defined ADB (
  for %%p in (adb.exe) do if not "%%~$PATH:p"=="" set "ADB=%%~$PATH:p"
)
if not defined ADB if exist "D:\BtBridge\tools\platform-tools\adb.exe" set "ADB=D:\BtBridge\tools\platform-tools\adb.exe"
if not defined ADB if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not defined ADB if exist "%USERPROFILE%\AppData\Local\Android\Sdk\platform-tools\adb.exe" set "ADB=%USERPROFILE%\AppData\Local\Android\Sdk\platform-tools\adb.exe"

if not defined ADB (
  echo [ERROR] adb.exe not found.
  echo         pass it explicitly:  fetch.bat C:\path\to\adb.exe
  exit /b 1
)

if not exist "%HERE%cache\miss.log" (
  echo [INFO] no miss.log yet.
  echo        start the proxy, run one build, then run this again.
  exit /b 0
)

echo using adb: %ADB%
powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%phone-fetch.ps1" ^
  -Adb "%ADB%" -CacheDir "%HERE%cache" -ListFile "%HERE%cache\miss.log"

if errorlevel 1 exit /b 1
echo.
echo done. now just run the build again - those URLs will come from the local cache.
