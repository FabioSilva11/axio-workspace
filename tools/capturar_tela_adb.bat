@echo off
echo ================================================================================
echo             CAPTURAR TELA DO CELULAR VIA ADB
echo ================================================================================
echo.

REM Verifica se ADB está disponível
adb version >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERRO] ADB nao encontrado!
    echo Instale Android SDK Platform Tools.
    pause
    exit /b 1
)

echo [1] Verificando dispositivos conectados...
adb devices
echo.

echo [2] Capturando tela...
set /p nome="Nome para a captura (ex: app_axion): "

if "%nome%"=="" (
    set timestamp=%date:~-4%%date:~-7,2%%date:~-10,2%_%time:~0,2%%time:~3,2%%time:~6,2%
    set nome=celular_%timestamp%
) else (
    set timestamp=%date:~-4%%date:~-7,2%%date:~-10,2%_%time:~0,2%%time:~3,2%%time:~6,2%
    set nome=%nome%_%timestamp%
)

REM Captura a tela no dispositivo
adb shell screencap -p /sdcard/screenshot_%timestamp%.png
if %errorlevel% neq 0 (
    echo [ERRO] Falha ao capturar tela!
    echo Talvez o celular precise de permissoes adicionais.
    pause
    exit /b 1
)

echo [3] Transferindo para o computador...
adb pull /sdcard/screenshot_%timestamp%.png screenshots\%nome%.png 2>nul

if not exist "screenshots\" mkdir screenshots
if exist "screenshots\%nome%.png" (
    adb shell rm /sdcard/screenshot_%timestamp%.png
    echo.
    echo [OK] Captura salva: screenshots\%nome%.png
) else (
    echo [AVISO] Nao foi possivel transferir o arquivo.
    echo A captura esta em: /sdcard/screenshot_%timestamp%.png no celular
)

echo.
echo [4] Informacoes da captura...
adb shell wm size
adb shell getprop ro.product.model

echo.
echo ================================================================================
echo                        CAPTURA CONCLUIDA
echo ================================================================================
echo.
echo Para capturar tela com video:
echo   adb shell screenrecord /sdcard/video.mp4
echo   (Ctrl+C para parar a gravacao)
echo   adb pull /sdcard/video.mp4 video.mp4
echo.
echo Abra a pasta: screenshots\
echo.
pause
