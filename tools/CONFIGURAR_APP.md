# 📱 Configurar App Axion para Usar o Proxy

## ✅ Servidor Proxy Está Rodando!

O servidor proxy está ativo e aguardando conexões do app Axion.

---

## 🌐 Endereços Disponíveis

### Para Dispositivo Físico (Celular/Tablet Real)
```
http://192.168.1.68:8090
```
**Use este endereço se você estiver testando em um celular/tablet real conectado na mesma rede WiFi.**

### Para Emulador Android
```
http://10.0.2.2:8090
```
**Use este endereço se você estiver usando um emulador Android no mesmo computador.**

---

## 📝 Como Configurar no App Axion

### Método 1: Configuração Manual da URL Base

1. Abra o app Axion
2. Vá em **Configurações** ou **Settings**
3. Procure por **URL da API** ou **Base URL** ou **Endpoint**
4. Insira o endereço apropriado:
   - Dispositivo físico: `http://192.168.1.68:8090`
   - Emulador: `http://10.0.2.2:8090`
5. Salve as configurações

### Método 2: Se o App Usar SharedPreferences

Se você tiver acesso ao código, procure por onde a URL base é definida e altere para:

```java
// No código do app
String BASE_URL = "http://192.168.1.68:8090"; // Para dispositivo físico
// ou
String BASE_URL = "http://10.0.2.2:8090"; // Para emulador
```

### Método 3: Buscar Modelos

1. No app, vá em **Configurações de IA** ou **AI Settings**
2. Clique em **Buscar Modelos** ou **Fetch Models**
3. O app deve se conectar ao proxy e listar os modelos disponíveis

---

## 🧪 Testar a Conexão

### Teste Rápido com CURL (do seu computador)

```bash
# Teste 1: Verificar saúde do servidor
curl http://192.168.1.68:8090/health

# Teste 2: Listar modelos
curl http://192.168.1.68:8090/v1/models

# Teste 3: Chat simples
curl -X POST http://192.168.1.68:8090/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gemini-3-flash",
    "messages": [{"role": "user", "content": "Olá!"}],
    "max_tokens": 50
  }'
```

### Teste do Celular (usando navegador)

1. No navegador do celular, acesse: `http://192.168.1.68:8090/health`
2. Você deve ver um JSON com status "ok"
3. Se aparecer, significa que o celular consegue acessar o proxy!

---

## 📊 Modelos Disponíveis

O proxy está conectado à API externa que oferece **18 modelos**:

### Recomendados para Teste
- ✅ **gemini-3-flash** (rápido, testado)
- ✅ **claude-sonnet-4-6** (alta qualidade)
- ✅ **gpt-5.5** (GPT-5 series)

### Todos os Modelos
1. gemini-3-flash
2. gemini-3.1-flash-image
3. gemini-3.1-flash-lite
4. gemini-3.1-pro-low
5. gemini-3.5-flash-lite
6. gemini-3.6-flash-high
7. gemini-3.7-flash-high
8. gemini-3.8-flash-high
9. gemini-pro-agent
10. claude-sonnet-4-6
11. claude-opus-4-6-thinking
12. gpt-5.5
13. gpt-5.6-luna
14. gpt-5.6-terra
15. gpt-image-1.5
16. gpt-image-2
17. codex-auto-review
18. gpt-oss-120b-medium

---

## 📝 Captura de Logs

### Onde os Logs São Salvos

Todo o tráfego entre o app e a API está sendo capturado em:

1. **traffic_log.txt** ⭐ (formato legível, recomendado)
2. **traffic_log.json** (formato estruturado)

### O Que É Capturado

- ✅ Todas as requisições HTTP do app
- ✅ Headers completos
- ✅ Body das requisições (mensagens, configurações, etc.)
- ✅ Respostas da API
- ✅ Timestamps precisos
- ✅ Status codes
- ✅ Erros (se houver)

### Exemplo do Log TXT

```
====================================================================================================
[2026-09-17T02:30:45.123456] REQUEST
====================================================================================================
Endpoint: https://api-ia.axion-ide.online/v1/chat/completions

--- HEADERS ---
Authorization: Bearer sk-***REDACTED***
Content-Type: application/json

--- BODY ---
{
  "model": "gemini-3-flash",
  "messages": [
    {
      "role": "user",
      "content": "Olá, como você está?"
    }
  ],
  "max_tokens": 100
}
====================================================================================================
```

---

## 🔧 Configurações do App para Modificar

### Arquivo: `AgentLlmGateway.java` ou similar

Procure por:
```java
private static final String BASE_URL = "...";
```

Altere para:
```java
private static final String BASE_URL = "http://192.168.1.68:8090";
```

### Se usar Retrofit/OkHttp

```java
Retrofit retrofit = new Retrofit.Builder()
    .baseUrl("http://192.168.1.68:8090/")
    .addConverterFactory(GsonConverterFactory.create())
    .build();
```

---

## ⚠️ Troubleshooting

### Problema: "Não consegue conectar"

**Soluções:**
1. ✅ Verifique se o servidor proxy está rodando (deve mostrar "AGUARDANDO CONEXÕES...")
2. ✅ Celular e computador estão na **mesma rede WiFi**?
3. ✅ Firewall do Windows pode estar bloqueando. Execute:
   ```powershell
   netsh advfirewall firewall add rule name="Axion Proxy" dir=in action=allow protocol=TCP localport=8090
   ```
4. ✅ Tente pingar o servidor do celular:
   - Instale um app "Network Tools" ou "Ping"
   - Ping para: `192.168.1.68`

### Problema: "Invalid API key" ou erro 401

**Não se preocupe!** O proxy já está configurado com a API key correta. Se você ver este erro:
1. Verifique se está acessando através do proxy (não direto na API)
2. Confirme a URL: deve ser `http://192.168.1.68:8090` (não https://api-ia.axion-ide.online)

### Problema: "Connection refused"

1. ✅ Servidor está rodando? Verifique a janela do terminal
2. ✅ Porta 8090 está livre? Execute:
   ```powershell
   netstat -ano | findstr :8090
   ```

### Problema: App não aparece nos logs

1. ✅ Verifique se o app está realmente fazendo requisições
2. ✅ Confirme a URL configurada no app
3. ✅ Tente fazer um teste manual com CURL primeiro

---

## 🎯 Próximos Passos

1. **Configure o app** com a URL: `http://192.168.1.68:8090`
2. **Faça alguns testes** no app (buscar modelos, enviar mensagens)
3. **Verifique os logs** em `traffic_log.txt`
4. **Compartilhe o arquivo** `traffic_log.txt` para análise

---

## 💡 Dicas

- 📱 Use modelo **gemini-3-flash** para testes rápidos
- 🔄 O servidor captura tudo automaticamente, não precisa fazer nada
- 📝 Os logs em TXT são fáceis de ler e compartilhar
- 🚀 Para reiniciar e limpar logs, pare e inicie o servidor novamente
- 🔍 Você pode ver logs em tempo real na janela do servidor

---

## 📞 Status Atual

✅ **Servidor Proxy:** RODANDO  
✅ **API Externa:** CONECTADA  
✅ **Captura de Logs:** ATIVA  
✅ **Endereço para o App:** `http://192.168.1.68:8090`

**Tudo pronto! Configure o app e comece os testes! 🚀**
