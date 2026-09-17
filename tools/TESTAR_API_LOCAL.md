# API local para testes de agentes

Execute no PC:

```powershell
python tools/openai_traffic_lab.py
```

No app, crie/edite um provedor **OpenAI-Compatible** com:

- Base URL: `http://192.168.1.68:8090/v1`
- API key: `axion-local-test`
- Modelo: `axion-test-chat` (ou `axion-test-tools`)

O celular precisa estar no mesmo Wi-Fi. Abra `http://192.168.1.68:8090/health` no navegador do celular: a resposta `status: ok` confirma que a rede e o firewall estão corretos.

Para testar: busque os modelos, envie uma mensagem normal, habilite uma ferramenta e envie “usar ferramenta”. O arquivo `traffic_log.jsonl` registra cada requisição e resposta, com tokens e chaves ocultos. Veja também `GET /logs`.

Para liberar a porta no Firewall do Windows, abra PowerShell como administrador:

```powershell
New-NetFirewallRule -DisplayName "Axion Traffic Lab 8090" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8090
```

O modo padrão é completamente local; não transmite mensagens para nenhum serviço externo. Para encaminhar a um serviço compatível, use `--mode proxy` e configure `UPSTREAM_OPENAI_BASE_URL` e `UPSTREAM_OPENAI_API_KEY` somente no ambiente da sessão.
