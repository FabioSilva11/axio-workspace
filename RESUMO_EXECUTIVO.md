# Resumo Executivo: Correção HTTP 400 - Schemas de Tools

## Problema

HTTP 400 ao chamar provider `custom_mocklocal`:
```
Invalid JSON payload received. Unknown name "items" at 'request.tools[0].function_declarations[13].parameters.properties[1].value': Proto field is not repeating, cannot start list.
```

## Causa

`RequestUserInputTool` construía schema com `"items": [...]` (array) em vez de `"items": {...}` (object):

```java
// INCORRETO
JSONArray items = new JSONArray().put(new JSONObject().put("type", "object"));
schema.put("items", items);  // ❌ Gera "items": [{"type": "object"}]
```

## Solução

Implementação de sistema tipado de schemas inspirado no Codex:

### 1. ToolJsonSchema (tipo seguro)
```java
// Impossível construir "items" como array
ToolJsonSchema.array(
    ToolJsonSchema.object(properties, required, false)
)  // ✅ Sempre gera "items": {"type": "object"}
```

### 2. ToolSchemaNormalizer (validação prévia)
```java
// Valida ANTES do HTTP request
ValidationResult result = ToolSchemaNormalizer.normalize(toolName, schema);
if (!result.isValid()) {
    // Erro local claro em vez de HTTP 400
}
```

### 3. RequestUserInputTool corrigido
```java
// Antes: construção manual propensa a erros
// Depois: API tipada e segura
Map<String, ToolJsonSchema> props = ToolJsonSchema.properties()
    .put("options", ToolJsonSchema.array(
        ToolJsonSchema.object(...)))  // ✅ Correto
    .build();
```

## Impacto

| Aspecto | Antes | Depois |
|---------|-------|--------|
| **Erro HTTP 400** | ✅ Ocorria | ❌ Eliminado |
| **Detecção** | Provider (após HTTP) | Local (antes HTTP) |
| **Mensagem** | Genérica e confusa | Clara com path |
| **Prevenção** | Manual (fácil errar) | Tipo seguro (impossível errar) |
| **Arquitetura** | Ad-hoc | Alinhado com Codex |

## Arquivos Criados

1. **ToolJsonSchema.java** - Sistema tipado de schemas (460 linhas)
2. **ToolSchemaNormalizer.java** - Validador/normalizador (470 linhas)
3. **3 arquivos de teste** - Cobertura completa (740 linhas)

## Arquivos Modificados

1. **RequestUserInputTool.java** - Usa ToolJsonSchema
2. **AgentRuntime.java** - Valida antes de enviar ao provider

## Testes

```bash
# Executar todos os testes relacionados
./gradlew :app:testDebugUnitTest --tests "*.schema.*"
./gradlew :app:testDebugUnitTest --tests "*RequestUserInputSchemaTest"
./gradlew :app:testDebugUnitTest --tests "*ToolPayloadIntegrationTest"
```

**Casos cobertos:**
- ✅ Construção tipada gera JSON correto
- ✅ Validação detecta `"items": [...]` como inválido
- ✅ RequestUserInputTool schema é válido
- ✅ Payload completo não tem padrões inválidos
- ✅ Old buggy schema falha, new correct schema passa

## Exemplo de Melhoria na Detecção de Erros

### Antes (HTTP 400 genérico)
```
Invalid JSON payload received. Unknown name "items" at 'request.tools[0].function_declarations[13]...
```

### Depois (erro local claro)
```
Invalid tool schema:
  tool=request_user_input
  path=properties.options.items
  reason=items must be a schema object, not an array
```

## Alinhamento com Codex

| Padrão Codex | Implementação Axion |
|--------------|---------------------|
| `JsonSchema::array(items)` | `ToolJsonSchema.array(itemsSchema)` |
| `parse_tool_input_schema` | `ToolSchemaNormalizer.normalize` |
| `sanitize_json_schema` | Normalização + inferência |
| Validação prévia | `validateToolset()` antes HTTP |
| Type safety | Impossível construir invalid schema |

## Status

✅ **Correção implementada e testada**
- Bug original eliminado
- Sistema tipado previne regressões
- Testes garantem correção
- Arquitetura alinhada com Codex

## Próximos Passos (Opcional, Futuro)

1. **Task Intent por Run** - Substituir `expectFileMutations` global
2. **Recovery Invisível** - Separar `visibleHistory` de `modelHistory`
3. **Migrar outras tools** - Usar ToolJsonSchema em vez de construção manual

---

**Referência Codex:** Commit `5c5308fc9a9ee789049d646ef11e5400384b9c6f`
- `codex-rs/tools/src/json_schema/types.rs`
- `codex-rs/core/src/tools/router.rs`
