package com.saaspaymentsolutions.axion;

import com.saaspaymentsolutions.axion.ProjectManager;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ProjectPathResolver {

    public static class ResolvedPath {
        private final File file;

        public ResolvedPath(File file) {
            this.file = file;
        }

        public File getFile() {
            return file;
        }
    }

    public static ResolvedPath resolveForRead(String scId, String path) {
        if (path == null || path.isEmpty()) return new ResolvedPath(new File(""));
        File file = new File(path);
        if (file.isAbsolute()) {
            return new ResolvedPath(file);
        }
        String projectDir = ProjectManager.getProjectDir(scId);
        return new ResolvedPath(new File(projectDir, path));
    }

    public static ResolvedPath resolveForWrite(String scId, String path) {
        if (path == null || path.isEmpty()) return new ResolvedPath(new File(""));
        File file = new File(path);
        if (file.isAbsolute()) {
            return new ResolvedPath(file);
        }
        String projectDir = ProjectManager.getProjectDir(scId);
        return new ResolvedPath(new File(projectDir, path));
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
