# Axion — Melhorias do openai/codex + openai-cookbook (Rodada 2)

> **Data:** setembro/2026 · **Complementa:** `docs/ATUALIZACAO_AXION_RUNTIME_AGENTE.md` (Rodada 1: runtime, eventos, permissões, sandbox, apply_patch, evals — já implementada)
> **Fontes:** [openai/codex](https://github.com/openai/codex) (protocol_v1.md, docs, long-horizon blog), [openai-cookbook](https://github.com/openai/openai-cookbook) (codex_exec_plans, per_run_spending_controller)

---

## TL;DR

A Rodada 1 transformou o Axion num runtime de agente com estado. Esta rodada ataca o que separa um runtime funcional de um agente que **trabalha por horas**: memória de projeto durável (ExecPlans), retomada de sessão com bookmark, gate de custo pré-execução, compaction de contexto, plan mode nativo e uma fronteira SQ/EQ entre UI e runtime. Da análise do código atual: **vários desses mecanismos já existem no Axion de forma fragmentada** (`TokenUsageStore` + `AgentManagerTokenPolicyTest`, `ChatPlanManager` + `update_plan`, `ContextBuilder` com compactação, streaming SSE com delta). A melhoria central é **convergir** esses fragmentos no runtime v2 — e não reimplementar do zero.

---

## 1. O que mudou no Codex desde a Rodada 1

| Mecanismo no Codex | O que é | Onde no Axion hoje |
|---|---|---|
| `response_id` bookmark (protocol_v1) | `TurnComplete` carrega o último `response_id` da API; novo turno retoma sem reenviar histórico completo | Nada equivalente — histórico é sempre reenviado |
| SQ/EQ (Submission Queue / Event Queue) | Fronteira explícita UI→runtime (Ops) e runtime→UI (Events), com IDs correlacionados | Metade pronta: EQ = `EventStream`; falta a SQ de Ops (interrupt/approval) |
| `Op::Interrupt` + retomada | Cancelar não perde a thread; novo turno retoma do bookmark | `AgentRuntime.cancel()` é cooperativo mas não persiste estado retomável |
| ExecPlans / PLANS.md (Cookbook) | Documento vivo: marcos com critérios de aceite, Progress, Decision Log, "done when" | `ChatPlanManager` + `update_plan` já têm metade: falta persistência e o ciclo de marcos |
| Long-horizon loop (blog 25h) | Plan → Edit → Verify → Repair → Update docs → Repeat | `AgentMemory` e `ChatDiffFragment` cobrem fragmentos; falta o ciclo disciplinado |
| Spending controller (Cookbook) | Reserva de custo **antes** da request, settle pós-resposta, halt em incerteza | `TokenUsageStore.record()` é pós-faturamento — não impede o request |
| Auto-compaction | Compactar contexto ao aproximar do limite antes de estourar | `ContextBuilder` compacta tool results, mas não tem gatilho de compaction |
| `request_user_input` tool | O modelo faz perguntas estruturadas ao usuário durante o run | Não existe — `TYPE_AWAITING_USER` existe no `ChatMessage` mas ninguém o usa |

**Licenças:** Codex Apache-2.0, Cookbook MIT. Todo o conteúdo desta rodada é port conceitual (sem cópia de código), então a licença do Axion continua pendente de decisão mas não bloqueia.

---

## 2. Melhorias priorizadas

### M1 — Memória de projeto durável: ExecPlans no Axion (maior ROI)

**Referência:** Cookbook `codex_exec_plans.md` + blog 25h (Prompt.md → Plan.md → Implement.md → Documentation.md).

**O conceito:** para tarefas longas, o agente grava um documento vivo no workspace — spec congelada, marcos com critérios de aceite verificáveis, progress com timestamps, decision log, "done when". A cada retomada, o agente lê o plano em vez de deduzir estado do histórico de chat.

**O Axion já tem metade:** `ChatPlanManager` (planos do modelo via `update_plan` + planos heurísticos), aba Plano na UI, `AgentMemory`, `TaskPlanner.Plan` com status por passo. O que falta: **persistência no workspace** e o **ciclo de marcos**.

**Implementação:**
1. Criar `agentsdk/ExecPlan.kt` com o esqueleto do Cookbook adaptado: `Purpose`, `Milestones` (cada um com `acceptance` verificável), `Progress` (checkboxes com timestamp), `DecisionLog`, `DoneWhen`.
2. Nova tool `update_exec_plan` (via `RuntimePolicyTools`): modelo cria/atualiza o plano no workspace (`.axion/plans/<sc>.md`), com evento `AgentEvent.FileChanged` para a UI.
3. Integração com o `AgentRuntime`: ao iniciar um run com plano ativo, injetar no system prompt o estado atual do plano (marco corrente + próximo passo); ao completar marco, o runtime valida o critério de aceite (tool de verificação) antes de avançar.
4. Persistir em `.axion/plans/` no workspace (não no SQLite) — sobrevive ao processo e é sincronizável via GitHub (que o Axion já tem).

**Critério de aceite:**
- [ ] Run longo retomado após morte do processo continua do marco correto, lendo o plano do workspace
- [ ] Marco só avança após validação do critério de aceite (comando/existência de arquivo)
- [ ] Aba Plano renderiza o ExecPlan com progresso ao vivo

### M2 — Retomada de run com bookmark de sessão (SQ/EQ v1)

**Referência:** `protocol_v1.md` — `response_id` no `TurnComplete`, `Op::Interrupt`, "Task executes until... blocked by user approval", e a regra de um Task por vez.

**Implementação:**
1. `AgentEvent.RunCompleted` ganha campo `bookmark` (último `response_id` reportado pelo gateway; vazio quando o provider não suporta).
2. `AgentRuntime.run(...)` aceita `resumeFrom(bookmark, history)`: novo run que retoma a thread em vez de reenviar tudo. O `AgentLlmGateway` propaga o bookmark ao `AxionAgentGateway` → provider Responses-style.
3. `Op` enum na direção UI→runtime (`AgentOp`): `UserTurn`, `Interrupt`, `ExecApproval`, `UserInputAnswer` — correlacionados por `sub_id`, espelhando a SQ do protocolo. O `EventStream` vira a EQ completa: `AgentMessage`, `AgentMessageContentDelta`, `ExecApprovalRequest`, `TurnComplete` com bookmark.
4. Aprovação pendente sobrevive à morte do processo: `AgentSession` persiste `pendingRequest` (SQLite ou arquivo em `.axion/`); ao reiniciar, a UI oferece aprovar/negar e o runtime retoma.

**Critério de aceite:**
- [ ] `AgentRuntime` pode ser interrompido e retomado sem repetir turnos pagos
- [ ] Aprovação pendente sobrevive à morte do processo e é retomável
- [ ] `EventStream` carrega `TurnComplete` com bookmark exposto na UI (debug)

### M3 — Gate de custo por run (spending controller)

**Referência:** Cookbook `per_run_spending_controller_responses_api.md` — reserve-before-request, settle-after-response, `block()` on uncertainty.

**O conceito:** budget por run em tokens (não em dólares — o Axion é multi-provider e o token é a moeda comum). Antes de cada turno: estimar input atual + reservar worst-case de output; se estoura o budget do run → `BudgetExceeded` vira `RunResult` (não crash), com evento `Error` e `RunCompleted(unsuccessful, "budget exceeded")`. Depois do turno: settle com o usage real reportado.

**O Axion global tem:** `TokenUsageStore` (global, pós-faturamento, wallet do plano) + `AgentManager.estimateInputTokens()` (chars/4) + `isOutputTruncated` (detecta truncamento por `length`/`max_tokens`) + `TokenUsageStore.reserve/notifyListeners` já existem no store global.

**Implementação:**
1. `agentsdk/RunBudget.kt` (port do controller, pure-JVM, testável):
   ```kotlin
   class RunBudget(maxTokens: Long) {
       fun ensureActive(minimum: Long)          // spent + pending + minimum <= max
       fun reserve(worstCase: Long): Handle     // reservado antes do turno
       fun settle(handle: Handle, actual: Long) // ajusta com o usage real
       fun block()                              // incerteza → halt do run
   }
   ```
   Thread-safe, sem Android deps (unit-testável como o resto da agentsdk).
2. Integrar no `AgentRuntime`: antes de `gateway.completeTurn`, `reserve(estimateInput(history) + maxOutputTokens)`; depois, `settle` com o usage real quando o provider reportar (o `AxionAgentGateway` já tem acesso ao usage). Provider sem usage → `block()` (fail-closed, como o Cookbook).
3. Semântica de falha: `RunResult.failure("Run budget exhausted: N/max tokens")` + evento `AgentEvent.Error`. **Nunca** estoura exception para a UI.
4. Conectar ao `TokenUsageStore` global: o `RunBudget` do run é um sub-orçamento do wallet global; o settle propaga para o store global.
5. `AgentManager.isOutputTruncated` já detecta truncamento — no runtime v2, virar `UncertainCharge` → `block()` do run (o Cookbook bloqueia o run quando não consegue confirmar o custo).
6. Configurável por conversa (SharedPreferences).

**Critério de aceite:**
- [ ] Run com budget de 50k tokens para **antes** do request que estouraria
- [ ] Gate funciona com usage real do provider e com provider sem usage (block)
- [ ] `AgentRuntime` para com `RunResult` de budget esgotado, sem crash e sem request extra
- [ ] Settle integra com `TokenUsageStore` (wallet global deduz o que o run gastou)

### M4 — Compaction de contexto com gatilho (auto-compact)

**Referência:** issues do Codex sobre auto-compaction pós-resume; `ContextBuilder` já compacta tool results (head 700/keep 2).

**Implementação: `ContextManager` no agentsdk:**
1. Monitorar o `ContextBudget` do `ContextBuilder` — quando `estimatedTokens(history) > 0.8 × totalTokens`, acionar compaction: sumarizar turnos antigos (LLM call de sumarização, ou extractiva para começar), reter system+recente+tool results recentes.
2. Evento novo `AgentEvent.ContextCompacted(beforeTokens, afterTokens)`.
3. `AgentSession.exportRecentEventsJson` já exporta eventos; compaction ganha entrada no log.
4. A sumarização tem orçamento próprio (não entra na conta do budget do run).

**Critério de aceite:**
- [ ] Run longo não estoura janela; compaction dispara a 80% e o run continua coerente
- [ ] Evento `ContextCompacted` visível nos logs de debug da UI

### M5 — Plan mode nativo + request_user_input

**Referência:** protocol_v1 (`EventMsg::PlanDelta`, `request_user_input` com opções), blog (plan mode nativo via `/plan`), Codex AGENTS.md.

**Implementação:** no `PromptConstants`/system prompt do agente (tool calling), ensinar o modelo a emitir bloco `<proposed_plan>` antes de executar tarefas multi-etapa; o `AgentTurnParser` detecta, emite `AgentEvent.PlanDelta` e a UI mostra o plano para aprovação antes do runtime executar tools de mutação.
- Aprovar o plano = runtime procede; rejeitar = runtime pede revisão ao modelo com o feedback do usuário.
- `request_user_input`: nova tool do runtime (SandboxAwareTool com `ASK_USER` fixo), parâmetros `question`, `options[]`, `allowFreeText`. Resolver via `ApprovalHandler` (a mesma fronteira de diálogo da UI).

**Critério de aceite:**
- [ ] Tarefas multi-etapa apresentam plano antes de editar arquivos
- [ ] O modelo pode fazer perguntas estruturadas mid-run (opções + texto livre), resolvidas via ApprovalHandler

### M6 — Streaming de deltas granulares (padding para o futuro)

**Referência:** protocol_v1 (`AgentMessageContentDelta`, `PlanDelta`), StreamingValueMerger existente.

**Implementação:** adicionar `AgentEvent.AssistantMessageDelta(text)` ao `AgentEvent` e uma rota de delta no `AgentLlmGateway` (o `AxionAgentGateway` já consome SSE; basta propagar os deltas para o stream de eventos). A UI do Axion já tem `KelivoTypingDotsView` e `streaming` em `ChatMessage` — ligar os deltas ao streaming da UI atual.

**Critério de aceite:**
- [ ] Respostas longas aparecem token-a-token na UI durante run v2
- [ ] Deltas não quebram o `AssistantMessage` final (o delta é prefixo do final)

### M7 — Durable memory: AGENTS.md do projeto

**Referência:** blog 25h (Documentation.md como "shared memory e audit log"), AGENTS.md do Codex/Cookbook.

**Implementação:** o agente lê/escreve `AGENTS.md` na raiz do workspace (convenção do Codex): instruções do projeto, restrições, decisões. O `ContextBuilder` injeta o AGENTS.md do workspace no system prompt (com orçamento do `ContextBudget.systemTokens`). O eval do M1 pode usar AGENTS.md como memória do marco.

**Critério:**
- [ ] `ContextBuilder` injeta AGENTS.md do workspace (com truncamento orçado)
- [ ] Tool `update_plan` atualiza também o AGENTS.md quando decisões mudam

---

## 3. Ordem de implementação sugerida

| # | Melhoria | Esforço | Risco | Dependência |
|---|---|---|---|---|
| 1 | ExecPlans (M1) | M | Baixo | — |
| 2 | RunBudget (M3) | P | Baixo | — |
| 3 | Compaction (M4) | M | Médio | M3 (budget também conta tokens de sumarização) |
| 4 | Bookmark/resume (M2) | G | Alto | gateway/provider support; feature flag atrás do `agent.runtime.v2` (Rodada 1) |
| 5 | Plan mode + request_user_input (M5) | M | Médio | M2 (aproveita a fronteira SQ/EQ) |
| 6 | Streaming deltas (M6) | P | Baixo | M2 |
| 7 | AGENTS.md (M7) | P | Baixo | M1 |

**Regra de ouro:** cada melhoria entra com eval (nível 3) no estilo do `AgentRuntimeEvalTest`: roteiro determinístico + afirmações de estado final.

---

## 4. O que NÃO portar (reafirmado e ampliado)

- **`response_id` bookmarks em providers que não suportam:** tratar como opicional (vazio = reenviar histórico completo, comportamento atual). Não forçar todos os providers a implementar.
- **Spending controller em dólares:** o Axion é multi-provider (OpenAI/Anthropic/Gemini/custom); orçamentar em dólares por provider é frágil. Token comum + conversão na UI.
- **Auto-compact "imediato pós-resume" do Codex Desktop** (bug no issue #29426): compactar por gatilho de limite, não por evento de resume.
- **`request_user_input` com `isOther` UI complexa:** versão simples (opções + texto livre) cobre 95%.
- **Harmony format / gpt-oss:** relevante apenas se o Axion servir modelos gpt-oss locais; avaliar depois.

---

## 5. Conexão com a Rodada 1 (o que já está no código)

| Da Rodada 1 | Habilita nesta rodada |
|---|---|
| `EventStream`/`AgentEvent` | EQ completa do protocolo (deltas, PlanDelta, bookmark) |
| `ApprovalHandler` async | `request_user_input` e aprovação persistida |
| `AgentSession` + eventos JSON | Base da persistência de sessão retomável |
| `CommandSandbox` | `request_user_input` e ExecPlans usam a mesma fronteira de política |
| `ApplyPatchTool` | Marcos do ExecPlan aplicam patches validados |
| `FakeAgentLlmGateway` + evals | Cada melhoria M1–M7 entra com eval de estado final |
| `ContextBudget` | Alimenta o gatilho de compaction (M4) e orçamento do AGENTS.md (M7) |
| `RunListenersAdapter` | Compat enquanto a UI migra para a EQ completa |

---

## 6. Checklist desta rodada

```
[ ] M1: ExecPlan (modelo + tool update_exec_plan + persistência .axion/plans/ + validação de marco)
[ ] M2: bookmark response_id + AgentOp enum + aprovação persistida
[ ] M3: RunBudget (reserve/settle/block) integrado ao AgentRuntime + TokenUsageStore
[ ] M4: ContextManager compaction com gatilho a 80% + evento ContextCompacted
[ ] M5: <proposed_plan> + request_user_input via ApprovalHandler
[ ] M6: AssistantMessageDelta no EventStream ligado ao streaming da UI
[ ] M7: AGENTS.md injetado no ContextBuilder com orçamento
[ ] Todas: eval de estado final por melhoria
[ ] Geral: definir licença do projeto (pendente da Rodada 1)
```

---

## 7. Fontes

- Codex `codex-rs/docs/protocol_v1.md` (SQ/EQ, response_id, Op/EventMsg)
- Codex blog "Run long horizon tasks with Codex" (loop, ExecPlans em ação, 25h/13M tokens/30k LOC)
- Cookbook `articles/codex_exec_plans.md` (esqueleto completo de ExecPlan)
- Cookbook `articles/per_run_spending_controller_responses_api.md` (reserve/settle/block)
- Codex issues (auto-compaction pós-resume #29426, rescue workflow)
- Rodada 1: `docs/ATUALIZACAO_AXION_RUNTIME_AGENTE.md`
