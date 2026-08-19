@echo off
setlocal
title Shiyun Server (Auto-Restart)
cd /d "%~dp0"

REM ============================================================
REM  NeoForge server launcher with auto-restart.
REM  If the server stops or crashes, it restarts automatically
REM  after a 5-second countdown.
REM
REM  To fully exit the restart loop:
REM    Press Ctrl+C during the countdown, then answer Y
REM    to "Terminate batch job (Y/N)?".
REM ============================================================

:restart_loop

echo.
echo ============================================================
echo  [%date% %time%] Starting NeoForge 21.1.248 server ...
echo  (auto-restart 5s after the server stops)
echo ============================================================

java @user_jvm_args.txt @libraries/net/neoforged/neoforge/21.1.248/win_args.txt nogui %*

echo.
echo  [%date% %time%] Server stopped. Restarting in 5 seconds ...
echo  Press Ctrl+C (then Y) to stop the restart loop.
timeout /t 5 >nul

goto restart_loop