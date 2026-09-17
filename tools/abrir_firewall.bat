@echo off
echo ========================================
echo Abrindo Firewall para Servidor Proxy
echo ========================================
echo.
echo Este script permite que o app Axion se conecte ao servidor proxy.
echo Porta: 8090
echo.

netsh advfirewall firewall add rule name="Axion Proxy Server" dir=in action=allow protocol=TCP localport=8090

if %errorlevel% == 0 (
    echo.
    echo [OK] Regra de firewall adicionada com sucesso!
    echo O app Axion agora pode se conectar ao servidor.
) else (
    echo.
    echo [ERRO] Nao foi possivel adicionar a regra.
    echo Execute este arquivo como Administrador:
    echo 1. Clique com botao direito
    echo 2. Selecione "Executar como administrador"
)

echo.
pause
