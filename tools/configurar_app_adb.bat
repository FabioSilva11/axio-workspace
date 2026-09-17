@echo off
echo ================================================================================
echo             CONFIGURAR APP AXION VIA ADB
echo ================================================================================
echo.
echo Este script configura a URL da API e a chave no app Axion.
echo.

REM Verifica se ADB está disponível
adb version >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERRO] ADB nao encontrado!
    echo.
    echo Instale o Android SDK Platform Tools:
    echo https://developer.android.com/studio/releases/platform-tools
    echo.
    pause
    exit /b 1
)

echo [1/5] Verificando dispositivos conectados...
adb devices
echo.

echo [2/5] Identificando o pacote do app Axion...
for /f "tokens=2" %%a in ('adb shell pm list packages ^| findstr "axion"') do (
    set PACKAGE=%%a
)

if not defined PACKAGE (
    echo [AVISO] Pacote com "axion" nao encontrado automaticamente.
    echo Tentando pacote padrao: com.saaspaymentsolutions.axion
    set PACKAGE=com.saaspaymentsolutions.axion
)

echo Pacote identificado: %PACKAGE%
echo.

echo [3/5] Configurando URL da API...
REM Tenta diferentes chaves de preferencias comuns
adb shell "am broadcast -a android.intent.action.MY_PACKAGE_REPLACED -n %PACKAGE%/com.saaspaymentsolutions.axion.ConfigReceiver --es api_url 'http://192.168.1.68:8090/v1'" 2>nul

REM Usando SharedPreferences diretamente
adb shell "run-as %PACKAGE% echo \"<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><string name='api_base_url'>http://192.168.1.68:8090/v1</string><string name='api_key'>test-key-123</string></map>\" > /data/data/%PACKAGE%/shared_prefs/api_settings.xml" 2>nul

echo URL configurada: http://192.168.1.68:8090/v1
echo.

echo [4/5] Configurando API Key...
adb shell "run-as %PACKAGE% echo \"<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><string name='api_base_url'>http://192.168.1.68:8090/v1</string><string name='api_key'>test-key-123</string></map>\" > /data/data/%PACKAGE%/shared_prefs/api_settings.xml" 2>nul

echo API Key configurada: test-key-123
echo.

echo [5/5] Reiniciando o app...
adb shell am force-stop %PACKAGE%
timeout /t 2 /nobreak >nul
adb shell monkey -p %PACKAGE% -c android.intent.category.LAUNCHER 1 >nul 2>&1

echo.
echo ================================================================================
echo                         CONFIGURACAO CONCLUIDA!
echo ================================================================================
echo.
echo Configuracoes aplicadas:
echo   Pacote:   %PACKAGE%
echo   Endpoint: http://192.168.1.68:8090/v1
echo   API Key:  test-key-123
echo.
echo O app foi reiniciado. Agora voce pode testar!
echo.
echo NOTA: Se a configuracao nao funcionar, configure manualmente no app.
echo ================================================================================
echo.
pause
