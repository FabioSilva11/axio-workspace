package com.saaspaymentsolutions.axion.port;

import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.saaspaymentsolutions.axion.DirectoryTreeService;
import com.saaspaymentsolutions.axion.R;
import com.saaspaymentsolutions.axion.SketchApplication;
import com.saaspaymentsolutions.axion.LanguageHelpers;
import com.saaspaymentsolutions.axion.PromptConstants;
import com.saaspaymentsolutions.axion.StringHelpers;
import com.saaspaymentsolutions.axion.ChatToolLog;
import com.saaspaymentsolutions.axion.ProjectPathResolver;
import com.saaspaymentsolutions.axion.SemanticFileSearcher;
import com.saaspaymentsolutions.axion.FileChangeTracker;

/**
 * Android port of browser/toolsService.ts
 * Provides all builtin tools from Void for use in Axion chat.
 */
public final class VoidPortToolsService {

    /**
     * Max characters of file content returned per read_file page.
     * Was 500 000 — half a megabyte per call blew up the LLM context budget
     * (≈125k tokens in one tool result). 24 000 chars ≈ 6k tokens per page;
     * the tool already reports hasNextPage/pagination so the model can page.
     */
    private static final int MAX_FILE_CHARS_PAGE = 24000;
    private static final int MAX_CHILDREN_URIS_PAGE = 500;
    /**
     * How long {@code run_persistent_command} waits before returning partial
     * output while the command continues in the background.
     * Raised from 5 s → 15 s; long-running builds / gradle tasks need more time.
     */
    private static final int MAX_TERMINAL_BG_COMMAND_TIME_SECONDS = 15;
    /**
     * Default wait for a one-shot {@code run_command} before force-killing it
     * and returning a timeout result. The model can override per call via the
     * optional {@code timeout_seconds} argument (clamped to 5-300 s).
     * Raised 8 s → 30 s → 60 s; Gradle/aapt/d8 invocations routinely exceed 30 s.
     */
    private static final int MAX_TERMINAL_INACTIVE_TIME_SECONDS = 60;
    private static final int LINT_ERROR_TIMEOUT = 1000;

    private static final Map<String, Process> activeTerminals = new ConcurrentHashMap<>();
    private static final Map<String, StringBuilder> terminalOutputs = new ConcurrentHashMap<>();
    private static final Map<String, BufferedReader> terminalReaders = new ConcurrentHashMap<>();

    private VoidPortToolsService() {
    }

    public static List<String> getPersistentTerminalIds() {
        return new ArrayList<>(activeTerminals.keySet());
    }

    /**
     * Force-kills every process spawned by run_command / persistent terminals and
     * clears the tracking maps. Called when the user cancels the current agent
     * run — previously those processes kept running (and leaking) in the background.
     */
    /**
     * update_plan tool: lets the model maintain the step plan shown in the
     * plan tab (Codex-style). Input format: one step per line,
     * "pending|running|done: step title".
     */
    private static String updatePlan(String scId, Object planObj) {
        try {
            String plan = planObj == null ? "" : String.valueOf(planObj);
            List<com.saaspaymentsolutions.axion.ChatPlanManager.Task> tasks = new ArrayList<>();
            for (String line : plan.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int status = com.saaspaymentsolutions.axion.ChatPlanManager.STATUS_PENDING;
                String title = trimmed;
                int colon = trimmed.indexOf(':');
                if (colon > 0) {
                    String statusToken = trimmed.substring(0, colon).trim()
                            .toLowerCase(java.util.Locale.US)
                            .replaceAll("[^a-z_]", "");
                    title = trimmed.substring(colon + 1).trim();
                    if ("done".equals(statusToken) || "completed".equals(statusToken)) {
                        status = com.saaspaymentsolutions.axion.ChatPlanManager.STATUS_DONE;
                    } else if ("running".equals(statusToken) || "in_progress".equals(statusToken)) {
                        status = com.saaspaymentsolutions.axion.ChatPlanManager.STATUS_RUNNING;
                    } else if (!"pending".equals(statusToken)) {
                        // No recognised status prefix — treat the whole line as a title.
                        title = trimmed;
                    }
                }
                if (!title.isEmpty()) {
                    tasks.add(new com.saaspaymentsolutions.axion.ChatPlanManager.Task(title, "", status));
                }
            }
            if (tasks.isEmpty()) {
                return "Erro: plano vazio. Envie um passo por linha no formato 'pending|running|done: título'.";
            }
            com.saaspaymentsolutions.axion.ChatPlanManager.setModelPlan(scId, tasks);
            return "Plano atualizado com " + tasks.size() + " passo(s).";
        } catch (Exception e) {
            return "Erro ao atualizar plano: " + e.getMessage();
        }
    }

    public static void killAllTerminals() {
        for (Map.Entry<String, Process> entry : activeTerminals.entrySet()) {
            try {
                entry.getValue().destroyForcibly();
            } catch (Exception ignored) {
            }
        }
        activeTerminals.clear();
        terminalOutputs.clear();
        terminalReaders.clear();
    }

    // ============================================
    // VALIDATION HELPERS
    // ============================================

    private static boolean isFalsy(Object value) {
        return value == null || "null".equals(String.valueOf(value)) || "undefined".equals(String.valueOf(value));
    }

    private static String validateStr(String argName, Object value) throws Exception {
        if (value == null) {
            throw new Exception("Invalid LLM output: " + argName + " was null.");
        }
        if (!(value instanceof String)) {
            throw new Exception("Invalid LLM output format: " + argName + " must be a string, but its type is \"" + (value != null ? value.getClass().getSimpleName() : "null") + "\". Full value: " + String.valueOf(value));
        }
        return (String) value;
    }

    private static String validateOptionalStr(String argName, Object value) {
        if (isFalsy(value)) return null;
        try {
            return validateStr(argName, value);
        } catch (Exception e) {
            return null;
        }
    }

    private static int validatePageNum(Object pageNumberUnknown) {
        if (pageNumberUnknown == null) return 1;
        try {
            int parsed = Integer.parseInt(String.valueOf(pageNumberUnknown));
            if (parsed < 1) return 1;
            return parsed;
        } catch (Exception e) {
            return 1;
        }
    }

    private static Integer validateNumber(Object numStr, Integer defaultVal) {
        if (numStr == null) return defaultVal;
        if (numStr instanceof Number) return ((Number) numStr).intValue();
        try {
            return Integer.parseInt(String.valueOf(numStr));
        } catch (Exception e) {
            return defaultVal;
        }
    }

    private static boolean validateBoolean(Object b, boolean defaultVal) {
        if (b instanceof Boolean) return (Boolean) b;
        if (b instanceof String) {
            if ("true".equals(b)) return true;
            if ("false".equals(b)) return false;
        }
        return defaultVal;
    }

    private static boolean checkIfIsFolder(String uriStr) {
        if (uriStr == null) return false;
        uriStr = uriStr.trim();
        return uriStr.endsWith("/") || uriStr.endsWith("\\");
    }

    // ============================================
    // TOOL CALL RESULTS
    // ============================================

    public static class ToolCallResult {
        public final String result;
        public final boolean hasNextPage;
        public final boolean hasPrevPage;
        public final int itemsRemaining;
        public final int totalFileLen;
        public final int totalNumLines;

        private ToolCallResult(String result) {
            this.result = result;
            this.hasNextPage = false;
            this.hasPrevPage = false;
            this.itemsRemaining = 0;
            this.totalFileLen = 0;
            this.totalNumLines = 0;
        }

        private ToolCallResult(String result, boolean hasNextPage) {
            this.result = result;
            this.hasNextPage = hasNextPage;
            this.hasPrevPage = false;
            this.itemsRemaining = 0;
            this.totalFileLen = 0;
            this.totalNumLines = 0;
        }

        private ToolCallResult(String result, boolean hasNextPage, boolean hasPrevPage, int itemsRemaining) {
            this.result = result;
            this.hasNextPage = hasNextPage;
            this.hasPrevPage = hasPrevPage;
            this.itemsRemaining = itemsRemaining;
            this.totalFileLen = 0;
            this.totalNumLines = 0;
        }

        private ToolCallResult(String result, int totalFileLen, int totalNumLines, boolean hasNextPage) {
            this.result = result;
            this.hasNextPage = hasNextPage;
            this.hasPrevPage = false;
            this.itemsRemaining = 0;
            this.totalFileLen = totalFileLen;
            this.totalNumLines = totalNumLines;
        }
    }

    // ============================================
    // FILE TOOLS
    // ============================================

    public static ToolCallResult readFile(String scId, Object uriObj, Object startLineObj, Object endLineObj, Object pageNumberObj) {
        try {
            String uriStr = validateStr("uri", uriObj);
            int pageNumber = validatePageNum(pageNumberObj);
            Integer startLine = validateNumber(startLineObj, null);
            Integer endLine = validateNumber(endLineObj, null);

            if (startLine != null && startLine < 1) startLine = null;
            if (endLine != null && endLine < 1) endLine = null;

            ProjectPathResolver.ResolvedPath resolved = ProjectPathResolver.resolveForRead(scId, uriStr);
            if (resolved == null) {
                return new ToolCallResult("File not found or outside project scope: " + uriStr);
            }

            String content = readFileDirect(scId, uriStr);
            if (content == null) {
                return new ToolCallResult("File not found or could not be read: " + uriStr);
            }

            String selected = sliceLines(content, startLine, endLine);
            int totalFileLen = content.length();
            int totalNumLines = content.split("\n", -1).length;

            int fromIdx = MAX_FILE_CHARS_PAGE * (pageNumber - 1);
            int toIdx = MAX_FILE_CHARS_PAGE * pageNumber - 1;
            String fileContents;
            if (fromIdx >= selected.length()) {
                fileContents = "";
            } else {
                fileContents = selected.substring(fromIdx, Math.min(toIdx + 1, selected.length()));
            }
            boolean hasNextPage = (selected.length() - 1) - toIdx >= 1;

            JSONObject resultObj = new JSONObject();
            resultObj.put("fileContents", fileContents);
            resultObj.put("totalFileLen", totalFileLen);
            resultObj.put("totalNumLines", totalNumLines);
            resultObj.put("hasNextPage", hasNextPage);

            return new ToolCallResult(resultObj.toString(), totalFileLen, totalNumLines, hasNextPage);
        } catch (Exception e) {
            return new ToolCallResult("Error reading file: " + e.getMessage());
        }
    }

    public static ToolCallResult lsDir(String scId, Object uriObj, Object pageNumberObj) {
        try {
            String uriStr = validateOptionalStr("uri", uriObj);
            if (uriStr == null) {
                uriStr = "";
            }
            int pageNumber = validatePageNum(pageNumberObj);

            List<File> entries = new ArrayList<>();
            if (uriStr.trim().isEmpty()) {
                for (File root : ProjectPathResolver.getReadableRoots(scId)) {
                    if (root != null && root.exists()) {
                        entries.add(root);
                    }
                }
            } else {
                if (ProjectPathResolver.isPlaceholderPath(uriStr)) {
                    return new ToolCallResult(
                            "Error: invalid directory path placeholder: " + uriStr);
                }
                ProjectPathResolver.ResolvedPath resolved = ProjectPathResolver.resolveForRead(scId, uriStr);
                if (resolved == null) {
                    return new ToolCallResult(
                            "Error: directory path is invalid, out of scope, or unavailable: " + uriStr);
                }

                File folder = resolved.getFile();
                if (!folder.exists()) {
                    return new ToolCallResult("Directory not found: " + uriStr);
                }
                if (!folder.isDirectory()) {
                    return new ToolCallResult("The path is a file, not a directory. Use read_file to view its contents: " + uriStr);
                }

                File[] files = folder.listFiles();
                if (files != null) {
                    for (File file : files) {
                        entries.add(file);
                    }
                }
            }

            if (entries.isEmpty()) {
                return new ToolCallResult("[]");
            }

            int fromIdx = MAX_CHILDREN_URIS_PAGE * (pageNumber - 1);
            int toIdx = MAX_CHILDREN_URIS_PAGE * pageNumber - 1;

            JSONArray resultArray = new JSONArray();
            for (int i = fromIdx; i <= Math.min(toIdx, entries.size() - 1); i++) {
                File f = entries.get(i);
                JSONObject item = new JSONObject();
                item.put("uri", f.getAbsolutePath());
                item.put("name", f.getName());
                item.put("isDirectory", f.isDirectory());
                item.put("isSymbolicLink", false);
                resultArray.put(item);
            }

            boolean hasNextPage = (entries.size() - 1) - toIdx >= 1;
            boolean hasPrevPage = pageNumber > 1;
            int itemsRemaining = Math.max(0, entries.size() - (toIdx + 1));

            JSONObject resultObj = new JSONObject();
            resultObj.put("children", resultArray);
            resultObj.put("hasNextPage", hasNextPage);
            resultObj.put("hasPrevPage", hasPrevPage);
            resultObj.put("itemsRemaining", itemsRemaining);

            return new ToolCallResult(resultObj.toString(), hasNextPage, hasPrevPage, itemsRemaining);
        } catch (Exception e) {
            return new ToolCallResult("Error listing directory: " + e.getMessage());
        }
    }

    public static ToolCallResult getDirTree(String scId, Object uriObj) {
        try {
            String uriStr = isFalsy(uriObj) ? "." : validateStr("uri", uriObj);
            uriStr = uriStr.trim().isEmpty() ? "." : uriStr;
            if (ProjectPathResolver.isPlaceholderPath(uriStr)) {
                return new ToolCallResult("Error: invalid folder path placeholder: " + uriStr);
            }

            ProjectPathResolver.ResolvedPath resolved = ProjectPathResolver.resolveForRead(scId, uriStr);
            if (resolved == null) {
                return new ToolCallResult("Directory not found: " + uriStr);
            }

            File folder = resolved.getFile();
            if (!folder.exists()) {
                return new ToolCallResult("Directory not found: " + uriStr);
            }
            if (!folder.isDirectory()) {
                return new ToolCallResult("The path is a file, not a directory. Use read_file instead: " + uriStr);
            }

            String tree = DirectoryTreeService.getDirectoryStrTool(folder);
            JSONObject resultObj = new JSONObject();
            resultObj.put("str", tree);
            return new ToolCallResult(resultObj.toString());
        } catch (Exception e) {
            return new ToolCallResult("Error getting directory tree: " + e.getMessage());
        }
    }

    // ============================================
    // SEARCH TOOLS
    // ============================================

    public static ToolCallResult searchPathnamesOnly(String scId, Object queryObj, Object includePatternObj, Object pageNumberObj) {
        try {
            String queryStr = validateStr("query", queryObj);
            int pageNumber = validatePageNum(pageNumberObj);
            String includePattern = validateOptionalStr("include_pattern", includePatternObj);

            List<SemanticFileSearcher.SearchResult> results = SemanticFileSearcher.searchByFilename(queryStr, scId);
            results = filterSearchResults(results, includePattern, null);
            
            int fromIdx = MAX_CHILDREN_URIS_PAGE * (pageNumber - 1);
            int toIdx = MAX_CHILDREN_URIS_PAGE * pageNumber - 1;

            JSONArray urisArray = new JSONArray();
            for (int i = fromIdx; i <= Math.min(toIdx, results.size() - 1); i++) {
                urisArray.put(results.get(i).filePath);
            }

            boolean hasNextPage = (results.size() - 1) - toIdx >= 1;

            JSONObject resultObj = new JSONObject();
            resultObj.put("uris", urisArray);
            resultObj.put("hasNextPage", hasNextPage);

            return new ToolCallResult(resultObj.toString(), hasNextPage);
        } catch (Exception e) {
            return new ToolCallResult("{\"uris\":[],\"hasNextPage\":false}");
        }
    }

    public static ToolCallResult searchForFiles(String scId, Object queryObj, Object isRegexObj, Object searchInFolderObj, Object pageNumberObj) {
        try {
            String queryStr = validateStr("query", queryObj);
            boolean isRegex = validateBoolean(isRegexObj, false);
            int pageNumber = validatePageNum(pageNumberObj);
            String searchInFolder = validateOptionalStr("search_in_folder", searchInFolderObj);

            List<SemanticFileSearcher.SearchResult> results;
            if (isRegex) {
                results = SemanticFileSearcher.searchByContentRegex(queryStr, scId);
            } else {
                results = SemanticFileSearcher.searchByContent(queryStr, scId);
            }
            results = filterSearchResults(results, null, searchInFolder);

            int fromIdx = MAX_CHILDREN_URIS_PAGE * (pageNumber - 1);
            int toIdx = MAX_CHILDREN_URIS_PAGE * pageNumber - 1;

            JSONArray urisArray = new JSONArray();
            for (int i = fromIdx; i <= Math.min(toIdx, results.size() - 1); i++) {
                urisArray.put(results.get(i).filePath);
            }

            boolean hasNextPage = (results.size() - 1) - toIdx >= 1;

            JSONObject resultObj = new JSONObject();
            resultObj.put("uris", urisArray);
            resultObj.put("hasNextPage", hasNextPage);

            return new ToolCallResult(resultObj.toString(), hasNextPage);
        } catch (Exception e) {
            return new ToolCallResult("{\"uris\":[],\"hasNextPage\":false}");
        }
    }

    public static ToolCallResult searchInFile(String scId, Object uriObj, Object queryObj, Object isRegexObj) {
        try {
            String uriStr = validateStr("uri", uriObj);
            String query = validateStr("query", queryObj);
            boolean isRegex = validateBoolean(isRegexObj, false);

            String content = readFileDirect(scId, uriStr);
            if (content == null) {
                JSONArray linesArray = new JSONArray();
                JSONObject resultObj = new JSONObject();
                resultObj.put("lines", linesArray);
                return new ToolCallResult(resultObj.toString());
            }

            String[] lines = content.split("\n", -1);
            JSONArray linesArray = new JSONArray();
            Pattern regex = isRegex ? Pattern.compile(query) : null;

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                boolean matches = isRegex ? (regex != null && regex.matcher(line).find()) : line.contains(query);
                if (matches) {
                    linesArray.put(i + 1);
                }
            }

            JSONObject resultObj = new JSONObject();
            resultObj.put("lines", linesArray);
            return new ToolCallResult(resultObj.toString());
        } catch (Exception e) {
            return new ToolCallResult("{\"lines\":[]}");
        }
    }

    // ============================================
    // EDIT TOOLS
    // ============================================

    public static ToolCallResult rewriteFile(String scId, Object uriObj, Object newContentObj) {
        try {
            String uriStr = validateStr("uri", uriObj);
            String newContent = validateStr("new_content", newContentObj);

            String oldContent = readFileDirect(scId, uriStr);
            boolean existedBefore = oldContent != null;
            if (oldContent == null) {
                oldContent = "";
            }

            if (!writeFileDirect(scId, uriStr, newContent)) {
                return new ToolCallResult("Cannot write to file: " + uriStr);
            }

            FileChangeTracker.trackChange(scId, uriStr, oldContent, newContent, existedBefore);

            // Get lint errors after write
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            List<VoidPortMarkerCheckService.LintError> lintErrors = VoidPortMarkerCheckService.getLintErrors(scId, uriStr);
            JSONArray lintErrorsArray = new JSONArray();
            for (VoidPortMarkerCheckService.LintError error : lintErrors) {
                JSONObject errorObj = new JSONObject();
                errorObj.put("code", error.code);
                errorObj.put("message", error.message);
                errorObj.put("startLineNumber", error.startLineNumber);
                errorObj.put("endLineNumber", error.endLineNumber);
                lintErrorsArray.put(errorObj);
            }

            JSONObject resultObj = new JSONObject();
            resultObj.put("lintErrors", lintErrorsArray);
            return new ToolCallResult(resultObj.toString());
        } catch (Exception e) {
            return new ToolCallResult("Error rewriting file: " + e.getMessage());
        }
    }

    public static ToolCallResult editFile(String scId, Object uriObj, Object searchReplaceBlocksObj) {
        try {
            String uriStr = validateStr("uri", uriObj);
            String searchReplaceBlocks = validateStr("search_replace_blocks", searchReplaceBlocksObj);

            String content = readFileDirect(scId, uriStr);
            if (content == null) {
                return new ToolCallResult("File not found or could not be read: " + uriStr);
            }

            SearchReplaceEngine.Result replaceResult =
                    SearchReplaceEngine.apply(content, searchReplaceBlocks);
            if (replaceResult.blockCount == 0) {
                return new ToolCallResult(
                        "Invalid SEARCH/REPLACE blocks: no valid blocks found. "
                                + "Use the exact marker format from the tool schema.");
            }
            if (!replaceResult.succeeded()) {
                return new ToolCallResult(
                        "Could not apply edit_file safely: block "
                                + replaceResult.failedBlock + "/" + replaceResult.blockCount
                                + " was not applied. " + replaceResult.failureReason
                                + " No changes were written. Call read_file again, then retry "
                                + "with a small unique ORIGINAL block from the current content.");
            }
            String newContent = replaceResult.content;

            if (!writeFileDirect(scId, uriStr, newContent)) {
                return new ToolCallResult("Cannot write to file: " + uriStr);
            }

            FileChangeTracker.trackChange(scId, uriStr, content, newContent);

            // Get lint errors after edit
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            List<VoidPortMarkerCheckService.LintError> lintErrors = VoidPortMarkerCheckService.getLintErrors(scId, uriStr);
            JSONArray lintErrorsArray = new JSONArray();
            for (VoidPortMarkerCheckService.LintError error : lintErrors) {
                JSONObject errorObj = new JSONObject();
                errorObj.put("code", error.code);
                errorObj.put("message", error.message);
                errorObj.put("startLineNumber", error.startLineNumber);
                errorObj.put("endLineNumber", error.endLineNumber);
                lintErrorsArray.put(errorObj);
            }

            JSONObject resultObj = new JSONObject();
            resultObj.put("lintErrors", lintErrorsArray);
            return new ToolCallResult(resultObj.toString());
        } catch (Exception e) {
            return new ToolCallResult("Error editing file: " + e.getMessage());
        }
    }

    public static ToolCallResult createFileOrFolder(String scId, Object uriObj) {
        try {
            String uriStr = validateStr("uri", uriObj);
            boolean isFolder = checkIfIsFolder(uriStr);

            ProjectPathResolver.ResolvedPath resolved = ProjectPathResolver.resolveForWrite(scId, uriStr);
            if (resolved == null) {
                return new ToolCallResult("Cannot create: " + uriStr);
            }

            File file = resolved.getFile();
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            if (isFolder) {
                if (!file.exists()) {
                    file.mkdirs();
                }
            } else {
                if (!file.exists()) {
                    file.createNewFile();
                    // existedBefore=false: rejecting this change must delete the file.
                    FileChangeTracker.trackChange(scId, uriStr, "", "", false);
                }
            }

            return new ToolCallResult("{}");
        } catch (Exception e) {
            return new ToolCallResult("Error creating file/folder: " + e.getMessage());
        }
    }

    public static ToolCallResult deleteFileOrFolder(String scId, Object uriObj, Object isRecursiveObj) {
        try {
            String uriStr = validateStr("uri", uriObj);
            boolean isRecursive = validateBoolean(isRecursiveObj, false);

            // Root aliases are useful for project discovery, but must never be accepted by
            // a destructive tool. In particular, "/" resolves to the active project root
            // for read-only tools such as get_dir_tree.
            if (isUnsafeMutationRoot(uriStr)) {
                return new ToolCallResult("Error: refusing to delete the active project root: " + uriStr);
            }

            ProjectPathResolver.ResolvedPath resolved = ProjectPathResolver.resolveForWrite(scId, uriStr);
            if (resolved == null) {
                return new ToolCallResult("File/folder not found: " + uriStr);
            }

            File file = resolved.getFile();
            if (isProtectedMutationRoot(file, ProjectPathResolver.getWritableRoots(scId))) {
                return new ToolCallResult("Error: refusing to delete the active project root: " + uriStr);
            }
            if (!file.exists()) {
                return new ToolCallResult("File/folder not found: " + uriStr);
            }

            if (file.isDirectory() && !isRecursive && file.list().length > 0) {
                return new ToolCallResult("Cannot delete non-empty directory without is_recursive=true");
            }

            String oldContent = "";
            boolean isFile = file.isFile();
            if (isFile) {
                oldContent = readFileDirect(scId, uriStr);
                if (oldContent == null) oldContent = "";
            }

            deleteRecursive(file);

            if (isFile) {
                FileChangeTracker.trackChange(scId, uriStr, oldContent, "");
            }

            return new ToolCallResult("{}");
        } catch (Exception e) {
            return new ToolCallResult("Error deleting file/folder: " + e.getMessage());
        }
    }

    public static ToolCallResult readFiles(String scId, Object urisObj) {
        try {
            JSONArray array;
            if (urisObj instanceof JSONArray) {
                array = (JSONArray) urisObj;
            } else if (urisObj instanceof String) {
                array = new JSONArray((String) urisObj);
            } else {
                return new ToolCallResult("Invalid parameter 'uris': array expected");
            }
            JSONObject result = new JSONObject();
            for (int i = 0; i < array.length(); i++) {
                String uri = array.optString(i, "").trim();
                if (uri.isEmpty()) continue;
                String content = readFileDirect(scId, uri);
                result.put(uri, content != null ? content : "Error: file not found or unreadable");
            }
            return new ToolCallResult(result.toString());
        } catch (Exception e) {
            return new ToolCallResult("Error in read_files: " + e.getMessage());
        }
    }

    public static ToolCallResult moveFile(String scId, String source, String destination) {
        try {
            if (source.isEmpty() || destination.isEmpty()) {
                return new ToolCallResult("Error: source and destination are required");
            }
            if (com.saaspaymentsolutions.axion.workspace.WorkspacePath.hasParentTraversal(source)
                    || com.saaspaymentsolutions.axion.workspace.WorkspacePath.hasParentTraversal(destination)) {
                return new ToolCallResult("Security error: path traversal blocked");
            }
            com.saaspaymentsolutions.axion.workspace.WorkspaceFileSystem fs = com.saaspaymentsolutions.axion.workspace.WorkspaceManager.getActiveFileSystem();
            if (fs != null) {
                boolean ok = fs.move(source, destination);
                return new ToolCallResult(ok ? "File moved successfully from " + source + " to " + destination : "Failed to move file");
            }
            ProjectPathResolver.ResolvedPath src = ProjectPathResolver.resolveForRead(scId, source);
            ProjectPathResolver.ResolvedPath dst = ProjectPathResolver.resolveForWrite(scId, destination);
            if (src == null || dst == null || !src.getFile().exists()) {
                return new ToolCallResult("Source file not found: " + source);
            }
            dst.getFile().getParentFile().mkdirs();
            boolean ok = src.getFile().renameTo(dst.getFile());
            return new ToolCallResult(ok ? "File moved successfully" : "Failed to move file");
        } catch (Exception e) {
            return new ToolCallResult("Error moving file: " + e.getMessage());
        }
    }

    public static ToolCallResult renameFile(String scId, String uri, String newName) {
        try {
            if (uri.isEmpty() || newName.isEmpty()) {
                return new ToolCallResult("Error: uri and new_name are required");
            }
            if (com.saaspaymentsolutions.axion.workspace.WorkspacePath.hasParentTraversal(uri)
                    || com.saaspaymentsolutions.axion.workspace.WorkspacePath.hasParentTraversal(newName)) {
                return new ToolCallResult("Security error: path traversal blocked");
            }
            com.saaspaymentsolutions.axion.workspace.WorkspaceFileSystem fs = com.saaspaymentsolutions.axion.workspace.WorkspaceManager.getActiveFileSystem();
            if (fs != null) {
                boolean ok = fs.rename(uri, newName);
                return new ToolCallResult(ok ? "File renamed successfully to " + newName : "Failed to rename file");
            }
            ProjectPathResolver.ResolvedPath src = ProjectPathResolver.resolveForRead(scId, uri);
            if (src == null || !src.getFile().exists()) {
                return new ToolCallResult("File not found: " + uri);
            }
            File target = new File(src.getFile().getParentFile(), newName);
            boolean ok = src.getFile().renameTo(target);
            return new ToolCallResult(ok ? "File renamed successfully" : "Failed to rename file");
        } catch (Exception e) {
            return new ToolCallResult("Error renaming file: " + e.getMessage());
        }
    }

    public static ToolCallResult copyFile(String scId, String source, String destination) {
        try {
            if (source.isEmpty() || destination.isEmpty()) {
                return new ToolCallResult("Error: source and destination are required");
            }
            if (com.saaspaymentsolutions.axion.workspace.WorkspacePath.hasParentTraversal(source)
                    || com.saaspaymentsolutions.axion.workspace.WorkspacePath.hasParentTraversal(destination)) {
                return new ToolCallResult("Security error: path traversal blocked");
            }
            com.saaspaymentsolutions.axion.workspace.WorkspaceFileSystem fs = com.saaspaymentsolutions.axion.workspace.WorkspaceManager.getActiveFileSystem();
            if (fs != null) {
                boolean ok = fs.copy(source, destination);
                return new ToolCallResult(ok ? "File copied successfully from " + source + " to " + destination : "Failed to copy file");
            }
            String content = readFileDirect(scId, source);
            if (content == null) {
                return new ToolCallResult("Source file not found: " + source);
            }
            boolean ok = writeFileDirect(scId, destination, content);
            return new ToolCallResult(ok ? "File copied successfully" : "Failed to copy file");
        } catch (Exception e) {
            return new ToolCallResult("Error copying file: " + e.getMessage());
        }
    }

    public static ToolCallResult getFileInfo(String scId, String uri) {
        try {
            if (com.saaspaymentsolutions.axion.workspace.WorkspacePath.hasParentTraversal(uri)) {
                return new ToolCallResult("Security error: path traversal blocked");
            }
            com.saaspaymentsolutions.axion.workspace.WorkspaceFileSystem fs = com.saaspaymentsolutions.axion.workspace.WorkspaceManager.getActiveFileSystem();
            if (fs != null) {
                com.saaspaymentsolutions.axion.workspace.WorkspaceFileSystem.FileMetadata meta = fs.getMetadata(uri);
                if (meta != null) {
                    JSONObject obj = new JSONObject();
                    obj.put("name", meta.getName());
                    obj.put("relativePath", meta.getRelativePath());
                    obj.put("isDirectory", meta.isDirectory());
                    obj.put("size", meta.getSize());
                    obj.put("lastModified", meta.getLastModified());
                    return new ToolCallResult(obj.toString());
                }
            }
            ProjectPathResolver.ResolvedPath resolved = ProjectPathResolver.resolveForRead(scId, uri);
            if (resolved == null || !resolved.getFile().exists()) {
                return new ToolCallResult("File not found: " + uri);
            }
            File f = resolved.getFile();
            JSONObject obj = new JSONObject();
            obj.put("name", f.getName());
            obj.put("isDirectory", f.isDirectory());
            obj.put("size", f.length());
            obj.put("lastModified", f.lastModified());
            return new ToolCallResult(obj.toString());
        } catch (Exception e) {
            return new ToolCallResult("Error getting file info: " + e.getMessage());
        }
    }

    static boolean isUnsafeMutationRoot(String uri) {
        return ProjectPathResolver.isReadRootAlias(uri)
                || ProjectPathResolver.hasParentTraversal(uri);
    }

    static boolean isProtectedMutationRoot(File candidate, List<File> writableRoots) {
        if (candidate == null || writableRoots == null) {
            return false;
        }
        try {
            String candidatePath = candidate.getCanonicalPath();
            for (File root : writableRoots) {
                if (root != null && candidatePath.equals(root.getCanonicalPath())) {
                    return true;
                }
            }
        } catch (IOException ignored) {
            return true;
        }
        return false;
    }

    // ============================================
    // TERMINAL TOOLS
    // ============================================

    public static ToolCallResult runCommand(String scId, Object commandObj, Object cwdObj, Object terminalIdObj) {
        return runCommand(scId, commandObj, cwdObj, terminalIdObj, null);
    }

    public static ToolCallResult runCommand(String scId, Object commandObj, Object cwdObj, Object terminalIdObj, Object timeoutObj) {
        try {
            String command = validateStr("command", commandObj);
            String cwd = validateOptionalStr("cwd", cwdObj);
            int timeoutSeconds = MAX_TERMINAL_INACTIVE_TIME_SECONDS;
            if (!isFalsy(timeoutObj)) {
                try {
                    timeoutSeconds = (int) Double.parseDouble(String.valueOf(timeoutObj));
                } catch (NumberFormatException ignored) {
                }
            }
            // Clamp: at least 5 s, at most 5 min (Gradle/aapt builds need headroom).
            timeoutSeconds = Math.max(5, Math.min(timeoutSeconds, 300));
            String terminalId = terminalIdObj != null ? String.valueOf(terminalIdObj) : java.util.UUID.randomUUID().toString();

            if (command.trim().isEmpty()) {
                return new ToolCallResult(SketchApplication.getContext().getString(R.string.chat_tool_empty_command));
            }

            if (commandLooksLikeFileMutation(command)) {
                return new ToolCallResult("Blocked: run_command cannot create, edit, overwrite, move or delete files. " +
                        "Use create_file_or_folder, delete_file_or_folder, edit_file or rewrite_file instead.");
            }

            File workingDir = resolveTerminalWorkingDir(scId, cwd);
            String shell = androidShellPath();

            if (!workingDir.exists()) {
                return new ToolCallResult(SketchApplication.getContext().getString(
                        R.string.chat_tool_working_directory_missing, workingDir.getAbsolutePath()));
            }

            ProcessBuilder pb = new ProcessBuilder(shell, "-c", command);
            configureAndroidProcess(pb, workingDir);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            activeTerminals.put(terminalId, process);
            terminalOutputs.put(terminalId, new StringBuilder());
            terminalReaders.put(terminalId, new BufferedReader(new InputStreamReader(process.getInputStream())));

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                String output = drainTerminalOutput(terminalId, false);
                process.destroyForcibly();
                activeTerminals.remove(terminalId);
                terminalOutputs.remove(terminalId);
                terminalReaders.remove(terminalId);

                JSONObject resolveReason = new JSONObject();
                resolveReason.put("type", "timeout");
                JSONObject resultObj = new JSONObject();
                resultObj.put("result", trimTerminalOutput(output));
                resultObj.put("resolveReason", resolveReason);
                resultObj.put("terminal", androidTerminalInfo(shell, workingDir));
                return new ToolCallResult(resultObj.toString());
            }

            String output = drainTerminalOutput(terminalId, true);

            int exitCode = process.exitValue();
            activeTerminals.remove(terminalId);
            terminalOutputs.remove(terminalId);
            terminalReaders.remove(terminalId);
            
            String normalizedOutput = trimTerminalOutput(output);

            JSONObject resultObj = new JSONObject();
            JSONObject resolveReason = new JSONObject();
            resolveReason.put("type", "done");
            resolveReason.put("exitCode", exitCode);
            resultObj.put("result", normalizedOutput);
            resultObj.put("resolveReason", resolveReason);
            resultObj.put("terminal", androidTerminalInfo(shell, workingDir));

            return new ToolCallResult(resultObj.toString());
        } catch (Exception e) {
            return new ToolCallResult(SketchApplication.getContext().getString(
                    R.string.chat_tool_command_failed, e.getMessage()));
        }
    }

    public static ToolCallResult openPersistentTerminal(String scId, Object cwdObj) {
        try {
            String cwd = validateOptionalStr("cwd", cwdObj);
            String terminalId = java.util.UUID.randomUUID().toString();

            File workingDir = resolveTerminalWorkingDir(scId, cwd);
            String shell = androidShellPath();

            ProcessBuilder pb = new ProcessBuilder(shell, "-i");
            configureAndroidProcess(pb, workingDir);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            activeTerminals.put(terminalId, process);
            terminalOutputs.put(terminalId, new StringBuilder());
            terminalReaders.put(terminalId, new BufferedReader(new InputStreamReader(process.getInputStream())));

            JSONObject resultObj = new JSONObject();
            resultObj.put("persistentTerminalId", terminalId);
            resultObj.put("terminal", androidTerminalInfo(shell, workingDir));
            return new ToolCallResult(resultObj.toString());
        } catch (Exception e) {
            return new ToolCallResult("Erro ao abrir terminal persistente: " + e.getMessage());
        }
    }

    public static ToolCallResult runPersistentCommand(String scId, Object commandObj, Object persistentTerminalIdObj) {
        try {
            String command = validateStr("command", commandObj);
            String terminalId = validateStr("persistent_terminal_id", persistentTerminalIdObj);

            if (commandLooksLikeFileMutation(command)) {
                return new ToolCallResult("Blocked: run_persistent_command cannot create, edit, overwrite, move or delete files. " +
                        "Use create_file_or_folder, delete_file_or_folder, edit_file or rewrite_file instead.");
            }

            Process process = activeTerminals.get(terminalId);
            if (process == null) {
                return new ToolCallResult("Terminal não encontrado: " + terminalId);
            }

            java.io.OutputStream os = process.getOutputStream();
            os.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            os.flush();

            // Wait for command to complete (with timeout)
            Thread.sleep(TimeUnit.SECONDS.toMillis(MAX_TERMINAL_BG_COMMAND_TIME_SECONDS));

            String result = trimTerminalOutput(drainTerminalOutput(terminalId, false));
            
            StringBuilder output = terminalOutputs.get(terminalId);
            if (output != null) {
                output.setLength(0);
            }

            JSONObject resultObj = new JSONObject();
            JSONObject resolveReason = new JSONObject();
            resolveReason.put("type", "timeout");
            resultObj.put("result", result);
            resultObj.put("resolveReason", resolveReason);
            resultObj.put("terminal", new JSONObject()
                    .put("platform", "android")
                    .put("persistentTerminalId", terminalId));
            return new ToolCallResult(resultObj.toString());
        } catch (Exception e) {
            return new ToolCallResult("Erro ao executar comando persistente: " + e.getMessage());
        }
    }

    public static ToolCallResult killPersistentTerminal(String scId, Object persistentTerminalIdObj) {
        try {
            String terminalId = validateStr("persistent_terminal_id", persistentTerminalIdObj);

            Process process = activeTerminals.remove(terminalId);
            terminalOutputs.remove(terminalId);
            terminalReaders.remove(terminalId);
            
            if (process != null) {
                process.destroyForcibly();
            }

            return new ToolCallResult("{}");
        } catch (Exception e) {
            return new ToolCallResult("Erro ao fechar terminal: " + e.getMessage());
        }
    }

    // ============================================
    // HELPER METHODS
    // ============================================

    private static File resolveTerminalWorkingDir(String scId, String cwd) throws IOException {
        // Scope the terminal to the PROJECT folder, not the shared .sketchware root.
        File projectRoot = ProjectPathResolver.getTerminalWorkingRoot(scId);
        File workingDir;
        if (cwd != null && !cwd.trim().isEmpty()) {
            // Previously any cwd was accepted verbatim (new File(cwd)), letting a
            // command run ANYWHERE on the device — the "run_command left the
            // project folder" bug. Now the cwd is resolved relative to the
            // project root and must stay inside it.
            String trimmed = cwd.trim();
            File requested = new File(trimmed);
            if (!requested.isAbsolute()) {
                requested = new File(projectRoot, trimmed);
            }
            try {
                String canonical = requested.getCanonicalPath();
                String rootCanonical = projectRoot.getCanonicalPath();
                if (!canonical.equals(rootCanonical) && !canonical.startsWith(rootCanonical + File.separator)) {
                    ChatToolLog.w("terminal", "cwd outside project, forcing root. requested=\"" + cwd
                            + "\" root=" + rootCanonical);
                    requested = projectRoot;
                }
            } catch (IOException e) {
                requested = projectRoot;
            }
            workingDir = requested;
        } else {
            workingDir = projectRoot;
        }
        if (workingDir == null) {
            throw new IOException("Android terminal working directory could not be resolved.");
        }
        if (!workingDir.exists()) {
            throw new IOException("Android terminal working directory not found: " + workingDir.getAbsolutePath());
        }
        if (!workingDir.isDirectory()) {
            throw new IOException("Android terminal cwd is not a directory: " + workingDir.getAbsolutePath());
        }
        ChatToolLog.d("terminal", "cwd=" + workingDir.getAbsolutePath());
        return workingDir;
    }

    private static String androidShellPath() {
        File systemShell = new File("/system/bin/sh");
        if (systemShell.exists() && systemShell.canExecute()) {
            return systemShell.getAbsolutePath();
        }
        File vendorShell = new File("/vendor/bin/sh");
        if (vendorShell.exists() && vendorShell.canExecute()) {
            return vendorShell.getAbsolutePath();
        }
        return "sh";
    }

    private static void configureAndroidProcess(ProcessBuilder processBuilder, File workingDir) {
        processBuilder.directory(workingDir);
        Map<String, String> env = processBuilder.environment();
        env.put("PWD", workingDir.getAbsolutePath());
        env.put("HOME", workingDir.getAbsolutePath());
        env.put("TERM", "xterm-256color");
        env.put("ANDROID_TERMINAL", "1");
    }

    private static JSONObject androidTerminalInfo(String shell, File workingDir) throws Exception {
        JSONObject info = new JSONObject();
        info.put("platform", "android");
        info.put("shell", shell);
        info.put("cwd", workingDir == null ? "" : workingDir.getAbsolutePath());
        info.put("sdk", Build.VERSION.SDK_INT);
        info.put("device", (Build.MANUFACTURER + " " + Build.MODEL).trim());
        return info;
    }

    private static String sliceLines(String content, Integer startLine, Integer endLine) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        if (startLine == null && endLine == null) {
            return content;
        }
        String[] lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int from = Math.max(1, startLine == null ? 1 : startLine);
        int to = endLine == null ? lines.length : Math.min(endLine, lines.length);
        if (to < from) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = from; i <= to; i++) {
            builder.append(lines[i - 1]);
            if (i < to) {
                builder.append('\n');
            }
        }
        return builder.toString();
    }

    private static String trimTerminalOutput(String output) {
        String normalized = output == null || output.trim().isEmpty() ? "(sem saida)" : output.trim();
        return normalized.length() > 100000 ? normalized.substring(0, 100000) : normalized;
    }

    private static String drainTerminalOutput(String terminalId, boolean readUntilEnd) throws IOException {
        BufferedReader reader = terminalReaders.get(terminalId);
        StringBuilder output = terminalOutputs.get(terminalId);
        if (reader == null || output == null) {
            return "";
        }

        long start = System.currentTimeMillis();
        while (readUntilEnd || reader.ready() || (System.currentTimeMillis() - start < 200)) {
            if (reader.ready()) {
                String line = reader.readLine();
                if (line == null) break;
                output.append(line).append("\n");
                start = System.currentTimeMillis(); // Reset timer if we are getting data
            } else {
                if (readUntilEnd) {
                   // If we must read until end, wait a bit for more data
                   try { Thread.sleep(50); } catch (Exception ignored) {}
                } else {
                   break; 
                }
            }
            if (readUntilEnd && !activeTerminals.containsKey(terminalId)) break; // Process died
        }
        return output.toString();
    }

    private static List<SemanticFileSearcher.SearchResult> filterSearchResults(
            List<SemanticFileSearcher.SearchResult> results,
            String includePattern,
            String searchInFolder) {
        if ((includePattern == null || includePattern.trim().isEmpty())
                && (searchInFolder == null || searchInFolder.trim().isEmpty())) {
            return results;
        }

        List<SemanticFileSearcher.SearchResult> filtered = new ArrayList<>();
        String normalizedFolder = normalizePathFilter(searchInFolder);
        Pattern includeRegex = compileGlobPattern(includePattern);
        for (SemanticFileSearcher.SearchResult result : results) {
            String normalizedPath = normalizePathFilter(result.filePath);
            if (normalizedFolder != null && !normalizedPath.startsWith(normalizedFolder)) {
                continue;
            }
            if (includeRegex != null && !includeRegex.matcher(normalizedPath).find()) {
                continue;
            }
            filtered.add(result);
        }
        return filtered;
    }

    private static String normalizePathFilter(String path) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        String normalized = path.replace('\\', '/').trim().toLowerCase();
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static Pattern compileGlobPattern(String pattern) {
        if (pattern == null || pattern.trim().isEmpty()) {
            return null;
        }
        String normalized = normalizePathFilter(pattern);
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c == '*') {
                regex.append(".*");
            } else if (c == '?') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }

    private static boolean commandLooksLikeFileMutation(String command) {
        if (command == null) return false;
        String c = command.trim();
        return c.contains(">") ||
                c.contains(">>") ||
                c.matches("(?s).*\\btee\\b.*") ||
                c.matches("(?s).*\\bsed\\s+-i\\b.*") ||
                c.matches("(?s).*\\brm\\b.*") ||
                c.matches("(?s).*\\bmv\\b.*") ||
                c.matches("(?s).*\\bcp\\b.*") ||
                c.matches("(?s).*\\btouch\\b.*") ||
                c.matches("(?s).*\\bmkdir\\b.*");
    }

    private static void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }

    // ============================================
    // TOOL REGISTRY FOR MCP
    // ============================================

    public static JSONArray getAllToolsAsMCP() {
        JSONArray array = new JSONArray();
        if (useVoidToolDescriptions()) {
            array.put(createToolMCP("read_file",
                    "Returns full contents of a given file.",
                    new String[]{"uri"}, new String[]{"start_line", "end_line", "page_number"}));
            array.put(createToolMCP("ls_dir",
                    "Lists all files and folders in the given URI.",
                    new String[]{}, new String[]{"uri", "page_number"}));
            array.put(createToolMCP("get_dir_tree",
                    "This is a very effective way to learn about the user's codebase. Returns a tree diagram of all the files and folders in the given folder.",
                    new String[]{}, new String[]{"uri"}));
            array.put(createToolMCP("search_pathnames_only",
                    "Returns all pathnames that match a given query (searches ONLY file names). You should use this when looking for a file with a specific name or path.",
                    new String[]{"query"}, new String[]{"include_pattern", "page_number"}));
            array.put(createToolMCP("search_for_files",
                    "Returns a list of file names whose content matches the given query. The query can be any substring or regex.",
                    new String[]{"query"}, new String[]{"search_in_folder", "is_regex", "page_number"}));
            array.put(createToolMCP("search_in_file",
                    "Returns an array of all the start line numbers where the content appears in the file.",
                    new String[]{"uri", "query"}, new String[]{"is_regex"}));
            array.put(createToolMCP("create_file_or_folder",
                    "Create a file or folder at the given path. To create a folder, the path MUST end with a trailing slash.",
                    new String[]{"uri"}, null));
            array.put(createToolMCP("delete_file_or_folder",
                    "Delete a file or folder at the given path.",
                    new String[]{"uri"}, new String[]{"is_recursive"}));
            array.put(createToolMCP("edit_file",
                    "Atomically edit a file using unique SEARCH/REPLACE blocks copied from a fresh read_file result. If an edit fails, read the file again before retrying.",
                    new String[]{"uri", "search_replace_blocks"}, null));
            array.put(createToolMCP("rewrite_file",
                    "Edits a file, deleting all the old contents and replacing them with your new contents. Use this tool if you want to edit a file you just created.",
                    new String[]{"uri", "new_content"}, null));
            array.put(createToolMCP("update_plan",
                    "Updates the model-maintained plan shown to the user. Send the full plan, one line per step: pending|running|done: title.",
                    new String[]{"plan"}, null));
            // Terminal/shell tool removed: the assistant no longer has shell access.
            return array;
        }

        // File tools
        array.put(createToolMCP("read_file",
            "Returns full contents of a given file.",
            new String[]{"uri"}, new String[]{"start_line", "end_line", "page_number"}));

        array.put(createToolMCP("ls_dir",
            "Lists all files and folders in the given URI.",
            new String[]{}, new String[]{"uri", "page_number"}));

        array.put(createToolMCP("get_dir_tree",
            "This is a very effective way to learn about the user's codebase. Returns a tree diagram of all the files and folders in the given folder.",
            new String[]{}, new String[]{"uri"}));

        // Search tools
        array.put(createToolMCP("search_pathnames_only",
            "Returns all pathnames that match a given query (searches ONLY file names). You should use this when looking for a file with a specific name or path.",
            new String[]{"query"}, new String[]{"include_pattern", "page_number"}));

        array.put(createToolMCP("search_for_files",
            "Returns a list of file names whose content matches the given query. The query can be any substring or regex.",
            new String[]{"query"}, new String[]{"search_in_folder", "is_regex", "page_number"}));

        array.put(createToolMCP("search_in_file",
            "Returns an array of all the start line numbers where the content appears in the file.",
            new String[]{"uri", "query"}, new String[]{"is_regex"}));

        // Edit tools
        array.put(createToolMCP("create_file_or_folder",
            "Create a file or folder at the given path. To create a folder, the path MUST end with a trailing slash.",
            new String[]{"uri"}, null));

        array.put(createToolMCP("delete_file_or_folder",
            "Delete a file or folder at the given path.",
            new String[]{"uri"}, new String[]{"is_recursive"}));

        array.put(createToolMCP("edit_file",
            "Atomically edit a file using unique SEARCH/REPLACE blocks copied from a fresh read_file result. If an edit fails, read the file again before retrying.",
            new String[]{"uri", "search_replace_blocks"}, null));

        array.put(createToolMCP("rewrite_file",
            "Edits a file, deleting all the old contents and replacing them with your new contents. Use this tool if you want to edit a file you just created.",
            new String[]{"uri", "new_content"}, null));
        array.put(createToolMCP("update_plan",
            "Updates the model-maintained plan shown to the user. Send the full plan, one line per step: pending|running|done: title.",
            new String[]{"plan"}, null));

        // Terminal/shell tool removed: the assistant no longer has shell access.

        return array;
    }

    private static JSONObject createToolMCP(String name, String description, String[] requiredParams, String[] optionalParams) {
        try {
            JSONObject toolObj = new JSONObject();
            JSONObject function = new JSONObject();
            
            function.put("name", name);
            function.put("description", description);
            
            JSONObject params = new JSONObject();
            params.put("type", "object");
            params.put("additionalProperties", false);
            
            JSONObject properties = new JSONObject();
            for (String param : requiredParams) {
                JSONObject prop = new JSONObject();
                prop.put("type", toolParamType(param));
                prop.put("description", toolParamDescription(name, param));
                properties.put(param, prop);
            }
            if (optionalParams != null) {
                for (String param : optionalParams) {
                    JSONObject prop = new JSONObject();
                    prop.put("type", toolParamType(param));
                    prop.put("description", toolParamDescription(name, param));
                    properties.put(param, prop);
                }
            }
            
            params.put("properties", properties);
            
            JSONArray required = new JSONArray();
            for (String param : requiredParams) {
                required.put(param);
            }
            params.put("required", required);
            
            function.put("parameters", params);
            toolObj.put("type", "function");
            toolObj.put("function", function);
            
            return toolObj;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static boolean useVoidToolDescriptions() {
        return true;
    }

    private static String toolParamDescription(String toolName, String paramName) {
        if ("uri".equals(paramName)) {
            if ("ls_dir".equals(toolName)) {
                return "Optional. The FULL path to the folder. Leave this as empty or \"\" to search all folders.";
            }
            if ("get_dir_tree".equals(toolName)) {
                return "Optional. Folder path inside the current project. Defaults to '.' (the current project root). Use '/' only as an alias for that project root, never for the device root.";
            }
            if ("create_file_or_folder".equals(toolName) || "delete_file_or_folder".equals(toolName)) {
                return "The FULL path to the file or folder.";
            }
            return "The FULL path to the file.";
        }
        if ("start_line".equals(paramName)) {
            return "Optional. Do NOT fill this field in unless you were specifically given exact line numbers to search. Defaults to the beginning of the file.";
        }
        if ("end_line".equals(paramName)) {
            return "Optional. Do NOT fill this field in unless you were specifically given exact line numbers to search. Defaults to the end of the file.";
        }
        if ("page_number".equals(paramName)) {
            return "Optional. The page number of the result. Default is 1.";
        }
        if ("timeout_seconds".equals(paramName)) {
            return "Optional. Max seconds to wait for the command (default 60, max 300). Use higher values for builds.";
        }
        if ("plan".equals(paramName)) {
            return "The full plan, one step per line: 'pending|running|done: step title'.";
        }
        if ("query".equals(paramName)) {
            return "Your query for the search.";
        }
        if ("include_pattern".equals(paramName)) {
            return "Optional. Only fill this in if you need to limit your search because there were too many results.";
        }
        if ("search_in_folder".equals(paramName)) {
            return "Optional. Leave as blank by default. ONLY fill this in if your previous search with the same query was truncated. Searches descendants of this folder only.";
        }
        if ("is_regex".equals(paramName)) {
            return "Optional. Default is false. Whether the query is a regex.";
        }
        if ("is_recursive".equals(paramName)) {
            return "Optional. Return true to delete recursively.";
        }
        if ("search_replace_blocks".equals(paramName)) {
            return PromptConstants.SEARCH_REPLACE_BLOCKS_TOOL_DESCRIPTION;
        }
        if ("new_content".equals(paramName)) {
            return "The new contents of the file. Must be a string.";
        }
        if ("command".equals(paramName)) {
            return "The terminal command to run.";
        }
        if ("cwd".equals(paramName)) {
            return "Optional. The directory in which to run the command. Defaults to the first workspace folder.";
        }
        if ("persistent_terminal_id".equals(paramName)) {
            return "The ID of the terminal created using open_persistent_terminal.";
        }
        return "";
    }

    private static String toolParamType(String paramName) {
        if ("start_line".equals(paramName) || "end_line".equals(paramName)
                || "page_number".equals(paramName) || "timeout_seconds".equals(paramName)) {
            return "integer";
        }
        if ("is_regex".equals(paramName) || "is_recursive".equals(paramName)) {
            return "boolean";
        }
        return "string";
    }

    // ============================================
    // MAIN TOOL EXECUTOR
    // ============================================

    public static String executeTool(String scId, String toolName, JSONObject args) {
        long startedAt = android.os.SystemClock.elapsedRealtime();
        ChatToolLog.d("tool", "▶ " + toolName + " sc=" + scId
                + " args=" + ChatToolLog.preview(args == null ? "{}" : args.toString(), 300));
        try {
            String out = executeToolInner(scId, toolName, args);
            long ms = android.os.SystemClock.elapsedRealtime() - startedAt;
            boolean looksError = out != null && (out.startsWith("Erro") || out.startsWith("Error")
                    || out.startsWith("Cannot") || out.startsWith("File not found") || out.startsWith("Blocked"));
            ChatToolLog.d("tool", (looksError ? "✖ " : "✔ ") + toolName
                    + " (" + ms + "ms) -> " + ChatToolLog.preview(out, 200));
            return out;
        } catch (Exception e) {
            ChatToolLog.e("tool", "crash in " + toolName, e);
            return "Erro ao executar ferramenta " + toolName + ": " + e.getMessage();
        }
    }

    private static String executeToolInner(String scId, String toolName, JSONObject args) {
        try {
            ToolCallResult result;

            switch (toolName) {
                case "read_file":
                    result = readFile(scId, 
                        args.opt("uri") != null ? args.opt("uri") : args.opt("path"),
                        args.opt("start_line") != null ? args.opt("start_line") : args.opt("startLine"),
                        args.opt("end_line") != null ? args.opt("end_line") : args.opt("endLine"),
                        args.opt("page_number") != null ? args.opt("page_number") : args.opt("pageNumber"));
                    break;

                case "read_files":
                    result = readFiles(scId, args.opt("uris") != null ? args.opt("uris") : args.opt("paths"));
                    break;
                    
                case "list_directory":
                case "ls_dir":
                    result = lsDir(scId,
                        args.opt("uri") != null ? args.opt("uri") : args.opt("path"),
                        args.opt("page_number") != null ? args.opt("page_number") : args.opt("pageNumber"));
                    break;
                    
                case "get_workspace_tree":
                case "get_dir_tree":
                    result = getDirTree(scId, args.opt("uri") != null ? args.opt("uri") : args.opt("path"));
                    break;
                    
                case "search_pathnames_only":
                    Object includePattern = args.opt("include_pattern") != null ? args.opt("include_pattern") : args.opt("includePattern");
                    if (includePattern == null) {
                        includePattern = args.opt("search_in_folder"); // Fallback
                    }
                    result = searchPathnamesOnly(scId,
                        args.opt("query"),
                        includePattern,
                        args.opt("page_number") != null ? args.opt("page_number") : args.opt("pageNumber"));
                    break;
                    
                case "search_files":
                case "search_for_files":
                    result = searchForFiles(scId,
                        args.opt("query"),
                        args.opt("is_regex") != null ? args.opt("is_regex") : args.opt("isRegex"),
                        args.opt("search_in_folder") != null ? args.opt("search_in_folder") : args.opt("searchInFolder"),
                        args.opt("page_number") != null ? args.opt("page_number") : args.opt("pageNumber"));
                    break;
                    
                case "search_text":
                case "search_in_file":
                    result = searchInFile(scId,
                        args.opt("uri") != null ? args.opt("uri") : args.opt("path"),
                        args.opt("query"),
                        args.opt("is_regex") != null ? args.opt("is_regex") : args.opt("isRegex"));
                    break;
                    
                case "write_file":
                case "rewrite_file":
                    result = rewriteFile(scId,
                        args.opt("uri") != null ? args.opt("uri") : (args.opt("path") != null ? args.opt("path") : args.opt("file_path")),
                        args.opt("new_content") != null ? args.opt("new_content") : (args.opt("newContent") != null ? args.opt("newContent") : args.opt("content")));
                    break;
                    
                case "patch_file":
                case "edit_file":
                    result = editFile(scId,
                        args.opt("uri") != null ? args.opt("uri") : (args.opt("path") != null ? args.opt("path") : args.opt("file_path")),
                        args.opt("search_replace_blocks") != null ? args.opt("search_replace_blocks") : args.opt("searchReplaceBlocks"));
                    break;
                    
                case "create_file":
                case "create_directory":
                case "create_file_or_folder":
                    String createTarget = args.optString("uri", args.optString("path", args.optString("file_path", "")));
                    if ("create_directory".equals(toolName) && !createTarget.endsWith("/")) {
                        createTarget = createTarget + "/";
                    }
                    result = createFileOrFolder(scId, createTarget);
                    break;
                    
                case "delete_file":
                case "delete_directory":
                case "delete_file_or_folder":
                    result = deleteFileOrFolder(scId,
                        args.opt("uri") != null ? args.opt("uri") : args.opt("path"),
                        args.opt("is_recursive") != null ? args.opt("is_recursive") : args.opt("isRecursive"));
                    break;

                case "move_file":
                    result = moveFile(scId,
                        args.optString("source", args.optString("source_path", args.optString("from", ""))),
                        args.optString("destination", args.optString("destination_path", args.optString("to", ""))));
                    break;

                case "rename_file":
                    result = renameFile(scId,
                        args.optString("uri", args.optString("path", "")),
                        args.optString("new_name", args.optString("newName", "")));
                    break;

                case "copy_file":
                    result = copyFile(scId,
                        args.optString("source", args.optString("source_path", args.optString("from", ""))),
                        args.optString("destination", args.optString("destination_path", args.optString("to", ""))));
                    break;

                case "get_file_info":
                    result = getFileInfo(scId, args.optString("uri", args.optString("path", "")));
                    break;

                case "update_plan":
                    return updatePlan(scId, args.opt("plan"));
                    
                case "run_command":
                    result = runCommand(scId,
                        args.opt("command"),
                        args.opt("cwd"),
                        args.opt("terminal_id") != null ? args.opt("terminal_id") : args.opt("terminalId"),
                        args.opt("timeout_seconds") != null ? args.opt("timeout_seconds") : args.opt("timeoutSeconds"));
                    break;
                    
                default:
                    if ("get_file".equals(toolName)) {
                        return SketchApplication.getContext().getString(R.string.chat_tool_get_file_alias_error);
                    }
                    return SketchApplication.getContext().getString(R.string.chat_tool_unknown_error, toolName);
            }
            
            String technicalResult = result.result;
            
            // If the result is an error message (doesn't look like JSON), return it as is
            if (technicalResult.startsWith("Error") || technicalResult.startsWith("Erro") || technicalResult.startsWith("Cannot") || technicalResult.startsWith("File not found")) {
                return technicalResult;
            }

            return getStringOfResult(toolName, args, result);
            
        } catch (Exception e) {
            return "Erro ao executar ferramenta " + toolName + ": " + e.getMessage();
        }
    }

    private static String getStringOfResult(String toolName, JSONObject args, ToolCallResult result) {
        try {
            JSONObject resObj = new JSONObject(result.result);
            
            switch (toolName) {
                case "read_file": {
                    String fsPath = args.optString("uri");
                    String fileContents = resObj.optString("fileContents");
                    boolean hasNextPage = resObj.optBoolean("hasNextPage");
                    int totalNumLines = resObj.optInt("totalNumLines");
                    int totalFileLen = resObj.optInt("totalFileLen");
                    
                    String nextPageStr = hasNextPage ? "\n\n(more on next page...)" : "";
                    String truncationInfo = hasNextPage ? 
                        String.format("\nMore info because truncated: this file has %d lines, or %d characters.", totalNumLines, totalFileLen) : "";
                    
                    return String.format("%s\n```\n%s\n```%s%s", fsPath, fileContents, nextPageStr, truncationInfo);
                }

                case "ls_dir": {
                    return stringifyDirectoryTree1Deep(args, resObj);
                }

                case "get_dir_tree": {
                    return resObj.optString("str");
                }

                case "search_pathnames_only":
                case "search_for_files": {
                    JSONArray uris = resObj.optJSONArray("uris");
                    StringBuilder sb = new StringBuilder();
                    if (uris != null) {
                        for (int i = 0; i < uris.length(); i++) {
                            sb.append(uris.optString(i)).append("\n");
                        }
                    }
                    if (resObj.optBoolean("hasNextPage")) {
                        sb.append("\n(more on next page...)");
                    }
                    return sb.toString().trim();
                }

                case "search_in_file": {
                    JSONArray lines = resObj.optJSONArray("lines");
                    if (lines == null || lines.length() == 0) return "No matches found.";
                    
                    String uri = args.optString("uri");
                    String content = readFileDirect("", uri); // scId ignored if absolute
                    String[] allLines = content != null ? content.split("\n", -1) : new String[0];
                    
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < lines.length(); i++) {
                        int lineNum = lines.optInt(i);
                        String lineContent = (lineNum > 0 && lineNum <= allLines.length) ? allLines[lineNum - 1] : "";
                        sb.append(String.format("Line %d:\n```\n%s\n```\n\n", lineNum, lineContent));
                    }
                    return sb.toString().trim();
                }

                case "create_file_or_folder":
                    return String.format("URI %s successfully created.", args.optString("uri"));

                case "delete_file_or_folder":
                    return String.format("URI %s successfully deleted.", args.optString("uri"));

                case "edit_file":
                case "rewrite_file": {
                    String uri = args.optString("uri");
                    JSONArray errors = resObj.optJSONArray("lintErrors");
                    String lintInfo = "";
                    
                    if (errors != null && errors.length() > 0) {
                        lintInfo = "\n\nLint errors found after change:\n" + stringifyLintErrors(errors) + 
                                 "\nIf this is related to a change made while calling this tool, you might want to fix the error.";
                    } else {
                        lintInfo = " No lint errors found.";
                    }
                    
                    return String.format("Change successfully made to %s.%s", uri, lintInfo);
                }

                case "run_command":
                case "run_persistent_command": {
                    String output = resObj.optString("result");
                    JSONObject resolveReason = resObj.optJSONObject("resolveReason");
                    String type = resolveReason != null ? resolveReason.optString("type") : "done";
                    
                    if ("done".equals(type)) {
                        int exitCode = resolveReason.optInt("exitCode", 0);
                        return String.format("%s\n(exit code %d)", output, exitCode);
                    } else if ("timeout".equals(type)) {
                        if ("run_persistent_command".equals(toolName)) {
                            String termId = args.optString("persistent_terminal_id");
                            return String.format("%s\nTerminal command is running in terminal %s. The given outputs are the results after %d seconds.", 
                                output, termId, MAX_TERMINAL_BG_COMMAND_TIME_SECONDS);
                        } else {
                            return String.format("%s\nTerminal command ran, but was automatically killed by Void after %ds of inactivity and did not finish successfully. To try with more time, open a persistent terminal and run the command there.", 
                                output, MAX_TERMINAL_INACTIVE_TIME_SECONDS);
                        }
                    }
                    return output;
                }

                case "open_persistent_terminal":
                    return String.format("Successfully created persistent terminal. persistentTerminalId=\"%s\"", resObj.optString("persistentTerminalId"));

                case "kill_persistent_terminal":
                    return String.format("Successfully closed terminal \"%s\".", args.optString("persistent_terminal_id"));

                default:
                    return result.result;
            }
        } catch (Exception e) {
            return result.result; // Fallback to raw result if parsing fails
        }
    }

    private static String stringifyDirectoryTree1Deep(JSONObject args, JSONObject result) {
        JSONArray children = result.optJSONArray("children");
        if (children == null) return "[]";
        
        StringBuilder sb = new StringBuilder();
        String uri = args.optString("uri", "");
        sb.append(uri.isEmpty() ? "Root directory:" : uri + ":").append("\n");
        
        for (int i = 0; i < children.length(); i++) {
            JSONObject child = children.optJSONObject(i);
            String name = child.optString("name");
            boolean isDir = child.optBoolean("isDirectory");
            sb.append(isDir ? "  / " : "    ").append(name).append("\n");
        }
        
        if (result.optBoolean("hasNextPage")) {
            int remaining = result.optInt("itemsRemaining", 0);
            sb.append("\n... and ").append(remaining).append(" more items (use page_number to see more)");
        }
        
        return sb.toString().trim();
    }

    private static String stringifyLintErrors(JSONArray lintErrors) {
        if (lintErrors == null || lintErrors.length() == 0) return "No lint errors found.";
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(lintErrors.length(), 100); i++) {
            JSONObject err = lintErrors.optJSONObject(i);
            sb.append(String.format("Error %d:\nLines Affected: %d-%d\nError message:%s\n\n", 
                i + 1, 
                err.optInt("startLineNumber"), 
                err.optInt("endLineNumber"), 
                err.optString("message")));
        }
        return sb.toString().trim();
    }

    private static String readFileDirect(String scId, String uriStr) {
        try {
            if (com.saaspaymentsolutions.axion.workspace.WorkspacePath.hasParentTraversal(uriStr)) {
                return null;
            }
            String norm = com.saaspaymentsolutions.axion.workspace.WorkspacePath.normalize(uriStr);
            com.saaspaymentsolutions.axion.workspace.WorkspaceFileSystem fs = com.saaspaymentsolutions.axion.workspace.WorkspaceManager.getActiveFileSystem();
            if (fs != null && fs.exists(norm) && !fs.isDirectory(norm)) {
                return fs.readText(norm);
            }
            ProjectPathResolver.ResolvedPath resolved = ProjectPathResolver.resolveForRead(scId, uriStr);
            if (resolved == null) return null;
            File file = resolved.getFile();
            if (!file.exists() || file.isDirectory()) return null;
            return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean writeFileDirect(String scId, String uriStr, String content) {
        try {
            if (com.saaspaymentsolutions.axion.workspace.WorkspacePath.hasParentTraversal(uriStr)) {
                return false;
            }
            String norm = com.saaspaymentsolutions.axion.workspace.WorkspacePath.normalize(uriStr);
            com.saaspaymentsolutions.axion.workspace.WorkspaceFileSystem fs = com.saaspaymentsolutions.axion.workspace.WorkspaceManager.getActiveFileSystem();
            if (fs != null) {
                fs.writeText(norm, content);
                return true;
            }
            ProjectPathResolver.ResolvedPath resolved = ProjectPathResolver.resolveForWrite(scId, uriStr);
            if (resolved == null) return false;
            File file = resolved.getFile();
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            java.nio.file.Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
