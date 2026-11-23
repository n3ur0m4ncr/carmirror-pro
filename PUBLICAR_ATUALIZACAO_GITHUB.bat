@echo off
chcp 65001 >nul
title CarMirror Pro - Publicar Atualizacao na Nuvem (GitHub)
cd /d "%~dp0"

echo ================================================================
echo    CarMirror Pro - PUBLICAR ATUALIZACAO GLOBAL VIA GITHUB
echo ================================================================
echo.
echo   [1] Publicar os APKs atuais da pasta APKs_PRONTOS no GitHub
echo   [2] Subir versao automaticamente, Recompilar e Publicar no GitHub
echo.
set /p OPCAO="Escolha [1] ou [2] (padrao = 2): "

if "%OPCAO%"=="1" (
    powershell -ExecutionPolicy Bypass -File "%~dp0CarMirror_GitHub_Publish.ps1"
) else (
    powershell -ExecutionPolicy Bypass -File "%~dp0CarMirror_GitHub_Publish.ps1" -BumpAndRebuild
)
pause
