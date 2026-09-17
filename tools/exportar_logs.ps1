# Script para exportar logs em diferentes formatos
# Uso: .\exportar_logs.ps1

param(
    [string]$formato = "txt"  # txt, html, csv, markdown
)

$logFile = "traffic_log.json"
$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"

if (-not (Test-Path $logFile)) {
    Write-Host "Arquivo $logFile nao encontrado!" -ForegroundColor Red
    exit 1
}

Write-Host "`nExportando logs em formato: $formato" -ForegroundColor Cyan

$logs = Get-Content $logFile -Raw | ConvertFrom-Json

switch ($formato) {
    "txt" {
        $outFile = "logs_export_$timestamp.txt"
        $content = @"
================================================================================
                        LOGS EXPORTADOS - AXION PROXY
================================================================================
Data de exportacao: $(Get-Date -Format "dd/MM/yyyy HH:mm:ss")
Total de entradas: $($logs.Count)
================================================================================

"@
        foreach ($log in $logs) {
            $content += "`n" + "="*80 + "`n"
            $content += "[$($log.timestamp)] $($log.direction)`n"
            $content += "="*80 + "`n"
            $content += "Endpoint: $($log.endpoint)`n`n"
            
            if ($log.headers) {
                $content += "Headers:`n"
                foreach ($key in $log.headers.PSObject.Properties.Name) {
                    $content += "  $key`: $($log.headers.$key)`n"
                }
                $content += "`n"
            }
            
            if ($log.body) {
                $content += "Body:`n"
                $content += ($log.body | ConvertTo-Json -Depth 10) + "`n"
            }
            
            if ($log.response) {
                $content += "Response:`n"
                $content += ($log.response | ConvertTo-Json -Depth 10) + "`n"
            }
            
            if ($log.status_code) {
                $content += "`nStatus Code: $($log.status_code)`n"
            }
        }
        
        $content | Out-File -FilePath $outFile -Encoding UTF8
        Write-Host "✓ Exportado para: $outFile" -ForegroundColor Green
    }
    
    "csv" {
        $outFile = "logs_export_$timestamp.csv"
        $csvData = @()
        
        foreach ($log in $logs) {
            $csvData += [PSCustomObject]@{
                Timestamp = $log.timestamp
                Direction = $log.direction
                Endpoint = $log.endpoint
                StatusCode = $log.status_code
                Model = if ($log.body.model) { $log.body.model } else { "" }
                MessageContent = if ($log.body.messages) { ($log.body.messages | ConvertTo-Json -Compress) } else { "" }
                ResponseContent = if ($log.response) { ($log.response | ConvertTo-Json -Compress) } else { "" }
            }
        }
        
        $csvData | Export-Csv -Path $outFile -NoTypeInformation -Encoding UTF8
        Write-Host "✓ Exportado para: $outFile" -ForegroundColor Green
    }
    
    "html" {
        $outFile = "logs_export_$timestamp.html"
        $html = @"
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>Logs Axion Proxy - $timestamp</title>
    <style>
        body { font-family: 'Segoe UI', Arial, sans-serif; margin: 20px; background: #f5f5f5; }
        h1 { color: #2c3e50; }
        .log-entry { background: white; margin: 10px 0; padding: 15px; border-radius: 5px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        .request { border-left: 4px solid #3498db; }
        .response { border-left: 4px solid #2ecc71; }
        .error { border-left: 4px solid #e74c3c; }
        .timestamp { color: #7f8c8d; font-size: 0.9em; }
        .endpoint { color: #34495e; font-weight: bold; }
        .status-200 { color: #2ecc71; }
        .status-400 { color: #e74c3c; }
        pre { background: #ecf0f1; padding: 10px; border-radius: 3px; overflow-x: auto; }
        .stats { background: #3498db; color: white; padding: 15px; border-radius: 5px; margin: 20px 0; }
    </style>
</head>
<body>
    <h1>📊 Logs Capturados - Axion Proxy</h1>
    <div class="stats">
        <strong>Data:</strong> $(Get-Date -Format "dd/MM/yyyy HH:mm:ss")<br>
        <strong>Total de entradas:</strong> $($logs.Count)<br>
        <strong>Requests:</strong> $(($logs | Where-Object { $_.direction -eq 'REQUEST' }).Count)<br>
        <strong>Responses:</strong> $(($logs | Where-Object { $_.direction -eq 'RESPONSE' }).Count)
    </div>
"@
        
        foreach ($log in $logs) {
            $class = "log-entry " + $log.direction.ToLower()
            if ($log.status_code -ge 400) { $class += " error" }
            
            $html += @"
    <div class="$class">
        <div class="timestamp">$($log.timestamp)</div>
        <div class="endpoint">$($log.direction): $($log.endpoint)</div>
"@
            
            if ($log.status_code) {
                $statusClass = if ($log.status_code -lt 400) { "status-200" } else { "status-400" }
                $html += "<div class='$statusClass'><strong>Status:</strong> $($log.status_code)</div>"
            }
            
            if ($log.body) {
                $html += "<h4>Body:</h4><pre>$($log.body | ConvertTo-Json -Depth 5)</pre>"
            }
            
            if ($log.response) {
                $html += "<h4>Response:</h4><pre>$($log.response | ConvertTo-Json -Depth 5)</pre>"
            }
            
            $html += "</div>`n"
        }
        
        $html += @"
</body>
</html>
"@
        
        $html | Out-File -FilePath $outFile -Encoding UTF8
        Write-Host "✓ Exportado para: $outFile" -ForegroundColor Green
        Write-Host "  Abra o arquivo no navegador para visualizar" -ForegroundColor Yellow
    }
    
    "markdown" {
        $outFile = "logs_export_$timestamp.md"
        $md = @"
# Logs Capturados - Axion Proxy

**Data:** $(Get-Date -Format "dd/MM/yyyy HH:mm:ss")  
**Total de entradas:** $($logs.Count)

---

"@
        
        foreach ($log in $logs) {
            $md += "## [$($log.timestamp)] $($log.direction)`n`n"
            $md += "**Endpoint:** ``$($log.endpoint)```n`n"
            
            if ($log.status_code) {
                $emoji = if ($log.status_code -lt 400) { "✅" } else { "❌" }
                $md += "**Status:** $emoji HTTP $($log.status_code)`n`n"
            }
            
            if ($log.body) {
                $md += "### Body`n``````json`n$($log.body | ConvertTo-Json -Depth 5)`n```````n`n"
            }
            
            if ($log.response) {
                $md += "### Response`n``````json`n$($log.response | ConvertTo-Json -Depth 5)`n```````n`n"
            }
            
            $md += "---`n`n"
        }
        
        $md | Out-File -FilePath $outFile -Encoding UTF8
        Write-Host "✓ Exportado para: $outFile" -ForegroundColor Green
    }
    
    default {
        Write-Host "Formato invalido! Use: txt, html, csv ou markdown" -ForegroundColor Red
        exit 1
    }
}

Write-Host "`n✓ Exportacao concluida com sucesso!`n" -ForegroundColor Green
