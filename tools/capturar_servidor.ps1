# Script para capturar apenas a janela do servidor proxy
# Uso: .\capturar_servidor.ps1

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

# Procura a janela do servidor
$processes = Get-Process | Where-Object { $_.MainWindowTitle -like "*Servidor Proxy*" -or $_.MainWindowTitle -like "*mock_axion_server*" -or $_.MainWindowTitle -like "*python*" }

if ($processes.Count -eq 0) {
    Write-Host "Janela do servidor nao encontrada!" -ForegroundColor Red
    Write-Host "Certifique-se de que o servidor esta rodando." -ForegroundColor Yellow
    exit 1
}

# Se houver múltiplas janelas, lista para escolher
if ($processes.Count -gt 1) {
    Write-Host "`nMultiplas janelas encontradas:" -ForegroundColor Yellow
    for ($i = 0; $i -lt $processes.Count; $i++) {
        Write-Host "  [$i] $($processes[$i].MainWindowTitle)" -ForegroundColor White
    }
    $escolha = Read-Host "`nEscolha o numero"
    $process = $processes[$escolha]
} else {
    $process = $processes[0]
}

Write-Host "`nCapturando janela: $($process.MainWindowTitle)" -ForegroundColor Cyan

# Captura a tela completa primeiro
$screen = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
$bitmap = New-Object System.Drawing.Bitmap($screen.Width, $screen.Height)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.CopyFromScreen($screen.Location, [System.Drawing.Point]::Empty, $screen.Size)

# Salva
$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$filename = "tools\screenshot_servidor_$timestamp.png"
$bitmap.Save($filename, [System.Drawing.Imaging.ImageFormat]::Png)

$graphics.Dispose()
$bitmap.Dispose()

Write-Host "✓ Screenshot salvo: $filename" -ForegroundColor Green
Write-Host ""

# Abre a imagem
$resposta = Read-Host "Abrir imagem? (S/N)"
if ($resposta -eq "S" -or $resposta -eq "s") {
    Start-Process $filename
}
