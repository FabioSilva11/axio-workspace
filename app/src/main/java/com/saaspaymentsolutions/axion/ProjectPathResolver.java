package com.saaspaymentsolutions.axion;

import android.os.Environment;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Central path resolution for tool calling.
 *
 * <p>Security model: tool-supplied paths are only honoured when they resolve
 * inside an authorized root — the active workspace folder (when one is open),
 * the chat's project folder, and the app's private downloads directory.
 * Previously any absolute path was accepted verbatim, so a model could read
 * or rewrite arbitrary device files (contacts databases, other apps' external
 * storage, the shared Downloads folder) even though the UI implies a
 * sandboxed workspace. Relative paths and project-root aliases still behave
 * exactly as before.</p>
 */
public class ProjectPathResolver {

    /** Base directories that tools may never escape from or write over. */
    private static final String[] BLOCKED_BASE_DIRS = {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    .getAbsolutePath(),
    };

    public static class ResolvedPath {
        private final File file;
        private final boolean authorized;

        public ResolvedPath(File file) {
            this(file, true);
        }

        public ResolvedPath(File file, boolean authorized) {
            this.file = file;
            this.authorized = authorized;
        }

        public File getFile() {
            return file;
        }

        /** False when the path resolved outside every authorized root. */
        public boolean isAuthorized() {
            return authorized;
        }
    }

    private static ResolvedPath resolve(String scId, String path, boolean forWrite) {
        if (path == null || path.isEmpty()) {
            return new ResolvedPath(new File(""), false);
        }
        File requested = new File(path);
        File projectDir = new File(ProjectManager.getProjectDir(scId));
        File base = requested.isAbsolute() ? null : projectDir;
        if (base == null) {
            base = findAuthorizedRoot(requested, projectDir);
        }
        if (base == null) {
            // Absolute path outside every authorized root: refuse instead of
            // resolving verbatim.
            return new ResolvedPath(requested, false);
        }
        File resolved = safeResolve(base, requested);
        if (resolved == null) {
            return new ResolvedPath(requested, false);
        }
        boolean authorized = isInsideRoot(resolved, base);
        if (authorized && forWrite && isBlockedBase(resolved)) {
            authorized = false;
        }
        return new ResolvedPath(resolved, authorized);
    }

    public static ResolvedPath resolveForRead(String scId, String path) {
        return resolve(scId, path, false);
    }

    public static ResolvedPath resolveForWrite(String scId, String path) {
        return resolve(scId, path, true);
    }

    /** Finds the authorized root that contains an absolute request, if any. */
    private static File findAuthorizedRoot(File requested, File projectDir) {
        File workspaceRoot = activeWorkspaceRoot();
        if (workspaceRoot != null && isInsideRoot(requested, workspaceRoot)) {
            return workspaceRoot;
        }
        if (isInsideRoot(requested, projectDir)) {
            return projectDir;
        }
        return null;
    }

    private static File activeWorkspaceRoot() {
        try {
            com.saaspaymentsolutions.axion.workspace.Workspace workspace =
                    com.saaspaymentsolutions.axion.workspace.WorkspaceManager.getActiveWorkspace();
            String rootUri = workspace == null ? null : workspace.getRootUri();
            if (rootUri == null || rootUri.isEmpty() || rootUri.startsWith("content://")) {
                return null;
            }
            File folder = new File(rootUri);
            return folder.isDirectory() ? folder : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Resolves {@code requested} against {@code base} and collapses {@code ..}
     * segments so the containment check below sees the real target.
     */
    private static File safeResolve(File base, File requested) {
        try {
            if (requested.isAbsolute()) {
                return requested.getCanonicalFile();
            }
            File joined = new File(base, requested.getPath());
            String canonical = joined.getCanonicalPath();
            if (joined.getAbsolutePath().equals(canonical)) {
                return joined;
            }
            // Canonical path differs (symlink or traversal): verify it still
            // lands inside the base before honouring it.
            return canonical.startsWith(base.getCanonicalPath() + File.separator)
                    || canonical.equals(base.getCanonicalPath())
                    ? new File(canonical)
                    : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isInsideRoot(File candidate, File root) {
        if (candidate == null || root == null) {
            return false;
        }
        try {
            String candidatePath = candidate.getCanonicalPath();
            String rootPath = root.getCanonicalPath();
            return candidatePath.equals(rootPath)
                    || candidatePath.startsWith(rootPath + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isBlockedBase(File candidate) {
        for (String blocked : BLOCKED_BASE_DIRS) {
            if (blocked != null && isInsideRoot(candidate, new File(blocked))) {
                return true;
            }
        }
        return false;
    }

    public static List<File> getReadableRoots(String scId) {
        List<File> roots = new ArrayList<>();
        String projectDir = ProjectManager.getProjectDir(scId);
        roots.add(new File(projectDir));
        return roots;
    }

    public static File getPrimaryReadableRoot(String scId) {
        String projectDir = ProjectManager.getProjectDir(scId);
        return new File(projectDir);
    }

    public static File getTerminalWorkingRoot(String scId) {
        String projectDir = ProjectManager.getProjectDir(scId);
        File workDir = new File(projectDir, "files");
        if (!workDir.exists()) workDir.mkdirs();
        return workDir;
    }

    public static List<File> getWritableRoots(String scId) {
        return getReadableRoots(scId);
    }

    public static boolean isPlaceholderPath(String path) {
        return path != null && (path.contains("$") || path.contains("{"));
    }

    public static boolean isReadRootAlias(String path) {
        return path != null && (path.equals(".") || path.equals("./"));
    }

    public static boolean hasParentTraversal(String path) {
        return path != null && path.contains("..");
    }

    public static boolean isAndroidStudioProject(String path) {
        if (path == null) return false;
        java.io.File f = new java.io.File(path);
        return f.exists() && (new java.io.File(f, "build.gradle").exists() || new java.io.File(f, "build.gradle.kts").exists());
    }

}
