package com.saaspaymentsolutions.axion.agentsdk;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

/**
 * Real persistence for the {@link TaskMemory} (item 7 of the context-model
 * migration): one JSON file per conversation under the Axion-internal
 * {@code tasks/} directory — the SAME storage contract used by the
 * {@code FileChangeTracker} ({@code .axion}-style internal state, never the
 * user's workspace, never SAF).
 *
 * <pre>
 * RunContext.taskMemory()
 *     ↓
 * TaskMemoryStore.save(scId, memory)     ← end of run
 *     ↓
 * TaskMemoryStore.load(scId)             ← next run / after process death
 * </pre>
 *
 * <p>This is what makes the task memory actually persistent: the agent keeps
 * knowing the objective, relevant files and progress across runs and process
 * death, even when the chat history was compacted.</p>
 */
public final class TaskMemoryStore {

    /**
     * Pseudo-conversation id for the host loop's single live run: one run
     * per process (the UI blocks concurrent runs), so the legacy path keeps
     * its durable task state under this key.
     */
    public static final String LEGACY_RUN_KEY = "_RUN";

    private static final Object LOCK = new Object();
    private static final Map<String, Long> LAST_WRITE_BY_SC = new ConcurrentHashMap<>();

    private TaskMemoryStore() {
    }

    /** Persists {@code memory} for {@code scId} (best-effort, never throws). */
    public static void save(String scId, @Nullable TaskMemory memory) {
        if (scId == null || scId.isEmpty() || memory == null) {
            return;
        }
        synchronized (LOCK) {
            try {
                File file = fileFor(scId);
                if (file == null) {
                    return;
                }
                JSONObject json = memory.toJson();
                json.put("savedAt", System.currentTimeMillis());
                File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(json.toString().getBytes(StandardCharsets.UTF_8));
                }
                if (!tmp.renameTo(file) && !file.exists()) {
                    return; // best-effort; leave the previous state intact
                }
                LAST_WRITE_BY_SC.put(scId, System.currentTimeMillis());
            } catch (Exception ignored) {
                // Persistence is best-effort: a failed save must never fail a run.
            }
        }
    }

    /** Restores the persisted memory for {@code scId}, or {@code null}. */
    @Nullable
    public static TaskMemory load(String scId) {
        if (scId == null || scId.isEmpty()) {
            return null;
        }
        synchronized (LOCK) {
            try {
                File file = fileFor(scId);
                if (file == null || !file.exists()) {
                    return null;
                }
                byte[] bytes = new byte[(int) file.length()];
                try (FileInputStream in = new FileInputStream(file)) {
                    int total = 0;
                    while (total < bytes.length) {
                        int read = in.read(bytes, total, bytes.length - total);
                        if (read < 0) {
                            break;
                        }
                        total += read;
                    }
                }
                String text = new String(bytes, StandardCharsets.UTF_8).trim();
                if (text.isEmpty()) {
                    return null;
                }
                return TaskMemory.fromJson(new JSONObject(text));
            } catch (Exception e) {
                return null;
            }
        }
    }

    /**
     * Restores the persisted memory into {@code memory} when the on-disk
     * state is newer than {@code memory}'s creation — the compaction-proof
     * handoff: the next run starts where the previous one stopped.
     */
    public static void restoreInto(String scId, @Nullable TaskMemory memory) {
        if (memory == null) {
            return;
        }
        Long lastWrite = LAST_WRITE_BY_SC.get(scId);
        if (lastWrite != null && lastWrite < memory.startedAt()) {
            return; // in-memory state is newer: nothing to restore
        }
        TaskMemory persisted = load(scId);
        if (persisted != null) {
            memory.mergeFrom(persisted);
        }
    }

    /** Clears the persisted memory for {@code scId} (conversation reset). */
    public static void clear(String scId) {
        if (scId == null || scId.isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            File file = fileFor(scId);
            if (file != null && file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
            LAST_WRITE_BY_SC.remove(scId);
        }
    }

    @Nullable
    private static File fileFor(String scId) {
        try {
            Context context = com.saaspaymentsolutions.axion.SketchApplication.getContext();
            File dir;
            if (context != null) {
                dir = new File(context.getFilesDir(), "tasks");
            } else {
                // No Android host (JVM evals): temp-dir fallback so the
                // persistence contract stays observable in unit tests.
                dir = new File(System.getProperty("java.io.tmpdir"), "axion-tasks");
            }
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            return new File(dir, scId + ".json");
        } catch (RuntimeException e) {
            return null;
        }
    }
}
