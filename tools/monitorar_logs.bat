@echo off
echo ================================================================================
echo                    MONITORAR LOGS EM TEMPO REAL
echo ================================================================================
echo.
echo Monitorando: traffic_log.txt
echo Pressione Ctrl+C para parar
echo.
echo ================================================================================
echo.

REM Aguarda o arquivo existir
:WAIT_FILE
if not exist "traffic_log.txt" (
    echo Aguardando arquivo traffic_log.txt ser criado...
    timeout /t 2 /nobreak >nul
    goto WAIT_FILE
)

REM Exibe as últimas linhas continuamente
powershell -Command "Get-Content traffic_log.txt -Wait -Tail 50"
