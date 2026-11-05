@echo off
rem Clear everything recorded so far. Next build records again from scratch.
setlocal
set "HERE=%~dp0"
if not exist "%HERE%cache" (
  echo [INFO] no cache directory.
  exit /b 0
)
del /q "%HERE%cache\*.bin" 2>nul
del /q "%HERE%cache\*.tmp" 2>nul
echo cache cleared: %HERE%cache
