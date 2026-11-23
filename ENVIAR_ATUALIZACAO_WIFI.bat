@echo off
chcp 65001 >nul
title CarMirror Pro - Enviar Atualizacao Wi-Fi (OTA)
cd /d "%~dp0"

echo ================================================================
echo    CarMirror Pro - ATUALIZADOR OTA VIA WI-FI (PC -^> ANDROID)
echo ================================================================
echo.
echo   [1] Enviar os APKs atuais da pasta APKs_PRONTOS por Wi-Fi
echo   [2] Recompilar os 2 APKs agora e enviar por Wi-Fi
echo.
set /p OPCAO="Escolha [1] ou [2] (padrao = 1): "

if "%OPCAO%"=="2" (
    powershell -ExecutionPolicy Bypass -File "%~dp0CarMirror_OTA_Server.ps1" -RebuildFirst
) else (
    powershell -ExecutionPolicy Bypass -File "%~dp0CarMirror_OTA_Server.ps1"
)
pause
