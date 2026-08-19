package com.saaspaymentsolutions.axion;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Keeps semantic retries separate from HTTP retries and therefore idempotency IDs. */
final class AgentEmptyResponsePolicy {
    static final String TECHNICAL_CODE = "EMPTY_ASSISTANT_PAYLOAD";
    private static final int MAX_ATTEMPTS = 2;

    private AgentEmptyResponsePolicy() {
    }

    static boolean shouldRetry(String technicalCode, int zeroBasedAttempt) {
        return TECHNICAL_CODE.equals(technicalCode) && zeroBasedAttempt + 1 < MAX_ATTEMPTS;
    }

    static boolean isEmptyResponse(String technicalCode) {
        return TECHNICAL_CODE.equals(technicalCode);
    }

    static String feedback() {
        return "A resposta anterior do modelo terminou vazia depois da ferramenta. "
                + "Use o resultado já disponível, continue a tarefa e entregue uma resposta conclusiva; "
                + "não encerre com conteúdo vazio.";
    }

    /**
     * Produces a useful terminal response when the provider finishes empty even
     * after the one allowed semantic retry. The execution evidence is already
     * in the local thread, so it must not be replaced by a generic error card.
     */
    static String buildLocalSummary(List<ChatMessage> messages, ChatMessage currentPlaceholder) {
        List<ChatMessage> safeMessages = messages == null ? new ArrayList<>() : messages;
        int requestIndex = findLastMeaningfulUserIndex(safeMessages, currentPlaceholder);
        String request = requestIndex >= 0
                ? cleanLine(safeMessages.get(requestIndex).getDisplayContent(), 220)
                : "a solicitação enviada";

        String priorSummary = findPriorConclusiveAssistant(
                safeMessages, requestIndex, currentPlaceholder);
        if (!priorSummary.isEmpty()) {
            return "## Resumo recuperado\n\n" + priorSummary
                    + "\n\n_O provedor encerrou a nova resposta sem conteúdo; "
                    + "o resumo já salvo foi preservado._";
        }

        Map<String, Integer> toolCounts = new LinkedHashMap<>();
        Set<String> targets = new LinkedHashSet<>();
        int successfulTools = 0;
        for (int i = Math.max(0, requestIndex + 1); i < safeMessages.size(); i++) {
            ChatMessage message = safeMessages.get(i);
            if (message == null || message == currentPlaceholder || !message.isTool()
                    || message.isToolError() || message.isToolRunning()) {
                continue;
            }
            successfulTools++;
            String toolName = cleanLine(message.getToolName(), 80);
            if (toolName.isEmpty()) {
                toolName = "ferramenta";
            }
            toolCounts.put(toolName, toolCounts.getOrDefault(toolName, 0) + 1);
            String target = extractTarget(message.getToolArgs());
            if (!target.isEmpty() && targets.size() < 6) {
                targets.add(target);
            }
        }

        StringBuilder summary = new StringBuilder("## Resumo da execução\n\n");
        summary.append("A solicitação foi processada e os resultados executados foram preservados.\n\n")
                .append("- **Solicitação:** ").append(request).append('\n');
        if (successfulTools > 0) {
            summary.append("- **Ações concluídas:** ").append(successfulTools).append(" — ");
            int emitted = 0;
            for (Map.Entry<String, Integer> entry : toolCounts.entrySet()) {
                if (emitted++ > 0) {
                    summary.append(", ");
                }
                summary.append(displayToolName(entry.getKey()))
                        .append(" ×").append(entry.getValue());
            }
            summary.append('\n');
        } else {
            summary.append("- **Ações concluídas:** nenhuma nova ferramenta foi necessária.\n");
        }
        if (!targets.isEmpty()) {
            summary.append("- **Arquivos envolvidos:** ")
                    .append(String.join(", ", targets)).append('\n');
        }
        summary.append("\n_O provedor encerrou a resposta final sem texto; "
                + "este resumo foi reconstruído localmente a partir do histórico verificado._");
        return summary.toString();
    }

    private static int findLastMeaningfulUserIndex(List<ChatMessage> messages,
                                                    ChatMessage currentPlaceholder) {
        int fallback = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message == null || message == currentPlaceholder || !message.isUser()) {
                continue;
            }
            if (fallback < 0) {
                fallback = i;
            }
            if (!isContinuation(message.getDisplayContent())) {
                return i;
            }
        }
        return fallback;
    }

    private static boolean isContinuation(String content) {
        String normalized = cleanLine(content, 80).toLowerCase(Locale.ROOT);
        return normalized.equals("continue")
                || normalized.equals("continuar")
                || normalized.equals("continue.")
                || normalized.equals("continuar.");
    }

    private static String findPriorConclusiveAssistant(List<ChatMessage> messages,
                                                        int requestIndex,
                                                        ChatMessage currentPlaceholder) {
        for (int i = messages.size() - 1; i > requestIndex; i--) {
            ChatMessage message = messages.get(i);
            if (message == null || message == currentPlaceholder || !message.isBot()) {
                continue;
            }
            String content = message.getDisplayContent() == null
                    ? "" : message.getDisplayContent().trim();
            if (content.length() >= 120 && !looksLikeProgress(content)) {
                return content;
            }
        }
        return "";
    }

    private static boolean looksLikeProgress(String content) {
        String normalized = cleanLine(content, 100).toLowerCase(Locale.ROOT);
        return normalized.startsWith("agora vou ")
                || normalized.startsWith("vou começar ")
                || normalized.startsWith("vou atualizar ")
                || normalized.startsWith("em seguida, vou ");
    }

    private static String extractTarget(String rawArgs) {
        if (rawArgs == null || rawArgs.trim().isEmpty()) {
            return "";
        }
        try {
            JSONObject args = new JSONObject(rawArgs);
            String[] keys = {"uri", "path", "file_path", "filePath", "target"};
            for (String key : keys) {
                String value = args.optString(key, "").trim();
                if (!value.isEmpty()) {
                    return compactProjectPath(value);
                }
            }
        } catch (Exception ignored) {
            // Invalid arguments remain visible on their tool card; omit them here.
        }
        return "";
    }

    private static String compactProjectPath(String path) {
        String normalized = path.replace('\\', '/');
        int projectRoot = normalized.indexOf("/.axion_ide_web/");
        if (projectRoot >= 0) {
            int idEnd = normalized.indexOf('/', projectRoot + "/.axion_ide_web/".length());
            if (idEnd >= 0 && idEnd + 1 < normalized.length()) {
                return "`" + normalized.substring(idEnd + 1) + "`";
            }
        }
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return name.isEmpty() ? "" : "`" + name + "`";
    }

    private static String displayToolName(String toolName) {
        switch (toolName) {
            case "read_file":
                return "leitura";
            case "edit_file":
            case "rewrite_file":
            case "write_file":
                return "edição";
            case "create_file":
                return "criação";
            default:
                return toolName.replace('_', ' ');
        }
    }

    private static String cleanLine(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replaceAll("\\s+", " ").trim();
        return cleaned.length() <= maxLength
                ? cleaned
                : cleaned.substring(0, Math.max(1, maxLength - 1)) + "…";
    }
}
