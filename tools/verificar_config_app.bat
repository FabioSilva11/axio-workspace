@echo off
echo ================================================================================
echo           VERIFICAR CONFIGURACAO DO APP AXION
echo ================================================================================
echo.

set PACKAGE=com.saaspaymentsolutions.axion

echo [1] Verificando dispositivos conectados...
adb devices
echo.

echo [2] Verificando se o app esta instalado...
adb shell pm list packages | findstr "axion"
echo.

echo [3] Verificando SharedPreferences...
echo.
adb shell "run-as %PACKAGE% ls -la /data/data/%PACKAGE%/shared_prefs/" 2>nul
echo.

echo [4] Tentando ler configuracoes...
echo.
adb shell "run-as %PACKAGE% cat /data/data/%PACKAGE%/shared_prefs/api_settings.xml" 2>nul
adb shell "run-as %PACKAGE% cat /data/data/%PACKAGE%/shared_prefs/app_settings.xml" 2>nul
adb shell "run-as %PACKAGE% cat /data/data/%PACKAGE%/shared_prefs/%PACKAGE%_preferences.xml" 2>nul
echo.

echo [5] Verificando propriedades do sistema...
adb shell getprop | findstr "axion"
echo.

echo ================================================================================
echo                    VERIFICACAO CONCLUIDA
echo ================================================================================
echo.
echo Se nao encontrou configuracoes, o app pode estar usando:
echo   - Banco de dados SQLite
echo   - Criptografia nas SharedPreferences
echo   - Configuracao hardcoded no codigo
echo.
echo Recomendacao: Configure manualmente no app em Configuracoes/Settings
echo.
pause
