package com.saaspaymentsolutions.axion;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class AgentEmptyResponsePolicyTest {
    @Test
    public void retriesEmptyResponseOnlyOnce() {
        assertTrue(AgentEmptyResponsePolicy.shouldRetry("EMPTY_ASSISTANT_PAYLOAD", 0));
        assertFalse(AgentEmptyResponsePolicy.shouldRetry("EMPTY_ASSISTANT_PAYLOAD", 1));
    }

    @Test
    public void doesNotRetryOtherSemanticErrors() {
        assertFalse(AgentEmptyResponsePolicy.shouldRetry("duplicate_request", 0));
        assertFalse(AgentEmptyResponsePolicy.shouldRetry(null, 0));
    }

    @Test
    public void buildsSummaryFromCompletedToolsAfterGenericContinue() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("Quero faces diferentes no cubo", true, 1L));
        messages.add(new ChatMessage("Agora vou atualizar o arquivo.", false, 2L));
        ChatMessage edit = new ChatMessage("edit_file",
                "{\"uri\":\"/storage/emulated/0/.axion_ide_web/602/js/main.js\"}",
                3L, "tool-1");
        edit.setToolRunning(false);
        edit.setToolResult("Arquivo atualizado.");
        messages.add(edit);
        messages.add(new ChatMessage("Continue", true, 4L));
        ChatMessage placeholder = new ChatMessage("", false, 5L);

        String summary = AgentEmptyResponsePolicy.buildLocalSummary(messages, placeholder);

        assertTrue(summary.contains("Quero faces diferentes no cubo"));
        assertTrue(summary.contains("edição ×1"));
        assertTrue(summary.contains("`js/main.js`"));
        assertFalse(summary.contains("Agora vou atualizar"));
    }

    @Test
    public void preservesPreviouslySavedConclusiveSummary() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("Faça a mudança", true, 1L));
        String prior = "A alteração foi concluída e verificada com sucesso. "
                + "O arquivo principal agora usa seis materiais diferentes, "
                + "um para cada face do cubo, e a documentação foi atualizada.";
        messages.add(new ChatMessage(prior, false, 2L));
        messages.add(new ChatMessage("Continue", true, 3L));

        String summary = AgentEmptyResponsePolicy.buildLocalSummary(
                messages, new ChatMessage("", false, 4L));

        assertTrue(summary.contains("Resumo recuperado"));
        assertTrue(summary.contains("seis materiais diferentes"));
    }
}
