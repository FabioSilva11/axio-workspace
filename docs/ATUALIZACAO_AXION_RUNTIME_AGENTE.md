# Axion — Plano de Atualização para Runtime de Agente

> **Data:** setembro/2026 · **Base:** código em `app/src/main/java/com/saaspaymentsolutions/axion/`
> **Objetivo:** transformar o Axion de "chat com ferramentas" em um **runtime de agente com estado, eventos, permissões, contexto limitado e testes de integração reais**, usando `openai/codex`, os OpenAI Agents SDKs, `openai/evals`, `agentscope-ai/agentscope-java`, MCP, `google-gemini/gemini-cli` e SWE-agent como referências.

---

## Contrato de mutação de arquivos

> Contrato arquitetural do Axion. Qualquer PR ou agente que toque o fluxo de
> edição deve preservá-lo; violações são bugs.

1. **Approval nunca escreve.** A decisão do `PermissionLayer`/usuário precede a execução e não toca no disco.
2. **Tool execution escreve uma única vez.** Não existe segunda etapa de commit; se a tool reportou sucesso, o arquivo já está no estado final.
3. **`WorkspaceFileSystem` é a abstração principal.** Toda mutação passa pelo filesystem associado ao projeto; `ProjectPathResolver`/`java.io.File` são fallback explícito para sessões sem workspace.
4. **`FileChangeTracker` registra uma alteração já aplicada** — é histórico/auditoria para revisão e revert, nunca buffer de commit.
5. **`FileChanged` significa side effect confirmado.** Só é emitido depois de a mutação estar real e integralmente no disco (patches: após commit-ordered de todas as operações).
6. **Accept nunca escreve.** É ação de revisão: limpa a entrada da lista de revisão.
7. **Revert escreve somente para desfazer** uma alteração registrada.
8. **Revert resolve o filesystem pelo workspace/scId da alteração** (bindado no momento da mutação) — não pelo workspace que estiver ativo no momento do revert.
9. **REVERT DEVE SER FAIL-CLOSED.** Sem binding e sem workspace ativo comprovadamente correspondente ao scId, o revert NÃO executa — nunca há fallback silencioso para o workspace ativo.
10. **NUNCA REVERTER UMA ALTERAÇÃO EM OUTRO WORKSPACE.** Uma alteração do projeto A jamais pode ser restaurada usando o filesystem do projeto B, em nenhuma circunstância.
11. **Falha de patch com rollback completo deixa zero rastros de commit** — sem tracker, sem `FileChanged`.
12. **Rollback incompleto gera erro explícito e estado potencialmente parcial** — nunca falso "totalmente revertido", nunca commit fake; a resposta nomeia os arquivos afetados.

Ordem contratada de eventos (sucesso e falha):

```
ApprovalRequired → PermissionResolved → ToolCallStarted → mutação real →
FileChangeTracker → FileChanged → ToolCallCompleted
```

```
ToolCallStarted → mutation failure → rollback → [Error] → ToolCallCompleted(error)
                                                     └─ rollback incompleto:
                                                        Error(partial/unknown filesystem state)
```

Resolução de filesystem no revert (fail-closed):

```
filesystem bound ao scId (momento da mutação)
  ↓ (sem binding: ex. process death)
workspace ativo, somente se comprovadamente do scId
  (id == scId, ou rootUri local que É o diretório do projeto;
   content:// nunca é resolvido por caminho)
  ↓ (caso contrário)
null → revert recusado (log de workspace incompatível)
```

## Estado atual (atualizado): fluxo de mutação de arquivos

> Esta seção reflete o código implementado (não o plano original abaixo). O fluxo
> de edição segue a separação conceitual do Codex (`apply_patch.rs` +
> `approvals.rs` + `orchestrator.rs`): **uma aprovação, uma mutação real**.

### Componentes

```
AgentRuntime           loop de turnos (v2), emite AgentEvent tipados
PermissionLayer        decide ANTES de executar: ALLOW / ASK_USER / DENY
ApprovalHandler        canal humano-no-loop (pode bloquear o turno até a decisão)
WorkspaceFileSystem    fonte única de E/S (LocalFolderWorkspaceFileSystem | SafWorkspaceFileSystem)
ApplyPatchTool         apply_patch (ADD/UPDATE/DELETE) com validação total + rollback
VoidPortToolsService   registry de tools; mutações passam pelo WorkspaceFileSystem ativo
FileChangeTracker      histórico/auditoria do que JÁ FOI aplicado (persistido em .axion/)
ChatDiffFragment       visualização before/after + revert
```

### Fluxo de uma mutação

```
Tool call do modelo
  ↓
PermissionLayer.check()          ← aprovação acontece AQUI, antes de tocar o disco
  ├─ ALLOW  → segue
  ├─ ASK_USER → ApprovalRequired → o usuário decide → PermissionResolved
  └─ DENY   → PolicyDenied, a tool não executa
  ↓
ToolCallStarted
  ↓
Executor da mutação (apply_patch / edit_file / rewrite_file / delete_file_or_folder)
  ↓
WorkspaceFileSystem (ativo)     ← UMA única escrita real
  ↓
FileChangeTracker.trackChange() ← registra o que já está aplicado (auditoria/diff)
AgentEvent.FileChanged          ← side effect real, consumido pela UI
  ↓
ToolCallCompleted
```

### Emissão é commit-ordered

O `ApplyPatchTool` valida tudo (Pass 1), aplica todas as operações (Pass 2,
com rollback em falha) e só então (Pass 3) registra no `FileChangeTracker` e
emite `AgentEvent.FileChanged`. Um patch revertido por rollback não deixa
rastro: sem tracker, sem eventos. O runtime **empresta** seu próprio
`EventStream`/scId ao patch tool (`boundTo`), e `apply_patch` está excluído do
heurístico `emitFileChangedIfAny` do runtime — 1 mutação real = 1 registro =
1 conjunto de eventos. A ordem contratada é:

```
ApprovalRequired → PermissionResolved → ToolCallStarted →
mutação real → FileChangeTracker → FileChanged → ToolCallCompleted
```

Todos os mutators do registry (`create/edit/rewrite/delete/move/rename/copy`)
usam o `WorkspaceFileSystem` ativo como caminho principal;
`ProjectPathResolver` permanece só como fallback de sessões sem workspace
(nesse fallback, `deleteRecursive` retorna boolean e o resultado é validado).

### Semântica dos três verbos

| Verbo | O que faz | Toca o disco? |
|---|---|---|
| **Approval** | decisão ANTES da execução (política + usuário) | nunca |
| **Diff (accept)** | marca uma mudança já aplicada como revisada; limpa a entrada da lista de revisão | **nunca** — o arquivo já está no estado `after` |
| **Revert (reject)** | desfaz uma mudança já aplicada, restaurando o conteúdo anterior **pelo mesmo `WorkspaceFileSystem` ativo** da mutação original | sim (só aqui) |

Não existe mais "segunda etapa de commit": se a tool reportou sucesso, o arquivo
já está alterado. Nenhuma ferramenta reporta sucesso sem verificar o retorno do
filesystem (`delete() == false` ou arquivo ainda existente ⇒ resultado de erro).

### Garantias cobertas por testes

- `FileChangeTrackerWorkspaceTest` — accept não reescreve; revert restaura via workspace; falha de delete/write falha o revert (fail-closed).
- `FileMutationFlowTest` — delete via workspace (com validação de retorno, traversal rejeitado, audit trail); `apply_patch` com delete validado e rollback de escritas parciais; `RegistryToolAdapter` preserva `isFileMutation/isDestructive/requiresApproval`; `PermissionLayer` classifica por metadata (fallback por nome só cobre adapters sem metadata).
- `FileMutationE2EEvalTest` — fluxo completo no runtime real: aprovação → execução → filesystem → `FileChanged`; negação ⇒ zero mutação e zero evento; **accept não é necessário para a alteração existir**.

### Divergência conhecida

O `ProjectPathResolver` (java.io.File) permanece apenas como **fallback** para
sessões sem workspace aberto. Todo o caminho principal (SAF ou pasta local)
passa pelo `WorkspaceFileSystem` ativo.

## 0. Estado real do código (inventário, setembro/2026)

Antes de listar o que falta, o que **já existe** — e que a análise externa não capturou:

| Camada | O que já existe no Axion | Onde |
|---|---|---|
| **Agent SDK (loop)** | `Agent`, `Runner` (loop com guardrails, tool calls, handoffs, max-turns=16), `AgentTurnParser`, `RunContext`, `Guardrail`/`GuardrailResult`, `HandoffTool`, `WorkspaceAgents` | `agentsdk/` |
| **Observabilidade do run** | `RunListeners` com `onTurnStart`, `onToolStart`, `onToolFinish`, `onToolApproval`, `onGuardrailBlocked`, `onOutputGuardrailPassed` | `agentsdk/RunListeners.java` |
| **Tool calling** | Parsers JSON/XML/DSML/nativo/MCP, `ToolArgumentsValidator`, detectores | `toolcalling/` |
| **Registry de tools** | `ToolManager` com registro por chat-mode, flag `mutationsAllowed`, `requiresApproval()` por tool | `ToolManager.java` |
| **Contexto limitado** | `ContextBuilder` com orçamentos em tokens (total 6k, system 2.4k, history 3k, compactação de tool results, cache TTL de diretórios) | `ContextBuilder.java` |
| **Workspace** | `Workspace.kt`, `WorkspaceFileSystem` (local/SAF), `WorkspaceIgnoreRules`, `WorkspaceScanner`, `WorkspacePermissionManager` | `workspace/` |
| **MCP** | Cliente HTTP Streamable (`VoidPortMcpChannel`, protocolo `2026-07-28` + fallback `2025-06-18`), cache de catálogo, `GitHubMcpService` | `port/` |
| **Skills** | `Skill`, `SkillManager`, UI | `skills/` |
| **Memória/agentes múltiplos** | `AgentMemory`, `MultiAgentOrchestrator`, `TaskPlanner`, `ToolSequenceValidator`, `RetryManager` | `agent/` |
| **Testes** | 43 arquivos de teste unitário (parser, streaming, contexto, dependências) | `app/src/test/` |

**Lacunas reais confirmadas no código neste levantamento (ESTADO ANTERIOR — setembro/2026; todas já endereçadas pelo runtime atual, ver "Estado atual" no topo deste documento):**

1. **Não há fluxo de permissões humano-no-loop**: `Runner` consulta `listeners.onToolApproval()` de forma **bloqueante e síncrona** — a UI não consegue mostrar um diálogo e retomar depois; e `ToolManager` só conhece o booleano `mutationsAllowed`.
2. **Não há `AgentEvent` tipado**: `RunListeners` são callbacks de método (não um stream observável), a UI deduz progresso pelo texto, e não existe `ToolCallStarted/Completed`, `FileChanged`, `CommandStarted/Finished` como eventos consumíveis.
3. **Não há retomada/interrupção de run** (`pause/resume/cancel`): cancelar hoje mata o serviço (`ChatRunForegroundService`) e perde o estado do turno.
4. **Sem `apply_patch` unificado**: *(estado anterior)* hoje o `ApplyPatchTool` existe no toolset padrão, valida o patch inteiro antes de escrever e registra o histórico no `FileChangeTracker`; o diff da aba Diferenças é revisão de algo já aplicado.
5. **Sem sandbox/limites de execução por ferramenta**: comandos shell rodam com a confiança do workspace; não há política `read-only`/`network`/`workspace-write` por turno.
6. **Sem evals/regression harness**: nenhum teste valida "o agente resolveu a tarefa fim-a-fim" — os 43 testes são unitários de componentes.
7. **Two tool systems paralelos** (`ToolManager` + `AgentTool` do agentsdk) sem convergência — o `Runner` não enxerga as tools do `ToolManager`.

---

## 1. Repositórios de referência e o que extrair de cada um

| # | Repositório | O que estudar | Aplicação direta no Axion |
|---|---|---|---|
| 1 | [openai/codex](https://github.com/openai/codex) (Apache-2.0) | Loop `Session → Task → Turn`, `protocol_v1.md`, `protocol.rs`, `sandboxing.rs`, `approvals.rs`, `apply_patch.rs`, `config.schema.json`, MCP conformance suite | **Núcleo do agent runtime** — permissões, sandbox, eventos, `apply_patch` |
| 2 | [openai/evals](https://github.com/openai/evals) (MIT) | Registry de evals, datasets, model-graded evals, harness | **Resolver o problema dos testes vagos** — evals de comportamento fim-a-fim |
| 3 | [agentscope-ai/agentscope-java](https://github.com/agentscope-ai/agentscope-java) (Apache-2.0) | Eventos tipados, permission engine, middleware stack, workspace/sandbox, sessão distribuída, JDK 17+ | **Arquitetura Java/Kotlin mais próxima** — espelho direto para o stack do Axion |
| 4 | [openai/openai-agents-js](https://github.com/openai/openai-agents-js) (MIT) | Agent loop, sessions, handoffs, guardrails, tracing, sandbox agents | Já é a base do `agentsdk/`; aprofundar sessions e tracing |
| 5 | [openai/openai-agents-python](https://github.com/openai/openai-agents-python) (MIT) | Separação Agent/Runner/Tool/Session/Handoff/Tracing | Conceito de runtime controlando turns vs. loop manual |
| 6 | [modelcontextprotocol/typescript-sdk](https://github.com/modelcontextprotocol/typescript-sdk) (MIT→Apache-2.0, verificar por arquivo) | Cliente/servidor, transports, tools/resources/prompts | Endurecer a camada MCP existente |
| 7 | [modelcontextprotocol/servers](https://github.com/modelcontextprotocol/servers) | Ferramentas MCP reais de referência | Validar catálogo de tools MCP |
| 8 | [google-gemini/gemini-cli](https://github.com/google-gemini/gemini-cli) (Apache-2.0) | Skills, memória/contexto, shell, MCP, extensions | UX de coding agent; separação `packages/core` |
| 9 | [SWE-agent/SWE-agent](https://github.com/SWE-agent/SWE-agent) (MIT) | Agent-Computer Interface, resolução de issues, avaliação | **Tarefas realistas** para os evals (issues reais) |
| 10 | [SWE-agent/SWE-ReX](https://github.com/SWE-agent/SWE-ReX) | Execução isolada/sandbox de código | Referência para execução segura de comandos |

> **Licenças:** Codex Apache-2.0, Agents SDKs MIT, SWE-agent MIT, AgentScope Apache-2.0. MCP está em transição MIT→Apache-2.0 — verificar o arquivo específico antes de portar código. Claude Code: tratar só como referência arquitetural (não copiar implementação). E **definir a licença do Axion** (README ainda diz "Definir").

---

## 2. Arquitetura alvo

```
┌─────────────────────────────────────────────────────────────┐
│                    Chat / Android UI                         │
│         (observa AgentEvent, nunca interpreta texto)         │
└──────────────────────────┬──────────────────────────────────┘
                           │
                ┌──────────▼──────────┐
                │    AgentSession     │  ← persistente (SQLite)
                │ messages · files    │
                │ context · perm · metadata │
                └──────────┬──────────┘
                           │
              ┌────────────▼────────────┐
              │      AgentRuntime       │  ← substitui o loop do Runner
              │  startTurn / pauseForApproval / resume / cancel │
              │      Plan → Act → Check │
              └────────────┬────────────┘
                           │
                ┌──────────▼──────────┐        ┌─────────────────┐
                │   ToolOrchestrator  │───────▶│ PermissionLayer │
                └───┬──────┬──────┬───┘        │ ALLOW/ASK/DENY  │
                    │      │      │            └────────┬────────┘
              ┌─────▼─┐ ┌──▼───┐ ┌▼────┐                │
              │ Files │ │Shell │ │ MCP │                │
              └───────┘ └──────┘ └─────┘                │
                    └──────┼───────┘                     │
                           ▼                             │
                   ┌───────────────┐                     │
                   │   Workspace   │◀────────────────────┘
                   └───────────────┘

  Ao redor:
  ContextManager (orçamentos por categoria) · Skills · EventStream
  → UI/Telemetry · Eval Harness (5 níveis)
```

**Princípio central (do Codex):** a decisão de executar uma tool nunca é do modelo — é do **`ToolPolicy`**. E o progresso do agente nunca é texto — é **`AgentEvent`**.

---

## 3. Entregas priorizadas

### Fase 1 — Eventos tipados + Sessão (maior impacto/risco, ~2 semanas)

**Referência principal:** `agentscope-java` (eventos tipados, JDK 17) + `codex protocol_v1.md`.

**3.1. Criar `AgentEvent` como sealed hierarchy (Kotlin) em `agentsdk/`:**

```kotlin
sealed interface AgentEvent {
    data object RunStarted : AgentEvent
    data object TurnStarted : AgentEvent
    data class AssistantMessage(val content: String) : AgentEvent
    data class ToolCallStarted(val tool: String, val callId: String) : AgentEvent
    data class ToolCallCompleted(val tool: String, val callId: String, val success: Boolean) : AgentEvent
    data class ApprovalRequired(val tool: String, val reason: String) : AgentEvent
    data class FileChanged(val path: String, val kind: Kind) : AgentEvent
    data class CommandStarted(val cmd: String) : AgentEvent
    data class CommandFinished(val cmd: String, val exitCode: Int) : AgentEvent
    data class Error(val message: String, val cause: Throwable?) : AgentEvent
    data object TurnCompleted : AgentEvent
    data object RunCompleted : AgentEvent
}
```

**3.2. Criar `EventStream` (Kotlin `SharedFlow<AgentEvent>`):**
- `Runner` emite em vez de chamar `RunListeners` (que se torna adapter legado → `RunListenersAdapter`).
- UI coleta via `repeatOnLifecycle` e **para de deduzir progresso pelo texto**.
- `ChatLogsFragment` e `ChatToolActivitySummary` passam a consumir o stream.

**3.3. Criar `AgentSession` persistente:**
- Tabela SQLite nova (`agent_sessions`, `agent_events`) ao lado do `SqliteChatStorage` existente.
- Sessão sobrevive à morte do processo: `pauseForApproval()`, `resume(decision)`, `cancel()`.

**3.4. Substituir a aprovação bloqueante do `Runner` por um `suspend fun requestApproval(req): PermissionDecision`** — a UI responde quando o usuário clicar.

**Critério de aceite:**
- [ ] UI reage a `ApprovalRequired` mostrando diálogo, sem travar o loop
- [ ] Run sobrevive a rotação de tela e morte do processo
- [ ] `ChatToolActivitySummary` renderiza a partir de eventos, não de texto

---

### Fase 2 — Permissões + Sandbox (referência: `codex sandboxing.rs`/`approvals.rs`)

**3.5. Criar `ToolPolicy` e `PermissionLayer` (`agentsdk/`):**

```kotlin
enum class PermissionDecision { ALLOW, ALLOW_ONCE, ASK_USER, DENY }

data class ToolPolicy(
    val mutation: Policy = ASK_USER,   // edit_file, rewrite_file, apply_patch
    val shell: Policy = ASK_USER,      // run_command
    val network: Policy = ASK_USER,    // tools MCP externas
    val mcpExternal: Policy = ASK_USER,
)
```

- Migrar `Tool.requiresApproval()` (booleano) para `ToolPolicy` por ferramenta.
- Substituir `ToolManager.mutationsAllowed` por `Policy.READ_ONLY`/`WORKSPACE_WRITE`/`FULL` por turno (mantendo compat com `setMutationsAllowed` como sugar).
- Registrar cada `PermissionRequest`/`Decision` na sessão (auditoria) e no `ChatToolLog`.

**3.6. Sandbox mínimo por turno (port conceitual do Codex):**
- Shell: `working-dir` fixo no workspace + deny-list (ex.: `rm -rf /`, escapes `../`, writes fora do workspace) + timeout + kill do processo.
- Rede: bloquear tools MCP não-whitelistadas em modo `read-only`.
- **Não copiar o sandbox de kernel do Codex** — inviável no Android; portar o **modelo de decisão** (estados `Skip/NeedsApproval/Forbidden`).

**Critério de aceite:**
- [ ] Nenhuma mutação fora do workspace raiz é possível em modo `READ_ONLY`
- [ ] Toda mutação tem entrada auditável em `agent_events`
- [ ] Comando shell que tenta escapar do workspace é negado + evento `Error`

---

### Fase 3 — `apply_patch` + ContextManager (referência: `codex apply_patch.rs` + orçamentos do Codex)

**3.7. Criar tool `apply_patch` unificada:**
- Formato patch (diff unificado leve) com operações Add/Update/Delete.
- Validação **antes** de aplicar: paths seguros (sem `..`, não sai do workspace), arquivo-alvo existe/não existe conforme op.
- Gera `AgentEvent.FileChanged` para cada arquivo e preview de diff na UI (reusa `VoidPortDiffService`).

**3.8. Endurecer `ContextBuilder` com `ContextManager` explícito:**

| Categoria | Orçamento | Ação ao estourar |
|---|---|---|
| system+skills | 2.4k | fixo |
| relevant files | 1.5k | truncar LRU |
| recent turns | 3k | sumarizar turnos antigos |
| tool results | 1k | compactar (já existe: head 700/keep 2) |
| summary | 500 | gerado |

- Extrair as constantes hardcoded do `ContextBuilder` para `ContextBudget` configurável (model-aware: `VoidPortProviderMaxTokens` já existe e deve alimentar os orçamentos).

**Critério de aceite:**
- [x] `apply_patch` rejeita patch inválido com erro estruturado (não corrompe arquivo) — implementado
- [x] Mudanças aparecem na aba Diferenças como revisão após a aplicação real (o diff não é etapa de commit) — implementado
- [ ] Orçamentos de contexto são model-aware e configuráveis (sem constantes mágicas) — pendente

---

### Fase 4 — Convergência de tools + MCP (referência: MCP SDK/servers + `gemini-cli packages/core`)

**3.9. Unificar `ToolManager` + `AgentTool` num `ToolRegistry`:**

```
Provider (OpenAI/Claude/Gemini)
   ↓ NormalizedModelResponse
ToolOrchestrator
   ↓
ToolRegistry  ← ToolManager (chat tools) + AgentTool (agentsdk) + MCP tools
```

- Adaptadores de provider viram *apenas* tradutores de protocolo (hoje `AgentManager`+`ContextBuilder` acumulam papéis).
- `Runner`/`ToolExecutor` passa a resolver tools do registry unificado.

**3.10. Suíte de conformidade MCP (port do `codex/scripts/mcp_conformance`):**

```
discover tools → validate schema → call tool → invalid args →
permission denied → timeout → server disconnect → reconnect →
malformed result
9 cenários contra VoidPortMcpChannel
```

**Critério de aceite:**
- [ ] Um único registry resolve todas as fontes de tools (chat, agentsdk, MCP)
- [ ] 9 cenários MCP passam contra um servidor mock
- [ ] Provider adapters sem lógica de negócio (só tradução de protocolo)

---

### Fase 5 — Eval Harness (referência: `openai/evals` + SWE-agent) — *fecha o problema dos "testes vagos"*

**5 níveis de teste** (os 43 testes unitários atuais cobrem o nível 1 apenas):

| Nível | O que valida | Como |
|---|---|---|
| 1. Unit | parser, schema, patch, path security | **já existe** (43 arquivos) |
| 2. Tool Integration | LLM → tool → filesystem | mock LLM + temp workspace real |
| 3. Agent Integration | prompt → múltiplos turns → estado final | fake gateway com roteiro de respostas |
| 4. Regression Evals | tarefas conhecidas, roteirizadas | datasets versionados em `evals/` |
| 5. Real Workspace | projeto Android real, agente modifica, Gradle compila, testes passam | device/emulador CI opcional |

**3.11. Criar `FakeAgentLlmGateway` (nível 2-3):** implementa `AgentLlmGateway` com roteiro determinístico de turnos ("responda com tool call X, depois Y, depois texto final") — mesmo padrão dos helpers de SSE mock do Codex.

**3.12. Criar evals versionados em `evals/` na raiz:**

```
evals/
├── README.md                  # como rodar, como adicionar caso
├── cases/
│   ├── fix_npe_user_repo/     # issue real do SWE-bench-like
│   │   ├── task.md            # prompt + estado inicial esperado
│   │   ├── workspace/         # arquivos do projeto fixado
│   │   └── expected.json      # asserts: patch válido, compila, sem tocar proibido
│   └── ...
└── runner/                    # harness que roda o Runner contra cada case
```

Exemplo de case (o do documento original, formalizado):

```json
{
  "id": "fix_npe_user_repo",
  "prompt": "Corrija o NullPointerException em UserRepository.kt",
  "workspace": "cases/fix_npe_user_repo/workspace/",
  "expected": {
    "mustCall": ["search_for_files", "read_file", "apply_patch"],
    "mustNotTouch": ["**/build/**", "**/.git/**"],
    "finalState": {
      "fileCompiles": true,
      "testPasses": "UserRepositoryTest"
    },
    "maxLlmCalls": 8,
    "maxToolCalls": 12
  }
}
```

**Critério de aceite:**- [ ] `./gradlew :app:testDebugUnitTest --tests "*Eval*"` roda os níveis 2–4 no CI local
- [ ] Cada PR que muda lógica do agente roda o eval suite (padrão do Codex: integration tests obrigatórios)
- [ ] Métrica: % de cases aprovados por suite, rastreada entre builds

---

## 4. Ordem de implementação e riscos

| Ordem | Entrega | Risco | Mitigação |
|---|---|---|---|
| 1 | `AgentEvent` + `EventStream` + `AgentSession` | Médio — toca UI toda | `RunListenersAdapter` mantém compat; migrar UI incrementalmente |
| 2 | `PermissionLayer` + `ToolPolicy` | Baixo | Compat: `requiresApproval()` → `Policy` default `ASK_USER` |
| 3 | `apply_patch` + ContextManager | Baixo | Reusar `SearchReplaceEngine` e `VoidPortDiffService` |
| 4 | `ToolRegistry` unificado + MCP conformance | Alto — refatora coração | Feature flag `agent.runtime.v2` para rollback |
| 5 | Eval Harness | Baixo | Não toca produção; só `app/src/test` + `evals/` |

**Regra de ouro (do `AGENTS.md` do Codex):** toda mudança na lógica do loop/agente entra com **integration test** no nível 3, não apenas unit.

**Dependências entre fases:** 1 → 2 → 3 são sequenciais; 4 e 5 podem paralelizar com 3. Fase 5 (evals) pode começar já na Fase 1 — quanto antes, mais cedo pega regressão.

---

## 5. Ordem de estudo recomendada

```
1. openai/codex                      → runtime, permissões, eventos
2. agentscope-ai/agentscope-java     → espelho Java/Kotlin dos mesmos conceitos
3. openai/evals                      → disciplina de avaliação
4. openai/openai-agents-js           → aprofundar a base já portada
5. google-gemini/gemini-cli          → UX de coding agent (skills, memória)
6. modelcontextprotocol/servers      → catálogo de tools de referência
7. modelcontextprotocol/typescript-sdk → transport/protocol details
8. SWE-agent/SWE-agent               → tarefas realistas para evals
9. SWE-agent/SWE-ReX                 → execução isolada
```

---

## 6. Quick wins (fazer nesta semana, sem tocar arquitetura)

1. **Definir a licença do projeto** (README diz "Definir") — bloqueia decisão de portar código de terceiros.
2. **Migrar `RunListeners` → `EventStream` mínimo** (só `onToolStart/onToolFinish` viram eventos; resto fica) — menor mudança com maior ganho de observabilidade.
3. **Adicionar `apply_patch` como tool nova** ao lado de `edit_file`/`rewrite_file` (não substitui nada) — menor risco, ganho imediato de qualidade de patch.
4. **Criar o primeiro eval case** (`fix_npe_user_repo`) manualmente, mesmo que o harness não exista — define o formato cedo.
5. **Timeout + kill de processo shell** (se ainda não houver) — segurança básica que não depende da Fase 2.
6. **`SslUtils.relaxedClientBuilder()` no `VoidPortMcpChannel`** — revisar desligar a validação TLS relaxada para servidores MCP em produção (attacker-in-the-middle pode executar tools no workspace).

---

## 7. O que NÃO copiar

- **Sandbox de kernel do Codex** (43k linhas, 4 backends) — Android não oferece os mesmos primitivos; portar só o modelo de decisão.
- **Distributed session do AgentScope** — o Axion é single-device; a abstração adiciona custo sem benefício hoje.
- **Subagents/HITL do AgentScope "Harness"** — o Axion já tem handoffs no `agentsdk`; duplicar mecanismos só cria segunda fonte de verdade.
- **Evals model-graded com LLM como juiz** no CI — caro e instável; usar asserts determinísticos (estado final, tools chamadas, patches válidos) e reservar model-graded para evals manuais.

---

## 8. Checklist resumido (copiar para o board)

```
[ ] Fase 1: AgentEvent + EventStream (SharedFlow) + AgentSession (SQLite)
[ ] Fase 1: requestApproval suspend (fim da aprovação bloqueante)
[ ] Fase 2: ToolPolicy + PermissionLayer (ALLOW/ALLOW_ONCE/ASK_USER/DENY)
[ ] Fase 2: Sandbox mínimo shell (workspace jail + deny-list + timeout)
[x] Fase 3: apply_patch (validação prévia; o diff é revisão, não etapa de aplicação) — implementado
[ ] Fase 3: ContextBudget model-aware (extrair constantes do ContextBuilder)
[ ] Fase 4: ToolRegistry unificado (ToolManager + AgentTool + MCP)
[ ] Fase 4: MCP conformance suite (9 cenários, mock server)
[ ] Fase 5: FakeAgentLlmGateway + evals versionados (5 níveis)
[ ] Fase 5: métrica de % evals passando no CI
[ ] Geral: definir licença do projeto
[ ] Geral: revisar TLS relaxado no cliente MCP
```
