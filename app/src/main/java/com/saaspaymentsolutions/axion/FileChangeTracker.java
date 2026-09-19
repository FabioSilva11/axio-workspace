package com.saaspaymentsolutions.axion;

import android.util.Log;

import com.saaspaymentsolutions.axion.workspace.WorkspaceFileSystem;
import com.saaspaymentsolutions.axion.workspace.WorkspaceManager;
import com.saaspaymentsolutions.axion.workspace.WorkspacePath;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records changes that the agent's tools have <b>already applied</b> to the
 * workspace, for the Diff page.
 *
 * <p>This is a history/audit log, not a commit buffer: by the time a change
 * is tracked here the file on disk is already in its {@code afterContent}
 * state. {@code acceptChange()} only clears the review entry (it never
 * writes anything); {@code revertChange()} undoes an applied change through
 * the <b>same active {@link WorkspaceFileSystem}</b> that performed the
 * original mutation.</p>
 *
 * <p>The old implementation kept everything in static memory, so Android process
 * recreation made all diffs disappear. Changes are now persisted inside each
 * project at {@code .axion/file_changes.json} and loaded lazily.</p>
 */
public class FileChangeTracker {
    private static final String TAG = "FileChangeTracker";
    private static final String STATE_DIR = ".axion";
    private static final String STATE_FILE = "file_changes.json";

    private static final Map<String, List<FileChange>> changesByProject = new ConcurrentHashMap<>();
    private static final Map<String, Object> projectLocks = new ConcurrentHashMap<>();
    private static final Set<String> loadedProjects = ConcurrentHashMap.newKeySet();

    public static class FileChange {
        public String filePath;
        public String beforeContent;
        public String afterContent;
        public long timestamp;
        public boolean existedBefore;

        public FileChange(String filePath, String beforeContent, String afterContent,
                          long timestamp, boolean existedBefore) {
            this.filePath = filePath;
            this.beforeContent = beforeContent;
            this.afterContent = afterContent;
            this.timestamp = timestamp;
            this.existedBefore = existedBefore;
        }
    }

    public static void trackChange(String scId, String filePath, String before, String after) {
        // A zero-byte file still existed before the edit. Treat only null as
        // "unknown/nonexistent"; creation call sites pass existedBefore=false explicitly.
        trackChange(scId, filePath, before, after, before != null);
    }

    public static void trackChange(String scId, String filePath, String before, String after,
                                   boolean existedBefore) {
        if (!valid(scId) || !valid(filePath)) return;
        Object lock = lockFor(scId);
        synchronized (lock) {
            ensureLoadedLocked(scId);
            FileChange change = new FileChange(filePath, before, after,
                    System.currentTimeMillis(), existedBefore);
            changesByProject.computeIfAbsent(scId, key -> new ArrayList<>()).add(change);
            persistLocked(scId);
        }
    }

    /**
     * Marks an already-applied change as reviewed and drops it from the diff
     * review list. Never touches the filesystem: the tool that tracked this
     * change has already written {@code afterContent} to the workspace.
     *
     * @return true when a tracked change existed for {@code filePath}.
     */
    public static boolean acceptChange(String scId, String filePath) {
        if (!valid(scId) || !valid(filePath)) return false;
        Object lock = lockFor(scId);
        synchronized (lock) {
            ensureLoadedLocked(scId);
            List<FileChange> changes = changesByProject.get(scId);
            if (changes == null) return false;
            FileChange change = findLatestChange(changes, filePath);
            if (change == null) return false;
            removeTrackedChangesLocked(scId, filePath);
            persistLocked(scId);
            return true;
        }
    }

    /**
     * Legacy alias. The mutation is applied the moment the tool runs, so
     * "accepting" a change can no longer apply anything.
     *
     * @deprecated use {@link #acceptChange(String, String)}.
     */
    @Deprecated
    public static boolean applyChange(String scId, String filePath) {
        return acceptChange(scId, filePath);
    }

    /**
     * Restores the workspace to the state before the change was applied.
     * Rewrites the previous content through the <b>active
     * {@link WorkspaceFileSystem}</b> — the same backend that performed the
     * original mutation — never through {@code ProjectPathResolver}.
     *
     * @return true when the revert was actually applied to the filesystem.
     */
    public static boolean rejectChange(String scId, String filePath) {
        if (!valid(scId) || !valid(filePath)) return false;
        Object lock = lockFor(scId);
        synchronized (lock) {
            ensureLoadedLocked(scId);
            List<FileChange> changes = changesByProject.get(scId);
            if (changes == null) return false;
            FileChange change = findOriginalChange(changes, filePath);
            if (change == null) return false;
            try {
                boolean restored = restorePreviousState(change);
                if (restored) {
                    removeTrackedChangesLocked(scId, filePath);
                    persistLocked(scId);
                }
                return restored;
            } catch (Exception error) {
                Log.e(TAG, "Could not revert diff for " + filePath, error);
                return false;
            }
        }
    }

    /**
     * Restores the pre-change state through the active workspace filesystem.
     * Must only run while holding the project lock.
     */
    private static boolean restorePreviousState(FileChange change) {
        WorkspaceFileSystem fs = WorkspaceManager.getActiveFileSystem();
        if (fs == null) {
            Log.e(TAG, "Cannot revert " + change.filePath
                    + ": no active workspace filesystem.");
            return false;
        }
        String path;
        try {
            path = WorkspacePath.normalize(change.filePath);
        } catch (Exception error) {
            Log.e(TAG, "Cannot revert unsafe path " + change.filePath, error);
            return false;
        }
        if (change.existedBefore) {
            String previous = change.beforeContent == null ? "" : change.beforeContent;
            fs.writeText(path, previous);
            return fs.exists(path) && !fs.isDirectory(path);
        }
        if (!fs.exists(path)) {
            return true; // already gone: revert is idempotent
        }
        if (!fs.delete(path) || fs.exists(path)) {
            Log.e(TAG, "Could not revert (delete) " + path + " through the active workspace.");
            return false;
        }
        return true;
    }

    public static Map<String, FileChange> getAllRecentChanges(String scId) {
        Map<String, FileChange> latestChanges = new HashMap<>();
        if (!valid(scId)) return latestChanges;
        Object lock = lockFor(scId);
        synchronized (lock) {
            ensureLoadedLocked(scId);
            List<FileChange> changes = changesByProject.get(scId);
            if (changes != null) {
                for (FileChange change : changes) {
                    latestChanges.put(change.filePath, change);
                }
            }
        }
        return latestChanges;
    }

    public static Map<String, FileChange> getAllRecentChanges() {
        Map<String, FileChange> latestChanges = new HashMap<>();
        Set<String> projectIds = new HashSet<>(changesByProject.keySet());
        try {
            for (HashMap<String, Object> project : ProjectManager.a()) {
                String scId = MapUtils.c(project, "sc_id");
                if (valid(scId)) projectIds.add(scId);
            }
        } catch (Exception error) {
            Log.w(TAG, "Could not enumerate projects while loading diffs", error);
        }
        for (String scId : projectIds) {
            latestChanges.putAll(getAllRecentChanges(scId));
        }
        return latestChanges;
    }

    public static void clearChanges(String scId) {
        if (!valid(scId)) return;
        Object lock = lockFor(scId);
        synchronized (lock) {
            changesByProject.remove(scId);
            loadedProjects.add(scId);
            deleteStateFiles(scId);
        }
    }

    /** Forces a disk reload, useful after a project has been restored externally. */
    public static void reload(String scId) {
        if (!valid(scId)) return;
        Object lock = lockFor(scId);
        synchronized (lock) {
            changesByProject.remove(scId);
            loadedProjects.remove(scId);
            ensureLoadedLocked(scId);
        }
    }

    private static FileChange findLatestChange(List<FileChange> changes, String filePath) {
        for (int i = changes.size() - 1; i >= 0; i--) {
            if (filePath.equals(changes.get(i).filePath)) return changes.get(i);
        }
        return null;
    }

    private static FileChange findOriginalChange(List<FileChange> changes, String filePath) {
        for (FileChange change : changes) {
            if (filePath.equals(change.filePath)) return change;
        }
        return null;
    }

    private static void removeTrackedChangesLocked(String scId, String filePath) {
        List<FileChange> changes = changesByProject.get(scId);
        if (changes == null) return;
        changes.removeIf(change -> filePath.equals(change.filePath));
        if (changes.isEmpty()) changesByProject.remove(scId);
    }

    private static Object lockFor(String scId) {
        return projectLocks.computeIfAbsent(scId == null ? "" : scId, key -> new Object());
    }

    private static void ensureLoadedLocked(String scId) {
        if (!loadedProjects.add(scId)) return;
        List<FileChange> loaded = readState(stateFile(scId));
        if (loaded == null) loaded = readState(backupFile(scId));
        if (loaded != null && !loaded.isEmpty()) {
            changesByProject.put(scId, loaded);
        }
    }

    private static List<FileChange> readState(File file) {
        if (file == null || !file.isFile()) return null;
        try {
            StringBuilder text = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) text.append(line).append('\n');
            }
            JSONObject root = new JSONObject(text.toString());
            JSONArray array = root.optJSONArray("changes");
            List<FileChange> result = new ArrayList<>();
            for (int i = 0; array != null && i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String path = item.optString("filePath", "");
                if (!valid(path)) continue;
                result.add(new FileChange(
                        path,
                        item.isNull("beforeContent") ? null : item.optString("beforeContent", ""),
                        item.isNull("afterContent") ? null : item.optString("afterContent", ""),
                        item.optLong("timestamp", 0L),
                        item.optBoolean("existedBefore", false)
                ));
            }
            return result;
        } catch (Exception error) {
            Log.w(TAG, "Invalid persisted diff state: " + file, error);
            return null;
        }
    }

    private static void persistLocked(String scId) {
        File target = stateFile(scId);
        if (target == null) {
            Log.e(TAG, "Diff state unavailable: project directory cannot be resolved.");
            return;
        }
        List<FileChange> changes = changesByProject.get(scId);
        if (changes == null || changes.isEmpty()) {
            deleteStateFiles(scId);
            return;
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Log.e(TAG, "Could not create diff state directory: " + parent);
            return;
        }
        JSONObject root = new JSONObject();
        JSONArray array = new JSONArray();
        try {
            root.put("version", 1);
            root.put("scId", scId);
            for (FileChange change : changes) {
                JSONObject item = new JSONObject();
                item.put("filePath", change.filePath);
                item.put("beforeContent", change.beforeContent == null ? JSONObject.NULL : change.beforeContent);
                item.put("afterContent", change.afterContent == null ? JSONObject.NULL : change.afterContent);
                item.put("timestamp", change.timestamp);
                item.put("existedBefore", change.existedBefore);
                array.put(item);
            }
            root.put("changes", array);
        } catch (Exception error) {
            Log.e(TAG, "Could not serialize diff state", error);
            return;
        }

        File temp = new File(parent, STATE_FILE + ".tmp");
        File backup = backupFile(scId);
        try {
            if (target.exists()) copy(target, backup);
            try (FileOutputStream output = new FileOutputStream(temp, false);
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                         output, StandardCharsets.UTF_8))) {
                writer.write(root.toString());
                writer.flush();
                output.getFD().sync();
            }
            if (target.exists() && !target.delete()) {
                throw new IllegalStateException("Could not replace previous diff state");
            }
            if (!temp.renameTo(target)) {
                throw new IllegalStateException("Could not finalize diff state");
            }
        } catch (Exception error) {
            Log.e(TAG, "Could not persist diff state", error);
            if (temp.exists()) temp.delete();
        }
    }

    /** Null when the project directory cannot be resolved (e.g. storage unmounted). */
    private static File stateFile(String scId) {
        try {
            return new File(new File(ProjectManager.getProjectDir(scId), STATE_DIR), STATE_FILE);
        } catch (Exception error) {
            Log.w(TAG, "Could not resolve diff state file", error);
            return null;
        }
    }

    private static File backupFile(String scId) {
        File target = stateFile(scId);
        return target == null ? null : new File(target.getParentFile(), STATE_FILE + ".bak");
    }

    private static void deleteStateFiles(String scId) {
        // Do not resolve through getProjectDir() here: after project deletion it
        // would create a new empty Android project directory for a former web ID.
        // Storage roots may be unavailable (unmounted storage); cleanup must
        // never crash its callers.
        File[] projectRoots;
        try {
            projectRoots = new File[]{
                    new File(ProjectManager.getWebProjectsRoot(), scId),
                    new File(ProjectManager.getAndroidStudioProjectsRoot(), scId)
            };
        } catch (Exception error) {
            Log.w(TAG, "Could not resolve diff state locations for cleanup", error);
            return;
        }
        for (File projectRoot : projectRoots) {
            File parent = new File(projectRoot, STATE_DIR);
            File state = new File(parent, STATE_FILE);
            File backup = new File(parent, STATE_FILE + ".bak");
            File temp = new File(parent, STATE_FILE + ".tmp");
            if (state.exists()) state.delete();
            if (backup.exists()) backup.delete();
            if (temp.exists()) temp.delete();
            File[] leftovers = parent.listFiles();
            if (parent.isDirectory() && (leftovers == null || leftovers.length == 0)) parent.delete();
        }
    }

    private static void copy(File source, File target) throws Exception {
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            output.getFD().sync();
        }
    }

    private static boolean valid(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
