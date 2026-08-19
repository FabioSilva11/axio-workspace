package com.saaspaymentsolutions.axion;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Restaura backups Axion de projetos Android Studio e Web sem confiar nos
 * caminhos do ZIP nem reutilizar IDs que já existem no dispositivo.
 */
public final class ProjectImportService {
    private static final String BACKUP_MANIFEST = "axion-backup.json";

    private ProjectImportService() { }

    public static Result importAxionProject(Context context, Uri source) throws Exception {
        if (context == null) throw new IllegalArgumentException("Contexto inválido para restauração.");

        File importsRoot = new File(context.getCacheDir(), "axion-project-imports");
        if (!importsRoot.exists() && !importsRoot.mkdirs()) {
            throw new IllegalStateException(context.getString(R.string.import_temp_folder_failed));
        }

        File staging = new File(importsRoot, ".import-" + UUID.randomUUID());
        if (!staging.mkdirs()) throw new IllegalStateException(context.getString(R.string.import_prepare_failed));

        File destination = null;
        try {
            try (InputStream input = context.getContentResolver().openInputStream(source)) {
                if (input == null) throw new IllegalArgumentException(context.getString(R.string.import_open_file_failed));
                extractZip(context, input, staging);
            }

            File metadataFile = findMetadata(staging);
            if (metadataFile == null) {
                throw new IllegalArgumentException(context.getString(R.string.import_invalid_axion_zip));
            }

            JSONObject project = readJson(metadataFile);
            JSONObject manifest = readOptionalManifest(staging);

            String originalId = project.optString("sc_id", "").trim();
            if (originalId.isEmpty()) throw new IllegalArgumentException(context.getString(R.string.import_missing_sc_id));

            File sourceRoot = metadataFile.getParentFile();
            if (sourceRoot == null || !sourceRoot.isDirectory()) {
                throw new IllegalArgumentException(context.getString(R.string.import_invalid_backup_structure));
            }

            String projectKind = resolveProjectKind(project, manifest, sourceRoot);
            boolean isWeb = ProjectManager.PROJECT_KIND_WEB.equals(projectKind);

            String targetId = originalId;
            if (projectIdExistsAnywhere(targetId)) targetId = ProjectManager.b();

            File targetProjectsRoot = new File(isWeb
                    ? ProjectManager.getWebProjectsDir()
                    : ProjectManager.getAndroidStudioProjectsDir());
            destination = new File(targetProjectsRoot, targetId);
            if (destination.exists()) {
                throw new IllegalStateException(context.getString(R.string.import_destination_exists));
            }

            copyDirectory(context, sourceRoot, destination);

            project.put("sc_id", targetId);
            project.put(ProjectManager.PROJECT_KIND_KEY, projectKind);
            project.put("proj_type", 2);
            project.put("studio_path", destination.getAbsolutePath());
            writeJson(context, new File(destination, "project"), project);

            if (isWeb) ensureWebDirectories(destination);

            // Recarrega os diffs que vieram no backup (.axion/file_changes.json).
            FileChangeTracker.reload(targetId);

            return new Result(
                    targetId,
                    project.optString("my_ws_name", targetId),
                    projectKind,
                    destination.getAbsolutePath());
        } catch (Exception error) {
            if (destination != null && destination.exists()) deleteRecursively(destination);
            throw error;
        } finally {
            deleteRecursively(staging);
        }
    }

    private static String resolveProjectKind(JSONObject project,
                                             JSONObject manifest,
                                             File sourceRoot) {
        String manifestKind = manifest == null
                ? ""
                : normalizeKind(manifest.optString(ProjectManager.PROJECT_KIND_KEY, ""));
        if (!manifestKind.isEmpty()) return manifestKind;

        String metadataKind = normalizeKind(project.optString(ProjectManager.PROJECT_KIND_KEY, ""));
        if (!metadataKind.isEmpty()) return metadataKind;

        // Compatibilidade com backups antigos, anteriores ao campo project_kind.
        if (looksLikeWebProject(sourceRoot)) return ProjectManager.PROJECT_KIND_WEB;
        return ProjectManager.PROJECT_KIND_ANDROID_STUDIO;
    }

    private static String normalizeKind(String kind) {
        if (ProjectManager.PROJECT_KIND_WEB.equals(kind)) return ProjectManager.PROJECT_KIND_WEB;
        if (ProjectManager.PROJECT_KIND_ANDROID_STUDIO.equals(kind)) {
            return ProjectManager.PROJECT_KIND_ANDROID_STUDIO;
        }
        return "";
    }

    private static boolean looksLikeWebProject(File root) {
        boolean hasIndex = new File(root, "index.html").isFile();
        boolean hasWebSource = new File(root, "js").isDirectory()
                || new File(root, "css").isDirectory()
                || new File(root, "assets").isDirectory();
        boolean hasAndroidModule = new File(root, "app").isDirectory()
                || new File(root, "settings.gradle").isFile()
                || new File(root, "settings.gradle.kts").isFile();
        return hasIndex && hasWebSource && !hasAndroidModule;
    }

    private static boolean projectIdExistsAnywhere(String scId) {
        return new File(ProjectManager.getAndroidStudioProjectsRoot(), scId).exists()
                || new File(ProjectManager.getWebProjectsRoot(), scId).exists();
    }

    private static JSONObject readOptionalManifest(File staging) {
        File file = new File(staging, BACKUP_MANIFEST);
        if (!file.isFile()) return null;
        try {
            JSONObject manifest = readJson(file);
            if (!"axion-project-backup".equals(manifest.optString("format"))) return null;
            return manifest;
        } catch (Exception ignored) {
            // Backups antigos não possuem manifesto; metadados e estrutura ainda
            // são suficientes para identificar o tipo do projeto.
            return null;
        }
    }

    private static JSONObject readJson(File file) throws Exception {
        return new JSONObject(new String(
                java.nio.file.Files.readAllBytes(file.toPath()),
                StandardCharsets.UTF_8));
    }

    private static void writeJson(Context context, File file, JSONObject json) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException(context.getString(R.string.import_restored_folder_create_failed));
        }
        java.nio.file.Files.write(file.toPath(), json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void ensureWebDirectories(File projectRoot) {
        new File(projectRoot, "assets/images").mkdirs();
        new File(projectRoot, "assets/sounds").mkdirs();
        new File(projectRoot, "assets/fonts").mkdirs();
    }

    private static void extractZip(Context context, InputStream input, File destination) throws Exception {
        String rootPath = destination.getCanonicalPath() + File.separator;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (name.isEmpty() || name.startsWith("/")) {
                    throw new IllegalArgumentException(context.getString(R.string.import_zip_invalid_path));
                }
                File output = new File(destination, name);
                if (!output.getCanonicalPath().startsWith(rootPath)) {
                    throw new IllegalArgumentException(context.getString(R.string.import_zip_unsafe_path));
                }
                if (entry.isDirectory()) {
                    if (!output.exists() && !output.mkdirs()) {
                        throw new IllegalStateException(context.getString(R.string.import_extract_failed));
                    }
                } else {
                    File parent = output.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw new IllegalStateException(context.getString(R.string.import_extract_failed));
                    }
                    try (FileOutputStream out = new FileOutputStream(output)) {
                        int count;
                        while ((count = zip.read(buffer)) != -1) out.write(buffer, 0, count);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static File findMetadata(File root) {
        File[] files = root.listFiles();
        if (files == null) return null;
        for (File file : files) {
            if (file.isDirectory()) {
                File found = findMetadata(file);
                if (found != null) return found;
            } else if ("project".equals(file.getName())) {
                return file;
            }
        }
        return null;
    }

    private static void copyDirectory(Context context, File source, File destination) throws Exception {
        if (source.isDirectory()) {
            if (!destination.exists() && !destination.mkdirs()) {
                throw new IllegalStateException(context.getString(R.string.import_project_create_failed));
            }
            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyDirectory(context, child, new File(destination, child.getName()));
                }
            }
            return;
        }

        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException(context.getString(R.string.import_destination_folder_failed));
        }
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }

    public static final class Result {
        public final String scId;
        public final String name;
        public final String projectKind;
        public final String projectPath;

        Result(String scId, String name, String projectKind, String projectPath) {
            this.scId = scId;
            this.name = name;
            this.projectKind = projectKind;
            this.projectPath = projectPath;
        }

        public boolean isWebProject() {
            return ProjectManager.PROJECT_KIND_WEB.equals(projectKind);
        }
    }
}
