@echo off
chcp 65001 >nul
title Menu Proxy Axion

:MENU
cls
echo.
echo ================================================================================
echo                         MENU PROXY AXION
echo ================================================================================
echo.
echo  1. Iniciar servidor proxy
echo  2. Parar servidor proxy
echo  3. Ver status do servidor
echo  4. Configurar app via ADB
echo  5. Monitorar logs em tempo real
echo  6. Analisar logs capturados
echo  7. Exportar logs (TXT)
echo  8. Exportar logs (HTML)
echo  9. Limpar logs
echo  10. Abrir firewall
echo  11. Verificar config do app
echo  0. Sair
echo.
echo ================================================================================
echo.
set /p opcao="Escolha uma opcao: "

if "%opcao%"=="1" goto INICIAR
if "%opcao%"=="2" goto PARAR
if "%opcao%"=="3" goto STATUS
if "%opcao%"=="4" goto CONFIG_ADB
if "%opcao%"=="5" goto MONITORAR
if "%opcao%"=="6" goto ANALISAR
if "%opcao%"=="7" goto EXPORTAR_TXT
if "%opcao%"=="8" goto EXPORTAR_HTML
if "%opcao%"=="9" goto LIMPAR
if "%opcao%"=="10" goto FIREWALL
if "%opcao%"=="11" goto VERIFICAR_APP
if "%opcao%"=="0" goto SAIR

echo.
echo Opcao invalida!
timeout /t 2 >nul
goto MENU

:INICIAR
cls
echo.
echo Iniciando servidor proxy...
echo.
start "Servidor Proxy Axion" cmd /k "python tools\mock_axion_server.py"
echo.
echo Servidor iniciado em nova janela!
timeout /t 3 >nul
goto MENU

:PARAR
cls
echo.
echo Parando servidor proxy...
taskkill /FI "WINDOWTITLE eq Servidor Proxy Axion*" /F >nul 2>&1
echo.
echo Servidor parado!
timeout /t 2 >nul
goto MENU

:STATUS
cls
echo.
echo Verificando status...
echo.
curl -s http://127.0.0.1:8090/health 2>nul
if %errorlevel% neq 0 (
    echo Servidor OFFLINE ou nao acessivel
) else (
    echo.
    echo Servidor ONLINE
)
echo.
pause
goto MENU

:CONFIG_ADB
cls
call tools\configurar_app_adb.bat
pause
goto MENU

:MONITORAR
cls
call tools\monitorar_logs.bat
goto MENU

:ANALISAR
cls
powershell -ExecutionPolicy Bypass -File tools\analisar_logs.ps1
pause
goto MENU

:EXPORTAR_TXT
cls
powershell -ExecutionPolicy Bypass -File tools\exportar_logs.ps1 -formato txt
pause
goto MENU

:EXPORTAR_HTML
cls
powershell -ExecutionPolicy Bypass -File tools\exportar_logs.ps1 -formato html
echo.
set /p abrir="Abrir arquivo HTML no navegador? (S/N): "
if /i "%abrir%"=="S" (
    for /f "delims=" %%a in ('dir /b /od logs_export_*.html 2^>nul') do set "ultimo=%%a"
    if defined ultimo start "" "!ultimo!"
)
pause
goto MENU

:LIMPAR
cls
echo.
echo Tem certeza que deseja limpar os logs?
set /p confirma="Digite SIM para confirmar: "
if /i "%confirma%"=="SIM" (
    del /q traffic_log.txt 2>nul
    del /q traffic_log.json 2>nul
    echo.
    echo Logs limpos!
) else (
    echo.
    echo Operacao cancelada.
)
timeout /t 2 >nul
goto MENU

:FIREWALL
cls
echo.
echo Abrindo firewall...
echo.
echo Execute este menu como Administrador para adicionar regra automaticamente.
echo Ou execute manualmente: tools\abrir_firewall.bat (como administrador)
echo.
pause
goto MENU

:VERIFICAR_APP
cls
call tools\verificar_config_app.bat
pause
goto MENU

:SAIR
cls
echo.
echo Encerrando...
echo.
timeout /t 1 >nul
exit
