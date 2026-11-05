@echo off
rem Stop the local cache proxy if it is running.
setlocal
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /r /c:"127.0.0.1:8899 .*LISTENING"') do (
  taskkill /pid %%p /f >nul 2>nul
  echo killed proxy pid %%p
)
echo done.
