# Script PowerShell para configurar App Axion via ADB
# Uso: .\configurar_adb_avancado.ps1

Write-Host "`n================================================================================" -ForegroundColor Green
Write-Host "              CONFIGURAR APP AXION VIA ADB (AVANCADO)" -ForegroundColor Green
Write-Host "================================================================================`n" -ForegroundColor Green

# Configurações
$API_URL = "http://192.168.1.68:8090/v1"
$API_KEY = "test-key-123"
$PACKAGE = "com.saaspaymentsolutions.axion"

# Verifica ADB
Write-Host "[1/8] Verificando ADB..." -ForegroundColor Cyan
try {
    $adbVersion = adb version 2>&1
    Write-Host "ADB encontrado: OK" -ForegroundColor Green
} catch {
    Write-Host "ERRO: ADB nao encontrado!" -ForegroundColor Red
    Write-Host "Instale Android SDK Platform Tools" -ForegroundColor Yellow
    exit 1
}

# Lista dispositivos
Write-Host "`n[2/8] Dispositivos conectados:" -ForegroundColor Cyan
adb devices
Start-Sleep -Seconds 1

# Identifica pacote
Write-Host "`n[3/8] Identificando pacote do app..." -ForegroundColor Cyan
$packages = adb shell pm list packages | Select-String "axion"
if ($packages) {
    Write-Host "Pacotes encontrados:" -ForegroundColor Yellow
    $packages | ForEach-Object { Write-Host "  $_" }
} else {
    Write-Host "Usando pacote padrao: $PACKAGE" -ForegroundColor Yellow
}

# Para o app
Write-Host "`n[4/8] Parando o app..." -ForegroundColor Cyan
adb shell am force-stop $PACKAGE
Start-Sleep -Seconds 1
Write-Host "App parado" -ForegroundColor Green

# Método 1: Configurar via SharedPreferences
Write-Host "`n[5/8] Configurando SharedPreferences..." -ForegroundColor Cyan

$xmlContent = @"
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name='api_base_url'>$API_URL</string>
    <string name='api_key'>$API_KEY</string>
    <string name='base_url'>$API_URL</string>
    <string name='openai_api_key'>$API_KEY</string>
    <string name='llm_base_url'>$API_URL</string>
    <string name='llm_api_key'>$API_KEY</string>
</map>
"@

# Salva em arquivo temporário
$tempFile = "$env:TEMP\axion_prefs.xml"
$xmlContent | Out-File -FilePath $tempFile -Encoding UTF8

# Tenta diferentes nomes de SharedPreferences
$prefNames = @(
    "api_settings",
    "app_settings",
    "$PACKAGE`_preferences",
    "llm_settings",
    "agent_settings"
)

foreach ($prefName in $prefNames) {
    Write-Host "  Tentando: $prefName.xml" -ForegroundColor Gray
    adb push $tempFile "/data/local/tmp/prefs.xml" 2>$null
    adb shell "run-as $PACKAGE cp /data/local/tmp/prefs.xml /data/data/$PACKAGE/shared_prefs/$prefName.xml" 2>$null
    adb shell "run-as $PACKAGE chmod 660 /data/data/$PACKAGE/shared_prefs/$prefName.xml" 2>$null
}

Remove-Item $tempFile -ErrorAction SilentlyContinue
Write-Host "SharedPreferences configurado" -ForegroundColor Green

# Método 2: Usando Activity Intent
Write-Host "`n[6/8] Tentando configurar via Intent..." -ForegroundColor Cyan
adb shell am start -n "$PACKAGE/.SettingsActivity" --es "api_url" "$API_URL" --es "api_key" "$API_KEY" 2>$null
adb shell am broadcast -a "$PACKAGE.CONFIG_CHANGED" --es "api_url" "$API_URL" --es "api_key" "$API_KEY" 2>$null
Write-Host "Intents enviados" -ForegroundColor Green

# Método 3: Usando setprop (se app usar)
Write-Host "`n[7/8] Configurando propriedades do sistema..." -ForegroundColor Cyan
adb shell setprop debug.axion.api_url "$API_URL" 2>$null
adb shell setprop debug.axion.api_key "$API_KEY" 2>$null
Write-Host "Propriedades configuradas" -ForegroundColor Green

# Reinicia o app
Write-Host "`n[8/8] Reiniciando o app..." -ForegroundColor Cyan
Start-Sleep -Seconds 2
adb shell monkey -p $PACKAGE -c android.intent.category.LAUNCHER 1 2>$null | Out-Null
Start-Sleep -Seconds 2
Write-Host "App iniciado" -ForegroundColor Green

# Resumo
Write-Host "`n================================================================================" -ForegroundColor Green
Write-Host "                    CONFIGURACAO CONCLUIDA!" -ForegroundColor Green
Write-Host "================================================================================`n" -ForegroundColor Green

Write-Host "Configuracoes aplicadas:" -ForegroundColor Cyan
Write-Host "  Pacote:   $PACKAGE" -ForegroundColor White
Write-Host "  Endpoint: $API_URL" -ForegroundColor Yellow
Write-Host "  API Key:  $API_KEY" -ForegroundColor Yellow

Write-Host "`nMétodos tentados:" -ForegroundColor Cyan
Write-Host "  [x] SharedPreferences (5 variações)" -ForegroundColor Gray
Write-Host "  [x] Activity Intent" -ForegroundColor Gray
Write-Host "  [x] System Properties" -ForegroundColor Gray

Write-Host "`nProximos passos:" -ForegroundColor Cyan
Write-Host "  1. Abra o app e va em Configuracoes" -ForegroundColor White
Write-Host "  2. Verifique se a URL foi aplicada" -ForegroundColor White
Write-Host "  3. Se nao, configure manualmente:" -ForegroundColor White
Write-Host "     URL: $API_URL" -ForegroundColor Yellow
Write-Host "     Key: $API_KEY" -ForegroundColor Yellow

Write-Host "`n================================================================================" -ForegroundColor Green
Write-Host ""
