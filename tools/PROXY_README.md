# Servidor Proxy Intermediário Axion

## Descrição

Este servidor Python atua como um **proxy intermediário** entre o aplicativo Axion e a API externa de IA. Ele captura e registra **todo o tráfego HTTP** que passa entre o cliente e a API externa, permitindo análise detalhada, debugging e monitoramento.

## Funcionalidades

### 1. Proxy Transparente
- Encaminha todas as requisições para a API externa
- Mantém compatibilidade total com o protocolo OpenAI
- Suporta streaming (Server-Sent Events)
- Adiciona headers CORS automaticamente

### 2. Captura de Tráfego
- **Logging em tempo real**: Todos os requests e responses são exibidos no console
- **Arquivo de log**: Todo o tráfego é salvo em `traffic_log.json`
- **Detalhes capturados**:
  - Timestamp de cada operação
  - Headers completos
  - Body dos requests
  - Responses da API
  - Códigos de status
  - Mensagens de erro

### 3. Estatísticas
- Total de requisições
- Requisições bem-sucedidas vs falhas
- Total de tokens consumidos
- Último horário de requisição

## Configuração

### API Externa
- **Endpoint**: `https://api-ia.axion-ide.online/v1`
- **API Key**: `sk-***REDACTED***-ROTACIONE-ESTA-CHAVE`
- **Porta local**: `8090`

Para modificar estas configurações, edite as constantes no início do arquivo `mock_axion_server.py`:

```python
EXTERNAL_API_URL = "https://api-ia.axion-ide.online/v1"
EXTERNAL_API_KEY = "sk-***REDACTED***-ROTACIONE-ESTA-CHAVE"
PORT = 8090
```

## Uso

### Iniciar o Servidor

```bash
python tools/mock_axion_server.py
```

O servidor iniciará em `http://127.0.0.1:8090`

### Endpoints Disponíveis

#### 1. POST `/v1/chat/completions`
Endpoint principal para chat completions. Proxy para a API externa.

**Exemplo de uso:**
```bash
curl -X POST http://127.0.0.1:8090/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-3.5-turbo",
    "messages": [{"role": "user", "content": "Olá!"}],
    "stream": false
  }'
```

#### 2. GET `/v1/models`
Lista os modelos disponíveis. Proxy para a API externa.

**Exemplo de uso:**
```bash
curl http://127.0.0.1:8090/v1/models
```

#### 3. GET `/health`
Retorna o status do proxy e estatísticas.

**Exemplo de uso:**
```bash
curl http://127.0.0.1:8090/health
```

**Resposta:**
```json
{
  "status": "ok",
  "proxy": "active",
  "external_api": "https://api-ia.axion-ide.online/v1",
  "stats": {
    "total_requests": 5,
    "successful": 3,
    "failed": 2,
    "total_tokens": 150
  },
  "log_file": "traffic_log.json",
  "logs_count": 10
}
```

#### 4. GET `/logs`
Retorna todos os logs capturados em formato JSON.

**Exemplo de uso:**
```bash
curl http://127.0.0.1:8090/logs
```

#### 5. POST `/control`
Endpoint de controle para gerenciar o proxy.

**Resetar estatísticas:**
```bash
curl -X POST http://127.0.0.1:8090/control \
  -H "Content-Type: application/json" \
  -d '{"reset": true}'
```

**Limpar logs:**
```bash
curl -X POST http://127.0.0.1:8090/control \
  -H "Content-Type: application/json" \
  -d '{"clear_logs": true}'
```

## Testes

Execute o script de teste automatizado:

```bash
python tools/test_proxy.py
```

Este script testará:
- ✓ Health check
- ✓ Listagem de modelos
- ✓ Chat completion (sem stream)
- ✓ Chat completion (com stream)
- ✓ Visualização de logs

## Arquivos Gerados

### `traffic_log.json`
Arquivo JSON contendo todos os logs de tráfego. Estrutura:

```json
[
  {
    "timestamp": "2026-09-17T01:41:51.803183",
    "direction": "REQUEST",
    "endpoint": "https://api-ia.axion-ide.online/v1/chat/completions",
    "headers": {
      "Authorization": "Bearer sk-...",
      "Content-Type": "application/json"
    },
    "body": {
      "model": "gpt-3.5-turbo",
      "messages": [...]
    }
  },
  {
    "timestamp": "2026-09-17T01:41:52.123456",
    "direction": "RESPONSE",
    "endpoint": "https://api-ia.axion-ide.online/v1/chat/completions",
    "status_code": 200,
    "response": {
      "id": "chatcmpl-...",
      "choices": [...]
    }
  }
]
```

## Configuração no App Android

Para usar este proxy no app Axion, configure a URL base da API para:

```
http://10.0.2.2:8090
```

Se estiver usando emulador Android, ou:

```
http://[SEU_IP_LOCAL]:8090
```

Se estiver usando dispositivo físico (substitua `[SEU_IP_LOCAL]` pelo IP da sua máquina na rede local).

## Troubleshooting

### Erro: "Invalid API key"

Se você receber este erro, significa que:
1. A API externa está rejeitando a chave fornecida
2. Verifique se a chave está correta e ativa
3. Verifique se há restrições de IP ou domínio na API externa

**Nota**: O proxy está funcionando corretamente, mas a API externa não está aceitando a autenticação.

### Porta já em uso

Se a porta 8090 já estiver em uso, altere a constante `PORT` no arquivo ou encerre o processo:

```bash
# Windows
netstat -ano | findstr :8090
taskkill /PID [PID] /F

# Ou simplesmente altere PORT para outra porta disponível
```

### Erro de conexão

Certifique-se de que:
1. O servidor está rodando (`python tools/mock_axion_server.py`)
2. A biblioteca `requests` está instalada (`pip install requests`)
3. Não há firewall bloqueando a porta 8090

## Análise de Logs

Para analisar os logs capturados, você pode:

1. **Ver no console**: Os logs aparecem em tempo real durante a execução
2. **Ler o arquivo JSON**: `traffic_log.json` contém todo o histórico
3. **Usar o endpoint `/logs`**: `curl http://127.0.0.1:8090/logs | python -m json.tool`
4. **Processar com Python**:

```python
import json

with open('traffic_log.json', 'r', encoding='utf-8') as f:
    logs = json.load(f)

# Analisar requests
requests = [log for log in logs if log['direction'] == 'REQUEST']
print(f"Total de requests: {len(requests)}")

# Analisar responses
responses = [log for log in logs if log['direction'] == 'RESPONSE']
successful = [r for r in responses if r.get('status_code') == 200]
print(f"Requests bem-sucedidos: {len(successful)}/{len(responses)}")
```

## Segurança

⚠️ **IMPORTANTE**: Este servidor é apenas para **desenvolvimento e testes**. Não use em produção!

- A API key está hardcoded no código (apenas para testes)
- Não há autenticação no proxy local
- Os logs podem conter informações sensíveis
- Não há rate limiting ou proteção contra abuso

## Próximos Passos

Possíveis melhorias:

1. [ ] Adicionar autenticação no proxy local
2. [ ] Implementar cache de respostas
3. [ ] Adicionar métricas de performance (latência, throughput)
4. [ ] Interface web para visualizar logs em tempo real
5. [ ] Suporte para múltiplas API keys
6. [ ] Modo de replay para testar com requisições salvas
7. [ ] Filtros e busca nos logs
8. [ ] Exportar estatísticas em diferentes formatos

## Suporte

Para problemas ou dúvidas, verifique:
1. Os logs no console
2. O arquivo `traffic_log.json`
3. O endpoint `/health` para status do proxy
