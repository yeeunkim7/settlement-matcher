@echo off
rem settlement-matcher launcher. Uses ONLY the bundled runtime\ (ignores JAVA_HOME, PATH and system Java).
rem Keep this file ASCII-only: cmd.exe reads .bat files in the system code page.
setlocal
cd /d "%~dp0"
chcp 65001 >nul
title settlement-matcher

rem Options in these variables would be picked up by any java.exe. Clear them for this window only.
set "JAVA_HOME="
set "JAVA_TOOL_OPTIONS="
set "JDK_JAVA_OPTIONS="
set "_JAVA_OPTIONS="

if not exist "%~dp0runtime\bin\java.exe" (
  echo.
  echo   [ERROR] runtime\bin\java.exe not found.
  echo   Extract ALL files from the zip first, then run this file again.
  echo.
  pause
  exit /b 1
)

"%~dp0runtime\bin\java.exe" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "%~dp0settlement-matcher.jar"
set "CODE=%ERRORLEVEL%"

rem Keep the window open on failure so the message can be read.
if not "%CODE%"=="0" (
  echo.
  pause
)
exit /b %CODE%
