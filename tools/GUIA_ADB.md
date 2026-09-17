# 🔧 Guia de Configuração via ADB

## Pré-requisitos

### 1. Instalar ADB (Android Debug Bridge)

**Opção A: Android SDK Platform Tools (Recomendado)**
- Download: https://developer.android.com/studio/releases/platform-tools
- Extrair em: `C:\platform-tools\`
- Adicionar ao PATH do Windows

**Opção B: Via Chocolatey**
```powershell
choco install adb
```

### 2. Habilitar Depuração USB no Celular

1. Abra **Configurações** no celular
2. Vá em **Sobre o telefone**
3. Toque 7 vezes em **Número da compilação**
4. Volte e entre em **Opções do desenvolvedor**
5. Ative **Depuração USB**
6. Conecte o celular no computador via USB

### 3. Verificar Conexão

```bash
adb devices
```

Deve aparecer algo como:
```
List of devices attached
ABC123456789    device
```

Se aparecer "unauthorized", aceite a solicitação no celular.

---

## 📝 Scripts Disponíveis

### Script 1: Configuração Automática (BAT)
```bash
tools\configurar_app_adb.bat
```

**O que faz:**
- Identifica o pacote do app Axion
- Configura URL: `http://192.168.1.68:8090/v1`
- Configura API Key: `test-key-123`
- Reinicia o app

### Script 2: Configuração Avançada (PowerShell)
```powershell
.\tools\configurar_adb_avancado.ps1
```

**O que faz:**
- Tenta 5 métodos diferentes de configuração
- Usa SharedPreferences (5 variações)
- Tenta Activity Intent
- Configura System Properties
- Mais chances de funcionar

### Script 3: Verificar Configuração
```bash
tools\verificar_config_app.bat
```

**O que faz:**
- Lista dispositivos conectados
- Verifica se o app está instalado
- Tenta ler as configurações atuais
- Mostra SharedPreferences

---

## 🚀 Passo a Passo

### Método Automático

1. **Conecte o celular via USB**
   ```bash
   adb devices
   ```

2. **Execute o script de configuração**
   ```bash
   tools\configurar_app_adb.bat
   ```

3. **Verifique se funcionou**
   - Abra o app Axion
   - Vá em Configurações
   - Verifique se a URL está configurada

4. **Se não funcionou, use o script avançado**
   ```powershell
   .\tools\configurar_adb_avancado.ps1
   ```

### Método Manual (via ADB)

Se os scripts não funcionarem, configure manualmente:

```bash
# 1. Identifique o pacote
adb shell pm list packages | findstr "axion"

# 2. Para o app
adb shell am force-stop com.saaspaymentsolutions.axion

# 3. Configure SharedPreferences
adb shell "run-as com.saaspaymentsolutions.axion echo '<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?><map><string name=\"api_base_url\">http://192.168.1.68:8090/v1</string><string name=\"api_key\">test-key-123</string></map>' > /data/data/com.saaspaymentsolutions.axion/shared_prefs/api_settings.xml"

# 4. Reinicie o app
adb shell monkey -p com.saaspaymentsolutions.axion -c android.intent.category.LAUNCHER 1
```

---

## 🔍 Comandos ADB Úteis

### Verificar dispositivos conectados
```bash
adb devices
```

### Listar pacotes instalados
```bash
adb shell pm list packages | findstr "axion"
```

### Ver informações do pacote
```bash
adb shell dumpsys package com.saaspaymentsolutions.axion
```

### Limpar dados do app (CUIDADO!)
```bash
adb shell pm clear com.saaspaymentsolutions.axion
```

### Ver logs em tempo real
```bash
adb logcat | findstr "axion"
```

### Ver logs do app específico
```bash
adb logcat --pid=$(adb shell pidof -s com.saaspaymentsolutions.axion)
```

### Abrir tela de configurações do app
```bash
adb shell am start -n com.saaspaymentsolutions.axion/.SettingsActivity
```

### Enviar broadcast para o app
```bash
adb shell am broadcast -a com.saaspaymentsolutions.axion.CONFIG_CHANGED --es "api_url" "http://192.168.1.68:8090/v1"
```

---

## 🎯 Localização dos Arquivos no Android

### SharedPreferences
```
/data/data/com.saaspaymentsolutions.axion/shared_prefs/
```

Possíveis nomes de arquivo:
- `api_settings.xml`
- `app_settings.xml`
- `com.saaspaymentsolutions.axion_preferences.xml`
- `llm_settings.xml`
- `agent_settings.xml`

### Banco de Dados
```
/data/data/com.saaspaymentsolutions.axion/databases/
```

### Logs
```bash
adb logcat -d > app_logs.txt
```

---

## ⚠️ Troubleshooting

### Problema: "device unauthorized"

**Solução:**
1. Aceite a solicitação de depuração USB no celular
2. Execute novamente: `adb devices`

### Problema: "no devices/emulators found"

**Solução:**
1. Verifique se o cabo USB está conectado
2. Tente outro cabo USB
3. Instale drivers USB do fabricante do celular
4. Execute: `adb kill-server` e depois `adb start-server`

### Problema: "run-as: Package 'com.saaspaymentsolutions.axion' is not debuggable"

**Solução:**
Este erro ocorre se o app não foi compilado em modo debug. Neste caso:
1. Configure manualmente no app (interface)
2. Ou recompile o app com `android:debuggable="true"`

### Problema: Configuração não persiste

**Possíveis causas:**
1. App usa criptografia (EncryptedSharedPreferences)
2. App sobrescreve configurações ao iniciar
3. App usa banco de dados SQLite ao invés de SharedPreferences

**Solução:**
Configure manualmente na interface do app.

---

## 📱 Configuração Manual Recomendada

Se o ADB não funcionar, configure diretamente no app:

1. Abra o app Axion
2. Vá em **⚙️ Configurações** ou **Settings**
3. Procure por **URL da API** ou **Base URL** ou **Endpoint**
4. Configure:
   - **URL Base:** `http://192.168.1.68:8090/v1`
   - **API Key:** `test-key-123`
5. Salve as configurações
6. Teste "Buscar Modelos" ou envie uma mensagem

---

## 🔐 Para Emulador Android

Se estiver usando emulador (Android Studio, Genymotion, etc.):

**Use este endpoint:**
```
http://10.0.2.2:8090/v1
```

**Por quê?**
- `10.0.2.2` é o IP especial que o emulador usa para acessar o host (seu PC)
- Equivale a `127.0.0.1` ou `localhost` do seu computador

---

## 📊 Verificar se Funcionou

### Via ADB
```bash
# Ver logs em tempo real
adb logcat | findstr "http"

# Filtrar por requisições de rede
adb logcat | findstr "192.168.1.68"
```

### Via Servidor Proxy
- Observe a janela do servidor proxy
- Deve aparecer "REQUEST" quando o app fizer chamadas
- Verifique `traffic_log.txt`

---

## 🎯 Configuração Ideal

**Para dispositivo físico:**
```
URL Base: http://192.168.1.68:8090/v1
API Key:  test-key-123
```

**Para emulador:**
```
URL Base: http://10.0.2.2:8090/v1
API Key:  test-key-123
```

---

## 📞 Resumo Rápido

```bash
# 1. Conectar celular
adb devices

# 2. Configurar automaticamente
tools\configurar_app_adb.bat

# 3. OU manualmente no app:
#    URL: http://192.168.1.68:8090/v1
#    Key: test-key-123

# 4. Verificar logs
#    Ver: traffic_log.txt
```

**Pronto! O app deve se conectar ao proxy e todos os dados serão capturados!** 🚀
