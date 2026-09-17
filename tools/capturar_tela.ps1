# Script para capturar screenshot
# Uso: .\capturar_tela.ps1 [-nome "descricao"]

param(
    [string]$nome = ""
)

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

$screen = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
$bitmap = New-Object System.Drawing.Bitmap($screen.Width, $screen.Height)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)

Write-Host "`nCapturando tela..." -ForegroundColor Cyan

# Captura a tela
$graphics.CopyFromScreen($screen.Location, [System.Drawing.Point]::Empty, $screen.Size)

# Nome do arquivo
$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
if ($nome -ne "") {
    $filename = "tools\screenshot_$timestamp`_$nome.png"
} else {
    $filename = "tools\screenshot_$timestamp.png"
}

# Salva o arquivo
$bitmap.Save($filename, [System.Drawing.Imaging.ImageFormat]::Png)

# Libera recursos
$graphics.Dispose()
$bitmap.Dispose()

# Resultado
Write-Host "✓ Screenshot salvo!" -ForegroundColor Green
Write-Host "  Arquivo: $filename" -ForegroundColor White
Write-Host "  Tamanho: $($screen.Width)x$($screen.Height)" -ForegroundColor Gray

# Abre a pasta
$resposta = Read-Host "`nAbrir pasta? (S/N)"
if ($resposta -eq "S" -or $resposta -eq "s") {
    explorer.exe (Split-Path $filename -Parent)
}
