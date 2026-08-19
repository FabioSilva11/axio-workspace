package com.saaspaymentsolutions.axion.port;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import com.saaspaymentsolutions.axion.SketchApplication;
import com.saaspaymentsolutions.axion.Tool;
import com.saaspaymentsolutions.axion.ToolManager;

/**
 * Extra Void AI tools that are services in desktop Void, not file-system tools.
 */
public final class VoidPortAiToolWrapper implements Tool {
    private final String name;
    private final String description;
    private final JSONObject parameters;

    private VoidPortAiToolWrapper(String name, String description, JSONObject parameters) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
    }

    public static void registerAll(ToolManager manager) {
        manager.registerTool(new VoidPortAiToolWrapper(
                "autocomplete_code",
                "Gera uma sugestao FIM de codigo usando o autocomplete portado do Void.",
                params(new String[]{"full_text", "cursor_offset"}, new String[][]{
                        {"language", "string", "Linguagem do arquivo, por exemplo java, kotlin ou xml"},
                        {"file_path", "string", "Caminho do arquivo para contexto"},
                        {"just_accepted", "boolean", "Se true, tenta continuar apos uma sugestao aceita"}
                })
        ));
        manager.registerTool(new VoidPortAiToolWrapper(
                "ai_regex_generate",
                "Cria um regex Java com IA a partir de uma descricao em linguagem natural.",
                params(new String[]{"task"}, new String[][]{
                        {"sample_text", "string", "Texto de exemplo opcional"},
                        {"replacement_task", "string", "Descricao opcional da substituicao desejada"}
                })
        ));
        manager.registerTool(new VoidPortAiToolWrapper(
                "ai_regex_search",
                "Gera um regex com IA e busca arquivos do projeto que combinam com ele.",
                params(new String[]{"task"}, new String[][]{
                        {"search_in_folder", "string", "Pasta para limitar a busca"},
                        {"max_files", "number", "Numero maximo de arquivos retornados"}
                })
        ));
        manager.registerTool(new VoidPortAiToolWrapper(
                "ai_regex_replace_preview",
                "Gera regex e replacement com IA e retorna uma pre-visualizacao de substituicoes em um arquivo.",
                params(new String[]{"uri", "task", "replacement_task"}, new String[][]{
                        {"max_matches", "number", "Numero maximo de ocorrencias na pre-visualizacao"}
                })
        ));
        manager.registerTool(new VoidPortAiToolWrapper(
                "refresh_local_models",
                "Atualiza o cache de modelos locais do Ollama, vLLM e LM Studio.",
                params(new String[]{}, new String[][]{
                        {"provider", "string", "ollama, vllm, lm_studio ou all"},
                        {"enable_provider_on_success", "boolean", "Se true, seleciona o provider quando houver modelos"}
                })
        ));
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public JSONObject getParameters() {
        return parameters;
    }

    @Override
    public String execute(String scId, JSONObject args) throws Exception {
        Context context = SketchApplication.getContext();
        return switch (name) {
            case "autocomplete_code" -> VoidPortAutocompleteService.complete(
                    context,
                    args.optString("full_text", ""),
                    args.optInt("cursor_offset", 0),
                    args.optString("language", ""),
                    args.optString("file_path", ""),
                    args.optBoolean("just_accepted", false)
            ).toJson().toString();
            case "ai_regex_generate" -> VoidPortAiRegexService.generateRegex(
                    context,
                    args.optString("task", ""),
                    args.optString("sample_text", ""),
                    args.optString("replacement_task", "")
            ).toJson().toString();
            case "ai_regex_search" -> VoidPortAiRegexService.search(
                    context,
                    scId,
                    args.optString("task", ""),
                    args.optString("search_in_folder", ""),
                    args.optInt("max_files", 20)
            ).toString();
            case "ai_regex_replace_preview" -> VoidPortAiRegexService.replacePreview(
                    context,
                    scId,
                    args.optString("uri", ""),
                    args.optString("task", ""),
                    args.optString("replacement_task", ""),
                    args.optInt("max_matches", 20)
            ).toString();
            case "refresh_local_models" -> executeRefreshModels(context, args);
            default -> "Erro: ferramenta '" + name + "' nao encontrada.";
        };
    }

    private String executeRefreshModels(Context context, JSONObject args) {
        String provider = args.optString("provider", "all");
        boolean enable = args.optBoolean("enable_provider_on_success", true);
        if (provider == null || provider.trim().isEmpty() || "all".equalsIgnoreCase(provider.trim())) {
            return VoidPortRefreshModelService.refreshAll(context, enable).toString();
        }
        return VoidPortRefreshModelService.refreshProvider(context, provider.trim(), enable).toJson().toString();
    }

    private static JSONObject params(String[] required, String[][] optional) {
        JSONObject params = new JSONObject();
        try {
            params.put("type", "object");
            params.put("additionalProperties", false);
            JSONObject properties = new JSONObject();
            JSONArray requiredArray = new JSONArray();
            for (String name : required) {
                properties.put(name, new JSONObject()
                        .put("type", "cursor_offset".equals(name) ? "number" : "string")
                        .put("description", "Parametro obrigatorio: " + name));
                requiredArray.put(name);
            }
            if (optional != null) {
                for (String[] opt : optional) {
                    properties.put(opt[0], new JSONObject()
                            .put("type", opt[1])
                            .put("description", opt[2]));
                }
            }
            params.put("properties", properties);
            params.put("required", requiredArray);
        } catch (Exception ignored) {
        }
        return params;
    }
}

