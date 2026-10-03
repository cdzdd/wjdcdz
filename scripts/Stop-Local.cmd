@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Stop-Local.ps1" %*
if errorlevel 1 pause
