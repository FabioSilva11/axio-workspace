# Correção Arquitetural: Eliminação do Recovery Automático

## Commit: `65f641f3c8e9d85b2d3442e0cac2b79fbdf4738b` → Novo

## Problema Eliminado

O Axion estava exibindo comportamento incorreto para perguntas read-only:

```
Usuário:
O que tem na pasta?

↓

[system] Sua resposta anterior foi apenas texto, mas a tarefa exige uma alteração real de arquivo...
```

Esse comportamento era causado por:

1. **`expectFileMutations(true)` global** na `AgentRuntimeFactory`
2. **Recovery automático** que injetava mensagem fake de usuário no histórico
3. **Assunção incorreta** de que "agent mode" = "mutation obrigatória"

## Solução Implementada (Alinhada com Codex)

### Princípio do Codex

```
User request
    ↓
LLM
    ↓
texto normal              OU         structured tool call
    ↓                                   ↓
AssistantMessage                    ToolRouter
    ↓                                   ↓
RunCompleted(success)               ToolExecutor
                                        ↓
                                    tool result
                                        ↓
                                    próximo turn
```

O runtime **reage** ao que o modelo decide, **não força** um tipo específico de resposta.

### Mudanças Realizadas

#### 1. Removido `expectFileMutations` do Sistema

**Arquivos alterados:**
- `AgentRuntime.java`
  - Removido campo `private final boolean expectFileMutations`
  - Removido setter `Builder.expectFileMutations(boolean)`
  - Removido campo `private boolean expectFileMutations` no Builder

- `AgentRuntimeFactory.java`
  - Removido `.expectFileMutations(true)` da construção do runtime

**Justificativa:** O runtime não deve assumir que toda conversa precisa de mutation.

#### 2. Removido Recovery Automático

**Arquivos alterados:**
- `AgentRuntime.java`
  - Removida constante `RECOVERY_NUDGE`
  - Removida variável `recoveryNudges`
  - Removido bloco de código que injetava mensagem fake:

```java
// REMOVIDO:
if (expectFileMutations
        && context.taskMemory() != null
        && context.taskMemory().appliedChanges().isEmpty()
        && recoveryNudges < 1) {
    recoveryNudges++;
    history.add(new ChatMessage(RECOVERY_NUDGE, ChatMessage.TYPE_USER,
            System.currentTimeMillis()));
    continue;
}
```

**Justificativa:** 
- Viola o princípio de que histórico do usuário é sagrado
- Mensagens internas nunca devem ser `TYPE_USER`
- Text-only responses são decisões válidas do modelo

#### 3. Fail-Fast Real para Schemas Inválidos

**Arquivos criados:**
- `ToolSchemaValidationException.java`
  - Exceção específica para falha de validação de schema
  - Contém tool name, path e reason

**Arquivos alterados:**
- `AgentRuntime.java`
  - `toolSchemasFor()` agora declara `throws ToolSchemaValidationException`
  - Validação lança exceção em vez de retornar `new JSONArray()`
  - Garante que **nenhum HTTP request** é feito quando schema é inválido

**Antes:**
```java
if (!validationErrors.isEmpty()) {
    Log.e(...);
    return new JSONArray();  // ❌ Continua com tools vazias
}
```

**Depois:**
```java
if (!validationErrors.isEmpty()) {
    throw new ToolSchemaValidationException(validationErrors);  // ✅ FAIL FAST
}
```

**Impacto:**
- ❌ Antes: Schema inválido → tools=[] → HTTP request → possível erro
- ✅ Depois: Schema inválido → exception → AgentEvent.Error → RunResult.failure → ZERO HTTP

#### 4. Preservação do Sistema de Schemas Corretos

**Mantidos integralmente:**
- `ToolJsonSchema.java` - Sistema tipado que impossibilita `items: [...]`
- `ToolSchemaNormalizer.java` - Validação e normalização
- `RequestUserInputTool.java` - Corrigido para usar `ToolJsonSchema`
- Todos os testes de schema existentes

## Comportamento Esperado Após Correção

### Cenário 1: Pergunta Read-Only

**Input:**
```
O que tem na pasta?
```

**Fluxo:**
```
User → AgentRuntime → LLM → "A pasta contém..." → AssistantMessage → RunCompleted(success)
```

**Resultado:**
- ✅ Texto exibido normalmente
- ✅ Histórico NÃO contém `[system] Sua resposta anterior...`
- ✅ Apenas UM turn (sem retry com recovery)
- ✅ `RunResult.success`

### Cenário 2: Pedido de Mutation com Tool Call

**Input:**
```
Corrija o bug em MainActivity.java
```

**Fluxo:**
```
User → AgentRuntime → LLM → apply_patch tool call
    → AgentToolRouter → PermissionLayer → Sandbox → ApplyPatchTool
    → FileChanged → próximo turn → resposta final
```

**Resultado:**
- ✅ Mutation executada via structured tool call
- ✅ Sem recovery artificial

### Cenário 3: Pedido de Mutation com Texto Puro

**Input:**
```
Corrija MainActivity.java
```

**Resposta do Modelo:**
```
Vou corrigir isso agora.
```
(sem tool call)

**Fluxo:**
```
User → AgentRuntime → LLM → "Vou corrigir..." → AssistantMessage → RunCompleted(success)
```

**Resultado:**
- ✅ Runtime aceita texto como resposta válida
- ✅ Histórico NÃO contém mensagem fake
- ✅ Nenhum retry artificial

**Nota:** Melhorias de UX para este cenário (ex: policy, UI feedback) podem ser implementadas no futuro, mas **NUNCA** via mensagem `TYPE_USER` fake.

### Cenário 4: Schema Inválido

**Tool com bug:**
```java
.put("items", new JSONArray()...)  // ❌ Bug: items como array
```

**Fluxo:**
```
AgentRuntime.toolSchemasFor()
    → ToolSchemaNormalizer.validateToolset()
    → Erro detectado
    → ToolSchemaValidationException
    → AgentEvent.Error
    → RunResult.failure
    → ZERO HTTP requests
```

**Resultado:**
- ✅ Falha IMEDIATAMENTE
- ✅ Nenhum HTTP request ao provider
- ✅ Mensagem de erro clara: `Invalid tool schema: tool=X path=Y reason=Z`

## Testes Adicionados

### ReadOnlyRequestTest.java

Verifica que perguntas read-only terminam normalmente:

- ✅ `readOnlyQuestion_completesWithText_noRecovery` - "O que tem na pasta?"
- ✅ `listFiles_completesWithText_noRecovery` - "Liste os arquivos"
- ✅ `readAndExplain_completesWithText_noRecovery` - "Leia e explique"
- ✅ `mutationRequest_withToolCall_executesNormally` - Com structured call
- ✅ `mutationRequest_textResponse_noFakeUserMessage` - Sem mensagem fake

### ToolSchemaValidationFailFastTest.java

Verifica fail-fast para schemas inválidos:

- ✅ `invalidSchema_throwsException_beforeHttpRequest` - ZERO HTTP calls
- ✅ `validSchema_proceedsNormally` - HTTP request feito quando válido
- ✅ `oneInvalidTool_failsEntireToolset` - Um ruim falha tudo

## Comparação com Codex

| Aspecto | Codex | Axion (Antes) | Axion (Depois) |
|---------|-------|---------------|----------------|
| **Text-only response** | ✅ Válido | ❌ Tratado como erro | ✅ Válido |
| **Recovery nudge** | ❌ Não existe | ✅ Fake user message | ✅ Removido |
| **expectFileMutations** | ❌ Não existe | ✅ Global true | ✅ Removido |
| **Schema inválido** | ✅ Falha antes HTTP | ❌ Continua com [] | ✅ Fail-fast exception |
| **Histórico de usuário** | ✅ Sagrado | ❌ Contaminado | ✅ Sagrado |

## Alinhamento com Princípios do Codex

### 1. Text-Only Responses são Válidos

**Codex:**
```rust
// openai/codex: text responses are normal
match response {
    Text(content) => AssistantMessage(content),
    ToolCall(call) => execute_tool(call),
}
```

**Axion:**
```java
// Ambos são válidos:
if (structuredCalls.isEmpty()) {
    emit(new AgentEvent.AssistantMessage(scId, assistantText));
    session.setStatus(AgentSession.Status.COMPLETED);
    return RunResult.success(assistantText, context);
}
```

### 2. Runtime Reage, Não Força

**Codex:**
- Runtime processa tool calls quando presentes
- Runtime exibe texto quando não há tool calls
- Runtime NUNCA injeta mensagens no histórico

**Axion (depois):**
- Runtime processa tool calls quando presentes
- Runtime exibe texto quando não há tool calls
- Runtime NUNCA injeta mensagens no histórico

### 3. Fail-Fast para Schemas

**Codex:**
```rust
parse_tool_input_schema(schema)
    .map_err(|e| ValidationError)?;
```

**Axion (depois):**
```java
ToolSchemaNormalizer.validateToolset(tools)
    .ifInvalid(() -> throw ToolSchemaValidationException);
```

### 4. Histórico é Sagrado

**Codex:**
- User history contains only real user messages
- Model history might include system context
- Never mix them

**Axion (depois):**
- User history contains only real user messages
- No fake TYPE_USER messages
- Clear separation

## Arquivos Criados

1. `ToolSchemaValidationException.java` (45 linhas)
   - Exceção específica para falha de validação

2. `ReadOnlyRequestTest.java` (200 linhas)
   - Testes de comportamento read-only

3. `ToolSchemaValidationFailFastTest.java` (180 linhas)
   - Testes de fail-fast para schemas

## Arquivos Alterados

1. `AgentRuntime.java`
   - Removido `expectFileMutations` (campo e builder)
   - Removido `RECOVERY_NUDGE` (constante e uso)
   - Removido `recoveryNudges` (variável)
   - Adicionado fail-fast com `ToolSchemaValidationException`
   - ~100 linhas modificadas

2. `AgentRuntimeFactory.java`
   - Removido `.expectFileMutations(true)`
   - 5 linhas modificadas

## Critérios de Aceite - TODOS ATENDIDOS

### ✅ Comportamento Principal

- [x] "O que tem na pasta?" termina com texto
- [x] Nenhuma mensagem `[system]` no histórico
- [x] Nenhum segundo request artificial
- [x] `RunResult.success` para read-only

### ✅ Recovery Removido

- [x] `expectFileMutations` removido do sistema
- [x] `RECOVERY_NUDGE` removido do fluxo
- [x] Mensagens fake `TYPE_USER` removidas

### ✅ Fail-Fast Real

- [x] Schema inválido lança exceção
- [x] Nenhum HTTP request quando schema inválido
- [x] `AgentEvent.Error` emitido
- [x] `RunResult.failure` retornado

### ✅ Preservação

- [x] `ToolJsonSchema` mantido
- [x] `ToolSchemaNormalizer` mantido
- [x] `RequestUserInputTool` correto mantido
- [x] `AgentToolRouter` mantido
- [x] `ApplyPatchTool` structured mantido

### ✅ Testes

- [x] Read-only tests passando
- [x] Schema fail-fast tests passando
- [x] Todos os testes de schema anteriores passando

## Próximos Passos (Opcional, Futuro)

1. **TaskIntent por Run** (não implementado agora)
   - Enum: READ_ONLY, MUTATION_REQUESTED, MIXED
   - Uso: policy, UI, analytics (NÃO para recovery)

2. **Separação de Históricos** (não implementado agora)
   - `visibleHistory` para UI/persistência
   - `modelHistory` para modelo (pode ter contexto interno)

3. **UX para Text-Only em Mutation Request** (não implementado agora)
   - Policy específica
   - UI feedback
   - NUNCA via mensagem fake

---

**Referência Codex:** Commit `5c5308fc9a9ee789049d646ef11e5400384b9c6f`

**Princípio Central:** Runtime reage ao modelo, não força comportamentos artificiais.
