# Script para capturar tela do celular focando no app Axion
# Uso: .\capturar_tela_app.ps1

Write-Host "`n================================================================================" -ForegroundColor Cyan
Write-Host "             CAPTURA DE TELA DO APP AXION VIA ADB" -ForegroundColor Cyan
Write-Host "================================================================================`n" -ForegroundColor Cyan

# Verifica ADB
try {
    $adbCheck = adb version 2>&1
    Write-Host "✓ ADB encontrado" -ForegroundColor Green
} catch {
    Write-Host "✗ ADB nao encontrado!" -ForegroundColor Red
    Write-Host "Instale Android SDK Platform Tools" -ForegroundColor Yellow
    exit 1
}

# Lista dispositivos
Write-Host "`n[1] Dispositivos conectados:" -ForegroundColor Cyan
adb devices
Write-Host ""

# Captura tela principal
$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$filename = "screenshots\axion_app_$timestamp.png"

# Cria pasta se não existir
if (-not (Test-Path "screenshots")) {
    New-Item -ItemType Directory -Path "screenshots" | Out-Null
}

Write-Host "[2] Capturando tela..." -ForegroundColor Cyan
adb shell screencap -p /sdcard/temp_screen.png 2>$null

if ($LASTEXITCODE -eq 0) {
    # Transfere
    Write-Host "[3] Transferindo para PC..." -ForegroundColor Cyan
    adb pull /sdcard/temp_screen.png $filename 2>$null
    
    if (Test-Path $filename) {
        # Limpa arquivo temporário
        adb shell rm /sdcard/temp_screen.png 2>$null
        
        # Captura info do dispositivo
        Write-Host "[4] Informacoes do dispositivo:" -ForegroundColor Cyan
        $deviceInfo = adb shell "wm size; getprop ro.product.model; getprop ro.build.version.release"
        $deviceInfo | ForEach-Object { Write-Host "  $_" -ForegroundColor Gray }
        
        Write-Host "`n✓ Captura concluida!" -ForegroundColor Green
        Write-Host "  Arquivo: $filename" -ForegroundColor White
        
        # Exibe informações de ajuda
        Write-Host "`n[5] Opcoes adicionais:" -ForegroundColor Cyan
        Write-Host "  - Para ver app em execucao: adb shell dumpsys window windows | findstr mCurrentFocus" -ForegroundColor Gray
        Write-Host "  - Para gravar video: adb shell screenrecord /sdcard/video.mp4" -ForegroundColor Gray
        Write-Host "  - Para abrir app: adb shell am start -n com.saaspaymentsolutions.axion/.MainActivity" -ForegroundColor Gray
        
        # Pergunta se quer abrir a pasta
        $resposta = Read-Host "`nAbrir pasta screenshots? (S/N)"
        if ($resposta -eq "S" -or $resposta -eq "s") {
            explorer.exe "screenshots\"
        }
    } else {
        Write-Host "✗ Falha ao transferir captura" -ForegroundColor Red
    }
} else {
    Write-Host "✗ Falha ao capturar tela" -ForegroundColor Red
    Write-Host "  Certifique-se que o dispositivo esta conectado e com depuracao USB ativa" -ForegroundColor Yellow
}

Write-Host "`n================================================================================`n" -ForegroundColor Cyan
