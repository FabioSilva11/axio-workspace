package com.saaspaymentsolutions.axion.agentsdk.tools;

import com.saaspaymentsolutions.axion.agentsdk.AgentToolResult;
import com.saaspaymentsolutions.axion.agentsdk.ApplyPatchTool;
import com.saaspaymentsolutions.axion.agentsdk.RunContext;

/**
 * Core {@code apply_patch} executor operating on the raw FREEFORM patch
 * document (Codex contract: the input is the patch text itself, never the
 * legacy {@code {"patch": "..."}} JSON envelope). All parse/validate/mutate
 * logic lives in the existing {@link ApplyPatchTool}; this class is the thin
 * freeform adapter that feeds the raw string through infrastructure capable of
 * producing an {@link ApplyPatchTool} bound to the run's workspace.
 *
 * <p>Execution requires the {@link RunContext} workspace to be resolvable —
 * the same fail-closed rule {@code ApplyPatchTool.apply} enforces, so a run
 * without a pinned filesystem can never mutate the wrong project.</p>
 */
final class ApplyPatchExecutor implements ToolExecutor {

    private final PatchToolFactory factory;

    /** Strategy producing a bound ApplyPatchTool for a run context. */
    interface PatchToolFactory {
        ApplyPatchTool create(RunContext context);
    }

    ApplyPatchExecutor(PatchToolFactory factory) {
        this.factory = factory;
    }

    @Override
    public AgentToolResult execute(ToolExecutionContext ctx) {
        String patch = ctx.freeformInput() == null ? "" : ctx.freeformInput();
        if (patch.trim().isEmpty()) {
            return AgentToolResult.error(
                    "Error: the patch document is empty. Send the patch text as the tool input "
                            + "(a FREEFORM tool: do not wrap the patch in JSON).");
        }
        ApplyPatchTool tool = factory.create(ctx.runContext());
        return tool.apply(ctx.runContext(), patch);
    }
}