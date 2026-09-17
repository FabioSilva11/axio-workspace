# Axion Workspace

Axion é um assistente de IA com modo agente para Android: converse com modelos de IA, anexe imagens e deixe o agente ler, editar e executar código diretamente no workspace do seu dispositivo.

## Recursos

- **Chat multi-modelo** — streaming de respostas, histórico persistente em SQLite, abas de Conversa / Diferenças / Artefatos / Plano
- **Múltiplos provedores** — OpenAI compatível, Anthropic (Claude) e Google Gemini, com configuração por provedor (chave de API, endpoint, modelo)
- **Busca de modelos automática** — baixa a lista de modelos disponíveis direto do provedor (`ProviderModelFetchSheet`)
- **Modo agente com tool calling** — o modelo pode usar ferramentas para operar arquivos:
  - leitura/escrita/rewrite de arquivos
  - edições atômicas via blocos `SEARCH/REPLACE`
  - árvore de diretórios e busca semântica de arquivos
  - terminal shell (comandos rápidos e terminal persistente)
- **Workspaces** — pasta local (`MANAGE_EXTERNAL_STORAGE`) ou SAF, com regras de ignore, permissões e sincronização com GitHub
- **Skills** — instruções reutilizáveis que podem ser ativadas por conversa
- **Servidores MCP** — integração com Model Context Protocol (inclui serviço GitHub MCP)
- **Visão** — envio de imagens como referência nas mensagens (respeitando as capacidades do modelo)
- **Extras** — busca online, digitação por voz, tradução, leitura em voz alta, temas claro/escuro, editor de código (sora-editor), renderização Markdown (Markwon)

## Stack técnica

| Camada | Tecnologia |
|---|---|
| Linguagem | Java + Kotlin |
| UI | Android Views (ViewBinding), Material Components |
| Assíncrono | Kotlin Coroutines |
| Rede | OkHttp |
| Persistência | SQLite (chat/paging), SharedPreferences |
| Editor | sora-editor |
| Markdown | Markwon |
| Outros | Firebase Analytics, AdMob |

- **minSdk**: 26 · **targetSdk**: 34 · **compileSdk**: 35 · **JVM**: 17

## Estrutura do projeto

```
app/src/main/java/com/saaspaymentsolutions/axion/
├── provider/     # Adaptadores de provedores (OpenAI, Anthropic, Gemini) e telas de configuração
├── chat/         # Componentes de chat
├── toolcalling/  # Parsers de tool call (JSON, XML, DSML, nativo) e validação
├── workspace/    # Sistema de arquivos do workspace (local/SAF), scanner, permissões
├── skills/       # Gerenciamento de skills
├── projects/     # Projetos e importação
├── port/         # Serviços VoidPort (autocomplete, diff, SCM, MCP channel, tools)
├── account/      # Perfil do usuário
├── resources/    # Gestão de recursos/assets de projetos
└── ui/           # Componentes de UI compartilhados
```

## Como compilar

Requisitos: Android Studio (AGP 8.6+) e JDK 17.

```bash
# Debug
./gradlew assembleDebug

# Release (requer assinatura configurada via gradle.properties:
# RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD)
./gradlew assembleRelease
```

Instale o APK gerado em `app/build/outputs/apk/`.

> O app solicita `MANAGE_EXTERNAL_STORAGE` para operar workspaces em pastas locais. Conceda "Acesso a todos os arquivos" nas configurações do sistema na primeira execução.

## Configuração de provedores

1. Abra **Configurações de IA** no menu
2. Adicione um provedor (OpenAI compatível, Anthropic ou Gemini)
3. Informe a chave de API e o endpoint — ou use "buscar modelos" para listar os modelos disponíveis automaticamente
4. Selecione o modelo ativo no seletor da barra de input do chat

## Licença

Definir.
