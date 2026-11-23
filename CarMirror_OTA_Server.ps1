param(
    [switch]$RebuildFirst,
    [int]$HttpPort = 8765,
    [int]$UdpPort = 49151
)

$ErrorActionPreference = 'Stop'
$projRoot = $PSScriptRoot
$apksDir = Join-Path $projRoot "APKs_PRONTOS"
$senderApk = Join-Path $apksDir "1_CarMirror_Sender_Celular.apk"
$receiverApk = Join-Path $apksDir "2_CarMirror_Receiver_Multimidia_T3L.apk"
$toolsBase = "C:\Users\igorl\AndroidBuildTools"
$aapt2 = Join-Path $toolsBase "sdk\build-tools\34.0.0\aapt2.exe"

if ($RebuildFirst) {
    Write-Host "============================================================" -ForegroundColor Cyan
    Write-Host "  RECOMPILANDO OS 2 APKs (SENDER + RECEIVER T3L)..." -ForegroundColor Cyan
    Write-Host "============================================================" -ForegroundColor Cyan
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
    Write-Host "Compilacao concluida com sucesso!" -ForegroundColor Green
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

$versionJson = @"
{
  "sender": {
    "versionCode": $($senderVer.Code),
    "versionName": "$($senderVer.Name)",
    "url": "/sender.apk"
  },
  "receiver": {
    "versionCode": $($receiverVer.Code),
    "versionName": "$($receiverVer.Name)",
    "url": "/receiver.apk"
  }
}
"@
$versionJsonBytes = [System.Text.Encoding]::UTF8.GetBytes($versionJson)

# Descobre todos os IPs IPv4 locais + enderecos de broadcast do PC
$localIps = @()
$broadcastTargets = @()
foreach ($ni in [System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()) {
    if ($ni.OperationalStatus -ne [System.Net.NetworkInformation.OperationalStatus]::Up) { continue }
    if ($ni.NetworkInterfaceType -eq [System.Net.NetworkInformation.NetworkInterfaceType]::Loopback) { continue }
    $props = $ni.GetIPProperties()
    foreach ($ua in $props.UnicastAddresses) {
        if ($ua.Address.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetwork) {
            $ipStr = $ua.Address.IPAddressToString
            if ($ipStr.StartsWith("169.254.")) { continue }
            $localIps += $ipStr
            try {
                $ipBytes = $ua.Address.GetAddressBytes()
                $maskBytes = $ua.IPv4Mask.GetAddressBytes()
                $bcastBytes = New-Object byte[] 4
                for ($i = 0; $i -lt 4; $i++) {
                    $bcastBytes[$i] = [byte]($ipBytes[$i] -bor ([byte]($maskBytes[$i] -bxor 255)))
                }
                $bcastIp = ([System.Net.IPAddress]::new($bcastBytes)).IPAddressToString
                $broadcastTargets += @{ LocalIp = $ipStr; BroadcastIp = $bcastIp }
            } catch {}
        }
    }
}

Write-Host ""
Write-Host "================================================================" -ForegroundColor Green
Write-Host "   CarMirror Pro — SERVIDOR DE ATUALIZACAO OTA VIA WI-FI" -ForegroundColor Green
Write-Host "================================================================" -ForegroundColor Green
Write-Host "  Celular (Sender)     : v$($senderVer.Name) (code $($senderVer.Code))" -ForegroundColor White
Write-Host "  Multimidia (T3L)     : v$($receiverVer.Name) (code $($receiverVer.Code))" -ForegroundColor White
Write-Host "  IPs deste PC no Wi-Fi: $($localIps -join ', ')" -ForegroundColor Cyan
Write-Host "  Porta HTTP           : $HttpPort  |  Auto-Discovery UDP: $UdpPort" -ForegroundColor Yellow
Write-Host "----------------------------------------------------------------" -ForegroundColor DarkGray
Write-Host "  COMO ATUALIZAR:" -ForegroundColor White
Write-Host "  1. Conecte o PC, o Celular e/ou a Multimidia T3L na mesma rede Wi-Fi" -ForegroundColor Gray
Write-Host "     (ou conecte o PC direto no Hotspot do seu celular!)." -ForegroundColor Gray
Write-Host "  2. Abra o app CarMirror no Celular ou na T3L — o botao de" -ForegroundColor Gray
Write-Host "     ATUALIZACAO aparecera automaticamente! Toque nele para instalar." -ForegroundColor Gray
Write-Host "  3. Pressione CTRL+C aqui no PC quando terminar." -ForegroundColor Gray
Write-Host "================================================================" -ForegroundColor Green
Write-Host ""

$tcpListener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Any, $HttpPort)
$tcpListener.Start()

$udpClient = [System.Net.Sockets.UdpClient]::new()
$udpClient.EnableBroadcast = $true
$lastBeaconTime = [DateTime]::MinValue

try {
    while ($true) {
        # 1. Envia beacon UDP de descoberta OTA a cada 1.5 segundos
        if (([DateTime]::UtcNow - $lastBeaconTime).TotalMilliseconds -ge 1500) {
            $lastBeaconTime = [DateTime]::UtcNow
            foreach ($target in $broadcastTargets) {
                $msg = "CARMIRROR_OTA_V1|$($target.LocalIp)|$HttpPort|$($senderVer.Code)|$($senderVer.Name)|$($receiverVer.Code)|$($receiverVer.Name)"
                $bytes = [System.Text.Encoding]::UTF8.GetBytes($msg)
                try {
                    $ep = [System.Net.IPEndPoint]::new([System.Net.IPAddress]::Parse($target.BroadcastIp), $UdpPort)
                    [void]$udpClient.Send($bytes, $bytes.Length, $ep)
                } catch {}
            }
            if ($localIps.Count -gt 0) {
                $msg255 = "CARMIRROR_OTA_V1|$($localIps[0])|$HttpPort|$($senderVer.Code)|$($senderVer.Name)|$($receiverVer.Code)|$($receiverVer.Name)"
                $bytes255 = [System.Text.Encoding]::UTF8.GetBytes($msg255)
                try {
                    $ep255 = [System.Net.IPEndPoint]::new([System.Net.IPAddress]::Broadcast, $UdpPort)
                    [void]$udpClient.Send($bytes255, $bytes255.Length, $ep255)
                } catch {}
            }
        }

        # 2. Atende requisicoes HTTP sem bloquear o loop de beacon UDP
        if ($tcpListener.Pending()) {
            $client = $tcpListener.AcceptTcpClient()
            try {
                $client.ReceiveTimeout = 5000
                $client.SendTimeout = 20000
                $remoteIp = $client.Client.RemoteEndPoint.ToString()
                $stream = $client.GetStream()
                $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::ASCII, $false, 1024, $true)
                $requestLine = $reader.ReadLine()

                if ($requestLine) {
                    $parts = $requestLine.Split(' ')
                    $path = if ($parts.Length -ge 2) { $parts[1] } else { "/" }
                    $timestamp = (Get-Date).ToString("HH:mm:ss")

                    if ($path -eq "/version.json") {
                        Write-Host "[$timestamp] Consulta de versao recebida de $remoteIp" -ForegroundColor Cyan
                        $header = "HTTP/1.1 200 OK`r`nContent-Type: application/json; charset=utf-8`r`nContent-Length: $($versionJsonBytes.Length)`r`nConnection: close`r`n`r`n"
                        $headerBytes = [System.Text.Encoding]::ASCII.GetBytes($header)
                        $stream.Write($headerBytes, 0, $headerBytes.Length)
                        $stream.Write($versionJsonBytes, 0, $versionJsonBytes.Length)
                        $stream.Flush()
                    }
                    elseif ($path -eq "/sender.apk") {
                        $apkBytes = [System.IO.File]::ReadAllBytes($senderApk)
                        Write-Host "[$timestamp] ENVIANDO CarMirror Sender v$($senderVer.Name) ($($apkBytes.Length) bytes) -> $remoteIp" -ForegroundColor Green
                        $header = "HTTP/1.1 200 OK`r`nContent-Type: application/vnd.android.package-archive`r`nContent-Length: $($apkBytes.Length)`r`nConnection: close`r`n`r`n"
                        $headerBytes = [System.Text.Encoding]::ASCII.GetBytes($header)
                        $stream.Write($headerBytes, 0, $headerBytes.Length)
                        $stream.Write($apkBytes, 0, $apkBytes.Length)
                        $stream.Flush()
                        Write-Host "[$timestamp] Download do Sender CONCLUIDO por $remoteIp!" -ForegroundColor Green
                    }
                    elseif ($path -eq "/receiver.apk") {
                        $apkBytes = [System.IO.File]::ReadAllBytes($receiverApk)
                        Write-Host "[$timestamp] ENVIANDO CarMirror T3L Receiver v$($receiverVer.Name) ($($apkBytes.Length) bytes) -> $remoteIp" -ForegroundColor Green
                        $header = "HTTP/1.1 200 OK`r`nContent-Type: application/vnd.android.package-archive`r`nContent-Length: $($apkBytes.Length)`r`nConnection: close`r`n`r`n"
                        $headerBytes = [System.Text.Encoding]::ASCII.GetBytes($header)
                        $stream.Write($headerBytes, 0, $headerBytes.Length)
                        $stream.Write($apkBytes, 0, $apkBytes.Length)
                        $stream.Flush()
                        Write-Host "[$timestamp] Download do Receiver T3L CONCLUIDO por $remoteIp!" -ForegroundColor Green
                    }
                    else {
                        $notFound = "HTTP/1.1 404 Not Found`r`nContent-Length: 0`r`nConnection: close`r`n`r`n"
                        $nfBytes = [System.Text.Encoding]::ASCII.GetBytes($notFound)
                        $stream.Write($nfBytes, 0, $nfBytes.Length)
                        $stream.Flush()
                    }
                }
            } catch {
                Write-Host "Aviso ao atender cliente: $($_.Exception.Message)" -ForegroundColor Yellow
            } finally {
                try { $client.Close() } catch {}
            }
        } else {
            Start-Sleep -Milliseconds 60
        }
    }
} finally {
    try { $udpClient.Close() } catch {}
    try { $tcpListener.Stop() } catch {}
    Write-Host "`nServidor OTA encerrado." -ForegroundColor Yellow
}
