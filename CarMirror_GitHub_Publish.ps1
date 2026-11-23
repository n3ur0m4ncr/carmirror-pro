param(
    [switch]$BumpAndRebuild,
    [string]$Repo = "n3ur0m4ncr/carmirror-pro"
)

$ErrorActionPreference = 'Stop'
$projRoot = $PSScriptRoot
$apksDir = Join-Path $projRoot "APKs_PRONTOS"
$senderApk = Join-Path $apksDir "1_CarMirror_Sender_Celular.apk"
$receiverApk = Join-Path $apksDir "2_CarMirror_Receiver_Multimidia_T3L.apk"
$versionJsonPath = Join-Path $apksDir "version.json"
$toolsBase = "C:\Users\igorl\AndroidBuildTools"
$aapt2 = Join-Path $toolsBase "sdk\build-tools\34.0.0\aapt2.exe"

if ($BumpAndRebuild) {
    Write-Host "============================================================" -ForegroundColor Cyan
    Write-Host "  INCREMENTANDO VERSAO E RECOMPILANDO OS 2 APKs..." -ForegroundColor Cyan
    Write-Host "============================================================" -ForegroundColor Cyan

    foreach ($gradleFile in @(
        (Join-Path $projRoot "sender\build.gradle.kts"),
        (Join-Path $projRoot "receiver\build.gradle.kts")
    )) {
        $content = Get-Content $gradleFile -Raw
        if ($content -match 'versionCode\s*=\s*(\d+)') {
            $newCode = ([int]$Matches[1]) + 1
            $content = $content -replace 'versionCode\s*=\s*\d+', "versionCode = $newCode"
        }
        if ($content -match 'versionName\s*=\s*"(\d+)\.(\d+)\.(\d+)(-T3L)?"') {
            $maj = $Matches[1]
            $min = $Matches[2]
            $pat = ([int]$Matches[3]) + 1
            $suf = $Matches[4]
            $content = $content -replace 'versionName\s*=\s*"[^"]+"', "versionName = `"$maj.$min.$pat$suf`""
        }
        Set-Content -Path $gradleFile -Value $content -NoNewline -Encoding UTF8
    }

    $jdk = (Get-ChildItem "$toolsBase\jdk-17*" -Directory | Select-Object -First 1).FullName
    $env:JAVA_HOME = $jdk
    $env:ANDROID_HOME = "$toolsBase\sdk"
    $env:ANDROID_SDK_ROOT = "$toolsBase\sdk"
    $env:PATH = "$jdk\bin;$toolsBase\gradle-8.7\bin;$toolsBase\sdk\build-tools\34.0.0;$env:PATH"

    Push-Location $projRoot
    & "$toolsBase\gradle-8.7\bin\gradle.bat" :sender:assembleRelease :receiver:assembleRelease --no-daemon
    if ($LASTEXITCODE -ne 0) {
        Pop-Location
        Write-Host "ERRO na compilacao dos APKs!" -ForegroundColor Red
        exit 1
    }
    Pop-Location

    New-Item -ItemType Directory -Force -Path $apksDir | Out-Null
    Copy-Item (Join-Path $projRoot "sender\build\outputs\apk\release\sender-release.apk") $senderApk -Force
    Copy-Item (Join-Path $projRoot "receiver\build\outputs\apk\release\receiver-release.apk") $receiverApk -Force
}

if (!(Test-Path $senderApk) -or !(Test-Path $receiverApk)) {
    Write-Host "ERRO: APKs nao encontrados em $apksDir" -ForegroundColor Red
    exit 1
}

function Get-ApkVersionInfo($apkPath, $fallbackGradle) {
    $code = 1
    $name = "1.0.0"
    if (Test-Path $aapt2) {
        try {
            $badging = (& $aapt2 dump badging $apkPath 2>$null | Select-String "package:" | Select-Object -First 1).ToString()
            if ($badging -match "versionCode='(\d+)'") { $code = [int]$Matches[1] }
            if ($badging -match "versionName='([^']+)'") { $name = $Matches[1] }
            return @{ Code = $code; Name = $name }
        } catch {}
    }
    if (Test-Path $fallbackGradle) {
        $txt = Get-Content $fallbackGradle -Raw
        if ($txt -match 'versionCode\s*=\s*(\d+)') { $code = [int]$Matches[1] }
        if ($txt -match 'versionName\s*=\s*"([^"]+)"') { $name = $Matches[1] }
    }
    return @{ Code = $code; Name = $name }
}

$senderVer = Get-ApkVersionInfo $senderApk (Join-Path $projRoot "sender\build.gradle.kts")
$receiverVer = Get-ApkVersionInfo $receiverApk (Join-Path $projRoot "receiver\build.gradle.kts")
$tagName = "v$($senderVer.Name)"

$versionJson = @"
{
  "sender": {
    "versionCode": $($senderVer.Code),
    "versionName": "$($senderVer.Name)",
    "url": "https://github.com/$Repo/releases/latest/download/1_CarMirror_Sender_Celular.apk"
  },
  "receiver": {
    "versionCode": $($receiverVer.Code),
    "versionName": "$($receiverVer.Name)",
    "url": "https://github.com/$Repo/releases/latest/download/2_CarMirror_Receiver_Multimidia_T3L.apk"
  }
}
"@
[System.IO.File]::WriteAllText($versionJsonPath, $versionJson, [System.Text.UTF8Encoding]::new($false))

Write-Host ""
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "  PUBLICANDO RELEASE $tagName NO GITHUB ($Repo)..." -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan

Push-Location $projRoot
try {
    # Faz commit/push do codigo-fonte atualizado se houver alteracoes
    $gitStatus = git status --porcelain 2>$null
    if ($gitStatus) {
        git add -A
        git commit -m "Release $tagName" | Out-Null
        git push origin main | Out-Null
    }

    # Cria ou atualiza a Release no GitHub com os 2 APKs + version.json
    $releaseExists = $true
    gh release view $tagName --repo $Repo *> $null
    if ($LASTEXITCODE -ne 0) { $releaseExists = $false }

    if ($releaseExists) {
        Write-Host "Atualizando arquivos da Release existente $tagName..." -ForegroundColor Yellow
        gh release upload $tagName $versionJsonPath $senderApk $receiverApk --repo $Repo --clobber
    } else {
        Write-Host "Criando nova Release $tagName no GitHub..." -ForegroundColor Green
        gh release create $tagName $versionJsonPath $senderApk $receiverApk `
            --repo $Repo `
            --title "CarMirror Pro $tagName (Sender + Receiver T3L)" `
            --notes "Atualizacao automatica OTA via GitHub Releases.`n- Celular (Sender): v$($senderVer.Name) (code $($senderVer.Code))`n- Multimidia T3L (Receiver): v$($receiverVer.Name) (code $($receiverVer.Code))" `
            --latest
    }

    if ($LASTEXITCODE -eq 0) {
        Write-Host ""
        Write-Host "================================================================" -ForegroundColor Green
        Write-Host "  PUBLICACAO CONCLUIDA COM SUCESSO NO GITHUB!" -ForegroundColor Green
        Write-Host "================================================================" -ForegroundColor Green
        Write-Host "  Release : https://github.com/$Repo/releases/tag/$tagName" -ForegroundColor Cyan
        Write-Host "  Qualquer celular ou multimidia T3L com internet (4G/5G/Wi-Fi)" -ForegroundColor White
        Write-Host "  ja pode baixar e instalar a versao $tagName automaticamente!" -ForegroundColor White
        Write-Host "================================================================" -ForegroundColor Green
    } else {
        Write-Host "ERRO ao publicar Release no GitHub!" -ForegroundColor Red
        exit 1
    }
} finally {
    Pop-Location
}
