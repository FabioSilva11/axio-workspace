# Script para analisar os logs capturados
# Uso: .\analisar_logs.ps1

$logFile = "traffic_log.json"

if (-not (Test-Path $logFile)) {
    Write-Host "Arquivo $logFile nao encontrado!" -ForegroundColor Red
    Write-Host "Execute alguns testes no app primeiro." -ForegroundColor Yellow
    exit 1
}

Write-Host "`n================================================================================" -ForegroundColor Cyan
Write-Host "                    ANALISE DE LOGS CAPTURADOS" -ForegroundColor Cyan
Write-Host "================================================================================`n" -ForegroundColor Cyan

# Lê o arquivo JSON
$logs = Get-Content $logFile -Raw | ConvertFrom-Json

Write-Host "Total de entradas: $($logs.Count)" -ForegroundColor Green
Write-Host ""

# Separa requests e responses
$requests = $logs | Where-Object { $_.direction -eq "REQUEST" }
$responses = $logs | Where-Object { $_.direction -eq "RESPONSE" }

Write-Host "Requests:  $($requests.Count)" -ForegroundColor Cyan
Write-Host "Responses: $($responses.Count)" -ForegroundColor Cyan
Write-Host ""

# Analisa endpoints acessados
Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "ENDPOINTS ACESSADOS" -ForegroundColor Cyan
Write-Host "================================================================================" -ForegroundColor Cyan
$endpoints = $logs | Select-Object -ExpandProperty endpoint -Unique
foreach ($endpoint in $endpoints) {
    $count = ($logs | Where-Object { $_.endpoint -eq $endpoint }).Count
    Write-Host "  $endpoint ($count vezes)" -ForegroundColor White
}
Write-Host ""

# Analisa status codes
Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "STATUS CODES" -ForegroundColor Cyan
Write-Host "================================================================================" -ForegroundColor Cyan
$statusCodes = $responses | Where-Object { $_.status_code -ne $null } | Group-Object -Property status_code
foreach ($status in $statusCodes) {
    $color = "Green"
    if ($status.Name -ge 400) { $color = "Red" }
    elseif ($status.Name -ge 300) { $color = "Yellow" }
    Write-Host "  HTTP $($status.Name): $($status.Count) vezes" -ForegroundColor $color
}
Write-Host ""

# Analisa modelos usados
Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "MODELOS UTILIZADOS" -ForegroundColor Cyan
Write-Host "================================================================================" -ForegroundColor Cyan
$models = $requests | Where-Object { $_.body -ne $null -and $_.body.model -ne $null } | Select-Object -ExpandProperty body | Select-Object -ExpandProperty model -Unique
if ($models) {
    foreach ($model in $models) {
        $count = ($requests | Where-Object { $_.body.model -eq $model }).Count
        Write-Host "  $model ($count vezes)" -ForegroundColor White
    }
} else {
    Write-Host "  Nenhum modelo usado ainda" -ForegroundColor Gray
}
Write-Host ""

# Analisa uso de tokens
Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "USO DE TOKENS" -ForegroundColor Cyan
Write-Host "================================================================================" -ForegroundColor Cyan
$totalTokens = 0
$totalPromptTokens = 0
$totalCompletionTokens = 0
$totalReasoningTokens = 0

foreach ($response in $responses) {
    if ($response.response.usage) {
        $totalTokens += $response.response.usage.total_tokens
        $totalPromptTokens += $response.response.usage.prompt_tokens
        $totalCompletionTokens += $response.response.usage.completion_tokens
        if ($response.response.usage.completion_tokens_details) {
            $totalReasoningTokens += $response.response.usage.completion_tokens_details.reasoning_tokens
        }
    }
}

Write-Host "  Total de tokens:      $totalTokens" -ForegroundColor White
Write-Host "  Tokens de prompt:     $totalPromptTokens" -ForegroundColor Cyan
Write-Host "  Tokens de completion: $totalCompletionTokens" -ForegroundColor Cyan
Write-Host "  Tokens de reasoning:  $totalReasoningTokens" -ForegroundColor Yellow
Write-Host ""

# Lista mensagens enviadas
Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "MENSAGENS ENVIADAS PELO APP" -ForegroundColor Cyan
Write-Host "================================================================================" -ForegroundColor Cyan
$chatRequests = $requests | Where-Object { $_.endpoint -like "*chat/completions" }
if ($chatRequests) {
    $count = 1
    foreach ($req in $chatRequests) {
        Write-Host "`nMensagem #$count ($($req.timestamp)):" -ForegroundColor Yellow
        if ($req.body.messages) {
            foreach ($msg in $req.body.messages) {
                Write-Host "  [$($msg.role)]: $($msg.content)" -ForegroundColor White
            }
        }
        $count++
    }
} else {
    Write-Host "  Nenhuma mensagem enviada ainda" -ForegroundColor Gray
}
Write-Host ""

# Analisa erros
Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "ERROS DETECTADOS" -ForegroundColor Cyan
Write-Host "================================================================================" -ForegroundColor Cyan
$errors = $responses | Where-Object { $_.error -ne $null -or $_.status_code -ge 400 }
if ($errors.Count -gt 0) {
    Write-Host "  Total de erros: $($errors.Count)" -ForegroundColor Red
    foreach ($error in $errors) {
        Write-Host "`n  Erro em: $($error.timestamp)" -ForegroundColor Red
        Write-Host "  Status: $($error.status_code)" -ForegroundColor Red
        if ($error.error) {
            Write-Host "  Mensagem: $($error.error)" -ForegroundColor Red
        }
        if ($error.response.error) {
            Write-Host "  Detalhes: $($error.response.error)" -ForegroundColor Red
        }
    }
} else {
    Write-Host "  Nenhum erro detectado! ✓" -ForegroundColor Green
}
Write-Host ""

# Timeline
Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "TIMELINE (ULTIMAS 10 OPERACOES)" -ForegroundColor Cyan
Write-Host "================================================================================" -ForegroundColor Cyan
$lastLogs = $logs | Select-Object -Last 10
foreach ($log in $lastLogs) {
    $time = $log.timestamp.Split('T')[1].Substring(0, 8)
    $direction = $log.direction
    $endpoint = $log.endpoint.Split('/')[-1]
    
    $color = "White"
    if ($direction -eq "REQUEST") { $color = "Cyan" }
    if ($direction -eq "RESPONSE" -and $log.status_code -eq 200) { $color = "Green" }
    if ($direction -eq "RESPONSE" -and $log.status_code -ge 400) { $color = "Red" }
    
    Write-Host "  [$time] $direction $endpoint" -ForegroundColor $color
}
Write-Host ""

Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "ANALISE CONCLUIDA!" -ForegroundColor Green
Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host ""
