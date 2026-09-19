package com.saaspaymentsolutions.axion.agentsdk;

import com.saaspaymentsolutions.axion.toolcalling.ToolCall;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The single canonical tool execution path (Codex {@code tools/router.rs}
 * parity). A {@link AgentToolRouter.StructuredToolCall} enters the router;
 * the router resolves it through: tool lookup → {@link PermissionLayer} →
 * {@link SandboxAwareTool} pre-check → {@link ToolExecutor} →
 * {@link AgentToolResult} — and nothing else in the runtime executes tools.
 *
 * <pre>
 * StructuredToolCall → AgentToolRouter → lookup → permissions → sandbox
 *     → executor → AgentToolResult → next LLM turn
 * </pre>
 *
 * <p>Deduplication is by {@code callId} (item 12 of the tool-call contract),
 * never by tool name: two {@code apply_patch} calls with different ids are
 * two legitimate, separate executions; the same id delivered twice (provider
 * retry, duplicated event) executes exactly once.</p>
 */
public final class AgentToolRouter {

    /** The input model of the router: a provider-structured tool call. */
    public static final class StructuredToolCall {
        private final String callId;
        private final String toolName;
        private final String arguments;

        public StructuredToolCall(String callId, String toolName, String arguments) {
            this.callId = callId == null || callId.trim().isEmpty()
                    ? "call_" + java.util.UUID.randomUUID()
                    : callId.trim();
            this.toolName = toolName == null ? "" : toolName.trim();
            this.arguments = arguments == null || arguments.trim().isEmpty()
                    ? "{}"
                    : arguments;
        }

        public String callId() {
            return callId;
        }

        public String toolName() {
            return toolName;
        }

        public String arguments() {
            return arguments;
        }
    }

    /** Result of routing one structured call. */
    public static final class RoutedCall {
        private final StructuredToolCall call;
        private final AgentToolResult result;
        private final boolean wasDeduplicated;
        private final boolean handedOff;

        RoutedCall(StructuredToolCall call, AgentToolResult result,
                   boolean wasDeduplicated, boolean handedOff) {
            this.call = call;
            this.result = result;
            this.wasDeduplicated = wasDeduplicated;
            this.handedOff = handedOff;
        }

        public StructuredToolCall call() {
            return call;
        }

        public AgentToolResult result() {
            return result;
        }

        /** True when this call was skipped because its callId already ran. */
        public boolean wasDeduplicated() {
            return wasDeduplicated;
        }

        public boolean handedOff() {
            return handedOff;
        }
    }

    /** Host hooks so the runtime emits events/appends history in order. */
    public interface LoopHooks {
        /** Emitted BEFORE execution, right after the permission check passes. */
        void onToolCallStarted(StructuredToolCall call, AgentTool tool);

        /** Emitted AFTER the result exists (execution or sandbox pre-block). */
        void onToolCallCompleted(StructuredToolCall call, AgentTool tool, AgentToolResult result);
    }

    private final PermissionLayer permissions;
    private final EventStream events;
    /** Every processed call id → its outcome (dedupe by callId, not name). */
    private final Map<String, RoutedCall> processedByCallId = new LinkedHashMap<>();
    private int executedCount;

    public AgentToolRouter(PermissionLayer permissions, EventStream events) {
        this.permissions = permissions;
        this.events = events;
    }

    /** Non-deduplicated calls routed so far (any outcome). */
    public int executedCount() {
        return executedCount;
    }

    /** Distinct call ids processed (dedupe table size). */
    public int distinctCallIdsSeen() {
        return processedByCallId.size();
    }

    /** Clears the dedupe table (fresh run on the same router instance). */
    public void resetForNewRun() {
        processedByCallId.clear();
        executedCount = 0;
    }

    /**
     * Routes {@code call} on the run's toolset. This is the ONLY way a tool
     * executes inside the {@link AgentRuntime} v2.
     *
     * <p>Item 22 of the migration: a toolset with DUPLICATE tool names is an
     * invalid configuration and is rejected outright — exactly one
     * {@code apply_patch} (the canonical {@link ApplyPatchTool}) may exist,
     * and every name must resolve to a single implementation.</p>
     */
    public RoutedCall route(List<AgentTool> tools,
                            StructuredToolCall call,
                            String scId,
                            RunContext context,
                            LoopHooks loopHooks) {
        // ---- 0) Toolset sanity: no duplicate names ----------------------
        // O(n) on a tiny toolset; catches two apply_patch's (or any other
        // collision) deterministically instead of silently picking one.
        if (tools != null) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (AgentTool tool : tools) {
                if (tool != null && !seen.add(tool.name())) {
                    return new RoutedCall(call, AgentToolResult.error(
                            "Error: invalid toolset configuration — duplicate tool name '"
                                    + tool.name() + "'."), false, false);
                }
            }
        }

        // ---- 1) Deduplication by callId (never by name) -------------------
        RoutedCall previous = processedByCallId.get(call.callId());
        if (previous != null) {
            return new RoutedCall(call, previous.result(), true, false);
        }

        AgentTool tool = findTool(tools, call.toolName());
        AgentToolResult result;
        boolean handedOff = false;

        if (tool == null) {
            // Unknown tool: controlled error to the model, never a crash.
            // The completion IS published: the UI must see the call end.
            result = AgentToolResult.error("Error: unknown tool '" + call.toolName() + "'.");
            publishCompleted(scId, null, call, result, context, loopHooks);
        } else if (permissions != null && permissions.check(tool, toLegacyCall(call), scId)
                == PermissionLayer.Outcome.BLOCKED) {
            result = AgentToolResult.error("Error: execution of '"
                    + call.toolName() + "' was denied by policy or user.");
            publishCompleted(scId, tool, call, result, context, loopHooks);
        } else {
            // The runtime lends its stream to stream-less patch tools so
            // FileChanged events flow on THIS run's channel.
            if (tool instanceof ApplyPatchTool && ((ApplyPatchTool) tool).hasNoStream()) {
                tool = ((ApplyPatchTool) tool).boundTo(events, scId);
            }
            handedOff = tool instanceof HandoffTool;
            if (loopHooks != null) {
                loopHooks.onToolCallStarted(call, tool);
            }
            result = executeThroughSandbox(tool, call, scId, context, loopHooks);
        }

        // The id is processed regardless of outcome: a duplicated event of a
        // FAILED call must not re-execute either.
        processedByCallId.put(call.callId(), new RoutedCall(call, result, false, handedOff));
        executedCount++;
        return new RoutedCall(call, result, false, handedOff);
    }

    /** Sandbox pre-check, then the executor (the only execution site). */
    private AgentToolResult executeThroughSandbox(AgentTool tool, StructuredToolCall call,
                                                  String scId, RunContext context,
                                                  LoopHooks loopHooks) {
        // Sandbox pre-check for tools that enforce their own rules (e.g.
        // shell deny-list). A non-null result blocks execution.
        if (tool instanceof SandboxAwareTool) {
            AgentToolResult pre = ((SandboxAwareTool) tool).preExecute(toLegacyCall(call));
            if (pre != null) {
                publishCompleted(scId, tool, call, pre, context, loopHooks);
                return pre;
            }
        }

        ToolExecutor executor = new ToolExecutor();
        JSONObject args = executor.parseArguments(call.arguments());
        AgentToolResult result = args == null
                ? AgentToolResult.error("Error: invalid JSON arguments for '"
                + call.toolName() + "'.")
                : executor.run(tool, context, args);
        publishCompleted(scId, tool, call, result, context, loopHooks);
        return result;
    }

    /** Emits ToolCallCompleted, FileChanged and the task-memory record. */
    private void publishCompleted(String scId, AgentTool tool, StructuredToolCall call,
                                  AgentToolResult result, RunContext context,
                                  LoopHooks loopHooks) {
        if (result == null) {
            if (loopHooks != null) {
                loopHooks.onToolCallCompleted(call, tool, null);
            }
            return;
        }
        JSONObject args = safeParse(call.arguments());
        String path = args.optString("uri", args.optString("path", ""));
        if (!result.isError() && context != null && context.taskMemory() != null
                && !path.isEmpty() && tool != null) {
            context.taskMemory().recordFile(path);
            context.taskMemory().recordAppliedChange(tool.name() + ": " + path);
        }
        emitFileChangedIfAny(scId, tool, args, result);
        if (loopHooks != null) {
            loopHooks.onToolCallCompleted(call, tool, result);
        }
    }

    /**
     * Emits {@code FileChanged} for registry file tools (best-effort
     * attribution) — apply_patch announces its own committed mutations per
     * file; the heuristic cannot attribute them (it looks at uri/path args).
     */
    private void emitFileChangedIfAny(String scId, AgentTool tool, JSONObject args,
                                      AgentToolResult result) {
        if (result.isError() || args == null || !tool.isFileMutation()) {
            return;
        }
        if ("apply_patch".equals(tool.name())) {
            return;
        }
        String path = args.optString("uri", args.optString("path", ""));
        if (path.isEmpty()) {
            return;
        }
        AgentEvent.FileChangeKind kind = tool.name().toLowerCase(Locale.ROOT).contains("delete")
                ? AgentEvent.FileChangeKind.DELETED
                : AgentEvent.FileChangeKind.MODIFIED;
        if (events != null) {
            events.emit(new AgentEvent.FileChanged(scId, path, kind, tool.name()));
        }
    }

    private static ToolCall toLegacyCall(StructuredToolCall call) {
        return new ToolCall(call.toolName(), call.arguments(), call.callId());
    }

    private static AgentTool findTool(List<AgentTool> tools, String name) {
        for (AgentTool tool : tools) {
            if (tool.name().equals(name)) {
                return tool;
            }
        }
        return null;
    }

    private static JSONObject safeParse(String arguments) {
        try {
            return new JSONObject(arguments == null ? "{}" : arguments);
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
