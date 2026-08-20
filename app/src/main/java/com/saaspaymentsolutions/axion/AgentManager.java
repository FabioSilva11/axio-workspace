package com.saaspaymentsolutions.axion;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;

import androidx.annotation.Nullable;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.io.File;

import com.saaspaymentsolutions.axion.R;
import com.saaspaymentsolutions.axion.agent.AgentMemory;
import com.saaspaymentsolutions.axion.agent.AgentRunGuard;
import com.saaspaymentsolutions.axion.agent.FinishChecker;
import com.saaspaymentsolutions.axion.agent.MultiAgentOrchestrator;
import com.saaspaymentsolutions.axion.agent.MultiAgentPolicy;
import com.saaspaymentsolutions.axion.agent.PatternMatcher;
import com.saaspaymentsolutions.axion.agent.RetryManager;
import com.saaspaymentsolutions.axion.agent.TaskPlanner;
import com.saaspaymentsolutions.axion.agent.ToolSequenceValidator;
import com.saaspaymentsolutions.axion.port.VoidToolWrapper;
import com.saaspaymentsolutions.axion.port.VoidPortDiffService;
import com.saaspaymentsolutions.axion.port.VoidPortConvertToLlmMessageService;
import com.saaspaymentsolutions.axion.port.VoidPortMcpChannel;
import com.saaspaymentsolutions.axion.port.VoidPortModelCapabilities;
import com.saaspaymentsolutions.axion.port.VoidPortSettings;
import com.saaspaymentsolutions.axion.Tool;
import com.saaspaymentsolutions.axion.ToolManager;
import com.saaspaymentsolutions.axion.AiProviderService;
import com.saaspaymentsolutions.axion.AiRequestHandle;
import com.saaspaymentsolutions.axion.ProjectPathResolver;
import com.saaspaymentsolutions.axion.toolcalling.ToolArgumentsValidator;

/**
 * Orchestrates the chat loop, approval flow, checkpoints, diff previews and
 * cancellation of the active stream/tool execution.
 */
public class AgentManager {

    private static final int MAX_PREVIEW_LINES = 48;
    private static final long STREAM_COALESCE_MS = 120L;
    /**
     * Retry na camada do AgentManager foi reduzido de 3 para 1 porque agora as camadas
     * inferiores (AiStreamingTransport e AiProviderService) já implementam retry
     * centralizado com AiRetryController (4 tentativas totais).
     * Manter múltiplas camadas de retry causava até 27 tentativas (3×3×3).
     */
    private static final int MAX_LLM_ATTEMPTS = 1;
    private static final long LLM_RETRY_DELAY_MS = 2500L;
    private static final int MAX_FINISH_REJECTIONS = 3;
    /** A truncated successful response may be continued, but never indefinitely. */
    private static final int MAX_OUTPUT_CONTINUATIONS = 2;
    /** Bounded maker-checker retries prevent reviewer feedback loops. */
    private static final int MAX_MULTI_AGENT_REVIEW_ROUNDS = 2;
    /**
     * Regex that strips any characters that are not valid in a tool name.
     * Protects against models (especially free/quantized ones) leaking internal
     * tokens into tool names, e.g. {@code edit_file<|channel|>commentary}.
     * Valid tool names contain only ASCII word chars, hyphens and dots.
     */
    private static final java.util.regex.Pattern TOOL_NAME_SANITIZER =
            java.util.regex.Pattern.compile("[^a-zA-Z0-9_\\-.]");

    public enum State {
        IDLE,
        THINKING,
        AWAITING_APPROVAL,
        EXECUTING_TOOL,
        FINISHED,
        ERROR
    }

    private final Context context;
    private final String scId;
    private final List<ChatMessage> messages;
    private final AgentListener listener;
    private final AiProviderService aiService;
    private final MultiAgentOrchestrator multiAgentOrchestrator;
    private final ToolManager toolManager;
    private final Handler mainHandler;
    private final Handler streamCoalesceHandler;
    private final ChatCheckpointManager checkpointManager;
    private AiRequestHandle currentRequestHandle;

    private State currentState = State.IDLE;
    private ChatMessage pendingToolMessage;
    private ChatMessage currentStreamingMessage;
    private Thread currentToolThread;
    private int runVersion = 0;
    private int pendingToolLoopStep = -1;
    private final AgentRunGuard runGuard = new AgentRunGuard();
    
    /** Contexto imutável da operação atual de IA (modelo, provedor, requestId). */
    private AiOperationContext currentOperationContext;
    
    /** Abort the run after this many consecutive failing tool executions. */
    private static final int MAX_CONSECUTIVE_TOOL_FAILURES = 4;
    private int consecutiveToolFailures = 0;
    /**
     * Tool calls returned by the LLM in the current turn that still await
     * execution. Modern models emit several (often parallel) tool calls per
     * turn; they are executed sequentially in the order received, and the
     * agent loop only advances once the queue drains.
     */
    private final java.util.ArrayDeque<String[]> queuedToolCalls = new java.util.ArrayDeque<>();
    private String queuedChatMode = "agent";

    // ---- History compaction (context only; the visible chat is untouched) ----
    /** The character estimator used by the documented compaction strategy. */
    private static final int COMPACTION_CHARS_PER_TOKEN = 4;
    /** Summarization runs only once the effective history exceeds its token budget. */
    private static final int COMPACTION_TRIGGER_PERCENT = 75;
    /** Keep Android requests responsive even when a provider advertises a huge context window. */
    private static final int MAX_COMPACTION_TRIGGER_TOKENS = 16_000;
    private static final int DEFAULT_TOTAL_CONTEXT_TOKENS = 6_000;
    private static final int DEFAULT_SYSTEM_CONTEXT_TOKENS = 2_400;
    private static final int DEFAULT_HISTORY_CONTEXT_TOKENS = 3_000;
    private static final int DEFAULT_COMPILE_ERROR_TOKENS = 500;
    private static final int MAX_CONTEXT_TOKENS = 128_000;
    /** Recent messages always kept verbatim in the context. */
    private static final int COMPACT_KEEP_TAIL = 12;
    /** Max chars of transcript sent to the summarizer. */
    private static final int COMPACT_TRANSCRIPT_MAX_CHARS = 32_000;
    /** Leaves room for fresh messages when an existing summary is summarized again. */
    private static final int COMPACT_SUMMARY_MAX_CHARS = 12_000;
    private String historySummary = "";
    private int historyCompactedUntil = 0;
    /** Prevents the same unchanged history from immediately re-entering compaction. */
    private int lastCompactionMessageCount = -1;
    private boolean compactionInFlight = false;
    private boolean compactionFailed = false;

    /** Checkpoint message shared by every file mutation of the current run (turn-level rollback). */
    private ChatMessage currentRunCheckpointMessage;
    private ChatInteractionTrace interactionTrace;
    private ChatMessage pendingStreamMessage;
    private boolean streamUpdateScheduled;
    private String streamingToolName = "";
    private String streamingToolId = "";
    private String streamingMcpServerName;
    private AgentMemory agentMemory;
    private PatternMatcher.Result requestPattern;
    private TaskPlanner.Plan taskPlan;
    private final java.util.List<ToolSequenceValidator.ToolUsage> toolUsageHistory = new java.util.ArrayList<>();
    private String pendingAgentFeedback = "";
    private int finishValidationFailures = 0;
    private int outputContinuationCount = 0;
    private String multiAgentGuidance = "";
    private boolean multiAgentEnabledForRun = false;
    private String multiAgentModeForRun = MultiAgentPolicy.MODE_AUTO;
    private String multiAgentDecisionReason = "not_evaluated";
    private boolean multiAgentPrepared = false;
    private boolean multiAgentPreparationInFlight = false;
    private boolean multiAgentReviewInFlight = false;
    private int multiAgentReviewRounds = 0;
    /** Tools are removed only for the terminal response forced by the circuit breaker. */
    private boolean finalResponseOnly = false;
    private String finalResponseReason = "";
    private boolean finalResponseForcedByGuard = false;
    /** A stale mutation is discarded and must be regenerated after a fresh read. */
    private boolean awaitingRecoveredMutation = false;

    public interface AgentListener {
        void onMessageAdded(ChatMessage message);
        void onMessageUpdated(ChatMessage message);
        void onMessageRemoved(ChatMessage message, int index);
        void onStatusChanged(String status);
        void onDebug(String message);
        void onProcessingFinished();
        void onToolExecuted(String toolName, boolean isMutation);
        void onError(String error);

        /** Persists context-only compaction without changing the visible chat. */
        default void onCompactionStateChanged(String summary, int compactedUntil) {
        }

        /** Erro final estruturado para a interface, com detalhes técnicos opcionais. */
        default void onUserFacingError(UserFacingError error, @Nullable String requestId) {
            String message = error == null
                    ? "Não foi possível concluir a operação."
                    : error.getTitle() + ": " + error.getMessage();
            onError(message);
        }
    }

    public AgentManager(Context context, String scId, List<ChatMessage> messages, AgentListener listener) {
        this.context = context.getApplicationContext();
        this.scId = scId;
        this.messages = messages;
        this.listener = listener;
        this.aiService = AiProviderService.getInstance();
        this.multiAgentOrchestrator = new MultiAgentOrchestrator(this.aiService);
        
        this.toolManager = new ToolManager();
        VoidToolWrapper.registerAllVoidTools(this.toolManager);

        this.mainHandler = new Handler(Looper.getMainLooper());
        this.streamCoalesceHandler = new Handler(Looper.getMainLooper());
        this.checkpointManager = new ChatCheckpointManager(context);
    }

    public State getCurrentState() {
        return currentState;
    }

    @Nullable
    public String getCurrentOperationId() {
        return currentOperationContext == null ? null : currentOperationContext.getRequestId();
    }

    /** Restores the local context checkpoint for the conversation being opened. */
    public void restoreCompactionState(@Nullable String summary, int compactedUntil) {
        if (currentState != State.IDLE) return;
        historySummary = limitCompactionSummary(summary);
        historyCompactedUntil = Math.max(0, Math.min(compactedUntil, messages.size()));
        lastCompactionMessageCount = historySummary.isEmpty() ? -1 : messages.size();
        compactionFailed = false;
    }

    public boolean hasCheckpoint() {
        return checkpointManager.hasCheckpoint(messages);
    }

    public ChatCheckpointManager.RollbackResult rollbackLastCheckpoint() {
        return checkpointManager.rollbackLatestCheckpoint(scId, messages);
    }

    private void setState(State state) {
        this.currentState = state;
        ChatFlowLogger.event("agent", "state", String.valueOf(state));
        String statusText = "";
        switch (state) {
            case THINKING:
                statusText = getString(R.string.chat_status_thinking);
                break;
            case AWAITING_APPROVAL:
                statusText = getString(R.string.chat_tool_status_waiting_approval);
                break;
            case EXECUTING_TOOL:
                statusText = getString(R.string.chat_tool_status_running);
                break;
            case IDLE:
                statusText = "";
                break;
        }
        updateRunStatus(statusText);
    }

    public void processUserMessage(String userText) {
        processUserMessage(userText, null);
    }

    public void processUserMessage(String userText, String contextPayload) {
        processUserMessage(userText, contextPayload, null);
    }

    public void processUserMessage(String userText, String contextPayload, List<ChatReference> stagingSelections) {
        if (currentState != State.IDLE) {
            ChatFlowLogger.event("agent", "message_ignored", "state=" + currentState);
            return;
        }

        String displayText = userText == null ? "" : userText.trim();
        ChatMessage userMsg = new ChatMessage(displayText, true, System.currentTimeMillis());
        userMsg.setContextPayload(contextPayload);
        userMsg.setStagingSelections(stagingSelections);
        userMsg.setLlmContent(ChatReferenceManager.buildLlmUserContent(displayText, contextPayload));
        messages.add(userMsg);
        listener.onMessageAdded(userMsg);
        ChatFlowLogger.event("agent", "turn_started", "chars=" + displayText.length()
                + ", references=" + (stagingSelections == null ? 0 : stagingSelections.size()));

        int version = ++runVersion;
        initializeAgentExecution(displayText, contextPayload, stagingSelections);
        captureOperationContextForRun();
        beginInteractionTrace(version, displayText, stagingSelections);
        startAgentLoop(version, 0);
    }

    public void continueFromExistingMessage(@Nullable ChatMessage sourceMessage) {
        if (currentState != State.IDLE) {
            return;
        }
        int version = ++runVersion;
        String displayText = sourceMessage == null ? findLatestUserMessage() : sourceMessage.getDisplayContent();
        List<ChatReference> selections = sourceMessage == null ? null : sourceMessage.getStagingSelections();
        String contextPayload = sourceMessage == null ? null : sourceMessage.getContextPayload();
        initializeAgentExecution(displayText, contextPayload, selections);
        captureOperationContextForRun();
        beginInteractionTrace(version, displayText, selections);
        startAgentLoop(version, 0);
    }

    public boolean cancelCurrentRun() {
        if (currentState == State.IDLE) {
            return false;
        }

        runVersion++;
        // A user-cancelled turn must not remain in any specialist's session.
        multiAgentOrchestrator.reset();
        AiRequestHandle requestHandle = currentRequestHandle;
        currentRequestHandle = null;
        if (requestHandle != null) {
            requestHandle.cancel();
        }
        if (currentOperationContext != null) {
            SecureLogger.logCancellation(currentOperationContext.getRequestId(),
                    CancellationReason.USER_REQUESTED);
        }
        toolManager.cancelActiveTool();
        queuedToolCalls.clear();
        // Kill any shell processes spawned by run_command / persistent terminals;
        // previously they kept running (and leaking) after the user cancelled.
        com.saaspaymentsolutions.axion.port.VoidPortToolsService.killAllTerminals();
        streamCoalesceHandler.removeCallbacksAndMessages(null);
        streamUpdateScheduled = false;
        pendingStreamMessage = null;

        Thread toolThread = currentToolThread;
        if (toolThread != null) {
            com.saaspaymentsolutions.axion.port.VoidPortMcpChannel.cancelRequestsForThread(toolThread);
            toolThread.interrupt();
        }
        currentToolThread = null;

        final String interruptedToolName = streamingToolName;
        final String interruptedMcpServer = streamingMcpServerName;
        final boolean hadPendingTool = pendingToolMessage != null;
        final ChatMessage streamingSnapshot = currentStreamingMessage;

        mainHandler.post(() -> {
            if (ChatMessage.hasVisibleText(interruptedToolName)) {
                ChatMessage interrupted = ChatMessage.interruptedStreamingTool(
                        interruptedToolName,
                        interruptedMcpServer,
                        System.currentTimeMillis()
                );
                messages.add(interrupted);
                listener.onMessageAdded(interrupted);
            } else if (pendingToolMessage != null) {
                pendingToolMessage.setToolRunning(false);
                pendingToolMessage.setToolError(true);
                if (currentState == State.AWAITING_APPROVAL) {
                    pendingToolMessage.setToolState("rejected");
                    pendingToolMessage.setRejected(true);
                    pendingToolMessage.setStatus(getString(R.string.chat_tool_status_cancelled));
                    pendingToolMessage.setDisplayContent(getString(R.string.chat_tool_cancelled_message));
                } else {
                    pendingToolMessage.setStatus(getString(R.string.chat_tool_status_cancelled));
                    pendingToolMessage.setDisplayContent(getString(R.string.chat_tool_cancelled_message));
                }
                pendingToolMessage.setToolResult(getString(R.string.chat_tool_cancelled_message));
                listener.onMessageUpdated(pendingToolMessage);
            } else if (streamingSnapshot != null) {
                if (!streamingSnapshot.hasDisplayContent()) {
                    streamingSnapshot.setDisplayContent(getString(R.string.chat_tool_cancelled_message));
                } else if (!streamingSnapshot.getDisplayContent().contains(getString(R.string.chat_cancelled_suffix))) {
                    streamingSnapshot.setDisplayContent(
                            streamingSnapshot.getDisplayContent().trim()
                                    + "\n\n"
                                    + getString(R.string.chat_cancelled_suffix));
                }
                streamingSnapshot.setStatus(getString(R.string.chat_tool_status_cancelled));
                publishAssistantMessage(streamingSnapshot);
            }

            if (!hadPendingTool && !ChatMessage.hasVisibleText(interruptedToolName)) {
                // Void adds a user checkpoint after abort when no tool approval is pending.
            }

            clearStreamingToolState();
            finishProcessing();
        });
        return true;
    }

    /**
     * Invalidates the complete workflow before clearing or switching a thread.
     * A reset must also abort an active run; otherwise its late callback can
     * append an old response to the newly opened conversation.
     */
    public void resetConversationState() {
        boolean wasActive = currentState != State.IDLE;
        runVersion++;
        multiAgentOrchestrator.reset();
        AiRequestHandle requestHandle = currentRequestHandle;
        currentRequestHandle = null;
        if (requestHandle != null) {
            requestHandle.cancel();
        }
        toolManager.cancelActiveTool();
        com.saaspaymentsolutions.axion.port.VoidPortToolsService.killAllTerminals();
        Thread toolThread = currentToolThread;
        currentToolThread = null;
        if (toolThread != null) {
            com.saaspaymentsolutions.axion.port.VoidPortMcpChannel.cancelRequestsForThread(toolThread);
            toolThread.interrupt();
        }
        streamCoalesceHandler.removeCallbacksAndMessages(null);
        mainHandler.removeCallbacksAndMessages(null);
        historySummary = "";
        historyCompactedUntil = 0;
        lastCompactionMessageCount = -1;
        compactionInFlight = false;
        compactionFailed = false;
        agentMemory = null;
        requestPattern = null;
        taskPlan = null;
        toolManager.setMutationsAllowed(true);
        ChatPlanManager.clearExecutionPlan(scId);
        ChatPlanManager.clearModelPlan(scId);
        toolUsageHistory.clear();
        pendingAgentFeedback = "";
        finishValidationFailures = 0;
        outputContinuationCount = 0;
        runGuard.reset();
        finalResponseOnly = false;
        finalResponseReason = "";
        finalResponseForcedByGuard = false;
        awaitingRecoveredMutation = false;
        queuedToolCalls.clear();
        pendingToolLoopStep = -1;
        currentRunCheckpointMessage = null;
        interactionTrace = null;
        currentStreamingMessage = null;
        currentOperationContext = null;
        multiAgentOrchestrator.endOperation();
        pendingStreamMessage = null;
        streamUpdateScheduled = false;
        multiAgentGuidance = "";
        multiAgentEnabledForRun = false;
        multiAgentPrepared = false;
        multiAgentPreparationInFlight = false;
        multiAgentReviewInFlight = false;
        multiAgentReviewRounds = 0;
        clearStreamingToolState();
        setState(State.IDLE);
        if (wasActive) {
            listener.onProcessingFinished();
        }
    }

    /** Releases asynchronous agent resources with the owning chat screen. */
    public void release() {
        resetConversationState();
        multiAgentOrchestrator.shutdown();
    }

    private void startAgentLoop(final int version, final int loopStep) {
        startAgentLoop(version, loopStep, 0);
    }

    private void startAgentLoop(final int version, final int loopStep, final int llmAttempt) {
        if (!isActiveRun(version)) {
            return;
        }

        AgentRunGuard.Decision turnDecision =
                runGuard.beforeModelTurn(loopStep, finalResponseOnly);
        if (!turnDecision.shouldContinue()) {
            if (turnDecision.getOutcome() == AgentRunGuard.Outcome.FORCE_FINAL_RESPONSE) {
                queuedToolCalls.clear();
                activateFinalResponseOnly(turnDecision.getReason());
                finalResponseForcedByGuard = true;
                emitTrace("Circuit breaker de rodadas", turnDecision.getReason());
            } else {
                listener.onError(turnDecision.getReason());
                finishProcessing();
                return;
            }
        }

        // Show feedback immediately. Context compaction and project scanning can
        // take seconds on a large conversation, and waiting for them made a sent
        // message look ignored even though the agent was already working.
        setState(State.THINKING);
        if (currentStreamingMessage == null) {
            currentStreamingMessage = createThinkingMessage();
            clearStreamingToolState();
            // O placeholder entra imediatamente na conversa e se transforma na
            // resposta conforme os deltas chegam. Ele continua excluído do
            // contexto enviado ao provedor por historySnapshot.removeIf abaixo.
            publishAssistantMessage(currentStreamingMessage);
        }

        // Fan out to isolated planner/architect sessions once per user run,
        // then fan their outputs into the manager before the implementer starts.
        // In Auto mode a short request can be reconsidered after the first file
        // inspections reveal a larger scope than the initial text suggested.
        if (!multiAgentPrepared) {
            SharedPreferences prefs = AiChatSettingsHelper.prefs(context);
            String chatMode = AiChatSettingsHelper.getChatMode(prefs);
            if (!"agent".equalsIgnoreCase(chatMode)) {
                multiAgentPrepared = true;
            } else if (!multiAgentEnabledForRun) {
                MultiAgentPolicy.Decision escalation =
                        MultiAgentPolicy.reconsiderAfterInspection(
                                multiAgentModeForRun,
                                requestPattern,
                                loopStep,
                                toolUsageHistory);
                if (escalation.isEnabled()) {
                    multiAgentEnabledForRun = true;
                    multiAgentDecisionReason = escalation.getReason();
                    ChatFlowLogger.event("agent", "multi_agent_escalated",
                            "reason=" + multiAgentDecisionReason
                                    + ", loop=" + loopStep
                                    + ", tools=" + toolUsageHistory.size());
                    emitTrace("Multiagente ativado após inspeção",
                            "reason=" + multiAgentDecisionReason
                                    + ", tools=" + toolUsageHistory.size());
                } else if (!MultiAgentPolicy.MODE_AUTO.equals(multiAgentModeForRun)) {
                    multiAgentPrepared = true;
                }
            }
            if (multiAgentEnabledForRun && !multiAgentPrepared) {
                prepareMultiAgentWorkflow(version, loopStep, llmAttempt);
                return;
            }
        }

        // Compact old history asynchronously before this turn if it grew too large.
        if (!compactionInFlight && !compactionFailed && shouldCompactHistory()) {
            compactHistoryAsync(version, () -> startAgentLoop(version, loopStep, llmAttempt));
            return;
        }

        emitTrace("Agent loop", "step=" + loopStep);

        // Context assembly walks the project file tree — heavy
        // work that must NOT run on the UI thread. Previously it ran synchronously
        // on every loop step, so a turn with several tool calls froze the UI.
        // Build it on a background thread, then resume streaming on the main thread.
        final java.util.List<ChatMessage> historySnapshot = new java.util.ArrayList<>(messages);
        // The placeholder is presentation-only. It must never be sent as an
        // empty assistant turn to the provider.
        historySnapshot.removeIf(ChatMessage::isStreaming);
        final String latestUser = findLatestUserMessage();
        final String agentGuidance = buildAgentGuidance();
        final boolean finalOnlyForRequest = finalResponseOnly;
        final boolean forcedTerminalForRequest = finalResponseForcedByGuard;
        updateRunStatus("Organizando o contexto da conversa…");
        new Thread(() -> {
            final SharedPreferences prefs = AiChatSettingsHelper.prefs(com.saaspaymentsolutions.axion.SketchApplication.getContext());
            // O modelo, o provedor e o modo são congelados no começo da operação.
            // Mudanças feitas pelo usuário durante o processamento só valem no próximo envio.
            final AiOperationContext operationContext = currentOperationContext;
            final String chatMode = operationContext != null && operationContext.getChatMode() != null
                    ? operationContext.getChatMode()
                    : AiChatSettingsHelper.getChatMode(prefs);
            final String providerId = operationContext != null
                    ? operationContext.getProviderId()
                    : prefs.getString(AiChatSettingsHelper.PREF_CURRENT_PROVIDER, "");
            final String modelName = operationContext != null
                    ? operationContext.getModelName()
                    : prefs.getString(AiChatSettingsHelper.PREF_CURRENT_MODEL, "");

            long contextStartedAt = SystemClock.elapsedRealtime();
            final JSONArray tools = finalOnlyForRequest
                    ? new JSONArray()
                    : toolManager.getToolsAsMCP(chatMode);
            if (!finalOnlyForRequest
                    && "agent".equalsIgnoreCase(chatMode)) {
                appendMcpTools(tools, VoidPortMcpChannel.getToolsAsMCP(prefs));
            }
            final int toolSchemaTokens = estimateInputTokens(tools == null ? "" : tools.toString());
            final ContextBuilder.Result contextResult = new ContextBuilder(scId, historySnapshot, toolManager)
                    .setCompactedHistory(historySummary, historyCompactedUntil)
                    .setAgentGuidance(agentGuidance)
                    .setFinalResponseOnly(finalOnlyForRequest)
                    .setIncludeNativeReferences(loopStep == 0)
                    .setProjectDocumentationGuidance(requestPattern != null
                            && requestPattern.requiresProjectExploration())
                    .setAdditionalInputTokens(toolSchemaTokens)
                    .build(latestUser, chatMode, providerId);
            final long contextMs = SystemClock.elapsedRealtime() - contextStartedAt;

            mainHandler.post(() -> {
                if (!isActiveRun(version)) {
                    return;
                }
                if ("agent".equalsIgnoreCase(chatMode)) {
                    // Surface a debug notice for stdio-only MCP servers (Android can't spawn them).
                    emitMcpStdioWarning(prefs);
                }
                emitTrace(
                        "Contexto montado",
                        "build=" + contextMs + "ms, msgs=" + historySnapshot.size()
                                + ", tools=" + (tools == null ? 0 : tools.length())
                                + ", estimatedTokens=" + contextResult.getEstimatedTokens()
                                + ", toolSchemaTokens=" + toolSchemaTokens
                                + ", mode=" + chatMode
                                + ", provider=" + providerId
                );
                final ChatMessage botMsg = currentStreamingMessage;
                if (botMsg == null) {
                    return;
                }

                emitTrace("Chamada LLM iniciada");
                String requestId = currentOperationContext != null 
                        ? currentOperationContext.getRequestId() 
                        : "unknown";
                android.util.Log.d("AgentManager", "=== LLM REQUEST START ===");
                android.util.Log.d("AgentManager", "Request ID: " + requestId);
                android.util.Log.d("AgentManager", "Provider: " + providerId);
                android.util.Log.d("AgentManager", "ChatMode: " + chatMode);
                android.util.Log.d("AgentManager", "Loop step: " + loopStep);
                android.util.Log.d("AgentManager", "LLM attempt: " + llmAttempt);
                android.util.Log.d("AgentManager", "Tools count: " + (tools != null ? tools.length() : 0));
                android.util.Log.d("AgentManager", "History messages: " + historySnapshot.size());
                android.util.Log.d("AgentManager", "Context build time: " + contextMs + "ms");
                android.util.Log.d("AgentManager", "Final response only: " + finalOnlyForRequest);
                ChatFlowLogger.event("llm", "request_started", "requestId=" + requestId
                        + ", provider=" + providerId + ", mode=" + chatMode
                        + ", loop=" + loopStep + ", tools=" + (tools == null ? 0 : tools.length()));
                
                botMsg.setDisplayContent("");
                listener.onMessageUpdated(botMsg);
                updateRunStatus("Enviando para a inteligência artificial…");
                currentRequestHandle = aiService.sendStreamingMessage(
                        contextResult, tools, chatMode, currentOperationContext,
                new AiProviderService.StreamListener() {
                    private final StringBuilder contentAccumulator = new StringBuilder();
                    private final StringBuilder reasoningAccumulator = new StringBuilder();
                    /** Final tool calls emitted this turn: [name, args, id]. */
                    private final java.util.List<String[]> collectedToolCalls = new java.util.ArrayList<>();
                    /** Repeated streaming updates for one tool id replace the prior payload. */
                    private final java.util.Map<String, Integer> collectedToolCallIndexes =
                            new java.util.LinkedHashMap<>();

                    @Override
                    public void onContent(String delta) {
                        if (!isActiveRun(version) || !ChatMessage.hasVisibleText(delta)) {
                            return;
                        }
                        android.util.Log.v("AgentManager", "LLM content delta: " + delta.length() + " chars");
                        contentAccumulator.append(delta);
                        if (VoidPortConvertToLlmMessageService.isProtocolEmptyMessagePrefix(
                                contentAccumulator.toString())) {
                            return;
                        }
                        botMsg.setStatus("");
                        botMsg.setDisplayContent(contentAccumulator.toString());
                        scheduleStreamUpdate(version, botMsg);
                    }

                    @Override
                    public void onReasoning(String delta) {
                        if (!isActiveRun(version) || !ChatMessage.hasVisibleText(delta)) {
                            return;
                        }
                        android.util.Log.v("AgentManager", "LLM reasoning delta: " + delta.length() + " chars");
                        reasoningAccumulator.append(delta);
                        botMsg.setReasoning(reasoningAccumulator.toString());
                        scheduleStreamUpdate(version, botMsg);
                    }

                    @Override
                    public void onToolCall(String name, String arguments, String id) {
                        if (!isActiveRun(version) || !ChatMessage.hasVisibleText(name)) {
                            return;
                        }
                        android.util.Log.d("AgentManager", "LLM tool call: name=" + name + ", id=" + id + ", args_length=" + (arguments != null ? arguments.length() : 0));
                        ChatFlowLogger.event("llm", "tool_call", "name=" + name + ", id=" + id
                                + ", argsChars=" + (arguments == null ? 0 : arguments.length()));
                        // Sanitize the tool name: strip any characters that are not valid
                        // in a tool name. Some free/quantized models (e.g. gpt-oss-20b:free)
                        // leak internal tokens into tool names, producing strings like
                        // "edit_file<|channel|>commentary" that the tool registry cannot
                        // recognise. The regex keeps only ASCII word chars, hyphens and dots.
                        String sanitized = TOOL_NAME_SANITIZER.matcher(name.trim()).replaceAll("");
                        if (sanitized.isEmpty()) {
                            android.util.Log.w("AgentManager", "Tool name sanitized to empty string: " + name);
                            return;
                        }
                        if (!sanitized.equals(name.trim())) {
                            android.util.Log.w("AgentManager", "Tool name sanitized: '" + name + "' -> '" + sanitized + "'");
                        }
                        String safeArgs = ChatMessage.hasVisibleText(arguments) ? arguments : "{}";
                        String safeId = ChatMessage.hasVisibleText(id) ? id : "";
                        collectOrReplaceToolCall(
                                collectedToolCalls,
                                collectedToolCallIndexes,
                                sanitized,
                                safeArgs,
                                safeId);
                        streamingToolName = sanitized;
                        streamingMcpServerName = resolveMcpServerName(sanitized);
                        if (!safeId.isEmpty()) {
                            streamingToolId = safeId;
                        }
                    }

                    @Override
                    public void onDebug(String message) {
                        if (!isActiveRun(version) || !ChatMessage.hasVisibleText(message)) {
                            return;
                        }
                        mainHandler.post(() -> {
                            if (!isActiveRun(version)) {
                                return;
                            }
                            listener.onDebug(message);
                        });
                    }

                    @Override
                    public void onFinalMessage(String fullContent, String fullReasoning,
                                               String finishReason) {
                        if (!isActiveRun(version)) {
                            return;
                        }
                        android.util.Log.d("AgentManager", "=== LLM RESPONSE COMPLETE ===");
                        android.util.Log.d("AgentManager", "Content length: " + (fullContent != null ? fullContent.length() : 0) + " chars");
                        android.util.Log.d("AgentManager", "Reasoning length: " + (fullReasoning != null ? fullReasoning.length() : 0) + " chars");
                        android.util.Log.d("AgentManager", "Tool calls collected: " + collectedToolCalls.size());
                        ChatFlowLogger.event("llm", "response_complete", "contentChars="
                                + (fullContent == null ? 0 : fullContent.length()) + ", reasoningChars="
                                + (fullReasoning == null ? 0 : fullReasoning.length()) + ", toolCalls="
                                + collectedToolCalls.size() + ", finishReason=" + safe(finishReason));
                        
                        mainHandler.post(() -> {
                            if (!isActiveRun(version)) {
                                return;
                            }
                            currentRequestHandle = null;

                            flushStreamUpdate(version);

                            // The detector removes protocol blocks only after the
                            // complete response is available. Treat the final
                            // payload as authoritative so streamed XML/JSON
                            // markers are also cleared when no visible text remains.
                            botMsg.setDisplayContent(sanitizeAssistantPayload(fullContent));
                            botMsg.setReasoning(sanitizeAssistantPayload(fullReasoning));
                            botMsg.setStatus("");

                            boolean hasAssistantPayload = botMsg.hasDisplayContent() || botMsg.hasReasoningContent();
                            if (!collectedToolCalls.isEmpty()) {
                                if (finalOnlyForRequest) {
                                    // The documented terminal phase has no tools. A
                                    // provider that still emits one is stopped here,
                                    // rather than reopening the execution loop.
                                    queuedToolCalls.clear();
                                    clearStreamingToolState();
                                    if (!hasAssistantPayload) {
                                        botMsg.setDisplayContent(buildTerminalFallback());
                                    }
                                    publishAssistantMessage(botMsg);
                                    currentStreamingMessage = null;
                                    emitTrace("Ferramentas bloqueadas na fase terminal",
                                            "count=" + collectedToolCalls.size());
                                    emitTraceSummary("encerrado pela condição de término");
                                    finishProcessing();
                                    return;
                                }
                                if (hasAssistantPayload) {
                                    publishAssistantMessage(botMsg);
                                } else {
                                    removeStreamingPlaceholderIfEmpty(botMsg);
                                }
                                currentStreamingMessage = null;
                                emitTrace("LLM pediu ferramentas", "count=" + collectedToolCalls.size());
                                clearStreamingToolState();
                                queuedToolCalls.clear();
                                // Execute every tool call from this provider response before
                                // asking the model again. The queue remains sequential so
                                // approvals, mutations and read-before-write validation preserve
                                // their deterministic ordering.
                                for (String[] toolCall : collectedToolCalls) {
                                    queuedToolCalls.addLast(toolCall);
                                }
                                ChatFlowLogger.event("tool", "batch_queued",
                                        "count=" + queuedToolCalls.size() + ", loop=" + loopStep);
                                queuedChatMode = chatMode;
                                processNextQueuedToolCall(version, loopStep);
                                return;
                            }

                            clearStreamingToolState();
                            if (!hasAssistantPayload) {
                                removeStreamingPlaceholderIfEmpty(botMsg);
                            } else {
                                publishAssistantMessage(botMsg);
                            }
                            if (isOutputTruncated(finishReason)) {
                                if (outputContinuationCount < MAX_OUTPUT_CONTINUATIONS) {
                                    outputContinuationCount++;
                                    pendingAgentFeedback = "A resposta anterior foi interrompida pelo limite "
                                            + "de saída. Continue exatamente de onde parou, sem repetir o texto "
                                            + "já entregue nem refazer ferramentas concluídas. Termine a tarefa "
                                            + "e entregue uma resposta conclusiva.";
                                    currentStreamingMessage = null;
                                    ChatFlowLogger.event("llm", "output_continuation",
                                            "finishReason=" + safe(finishReason)
                                                    + ", attempt=" + outputContinuationCount);
                                    emitTrace("Resposta truncada",
                                            "continuação=" + outputContinuationCount);
                                    startAgentLoop(version, loopStep + 1);
                                    return;
                                }
                                botMsg.setDisplayContent(safe(botMsg.getDisplayContent())
                                        + "\n\n[A resposta atingiu novamente o limite de saída. "
                                        + "Use Regenerar para retomar deste ponto.]");
                                publishAssistantMessage(botMsg);
                                ChatFlowLogger.event("llm", "output_continuation_exhausted",
                                        "finishReason=" + safe(finishReason));
                                currentStreamingMessage = null;
                                listener.onError("O modelo atingiu repetidamente o limite de saída. "
                                        + "A resposta parcial foi preservada e pode ser retomada.");
                                emitTraceSummary("limite de saída atingido");
                                finishProcessing();
                                return;
                            }
                            if (finalOnlyForRequest && forcedTerminalForRequest) {
                                if (!hasAssistantPayload) {
                                    botMsg.setDisplayContent(buildTerminalFallback());
                                    publishAssistantMessage(botMsg);
                                }
                                currentStreamingMessage = null;
                                emitTraceSummary("encerrado pelo circuit breaker");
                                finishProcessing();
                                return;
                            }
                            if (awaitingRecoveredMutation) {
                                if (finishValidationFailures < MAX_FINISH_REJECTIONS) {
                                    finishValidationFailures++;
                                    pendingAgentFeedback = recoveredMutationFeedback();
                                    removeMessage(botMsg);
                                    currentStreamingMessage = null;
                                    emitTrace("Mutacao obsoleta ainda nao refeita");
                                    startAgentLoop(version, loopStep + 1);
                                    return;
                                }
                                listener.onError("A edição obsoleta não foi regenerada após a leitura atualizada.");
                                finishProcessing();
                                return;
                            }
                            FinishChecker.ValidationResult finishResult = FinishChecker.validate(
                                    agentMemory,
                                    requestPattern,
                                    taskPlan,
                                    toolUsageHistory,
                                    botMsg.getDisplayContent(),
                                    chatMode
                            );
                            if (!hasAssistantPayload
                                    && finishResult.canFinish()
                                    && runGuard.hasSuccessfulToolCall()) {
                                botMsg.setDisplayContent(buildTerminalFallback());
                                publishAssistantMessage(botMsg);
                                hasAssistantPayload = true;
                            }
                            if (!finishResult.canFinish()
                                    && finishValidationFailures < MAX_FINISH_REJECTIONS) {
                                finishValidationFailures++;
                                pendingAgentFeedback = finishResult.getFeedbackPrompt();
                                emitTrace("Finalizacao adiada", finishResult.getReason());
                                removeMessage(botMsg);
                                currentStreamingMessage = null;
                                startAgentLoop(version, loopStep + 1);
                                return;
                            }
                            if (!finishResult.canFinish()) {
                                emitTrace("Finalizacao bloqueada", finishResult.getReason());
                                listener.onError("O agente nao concluiu as etapas obrigatorias: "
                                        + finishResult.getReason());
                            }
                            if (finishResult.canFinish()
                                    && "agent".equalsIgnoreCase(chatMode)
                                    && multiAgentEnabledForRun
                                    && multiAgentPrepared
                                    && multiAgentReviewRounds < MAX_MULTI_AGENT_REVIEW_ROUNDS) {
                                reviewMultiAgentCompletion(
                                        version, loopStep, chatMode, botMsg);
                                return;
                            }
                            emitTraceSummary("resposta final sem ferramenta");
                            finishProcessing();
                        });
                    }

                    @Override
                    public void onOperationStatus(AiOperationStatus status) {
                        if (status == null || !isActiveRun(version)) {
                            return;
                        }
                        mainHandler.post(() -> {
                            if (isActiveRun(version)) {
                                updateRunStatus(status.getUserMessage());
                            }
                        });
                    }

                    @Override
                    public void onUserFacingError(UserFacingError error, String requestId) {
                        if (!isActiveRun(version)) {
                            return;
                        }
                        currentRequestHandle = null;
                        mainHandler.post(() -> {
                            if (!isActiveRun(version)) {
                                return;
                            }
                            String technicalCode = error == null ? null : error.getTechnicalCode();
                            if (AgentEmptyResponsePolicy.shouldRetry(technicalCode, llmAttempt)) {
                                ChatFlowLogger.event("llm", "empty_response_retry",
                                        "requestId=" + requestId + ", loop=" + loopStep
                                                + ", nextAttempt=" + (llmAttempt + 2));
                                pendingAgentFeedback = AgentEmptyResponsePolicy.feedback();
                                removeMessage(botMsg);
                                currentStreamingMessage = null;
                                clearStreamingToolState();
                                emitTrace("Resposta vazia do modelo",
                                        "nova chamada lógica=" + (llmAttempt + 2));
                                setState(State.THINKING);
                                mainHandler.postDelayed(
                                        () -> startAgentLoop(version, loopStep, llmAttempt + 1),
                                        LLM_RETRY_DELAY_MS);
                                return;
                            }
                            if (AgentEmptyResponsePolicy.isEmptyResponse(technicalCode)) {
                                botMsg.setDisplayContent(AgentEmptyResponsePolicy.buildLocalSummary(
                                        messages, botMsg));
                                botMsg.setReasoning("");
                                botMsg.setStatus("");
                                publishAssistantMessage(botMsg);
                                currentStreamingMessage = null;
                                clearStreamingToolState();
                                ChatFlowLogger.event("llm", "empty_response_local_summary",
                                        "requestId=" + requestId + ", loop=" + loopStep
                                                + ", attempt=" + (llmAttempt + 1));
                                emitTrace("Resposta vazia do modelo",
                                        "resumo local publicado após nova tentativa");
                                emitTraceSummary("resumo local após resposta vazia");
                                finishProcessing();
                                return;
                            }
                            setState(State.ERROR);
                            removeStreamingPlaceholderIfEmpty(botMsg);
                            listener.onUserFacingError(error, requestId);
                            emitTrace("Erro LLM", error == null ? "unknown" : error.getTechnicalCode());
                            emitTraceSummary("erro");
                            finishProcessing();
                        });
                    }

                    @Override
                    public void onError(String message, Throwable t) {
                        if (!isActiveRun(version) || "cancelled".equalsIgnoreCase(message)) {
                            return;
                        }
                        android.util.Log.e("AgentManager", "=== LLM ERROR ===");
                        ChatFlowLogger.error("llm", "request_failed requestId=" + requestId, t);
                        android.util.Log.e("AgentManager", "Error message: " + message);
                        android.util.Log.e("AgentManager", "Loop step: " + loopStep);
                        android.util.Log.e("AgentManager", "LLM attempt: " + (llmAttempt + 1) + "/" + MAX_LLM_ATTEMPTS);
                        if (t != null) {
                            android.util.Log.e("AgentManager", "Exception: " + t.getClass().getName() + ": " + t.getMessage(), t);
                        }
                        
                        currentRequestHandle = null;
                        mainHandler.post(() -> {
                            if (!isActiveRun(version)) {
                                return;
                            }
                            if (llmAttempt + 1 < MAX_LLM_ATTEMPTS) {
                                android.util.Log.d("AgentManager", "Retrying LLM request, attempt " + (llmAttempt + 2));
                                removeMessage(botMsg);
                                currentStreamingMessage = null;
                                clearStreamingToolState();
                                emitTrace("Retry LLM", "attempt=" + (llmAttempt + 2));
                                setState(State.THINKING);
                                mainHandler.postDelayed(
                                        () -> startAgentLoop(version, loopStep, llmAttempt + 1),
                                        LLM_RETRY_DELAY_MS
                                );
                                return;
                            }
                            android.util.Log.e("AgentManager", "Max LLM attempts reached, giving up");
                            setState(State.ERROR);
                            removeStreamingPlaceholderIfEmpty(botMsg);
                            emitTrace("Erro LLM", message);
                            listener.onError(message);
                            emitTraceSummary("erro");
                            finishProcessing();
                        });
                    }
                });
            });
        }, "chat-context-builder").start();
    }

    private void reviewMultiAgentCompletion(int version, int loopStep,
                                            String chatMode, ChatMessage botMsg) {
        if (multiAgentReviewInFlight) {
            return;
        }
        multiAgentReviewInFlight = true;
        multiAgentReviewRounds++;
        setState(State.THINKING);
        emitTrace("Revisor multiagente",
                "round=" + multiAgentReviewRounds + "/" + MAX_MULTI_AGENT_REVIEW_ROUNDS);
        String objective = agentMemory == null
                ? findLatestUserMessage()
                : agentMemory.getOriginalUserMessage();
        String evidence = buildMultiAgentExecutionEvidence(chatMode);
        multiAgentOrchestrator.reviewAsync(
                objective,
                multiAgentGuidance,
                evidence,
                botMsg.getDisplayContent(),
                decision -> mainHandler.post(() -> {
                    if (!isActiveRun(version)) {
                        return;
                    }
                    multiAgentReviewInFlight = false;
                    if (decision.isApproved()) {
                        emitTrace("Revisao aprovada",
                                decision.isDegraded() ? "degraded: " + decision.getReason()
                                        : decision.getReason());
                        emitTraceSummary("multiagente concluido");
                        finishProcessing();
                        return;
                    }

                    emitTrace("Revisao rejeitada", decision.getReason());
                    if (multiAgentReviewRounds < MAX_MULTI_AGENT_REVIEW_ROUNDS) {
                        finalResponseOnly = false;
                        finalResponseReason = "";
                        finalResponseForcedByGuard = false;
                        pendingAgentFeedback = "[MULTI-AGENT REVIEW FEEDBACK]\n"
                                + decision.getFeedback()
                                + "\nResolve the concrete gap, verify with tools, and produce a corrected final response.";
                        removeMessage(botMsg);
                        currentStreamingMessage = null;
                        startAgentLoop(version, loopStep + 1);
                        return;
                    }

                    String warning = decision.getReason();
                    if (!ChatMessage.hasVisibleText(warning)) {
                        warning = decision.getFeedback();
                    }
                    if (ChatMessage.hasVisibleText(warning)) {
                        botMsg.setDisplayContent(botMsg.getDisplayContent()
                                + "\n\n[Multi-agent review warning]\n" + warning);
                        publishAssistantMessage(botMsg);
                    }
                    emitTraceSummary("multiagente encerrou no limite de revisao");
                    finishProcessing();
                }));
    }

    private String buildMultiAgentExecutionEvidence(String chatMode) {
        StringBuilder evidence = new StringBuilder();
        evidence.append("Chat mode: ").append(chatMode)
                .append("\nTool executions: ").append(toolUsageHistory.size());
        for (ToolSequenceValidator.ToolUsage usage : toolUsageHistory) {
            evidence.append("\n- ")
                    .append(usage.getToolName())
                    .append(": ")
                    .append(usage.wasSuccessful() ? "success" : "failed")
                    .append(" args=")
                    .append(truncateForTranscript(safe(usage.getArgs()), 300));
        }
        if (taskPlan != null) {
            evidence.append("\n\n").append(taskPlan.buildPlanSummary());
        }
        return truncateForTranscript(evidence.toString(), 6_000);
    }

    private ChatMessage createThinkingMessage() {
        // Placeholder visual que será atualizado no mesmo item durante o stream.
        ChatMessage botMsg = new ChatMessage("", false,
                System.currentTimeMillis());
        botMsg.setStatus("");
        botMsg.setStreaming(true);
        return botMsg;
    }

    /**
     * Mantém o andamento da operação no mesmo item de resposta da conversa.
     * O status continua disponível para os painéis auxiliares, mas não cria um
     * segundo indicador visual fora da lista.
     */
    private void updateRunStatus(@Nullable String status) {
        String safeStatus = status == null ? "" : status.trim();
        listener.onStatusChanged(safeStatus);

        ChatMessage streamingMessage = currentStreamingMessage;
        if (streamingMessage == null
                || !streamingMessage.isStreaming()
                || streamingMessage.hasDisplayContent()
                || streamingMessage.hasReasoningContent()) {
            return;
        }
        streamingMessage.setStatus(safeStatus);
        publishAssistantMessage(streamingMessage);
    }

    private void prepareMultiAgentWorkflow(int version, int loopStep, int llmAttempt) {
        if (multiAgentPreparationInFlight) {
            return;
        }
        multiAgentPreparationInFlight = true;
        String objective = agentMemory == null
                ? findLatestUserMessage()
                : agentMemory.getOriginalUserMessage();
        String sharedContext = buildMultiAgentSharedContext();
        updateRunStatus(getString(R.string.chat_status_preparing_multi_agent));
        ChatFlowLogger.event("agent", "multi_agent_started",
                "reason=" + multiAgentDecisionReason + ", planner+architect->manager");
        emitTrace("Multiagente iniciado",
                "reason=" + multiAgentDecisionReason + ", planner+architect -> manager");
        multiAgentOrchestrator.prepareAsync(objective, sharedContext, preparation ->
                mainHandler.post(() -> {
                    if (!isActiveRun(version)) {
                        return;
                    }
                    multiAgentPreparationInFlight = false;
                    multiAgentPrepared = true;
                    multiAgentGuidance = preparation.toGuidance();
                    updateRunStatus(getString(R.string.chat_status_multi_agent_ready));
                    ChatFlowLogger.event("agent", "multi_agent_ready",
                            "degraded=" + preparation.isDegraded()
                                    + ", guidanceChars=" + multiAgentGuidance.length());
                    emitTrace("Multiagente preparado",
                            "plannerChars=" + preparation.getPlannerOutputChars()
                                    + ", architectChars=" + preparation.getArchitectOutputChars()
                                    + ", managerChars=" + preparation.getManagerOutputChars()
                                    + ", degraded=" + preparation.isDegraded());
                    startAgentLoop(version, loopStep, llmAttempt);
                }));
    }

    private String buildMultiAgentSharedContext() {
        StringBuilder contextBuilder = new StringBuilder();
        File root = ProjectPathResolver.getPrimaryReadableRoot(scId);
        contextBuilder.append("Workspace root: ")
                .append(root == null ? "" : root.getAbsolutePath());
        if (agentMemory != null) {
            contextBuilder.append("\n\n").append(agentMemory.buildContextInjection());
        }
        if (taskPlan != null) {
            contextBuilder.append("\n\n").append(taskPlan.buildPlanSummary());
        }
        if (ChatMessage.hasVisibleText(historySummary)) {
            contextBuilder.append("\n\n[Prior conversation summary]\n")
                    .append(truncateForTranscript(historySummary, 4_000));
        }
        return truncateForTranscript(contextBuilder.toString(), 12_000);
    }

    /**
     * True only when the effective history approaches the selected model's
     * actual context budget. The former fixed 32k-character trigger was about
     * 8k tokens for every model, including models with much larger windows, so
     * a verbose tool result could repeatedly start a summarization mid-run.
     */
    private boolean shouldCompactHistory() {
        // A successful compaction does not remove visible messages. Without a
        // progress marker, the continuation sees the exact same message count
        // and can immediately compact again until the Android heap is exhausted.
        if (messages.size() <= lastCompactionMessageCount) {
            return false;
        }
        int end = messages.size() - COMPACT_KEEP_TAIL;
        if (end - historyCompactedUntil < 8) {
            return false;
        }
        long effectiveChars = 0;
        int recentToolResults = 0;
        for (int i = messages.size() - 1; i >= historyCompactedUntil; i--) {
            ChatMessage message = messages.get(i);
            if (message != null && message.isTool() && !safe(message.getToolResult()).isEmpty()) {
                recentToolResults++;
            }
        }
        for (int i = historyCompactedUntil; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if (m == null) {
                continue;
            }
            if (m.isTool()) {
                boolean keepVerbatim = recentToolResults <= 2 && !safe(m.getToolResult()).isEmpty();
                effectiveChars += Math.min(safe(m.getToolArgs()).length(), 4_000);
                effectiveChars += keepVerbatim
                        ? Math.min(safe(m.getToolResult()).length(), 16_000)
                        : Math.min(safe(m.getToolResult()).length(), 1_200);
                if (!safe(m.getToolResult()).isEmpty()) {
                    recentToolResults--;
                }
            } else {
                effectiveChars += safe(m.getDisplayContent()).length()
                        + safe(m.getReasoning()).length();
            }
            if (effectiveChars / COMPACTION_CHARS_PER_TOKEN > historyCompactionTriggerTokens()) {
                return true;
            }
        }
        return false;
    }

    /** Mirrors ContextBuilder's provider-aware history allocation. */
    private int historyCompactionTriggerTokens() {
        SharedPreferences prefs = VoidPortSettings.prefs(context);
        String providerId = currentOperationContext != null
                ? currentOperationContext.getProviderId()
                : prefs.getString(AiChatSettingsHelper.PREF_CURRENT_PROVIDER, "");
        String modelName = currentOperationContext != null
                ? currentOperationContext.getModelName()
                : prefs.getString(VoidPortSettings.PREF_CURRENT_MODEL, "");
        VoidPortModelCapabilities.Capabilities capabilities =
                VoidPortModelCapabilities.getModelCapabilities(providerId, modelName);
        boolean reasoningEnabled = capabilities.reasoningCapabilities.supportsReasoning
                && !capabilities.reasoningCapabilities.canTurnOffReasoning;
        int reservedOutput = Math.max(1024,
                capabilities.effectiveReservedOutputTokenSpace(reasoningEnabled));
        int totalBudget = Math.max(DEFAULT_TOTAL_CONTEXT_TOKENS,
                Math.min(MAX_CONTEXT_TOKENS, capabilities.contextWindow - reservedOutput));
        int systemBudget = Math.max(DEFAULT_SYSTEM_CONTEXT_TOKENS,
                Math.min(16_000, totalBudget / 4));
        int compileErrorBudget = Math.max(DEFAULT_COMPILE_ERROR_TOKENS,
                Math.min(2_000, systemBudget / 6));
        int historyBudget = Math.max(DEFAULT_HISTORY_CONTEXT_TOKENS,
                totalBudget - systemBudget - compileErrorBudget);
        return Math.max(DEFAULT_HISTORY_CONTEXT_TOKENS,
                Math.min(MAX_COMPACTION_TRIGGER_TOKENS,
                        historyBudget * COMPACTION_TRIGGER_PERCENT / 100));
    }

    /**
     * Summarizes messages[historyCompactedUntil, size-KEEP_TAIL) on a background
     * thread and swaps them for a summary in the LLM context (UI untouched).
     * On any failure compaction is disabled for this session and the loop
     * continues with plain truncation as before.
     */
    private void compactHistoryAsync(int version, Runnable continuation) {
        compactionInFlight = true;
        lastCompactionMessageCount = messages.size();
        updateRunStatus(getString(R.string.chat_status_compacting_context));
        final int requestedEnd = Math.max(historyCompactedUntil, messages.size() - COMPACT_KEEP_TAIL);
        int compactedEnd = historyCompactedUntil;
        final StringBuilder transcript = new StringBuilder();
        if (!historySummary.isEmpty()) {
            transcript.append("[Resumo acumulado até aqui]\n").append(historySummary).append("\n\n");
        }
        for (int i = historyCompactedUntil; i < requestedEnd; i++) {
            ChatMessage m = messages.get(i);
            if (m == null || m.isCheckpoint()) {
                compactedEnd = i + 1;
                continue;
            }
            int transcriptLengthBeforeMessage = transcript.length();
            if (m.isUser()) {
                transcript.append("USUÁRIO: ")
                        .append(truncateForTranscript(safe(m.getDisplayContent()), 6000)).append('\n');
            } else if (m.isTool()) {
                transcript.append("FERRAMENTA ").append(safe(m.getToolName()))
                        .append(" args=").append(truncateForTranscript(safe(m.getToolArgs()), 400))
                        .append(" resultado=").append(truncateForTranscript(safe(m.getToolResult()), 1200))
                        .append('\n');
            } else {
                transcript.append("ASSISTENTE: ")
                        .append(truncateForTranscript(safe(m.getDisplayContent()), 6000)).append('\n');
            }
            if (transcript.length() > COMPACT_TRANSCRIPT_MAX_CHARS) {
                // Keep this whole message in the live window. Summarizing only a
                // prefix and then excluding the full message would lose context.
                transcript.setLength(transcriptLengthBeforeMessage);
                break;
            }
            compactedEnd = i + 1;
        }

        // Never mark messages as compacted unless they were actually provided to
        // the summarizer. This is especially important for long tool results.
        final int end = compactedEnd;
        if (end <= historyCompactedUntil) {
            compactionInFlight = false;
            emitTrace("Compactação sem progresso",
                    "mantendo janela atual e evitando nova tentativa para o mesmo histórico");
            // Post instead of calling recursively so the current stack and its
            // transcript can be released before the agent loop continues.
            mainHandler.post(continuation);
            return;
        }

        emitTrace("Compactação iniciada", "msgs=" + (end - historyCompactedUntil)
                + ", transcriptChars=" + transcript.length());

        new Thread(() -> {
            String summary = null;
            try {
                String systemPrompt = "Você é um sumarizador de contexto de um agente de programação. "
                        + "Resuma a conversa a seguir preservando: objetivo do usuário, decisões tomadas, "
                        + "arquivos criados/alterados (com caminhos), erros encontrados e estado atual da tarefa. "
                        + "Seja denso e factual; máximo ~600 palavras.";
                String userPrompt = truncateForTranscript(
                        transcript.toString(), COMPACT_TRANSCRIPT_MAX_CHARS);
                AiOperationContext frozenContext = currentOperationContext;
                summary = frozenContext == null
                        ? aiService.sendTextMessage(systemPrompt, userPrompt)
                        : aiService.sendTextMessage(
                                frozenContext.getProviderId(),
                                frozenContext.getModelName(),
                                systemPrompt,
                                userPrompt);
            } catch (Exception ignored) {
            }
            final String result = summary;
            mainHandler.post(() -> {
                compactionInFlight = false;
                if (result != null && !result.trim().isEmpty()) {
                    historySummary = limitCompactionSummary(result);
                    historyCompactedUntil = end;
                    listener.onCompactionStateChanged(historySummary, historyCompactedUntil);
                    emitTrace("Compactação concluída", "summaryChars=" + historySummary.length()
                            + ", compactadoAté=" + historyCompactedUntil);
                } else {
                    // Don't retry every turn if the summarizer is failing.
                    compactionFailed = true;
                    emitTrace("Compactação falhou", "seguindo com truncamento padrão");
                }
                if (isActiveRun(version)) {
                    continuation.run();
                }
            });
        }, "chat-history-compactor").start();
    }

    private static String limitCompactionSummary(@Nullable String summary) {
        String value = summary == null ? "" : summary.trim();
        if (value.length() <= COMPACT_SUMMARY_MAX_CHARS) {
            return value;
        }
        return value.substring(0, COMPACT_SUMMARY_MAX_CHARS)
                + "\n[Resumo truncado para proteger a memória do dispositivo]";
    }

    /**
     * Adds a new file snapshot to an existing turn checkpoint message.
     * Keeps the EARLIEST snapshot when the same file is touched twice in the
     * turn, so rollback restores the pre-turn content.
     */
    private boolean mergeSnapshotIntoCheckpoint(ChatMessage checkpointMsg,
                                                ChatCheckpointManager.CheckpointEntry entry) {
        try {
            JSONObject snapshots = new JSONObject(safe(checkpointMsg.getCheckpointSnapshotsJson()));
            if (snapshots.has(entry.filePath)) {
                return true; // earliest snapshot already stored
            }
            JSONObject snapshot = new JSONObject();
            snapshot.put("toolId", entry.toolId);
            snapshot.put("toolName", entry.toolName);
            snapshot.put("filePath", entry.filePath);
            snapshot.put("beforeContent", entry.beforeContent);
            snapshot.put("existedBefore", entry.existedBefore);
            snapshots.put(entry.filePath, snapshot);
            checkpointMsg.setCheckpointSnapshotsJson(snapshots.toString());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String truncateForTranscript(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "…";
    }

    /** Runs the next queued tool call, or advances the agent loop when the queue drains. */
    private void processNextQueuedToolCall(int version, int loopStep) {
        if (!isActiveRun(version)) {
            return;
        }
        String[] next = queuedToolCalls.pollFirst();
        if (next == null) {
            if (!awaitingRecoveredMutation && runGuard.hasSuccessfulToolCall()) {
                FinishChecker.ValidationResult completion = FinishChecker.validate(
                        agentMemory,
                        requestPattern,
                        taskPlan,
                        toolUsageHistory,
                        "",
                        queuedChatMode
                );
                boolean deterministicPlanComplete = completion.canFinish()
                        && taskPlan != null
                        && taskPlan.isComplete();
                AgentRunGuard.Decision completionDecision =
                        runGuard.afterCompletionCandidate(deterministicPlanComplete);
                if (!completionDecision.shouldContinue()) {
                    pendingAgentFeedback = "";
                    activateFinalResponseOnly(completionDecision.getReason());
                    finalResponseForcedByGuard = true;
                    emitTrace("Conclusão confirmada", completionDecision.getReason());
                } else if (deterministicPlanComplete) {
                    pendingAgentFeedback =
                            "[COMPLETION CANDIDATE]\n"
                                    + "The deterministic requirements appear satisfied. "
                                    + "If the user's actual objective is complete, return the final answer now "
                                    + "without tools. If concrete work is still missing, call only the tools "
                                    + "needed to complete it.";
                    emitTrace("Conclusão candidata",
                            "tools=" + runGuard.getToolCalls());
                }
            }
            startAgentLoop(version, loopStep + 1);
            return;
        }
        handleToolCall(next[0], next[1], next[2], version, loopStep, queuedChatMode);
    }

    private void handleToolCall(String name, String args, String id, int version, int loopStep, String chatMode) {
        ToolArgumentsValidator.Result rawValidation = ToolArgumentsValidator.validate(args, null);
        if (!rawValidation.isValid()) {
            addUnavailableToolMessage(name, args, id, chatMode, version, loopStep,
                    "Erro: argumentos inválidos para '" + name + "'. " + rawValidation.getError());
            return;
        }
        args = rawValidation.getArguments().toString();

        AgentRunGuard.Decision guardDecision =
                runGuard.beforeToolCall(name, args, finalResponseOnly);
        if (!guardDecision.shouldContinue()) {
            queuedToolCalls.clear();
            emitTrace("Circuit breaker de ferramentas", guardDecision.getReason());
            if (guardDecision.getOutcome() == AgentRunGuard.Outcome.FORCE_FINAL_RESPONSE) {
                activateFinalResponseOnly(guardDecision.getReason());
                finalResponseForcedByGuard = true;
                startAgentLoop(version, loopStep + 1);
            } else {
                listener.onError(guardDecision.getReason());
                finishProcessing();
            }
            return;
        }

        ToolSequenceValidator.ValidationResult sequenceResult = ToolSequenceValidator.validate(
                name,
                args == null ? "{}" : args,
                toolUsageHistory,
                null
        );
        if (!sequenceResult.isValid()) {
            String predecessorArgs = ToolSequenceValidator.buildPredecessorArgs(
                    sequenceResult, args == null ? "{}" : args);
            if (predecessorArgs != null
                    && toolManager.hasToolForChatMode("read_file", chatMode)) {
                // The stale patch is discarded. Only a fresh read is queued;
                // the model must generate a new patch from that returned content.
                queuedToolCalls.clear();
                queuedToolCalls.addFirst(new String[]{"read_file", predecessorArgs, ""});
                awaitingRecoveredMutation = isMutationTool(name);
                pendingAgentFeedback = recoveredMutationFeedback();
                emitTrace("Recuperação automática de edição obsoleta",
                        "predecessor=read_file");
                processNextQueuedToolCall(version, loopStep);
                return;
            }
            String guidance = sequenceResult.getSuggestion();
            addUnavailableToolMessage(name, args, id, chatMode, version, loopStep,
                    sequenceResult.getErrorMessage()
                            + (guidance == null || guidance.isEmpty() ? "" : " " + guidance));
            return;
        }

        if ("get_file".equals(name)) {
            addUnavailableToolMessage(name, args, id, chatMode, version, loopStep,
                    "Erro: ferramenta 'get_file' não existe. Use 'read_file' para ler arquivos.");
            return;
        }

        Tool tool = toolManager.getTool(name);
        boolean mcpTool = tool == null && isMcpToolAvailable(name, chatMode);
        if ((!mcpTool && tool == null) || (!mcpTool && !toolManager.hasToolForChatMode(name, chatMode))) {
            addUnavailableToolMessage(name, args, id, chatMode, version, loopStep, null);
            return;
        }

        JSONObject parameterSchema = mcpTool ? findMcpToolSchema(name) : tool.getParameters();
        ToolArgumentsValidator.Result schemaValidation =
                ToolArgumentsValidator.validate(args, parameterSchema);
        if (!schemaValidation.isValid()) {
            addUnavailableToolMessage(name, args, id, chatMode, version, loopStep,
                    "Erro: argumentos inválidos para '" + name + "'. " + schemaValidation.getError());
            return;
        }
        args = schemaValidation.getArguments().toString();

        boolean needsApproval = mcpTool
                ? !VoidPortSettings.isAutoApprovalEnabled(
                        VoidPortSettings.prefs(context),
                        VoidPortSettings.APPROVAL_MCP_TOOLS)
                : VoidPortSettings.requiresApproval(context, tool);

        ChatMessage toolMsg = new ChatMessage(name, args, System.currentTimeMillis(), id);
        toolMsg.setToolState(needsApproval ? "tool_request" : "running_now");
        toolMsg.setRequiresApproval(needsApproval);
        toolMsg.setStatus(needsApproval
                ? getString(R.string.chat_tool_status_waiting_approval)
                : getString(R.string.chat_tool_status_running));
        toolMsg.setDisplayContent(needsApproval
                ? getString(R.string.chat_tool_approval_message_named, name)
                : getString(R.string.chat_tool_running_message));
        toolMsg.setMcpServerName(mcpTool ? resolveMcpServerName(name) : null);
        pendingToolMessage = toolMsg;
        pendingToolLoopStep = loopStep;

        final Tool previewTool = mcpTool ? null : tool;
        mainHandler.post(() -> {
            if (!isActiveRun(version)) {
                return;
            }

            messages.add(toolMsg);
            listener.onMessageAdded(toolMsg);
            emitTrace("Ferramenta na fila", "name=" + name + ", approval=" + needsApproval);

            if (needsApproval) {
                setState(State.AWAITING_APPROVAL);
                // Build the diff preview OFF the UI thread (the LCS diff is heavy)
                // and refresh the message when ready — the user is reviewing anyway.
                if (previewTool != null && previewTool.isDestructive()) {
                    new Thread(() -> {
                        prepareToolPreview(toolMsg, previewTool);
                        mainHandler.post(() -> {
                            if (isActiveRun(version)) {
                                listener.onMessageUpdated(toolMsg);
                            }
                        });
                    }, "chat-tool-preview").start();
                }
            } else {
                executeTool(toolMsg, version, loopStep);
            }
        });
    }

    private void addUnavailableToolMessage(String name, String args, String id, String chatMode, int version, int loopStep, String customError) {
        String safeName = name == null ? "" : name.trim();
        String mode = chatMode == null || chatMode.trim().isEmpty() ? "agent" : chatMode.trim();
        String availableTools = toolManager.getToolNamesForChatMode(mode);
        String result = (customError != null) ? customError : "Erro: ferramenta '" + safeName + "' nao esta disponivel no modo '" + mode + "'.";
        if (!availableTools.isEmpty()) {
            result += " Ferramentas disponiveis: " + availableTools + ".";
        }

        ChatMessage toolMsg = new ChatMessage(safeName, args, System.currentTimeMillis(), id);
        toolMsg.setToolRunning(false);
        toolMsg.setToolError(true);
        toolMsg.setToolState("error");
        toolMsg.setStatus(getString(R.string.chat_tool_status_error));
        toolMsg.setDisplayContent(getString(R.string.chat_tool_error_message));
        toolMsg.setToolResult(result);
        pendingToolMessage = null;
        if (taskPlan != null) {
            taskPlan.recordToolFailure(safeName);
            syncExecutionPlan();
        }

        mainHandler.post(() -> {
            if (!isActiveRun(version)) {
                return;
            }
            messages.add(toolMsg);
            listener.onMessageAdded(toolMsg);
            consecutiveToolFailures++;
            if (consecutiveToolFailures >= MAX_CONSECUTIVE_TOOL_FAILURES) {
                listener.onError(consecutiveToolFailureMessage());
                finishProcessing();
                return;
            }
            processNextQueuedToolCall(version, loopStep);
        });
    }

    public void approveTool() {
        if (currentState != State.AWAITING_APPROVAL || pendingToolMessage == null) {
            return;
        }

        pendingToolMessage.setApproved(true);
        pendingToolMessage.setToolState("running_now");
        pendingToolMessage.setStatus(getString(R.string.chat_tool_status_approved));
        pendingToolMessage.setDisplayContent(getString(R.string.chat_tool_approved_message));
        listener.onMessageUpdated(pendingToolMessage);
        executeTool(pendingToolMessage, runVersion, pendingToolLoopStep);
    }

    public void rejectTool() {
        if (currentState != State.AWAITING_APPROVAL || pendingToolMessage == null) {
            return;
        }

        pendingToolMessage.setRejected(true);
        pendingToolMessage.setToolRunning(false);
        pendingToolMessage.setToolError(true);
        pendingToolMessage.setToolState("rejected");
        pendingToolMessage.setStatus(getString(R.string.chat_tool_status_rejected));
        pendingToolMessage.setDisplayContent(getString(R.string.chat_tool_rejected_message));
        pendingToolMessage.setToolResult(getString(R.string.chat_tool_rejected_message));
        if (taskPlan != null) {
            taskPlan.recordToolFailure(pendingToolMessage.getToolName());
            syncExecutionPlan();
        }
        listener.onMessageUpdated(pendingToolMessage);
        finishProcessing();
    }

    private void executeTool(final ChatMessage toolMsg, final int version, final int loopStep) {
        if (!isActiveRun(version)) {
            return;
        }

        if (taskPlan != null) {
            taskPlan.markToolStarted(toolMsg.getToolName());
            syncExecutionPlan();
        }
        setState(State.EXECUTING_TOOL);
        updateRunStatus(userStatusForTool(toolMsg.getToolName()));
        toolMsg.setStatus(getString(R.string.chat_tool_status_running));
        toolMsg.setDisplayContent(getString(R.string.chat_tool_running_message));
        listener.onMessageUpdated(toolMsg);

        android.util.Log.d("AgentManager", "=== TOOL EXECUTION START ===");
        android.util.Log.d("AgentManager", "Tool name: " + toolMsg.getToolName());
        android.util.Log.d("AgentManager", "Tool ID: " + toolMsg.getToolId());
        android.util.Log.d("AgentManager", "Loop step: " + loopStep);
        ChatFlowLogger.event("tool", "execution_started", "name=" + toolMsg.getToolName()
                + ", id=" + toolMsg.getToolId() + ", loop=" + loopStep);
        
        emitTrace("Ferramenta iniciada", "name=" + toolMsg.getToolName());
        final long toolStartedAt = SystemClock.elapsedRealtime();
        currentToolThread = new Thread(() -> {
            ChatCheckpointManager.CheckpointEntry checkpointEntry = createCheckpointIfNeeded(toolMsg);
            if (checkpointEntry != null) {
                mainHandler.post(() -> {
                    if (!isActiveRun(version)) {
                        return;
                    }
                    // Turn-level (transactional) checkpoint: all files touched during
                    // the same run share ONE checkpoint message, so a rollback
                    // restores the whole turn instead of a single file.
                    if (currentRunCheckpointMessage != null
                            && mergeSnapshotIntoCheckpoint(currentRunCheckpointMessage, checkpointEntry)) {
                        listener.onMessageUpdated(currentRunCheckpointMessage);
                        return;
                    }
                    ChatMessage checkpointMsg = checkpointEntry.toChatMessage();
                    currentRunCheckpointMessage = checkpointMsg;
                    messages.add(checkpointMsg);
                    listener.onMessageAdded(checkpointMsg);
                });
            }

            com.saaspaymentsolutions.axion.ToolExecResult execResult = executeToolCall(toolMsg);
            final String result = execResult.output;
            boolean isError = !execResult.ok;
            final long toolDurationMs = SystemClock.elapsedRealtime() - toolStartedAt;

            android.util.Log.d("AgentManager", "=== TOOL EXECUTION COMPLETE ===");
            android.util.Log.d("AgentManager", "Tool name: " + toolMsg.getToolName());
            android.util.Log.d("AgentManager", "Duration: " + toolDurationMs + "ms");
            android.util.Log.d("AgentManager", "Success: " + !isError);
            android.util.Log.d("AgentManager", "Result length: " + (result != null ? result.length() : 0) + " chars");
            if (isError && result != null) {
                android.util.Log.e("AgentManager", "Tool error: " + (result.length() > 500 ? result.substring(0, 500) + "..." : result));
            }
            ChatFlowLogger.event("tool", "execution_complete", "name=" + toolMsg.getToolName()
                    + ", ok=" + !isError + ", durationMs=" + toolDurationMs
                    + ", resultChars=" + (result == null ? 0 : result.length()));

            mainHandler.post(() -> {
                currentToolThread = null;
                if (!isActiveRun(version)) {
                    return;
                }
                emitTrace(
                        "Ferramenta concluída",
                        "name=" + toolMsg.getToolName()
                                + ", ok=" + !isError
                                + ", duration=" + toolDurationMs + "ms"
                                + ", resultChars=" + (result == null ? 0 : result.length())
                );

                toolMsg.setToolRunning(false);
                toolMsg.setToolError(isError);
                toolMsg.setToolState(isError ? "error" : "success");
                toolMsg.setToolResult(result);
                toolMsg.setStatus(getString(isError
                        ? R.string.chat_tool_status_error
                        : R.string.chat_tool_status_done));
                toolMsg.setDisplayContent(getString(isError
                        ? R.string.chat_tool_error_message
                        : R.string.chat_tool_done_message));
                toolMsg.setExpanded(isError);
                listener.onMessageUpdated(toolMsg);

                toolUsageHistory.add(ToolSequenceValidator.createUsage(
                        toolMsg.getToolName(),
                        toolMsg.getToolArgs() == null ? "{}" : toolMsg.getToolArgs(),
                        !isError
                ));
                runGuard.onToolCompleted(
                        toolMsg.getToolName(),
                        toolMsg.getToolArgs() == null ? "{}" : toolMsg.getToolArgs(),
                        result == null ? "" : result,
                        !isError);

                if (!isError) {
                    consecutiveToolFailures = 0;
                    String toolName = toolMsg.getToolName();
                    boolean isMutation = "rewrite_file".equals(toolName) ||
                            "edit_file".equals(toolName) ||
                            "create_file_or_folder".equals(toolName) ||
                            "delete_file_or_folder".equals(toolName);
                    listener.onToolExecuted(toolName, isMutation);
                    if (isMutation) {
                        ContextBuilder.invalidateWorkspaceCache(scId);
                        awaitingRecoveredMutation = false;
                    }
                    if (taskPlan != null) {
                        taskPlan.recordToolUsage(toolName);
                        if (agentMemory != null) {
                            agentMemory.setProgress(taskPlan.getCompletedSteps(), taskPlan.getTotalSteps());
                        }
                        syncExecutionPlan();
                    }
                } else {
                    if (taskPlan != null) {
                        taskPlan.recordToolFailure(toolMsg.getToolName());
                        syncExecutionPlan();
                    }
                    consecutiveToolFailures++;
                    RetryManager.RetryDecision retryDecision = RetryManager.shouldRetry(
                            toolMsg.getToolName(),
                            toolMsg.getToolArgs() == null ? "{}" : toolMsg.getToolArgs(),
                            result == null ? "" : result,
                            consecutiveToolFailures,
                            toolUsageHistory
                    );
                    if (retryDecision.shouldRetry()
                            && retryDecision.getAlternativeTool() != null
                            && retryDecision.getAlternativeArgs() != null
                            && toolManager.hasToolForChatMode(
                                    retryDecision.getAlternativeTool(), queuedChatMode)) {
                        queuedToolCalls.addFirst(new String[]{
                                retryDecision.getAlternativeTool(),
                                retryDecision.getAlternativeArgs(),
                                ""
                        });
                        emitTrace("Retry alternativo", retryDecision.getReason());
                    }
                    if (consecutiveToolFailures >= MAX_CONSECUTIVE_TOOL_FAILURES) {
                        // Stop burning tokens: repeated tool failures indicate the
                        // model is stuck; surface the problem instead of looping.
                        emitTrace("Loop de falhas", "falhas consecutivas=" + consecutiveToolFailures);
                        listener.onError(consecutiveToolFailureMessage());
                        clearPendingToolState();
                        finishProcessing();
                        return;
                    }
                }

                clearPendingToolState();
                processNextQueuedToolCall(version, loopStep);
            });
        }, "chat-tool-worker");
        currentToolThread.start();
    }

    private com.saaspaymentsolutions.axion.ToolExecResult executeToolCall(ChatMessage toolMsg) {
        String toolName = toolMsg.getToolName();
        if (toolName != null && toolName.startsWith("mcp_")) {
            if (false) {
            }
            return com.saaspaymentsolutions.axion.ToolExecResult.fromLegacyString(VoidPortMcpChannel.callTool(
                    VoidPortSettings.prefs(context),
                    toolName,
                    parseToolArgs(toolMsg.getToolArgs())
            ));
        }
        return toolManager.executeTool(scId, toolName, toolMsg.getToolArgs());
    }

    private void appendMcpTools(JSONArray target, JSONArray mcpTools) {
        if (target == null || mcpTools == null || mcpTools.length() == 0) {
            return;
        }
        for (int i = 0; i < mcpTools.length(); i++) {
            JSONObject tool = mcpTools.optJSONObject(i);
            if (tool != null) {
                target.put(tool);
            }
        }
    }

    private boolean isMcpToolAvailable(String name, String chatMode) {
        if (!"agent".equalsIgnoreCase(chatMode) || name == null || !name.startsWith("mcp_")) {
            return false;
        }
        JSONArray mcpTools = VoidPortMcpChannel.getToolsAsMCP(VoidPortSettings.prefs(context));
        for (int i = 0; i < mcpTools.length(); i++) {
            JSONObject tool = mcpTools.optJSONObject(i);
            JSONObject function = tool == null ? null : tool.optJSONObject("function");
            if (function != null && name.equals(function.optString("name", ""))) {
                return true;
            }
        }
        return false;
    }

    private void prepareToolPreview(ChatMessage toolMsg, Tool tool) {
        if (toolMsg == null || tool == null || !tool.isDestructive()) {
            return;
        }

        try {
            JSONObject args = parseToolArgs(toolMsg.getToolArgs());
            String filePath = normalizeToolPath(toolPathArg(args));
            String content = args.optString("new_content", "");
            if (content.isEmpty()) {
                content = args.optString("search_replace_blocks", "");
            }
            if (content.isEmpty()) {
                content = args.optString("content", "");
            }
            if (content.isEmpty()) {
                content = args.optString("code_edit", "");
            }
            if (filePath.isEmpty() || content.isEmpty()) {
                return;
            }

            boolean existedBefore = new File(ProjectPathResolver.resolveForRead(scId, filePath).getFile().getAbsolutePath()).exists();
            String beforeContent = existedBefore ? safe(new String(java.nio.file.Files.readAllBytes(new File(ProjectPathResolver.resolveForRead(scId, filePath).getFile().getAbsolutePath()).toPath()))) : "";
            String preview = buildVoidPreview(filePath, beforeContent, content, existedBefore);
            toolMsg.setToolResult(preview);
        } catch (Exception ignored) {
        }
    }

    private String buildVoidPreview(String filePath, String beforeContent, String generatedContent, boolean existedBefore) {
        String cleanedContent = extractRegularCode(generatedContent);
        List<ExtractCodeFromResult.ExtractedSearchReplaceBlock> blocks =
                ExtractCodeFromResult.extractSearchReplaceBlocks(cleanedContent);
        if (!blocks.isEmpty()) {
            return buildSearchReplacePreview(filePath, cleanedContent, blocks);
        }
        return buildWholeFilePreview(filePath, beforeContent, cleanedContent, existedBefore);
    }

    private String buildSearchReplacePreview(String filePath, String content,
                                             List<ExtractCodeFromResult.ExtractedSearchReplaceBlock> blocks) {
        String language = LanguageHelpers.detectLanguage(filePath, content);
        StringBuilder builder = new StringBuilder();
        builder.append("VOID SEARCH/REPLACE PREVIEW\n");
        builder.append("File: ").append(filePath).append("\n");
        builder.append("Language: ").append(language).append("\n");
        builder.append("Actions: ")
                .append(ActionIds.VOID_ACCEPT_DIFF_ACTION_ID)
                .append(" / ")
                .append(ActionIds.VOID_REJECT_DIFF_ACTION_ID)
                .append("\n\n");

        int printed = 0;
        for (int i = 0; i < blocks.size() && printed < MAX_PREVIEW_LINES; i++) {
            ExtractCodeFromResult.ExtractedSearchReplaceBlock block = blocks.get(i);
            builder.append("Block ").append(i + 1).append(" - ").append(block.state).append("\n");
            builder.append(PromptConstants.TRIPLE_TICK.get(0)).append(language).append("\n");
            builder.append(PromptConstants.ORIGINAL).append("\n");
            printed = appendPreviewLines(builder, block.orig, printed);
            builder.append(PromptConstants.DIVIDER).append("\n");
            printed = appendPreviewLines(builder, block.fin, printed);
            builder.append(PromptConstants.FINAL).append("\n");
            builder.append(PromptConstants.TRIPLE_TICK.get(1)).append("\n\n");
        }
        if (printed >= MAX_PREVIEW_LINES) {
            builder.append("... preview truncated ...\n");
        }
        return builder.toString().trim();
    }

    private String buildWholeFilePreview(String filePath, String beforeContent, String afterContent, boolean existedBefore) {
        String safeBefore = safe(beforeContent);
        String safeAfter = safe(afterContent);
        String language = LanguageHelpers.detectLanguage(filePath, safeAfter);
        List<VoidPortDiffService.ComputedDiff> diffs =
                VoidPortDiffService.findDiffs(safeBefore, safeAfter);

        StringBuilder builder = new StringBuilder();
        builder.append("VOID DIFF PREVIEW\n");
        builder.append("File: ").append(filePath).append("\n");
        builder.append("Mode: ").append(existedBefore ? "update" : "create").append("\n");
        builder.append("Language: ").append(language).append("\n");
        builder.append("Actions: ")
                .append(ActionIds.VOID_ACCEPT_FILE_ACTION_ID)
                .append(" / ")
                .append(ActionIds.VOID_REJECT_FILE_ACTION_ID)
                .append("\n\n");

        if (diffs.isEmpty()) {
            builder.append("No content changes detected.");
            return builder.toString();
        }

        int printed = 0;
        for (int i = 0; i < diffs.size() && printed < MAX_PREVIEW_LINES; i++) {
            VoidPortDiffService.ComputedDiff diff = diffs.get(i);
            builder.append("Change ")
                    .append(i + 1)
                    .append(" - ")
                    .append(diff.type)
                    .append(" original lines ")
                    .append(formatLineRange(diff.originalStartLine, diff.originalEndLine))
                    .append(" -> new lines ")
                    .append(formatLineRange(diff.startLine, diff.endLine))
                    .append("\n");
            builder.append(PromptConstants.TRIPLE_TICK.get(0)).append(language).append("\n");
            builder.append(PromptConstants.ORIGINAL).append("\n");
            printed = appendPreviewLines(builder, diff.originalCode, printed);
            builder.append(PromptConstants.DIVIDER).append("\n");
            printed = appendPreviewLines(builder, diff.code, printed);
            builder.append(PromptConstants.FINAL).append("\n");
            builder.append(PromptConstants.TRIPLE_TICK.get(1)).append("\n\n");
        }

        if (printed >= MAX_PREVIEW_LINES) {
            builder.append("... preview truncated ...\n");
        }
        return builder.toString().trim();
    }

    private String formatLineRange(int startLine, int endLine) {
        if (startLine <= 0 || endLine < startLine) {
            return "none";
        }
        if (startLine == endLine) {
            return String.valueOf(startLine);
        }
        return startLine + "-" + endLine;
    }

    private String extractRegularCode(String content) {
        ExtractCodeFromResult.Extraction extraction =
                ExtractCodeFromResult.extractCodeFromRegular(content, content == null ? 0 : content.length());
        return extraction.fullText;
    }

    private int appendPreviewLines(StringBuilder builder, String content, int printed) {
        return appendLineRange(builder, splitLines(safe(content)), 0, splitLines(safe(content)).length, printed);
    }

    private int appendLineRange(StringBuilder builder, String[] lines, int start, int end, int printed) {
        for (int i = start; i < end && printed < MAX_PREVIEW_LINES; i++) {
            builder.append(lines[i]).append("\n");
            printed++;
        }
        return printed;
    }

    private String[] splitLines(String content) {
        if (content == null || content.isEmpty()) {
            return new String[0];
        }
        return content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    }

    private ChatCheckpointManager.CheckpointEntry createCheckpointIfNeeded(ChatMessage toolMsg) {
        Tool tool = toolManager.getTool(toolMsg.getToolName());
        if (tool == null || (!tool.isDestructive() && !tool.isFileMutation())) {
            return null;
        }

        try {
            JSONObject args = parseToolArgs(toolMsg.getToolArgs());
            String filePath = normalizeToolPath(toolPathArg(args));
            if (filePath.isEmpty()) {
                return null;
            }

            boolean existedBefore = new File(ProjectPathResolver.resolveForRead(scId, filePath).getFile().getAbsolutePath()).exists();
            String beforeContent = existedBefore ? safe(new String(java.nio.file.Files.readAllBytes(new File(ProjectPathResolver.resolveForRead(scId, filePath).getFile().getAbsolutePath()).toPath()))) : "";
            return checkpointManager.createCheckpoint(
                    scId,
                    toolMsg.getToolId() != null ? toolMsg.getToolId() : "",
                    safe(toolMsg.getToolName()),
                    filePath,
                    beforeContent,
                    existedBefore
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private void clearPendingToolState() {
        pendingToolMessage = null;
        pendingToolLoopStep = -1;
    }

    private void scheduleStreamUpdate(int version, ChatMessage message) {
        if (!isActiveRun(version) || message == null) {
            return;
        }
        pendingStreamMessage = message;
        if (streamUpdateScheduled) {
            return;
        }
        streamUpdateScheduled = true;
        streamCoalesceHandler.postDelayed(() -> {
            streamUpdateScheduled = false;
            flushStreamUpdate(version);
        }, STREAM_COALESCE_MS);
    }

    private void flushStreamUpdate(int version) {
        if (!isActiveRun(version)) {
            return;
        }
        ChatMessage message = pendingStreamMessage;
        pendingStreamMessage = null;
        if (message != null) {
            publishAssistantMessage(message);
        }
    }

    /**
     * Adds a deferred assistant message on its first real payload, then updates
     * it normally. This prevents an empty, blinking "Pensando" row in the list.
     */
    private void publishAssistantMessage(ChatMessage message) {
        if (message == null) {
            return;
        }
        if (!messages.contains(message)) {
            messages.add(message);
            listener.onMessageAdded(message);
        } else {
            listener.onMessageUpdated(message);
        }
    }

    private void clearStreamingToolState() {
        streamingToolName = "";
        streamingToolId = "";
        streamingMcpServerName = null;
    }

    @Nullable
    private String resolveMcpServerName(String toolName) {
        if (toolName == null || !toolName.startsWith("mcp_")) {
            return null;
        }
        SharedPreferences prefs = VoidPortSettings.prefs(context);
        return VoidPortMcpChannel.resolveServerNameForTool(prefs, toolName);
    }

    /**
     * Emits a one-time debug notice per run when stdio-only MCP servers are
     * configured. Android cannot spawn desktop stdio processes, so those servers
     * are silently skipped by {@link VoidPortMcpChannel}; surfacing the warning
     * here prevents confusing "tool not found" errors for the user.
     */
    private boolean mcpStdioWarningEmitted = false;

    private void emitMcpStdioWarning(SharedPreferences prefs) {
        if (mcpStdioWarningEmitted) {
            return;
        }
        java.util.List<VoidPortMcpChannel.ServerStatus> statuses = VoidPortMcpChannel.readServerStatuses(prefs);
        boolean hasStdio = false;
        for (VoidPortMcpChannel.ServerStatus s : statuses) {
            if ("stdio-config-only".equals(s.status)) {
                hasStdio = true;
                break;
            }
        }
        if (hasStdio) {
            mcpStdioWarningEmitted = true;
            listener.onDebug("[MCP] Aviso: um ou mais servidores MCP usam stdio/command e não podem ser iniciados pelo Android. " +
                    "Exponha-os como endpoint HTTP em mcpServers para usá-los aqui.");
        }
    }

    private void finishProcessing() {
        streamCoalesceHandler.removeCallbacksAndMessages(null);
        streamUpdateScheduled = false;
        pendingStreamMessage = null;
        queuedToolCalls.clear();
        clearPendingToolState();
        clearStreamingToolState();
        currentStreamingMessage = null;
        currentToolThread = null;
        currentOperationContext = null;
        multiAgentOrchestrator.endOperation();
        setState(State.IDLE);
        if (interactionTrace != null) {
            emitTraceSummary("processamento concluído");
        }
        listener.onProcessingFinished();
    }

    private void captureOperationContextForRun() {
        SharedPreferences prefs = AiChatSettingsHelper.prefs(context);
        AiChatSettingsHelper.ensureValidCurrentSelection(prefs);
        String providerId = prefs.getString(AiChatSettingsHelper.PREF_CURRENT_PROVIDER, "").trim();
        String modelName = prefs.getString(AiChatSettingsHelper.PREF_CURRENT_MODEL, "").trim();
        String chatMode = AiChatSettingsHelper.getChatMode(prefs);
        if (providerId.isEmpty() || modelName.isEmpty()) {
            currentOperationContext = null;
            return;
        }
        currentOperationContext = AiOperationContext.builder()
                .providerId(providerId)
                .modelName(modelName)
                .chatMode(chatMode)
                .build();
        multiAgentOrchestrator.beginOperation(providerId, modelName);
        SecureLogger.d("AgentManager", "Operação iniciada: "
                + currentOperationContext.getRequestId() + " " + providerId + "/" + modelName);
    }

    private String userStatusForTool(@Nullable String toolName) {
        String name = toolName == null ? "" : toolName.trim();
        if ("read_file".equals(name) || "search_files".equals(name)
                || "list_files".equals(name)) {
            return "Analisando os arquivos do projeto…";
        }
        if ("rewrite_file".equals(name) || "edit_file".equals(name)
                || "create_file_or_folder".equals(name)
                || "delete_file_or_folder".equals(name)) {
            return "Aplicando alterações no projeto…";
        }
        if ("run_command".equals(name) || "compile_project".equals(name)
                || "build_project".equals(name)) {
            return "Verificando se existem erros…";
        }
        return name.isEmpty() ? "Executando ferramenta…" : "Executando " + name + "…";
    }

    private void initializeAgentExecution(String userText, String contextPayload,
                                          List<ChatReference> stagingSelections) {
        multiAgentOrchestrator.cancelActiveWorkflow();
        multiAgentGuidance = "";
        multiAgentModeForRun = MultiAgentPolicy.MODE_AUTO;
        multiAgentDecisionReason = "not_evaluated";
        multiAgentPrepared = false;
        multiAgentPreparationInFlight = false;
        multiAgentReviewInFlight = false;
        multiAgentReviewRounds = 0;
        String safeText = userText == null ? "" : userText.trim();
        if (safeText.isEmpty()) {
            agentMemory = null;
            requestPattern = null;
            taskPlan = null;
            ChatPlanManager.clearExecutionPlan(scId);
            multiAgentEnabledForRun = false;
            multiAgentDecisionReason = "empty_request";
            return;
        }
        requestPattern = PatternMatcher.analyze(safeText, contextPayload, stagingSelections);
        // Permission is a host invariant. A clear read-only request removes
        // mutation tools from the catalog and blocks them again at execution.
        toolManager.setMutationsAllowed(requestPattern.allowsMutations());

        // Freeze the user's multi-agent preference together with the operation.
        // The policy also recognizes broad short fixes and explicit activation;
        // Auto may still escalate later if project inspection reveals complexity.
        SharedPreferences prefs = AiChatSettingsHelper.prefs(context);
        String chatMode = AiChatSettingsHelper.getChatMode(prefs);
        multiAgentModeForRun = AiChatSettingsHelper.getMultiAgentMode(prefs);
        MultiAgentPolicy.Decision multiAgentDecision = "agent".equalsIgnoreCase(chatMode)
                ? MultiAgentPolicy.decide(multiAgentModeForRun, requestPattern, safeText)
                : MultiAgentPolicy.decide(MultiAgentPolicy.MODE_OFF, requestPattern, safeText);
        multiAgentEnabledForRun = multiAgentDecision.isEnabled();
        multiAgentDecisionReason = "agent".equalsIgnoreCase(chatMode)
                ? multiAgentDecision.getReason()
                : "chat_mode_" + chatMode;
        ChatFlowLogger.event("agent", "multi_agent_decision",
                "enabled=" + multiAgentEnabledForRun
                        + ", mode=" + multiAgentModeForRun
                        + ", reason=" + multiAgentDecisionReason
                        + ", requestType=" + requestPattern.getPrimaryType());

        AgentMemory.Builder memoryBuilder = AgentMemory.builder(safeText)
                .originalSelections(stagingSelections)
                .addKeyFiles(requestPattern.getExtractedFilePaths());
        agentMemory = memoryBuilder.build();

        // The deterministic TaskPlanner is no longer authoritative. Complex
        // tasks may expose update_plan to let the model maintain the visible plan.
        taskPlan = null;
        ChatPlanManager.clearExecutionPlan(scId);
        ChatPlanManager.clearModelPlan(scId);
        pendingAgentFeedback = "";
        toolUsageHistory.clear();
        finishValidationFailures = 0;
        outputContinuationCount = 0;
    }

    private String buildAgentGuidance() {
        StringBuilder guidance = new StringBuilder();
        if (finalResponseOnly) {
            String terminalInstruction = "TERMINATION CONDITION:\n"
                    + (ChatMessage.hasVisibleText(finalResponseReason)
                            ? finalResponseReason
                            : "The required work is complete.")
                    + "\nThe tool phase is over. Do not emit, request, or describe another tool call. "
                    + "Return the final answer now using the completed results.";
            guidance.append(terminalInstruction);
            if (agentMemory != null) {
                guidance.append("\n\n").append(agentMemory.buildContextInjection());
            }
            if (taskPlan != null) {
                guidance.append("\n\n").append(taskPlan.buildPlanSummary());
            }
            // Keep the terminal requirement both at the beginning (survives
            // prompt trimming) and at the end (wins over stale history).
            guidance.append("\n\nFINAL RESPONSE REQUIRED: tools are disabled; answer now without a tool call.");
            pendingAgentFeedback = "";
            return guidance.toString();
        }

        boolean completionCandidate = pendingAgentFeedback.startsWith("[COMPLETION CANDIDATE]");
        // The preparation briefing describes how to begin the task. Once the
        // host has confirmed completion it is stale and must not compete with
        // the instruction to return the final answer.
        if (!completionCandidate && ChatMessage.hasVisibleText(multiAgentGuidance)) {
            guidance.append(multiAgentGuidance);
        }
        if (agentMemory != null) {
            if (guidance.length() > 0) {
                guidance.append("\n\n");
            }
            guidance.append(agentMemory.buildContextInjection());
        }
        if (taskPlan != null) {
            if (guidance.length() > 0) {
                guidance.append("\n\n");
            }
            guidance.append(taskPlan.buildPlanSummary());
        }
        // Put volatile feedback last so it remains the most recent instruction.
        if (ChatMessage.hasVisibleText(pendingAgentFeedback)) {
            if (guidance.length() > 0) {
                guidance.append("\n\n");
            }
            guidance.append("FINISH VALIDATION FEEDBACK:\n").append(pendingAgentFeedback);
            pendingAgentFeedback = "";
        }
        return guidance.toString();
    }

    private void beginInteractionTrace(int version, String userText, List<ChatReference> stagingSelections) {
        interactionTrace = new ChatInteractionTrace(version);
        mcpStdioWarningEmitted = false;
        runGuard.reset();
        finalResponseOnly = false;
        finalResponseReason = "";
        finalResponseForcedByGuard = false;
        awaitingRecoveredMutation = false;
        consecutiveToolFailures = 0;
        queuedToolCalls.clear();
        toolUsageHistory.clear();
        pendingAgentFeedback = "";
        finishValidationFailures = 0;
        outputContinuationCount = 0;
        currentRunCheckpointMessage = null;
        int textChars = userText == null ? 0 : userText.trim().length();
        int selectionCount = stagingSelections == null ? 0 : stagingSelections.size();
        int imageCount = stagingSelections == null ? 0 : ChatReferenceManager.getImageReferences(stagingSelections).size();
        emitTrace("Interação iniciada", "textChars=" + textChars + ", selections=" + selectionCount + ", images=" + imageCount);
        emitTrace("Decisão multiagente",
                "enabled=" + multiAgentEnabledForRun
                        + ", mode=" + multiAgentModeForRun
                        + ", reason=" + multiAgentDecisionReason
                        + (requestPattern == null ? "" : ", type=" + requestPattern.getPrimaryType()));
    }

    private void emitTrace(String event) {
        emitTrace(event, null);
    }

    private void emitTrace(String event, String detail) {
        if (interactionTrace == null) {
            return;
        }
        String line = interactionTrace.mark(event, detail);
        if (ChatMessage.hasVisibleText(line)) {
            listener.onDebug(line);
        }
    }

    private void emitTraceSummary(String label) {
        if (interactionTrace == null) {
            return;
        }
        String line = interactionTrace.summary(label);
        interactionTrace = null;
        if (ChatMessage.hasVisibleText(line)) {
            listener.onDebug(line);
        }
    }

    private void removeStreamingPlaceholderIfEmpty(ChatMessage botMsg) {
        if (botMsg == null) {
            return;
        }
        if (botMsg.hasDisplayContent() || botMsg.hasReasoningContent()) {
            return;
        }
        removeMessage(botMsg);
    }

    private void removeMessage(ChatMessage message) {
        int index = messages.indexOf(message);
        if (index < 0) {
            return;
        }
        messages.remove(index);
        listener.onMessageRemoved(message, index);
    }

    /** Enters a terminal model turn with the tool catalog removed. */
    private void activateFinalResponseOnly(String reason) {
        finalResponseOnly = true;
        finalResponseReason = reason == null ? "" : reason.trim();
        queuedToolCalls.clear();
    }

    private String sanitizeAssistantPayload(String payload) {
        String safePayload = payload == null ? "" : payload.trim();
        return VoidPortConvertToLlmMessageService.isProtocolEmptyMessage(safePayload)
                ? ""
                : safePayload;
    }

    private String buildTerminalFallback() {
        if (ChatMessage.hasVisibleText(finalResponseReason)
                && !finalResponseReason.contains("etapas obrigatórias")) {
            return "A execução foi encerrada de forma segura para evitar um loop. "
                    + "O melhor resultado verificado foi preservado. Motivo: "
                    + finalResponseReason;
        }
        return "Tarefa concluída e verificada. As ações e os resultados estão registrados acima.";
    }

    private String recoveredMutationFeedback() {
        return "A mutação anterior foi descartada porque usava conteúdo obsoleto. "
                + "O arquivo foi lido novamente. Gere um novo edit_file ou rewrite_file "
                + "a partir desse conteúdo atual; não reutilize o patch anterior.";
    }

    private static boolean isMutationTool(String toolName) {
        return "edit_file".equals(toolName)
                || "rewrite_file".equals(toolName)
                || "create_file_or_folder".equals(toolName)
                || "delete_file_or_folder".equals(toolName);
    }

    static boolean isOutputTruncated(String finishReason) {
        String normalized = finishReason == null
                ? ""
                : finishReason.trim().toLowerCase(java.util.Locale.ROOT);
        return "length".equals(normalized)
                || "max_tokens".equals(normalized)
                || "max_output_tokens".equals(normalized);
    }

    static int estimateInputTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, (text.length() + COMPACTION_CHARS_PER_TOKEN - 1)
                / COMPACTION_CHARS_PER_TOKEN);
    }

    private boolean isActiveRun(int version) {
        return version == runVersion;
    }

    private String findLatestUserMessage() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message != null && message.isUser()) {
                return message.getLlmContent();
            }
        }
        return "";
    }

    private JSONObject findMcpToolSchema(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        JSONArray mcpTools = VoidPortMcpChannel.getToolsAsMCP(VoidPortSettings.prefs(context));
        for (int i = 0; i < mcpTools.length(); i++) {
            JSONObject tool = mcpTools.optJSONObject(i);
            JSONObject function = tool == null ? null : tool.optJSONObject("function");
            if (function != null && name.equals(function.optString("name", ""))) {
                return function.optJSONObject("parameters");
            }
        }
        return null;
    }

    private void syncExecutionPlan() {
        if (taskPlan == null) {
            ChatPlanManager.clearExecutionPlan(scId);
        } else {
            ChatPlanManager.setExecutionPlan(scId, taskPlan);
        }
    }

    private JSONObject parseToolArgs(String toolArgs) {
        try {
            if (toolArgs == null || toolArgs.trim().isEmpty() || "null".equals(toolArgs.trim())) {
                return new JSONObject();
            }
            return new JSONObject(toolArgs);
        } catch (Exception invalidArguments) {
            android.util.Log.e("AgentManager", "Invalid tool arguments reached execution", invalidArguments);
            return new JSONObject();
        }
    }

    static void collectOrReplaceToolCall(java.util.List<String[]> calls,
                                         java.util.Map<String, Integer> indexesById,
                                         String name,
                                         String args,
                                         String id) {
        String[] value = new String[]{name, args, id};
        if (id == null || id.trim().isEmpty()) {
            calls.add(value);
            return;
        }
        Integer existingIndex = indexesById.get(id);
        if (existingIndex != null && existingIndex >= 0 && existingIndex < calls.size()) {
            calls.set(existingIndex, value);
            return;
        }
        indexesById.put(id, calls.size());
        calls.add(value);
    }

    private String consecutiveToolFailureMessage() {
        return "Erro: limite de falhas consecutivas de ferramentas atingido ("
                + consecutiveToolFailures + ").";
    }

    private String toolPathArg(JSONObject args) {
        if (args == null) {
            return "";
        }
        String uri = args.optString("uri", "");
        if (!uri.trim().isEmpty()) {
            return uri;
        }
        return args.optString("file_path", "");
    }

    private String normalizeToolPath(String input) {
        if (input == null) {
            return "";
        }
        String normalized = input.trim().replace("\\", "/");
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String getString(int resId) {
        return context.getString(resId);
    }

    private String getString(int resId, Object... args) {
        return context.getString(resId, args);
    }
}
