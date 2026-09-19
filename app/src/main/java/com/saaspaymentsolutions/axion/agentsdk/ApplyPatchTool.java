package com.saaspaymentsolutions.axion.agentsdk;

import com.saaspaymentsolutions.axion.workspace.WorkspaceFileSystem;
import com.saaspaymentsolutions.axion.workspace.WorkspaceManager;
import com.saaspaymentsolutions.axion.workspace.WorkspacePath;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Port of Codex's {@code apply_patch} tool: the model expresses multi-file
 * changes as one patch document which is validated in full before anything
 * is written. Validation is all-or-nothing: if any op fails validation, no
 * file is touched and the model receives a structured error it can retry on.
 *
 * <p>Application is best-effort with <b>rollback</b>: writes happen through the
 * active workspace filesystem first, and if a later write or delete fails, the
 * already-applied ops are restored from their pre-patch content (failures here
 * are reported honestly). Deleted files' previous content is kept in memory
 * for the duration of the patch so a rollback can recreate them.</p>
 *
 * <p>Path security: every path goes through {@link WorkspacePath#normalize}
 * (rejects traversal) and must not be absolute or contain a drive letter —
 * the workspace filesystem works with relative paths only.</p>
 */
public final class ApplyPatchTool implements AgentTool {

    private final String scId;
    private final EventStream events;
    private final WorkspaceFileSystem injectedFs;

    /** Production constructor: resolves the active workspace at execute time. */
    public ApplyPatchTool(String scId, EventStream events) {
        this(scId, events, null);
    }

    /** Injected-filesystem constructor (tests, evals, multi-workspace hosts). */
    public ApplyPatchTool(String scId, EventStream events, WorkspaceFileSystem injectedFs) {
        this.scId = scId == null ? "" : scId;
        this.events = events;
        this.injectedFs = injectedFs;
    }

    @Override
    public String name() {
        return "apply_patch";
    }

    @Override
    public String description() {
        return "Applies multiple file changes using the Codex patch format. "
                + "Supports Add File, Update File (hunks with context ' ', removal '-' and addition '+') "
                + "and Delete File ops. The whole patch is validated before anything is written: if any "
                + "operation is invalid, no file is modified. If a write fails after validation, the "
                + "already-applied changes are rolled back and the error is reported.";
    }

    @Override
    public JSONObject parameters() {
        try {
            return new JSONObject()
                    .put("type", "object")
                    .put("properties", new JSONObject()
                            .put("patch", new JSONObject()
                                    .put("type", "string")
                                    .put("description", "Documento de patch completo, entre marcadores "
                                            + "*** Begin Patch / *** End Patch")))
                    .put("required", new org.json.JSONArray().put("patch"));
        } catch (org.json.JSONException e) {
            return new JSONObject();
        }
    }

    @Override
    public boolean isFileMutation() {
        return true;
    }

    @Override
    public boolean isDestructive() {
        return true; // Delete File ops and full rewrites are irreversible
    }

    @Override
    public AgentToolResult execute(RunContext context, JSONObject args) {
        if (args == null || !args.has("patch")) {
            return AgentToolResult.error("Error: 'patch' argument is required.");
        }
        String patch = args.optString("patch", "");
        List<PatchParser.PatchOp> ops;
        try {
            ops = PatchParser.parse(patch);
        } catch (PatchParser.PatchParseException e) {
            return AgentToolResult.error("Error: invalid patch — " + e.getMessage());
        }

        // ---- Pass 1: validate everything against the current workspace ----
        WorkspaceFileSystem fs = injectedFs != null ? injectedFs : WorkspaceManager.getActiveFileSystem();
        if (fs == null) {
            return AgentToolResult.error("Error: no active workspace is open.");
        }
        List<String[]> writes = new ArrayList<>(); // [relativePath, newContent, originalContentOrNull]
        List<String> deletes = new ArrayList<>();
        List<AgentEvent.FileChangeKind> kinds = new ArrayList<>();

        try {
            for (PatchParser.PatchOp op : ops) {
                String path = safePath(op.getPath());
                if (path == null) {
                    return AgentToolResult.error("Error: unsafe path '" + op.getPath() + "'.");
                }
                switch (op.getType()) {
                    case ADD: {
                        if (fs.exists(path)) {
                            return AgentToolResult.error("Error: Add File '" + path
                                    + "' but the file already exists.");
                        }
                        writes.add(new String[]{path, renderContent(op.getHunks()), null});
                        kinds.add(AgentEvent.FileChangeKind.CREATED);
                        break;
                    }
                    case UPDATE: {
                        if (!fs.exists(path) || fs.isDirectory(path)) {
                            return AgentToolResult.error("Error: Update File '" + path
                                    + "' but the file does not exist.");
                        }
                        String original = fs.readText(path);
                        String updated = applyHunks(path, original, op.getHunks());
                        if (updated == null) {
                            return AgentToolResult.error("Error: patch does not apply cleanly to '"
                                    + path + "'.");
                        }
                        writes.add(new String[]{path, updated, original});
                        kinds.add(AgentEvent.FileChangeKind.MODIFIED);
                        break;
                    }
                    case DELETE: {
                        if (!fs.exists(path) || fs.isDirectory(path)) {
                            return AgentToolResult.error("Error: Delete File '" + path
                                    + "' but the file does not exist.");
                        }
                        deletes.add(path);
                        kinds.add(AgentEvent.FileChangeKind.DELETED);
                        break;
                    }
                    default:
                        return AgentToolResult.error("Error: unsupported patch operation.");
                }
            }
        } catch (Exception e) {
            return AgentToolResult.error("Error: validation failed — "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }

        // ---- Pass 2: apply with rollback. Every op captures enough state to
        // restore what it touched, so a mid-patch failure undoes the patch. ----
        StringBuilder report = new StringBuilder();
        List<String> appliedWrites = new ArrayList<>();
        List<String[]> restoredDeletes = new ArrayList<>(); // [path, previousContent]
        try {
            for (int i = 0; i < writes.size(); i++) {
                String[] write = writes.get(i);
                fs.writeText(write[0], write[1]);
                appliedWrites.add(write[0]);
                report.append("Updated ").append(write[0]).append('\n');
                if (events != null) {
                    events.emit(new AgentEvent.FileChanged(scId, write[0], kinds.get(i), name()));
                }
            }
            for (String path : deletes) {
                String previous = null;
                try {
                    previous = fs.readText(path);
                } catch (Exception readFailure) {
                    previous = null;
                }
                boolean deleted = fs.delete(path);
                if (!deleted || fs.exists(path)) {
                    throw new IllegalStateException("filesystem did not delete '" + path + "'");
                }
                restoredDeletes.add(new String[]{path, previous});
                report.append("Deleted ").append(path).append('\n');
                if (events != null) {
                    events.emit(new AgentEvent.FileChanged(scId, path,
                            AgentEvent.FileChangeKind.DELETED, name()));
                }
            }
        } catch (Exception e) {
            rollback(fs, appliedWrites, restoredDeletes, writes);
            return AgentToolResult.error("Error: write failed after validation — "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                    + " (already-applied changes were rolled back).");
        }
        return AgentToolResult.success(report.toString().trim());
    }

    /**
     * Restores the workspace state from before the failing patch: written
     * files go back to their pre-patch content (or are removed when they were
     * created by this patch), deleted files are recreated with their previous
     * content. Rollback failures are collected but never mask the original
     * error; the returned message lists anything that could not be restored.
     */
    private static void rollback(WorkspaceFileSystem fs, List<String> appliedWrites,
                                 List<String[]> restoredDeletes, List<String[]> plannedWrites) {
        StringBuilder problems = new StringBuilder();
        // Remove files this patch created, restore the ones it overwrote.
        for (int i = appliedWrites.size() - 1; i >= 0; i--) {
            String path = appliedWrites.get(i);
            try {
                String original = originalContentFor(plannedWrites, path);
                if (original == null) {
                    // CREATED by this patch: remove it again.
                    fs.delete(path);
                } else {
                    fs.writeText(path, original);
                }
            } catch (Exception rollbackFailure) {
                problems.append(path).append("; ");
            }
        }
        // Recreate files this patch deleted.
        for (int i = restoredDeletes.size() - 1; i >= 0; i--) {
            String[] entry = restoredDeletes.get(i);
            try {
                fs.writeText(entry[0], entry[1] == null ? "" : entry[1]);
            } catch (Exception rollbackFailure) {
                problems.append(entry[0]).append("; ");
            }
        }
        if (problems.length() > 0) {
            android.util.Log.e("ApplyPatchTool", "Rollback incomplete for: " + problems);
        }
    }

    /** Pre-patch content for a written path, or null when the patch created it. */
    private static String originalContentFor(List<String[]> plannedWrites, String path) {
        for (String[] planned : plannedWrites) {
            if (planned[0].equals(path)) {
                return planned[2];
            }
        }
        return null;
    }

    /** Normalizes and rejects unsafe paths; returns {@code null} when unsafe. */
    private static String safePath(String rawPath) {
        if (rawPath == null || rawPath.trim().isEmpty()) {
            return null;
        }
        String trimmed = rawPath.trim();
        if (trimmed.startsWith("/") || trimmed.contains(":") || trimmed.contains("\\")) {
            return null;
        }
        try {
            String normalized = WorkspacePath.normalize(trimmed);
            return normalized.isEmpty() ? null : normalized;
        } catch (Exception e) {
            return null;
        }
    }

    /** Concatenates ADD lines into the new file content. */
    private static String renderContent(List<PatchParser.Hunk> hunks) {
        StringBuilder sb = new StringBuilder();
        for (PatchParser.Hunk hunk : hunks) {
            for (PatchParser.Line line : hunk.getLines()) {
                sb.append(line.getText()).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Applies UPDATE hunks: each hunk is located by its context lines
     * (optionally seeded by the {@code @@ marker}) and applied once.
     * Returns {@code null} when any hunk fails to match.
     */
    static String applyHunks(String path, String original, List<PatchParser.Hunk> hunks) {
        List<String> lines = new ArrayList<>();
        String[] split = original.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
        Collections.addAll(lines, split);

        for (PatchParser.Hunk hunk : hunks) {
            int at = findHunkPosition(lines, hunk);
            if (at < 0) {
                return null;
            }
            lines = applyAt(lines, at, hunk);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    private static int findHunkPosition(List<String> lines, PatchParser.Hunk hunk) {
        List<PatchParser.Line> hunkLines = hunk.getLines();
        if (hunkLines.isEmpty()) {
            return -1;
        }
        // Fast path: append-only hunk (adds after last context/add line).
        boolean onlyAdds = true;
        for (PatchParser.Line line : hunkLines) {
            if (line.getKind() == PatchParser.Line.Kind.REMOVE
                    || line.getKind() == PatchParser.Line.Kind.CONTEXT) {
                onlyAdds = false;
                break;
            }
        }
        if (onlyAdds && hunk.getContextMarker().isEmpty()) {
            return lines.size();
        }

        // The optional @@ marker is a disambiguator (Codex semantics): it must
        // match some line of the file, and the hunk applies at or after it.
        int searchFrom = 0;
        String anchor = hunk.getContextMarker().trim();
        if (!anchor.isEmpty()) {
            int markerIndex = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains(anchor)) {
                    markerIndex = i;
                    break;
                }
            }
            if (markerIndex < 0) {
                return -1;
            }
            searchFrom = markerIndex;
        }

        for (int start = searchFrom; start <= lines.size(); start++) {
            if (matchesAt(lines, start, hunkLines)) {
                return start;
            }
        }
        return -1;
    }

    private static boolean matchesAt(List<String> lines, int start, List<PatchParser.Line> hunkLines) {
        int cursor = start;
        for (PatchParser.Line line : hunkLines) {
            if (line.getKind() == PatchParser.Line.Kind.ADD) {
                continue; // adds do not consume original lines
            }
            if (cursor >= lines.size()) {
                return false;
            }
            if (!lines.get(cursor).equals(line.getText())) {
                return false;
            }
            cursor++;
        }
        return true;
    }

    private static List<String> applyAt(List<String> lines, int at, PatchParser.Hunk hunk) {
        List<String> result = new ArrayList<>();
        result.addAll(lines.subList(0, at));
        int cursor = at;
        for (PatchParser.Line line : hunk.getLines()) {
            switch (line.getKind()) {
                case ADD:
                    result.add(line.getText());
                    break;
                case REMOVE:
                    cursor++; // skip the original line
                    break;
                case CONTEXT:
                    if (cursor < lines.size()) {
                        result.add(lines.get(cursor));
                        cursor++;
                    }
                    break;
            }
        }
        result.addAll(lines.subList(Math.min(cursor, lines.size()), lines.size()));
        return result;
    }
}
