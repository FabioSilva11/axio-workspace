package com.saaspaymentsolutions.axion.port;

import java.io.File;

import com.saaspaymentsolutions.axion.DirectoryTreeService;
import com.saaspaymentsolutions.axion.LanguageHelpers;
import com.saaspaymentsolutions.axion.ProjectPathResolver;

/**
 * Android port of browser/fileService.ts prompt-copy behavior.
 */
public final class VoidPortFileService {
    private static final int MAX_FILE_CHARS = 24_000;
    private static final int MAX_FOLDER_TREE_CHARS = 18_000;

    private VoidPortFileService() {
    }

    public static String readFileForPrompt(String scId, String filePath, int startLine, int endLine) {
        String content = null;
        try {
            ProjectPathResolver.ResolvedPath resolved = ProjectPathResolver.resolveForRead(scId, filePath);
            if (resolved != null && resolved.getFile().exists()) {
                content = new String(java.nio.file.Files.readAllBytes(resolved.getFile().toPath()), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
        }
        if (content == null) {
            return "";
        }
        String selected = sliceLines(content, startLine, endLine);
        if (selected.length() > MAX_FILE_CHARS) {
            selected = selected.substring(0, MAX_FILE_CHARS) + "\n... file truncated ...";
        }
        return selected;
    }

    public static String messageOfFile(String scId, String filePath) {
        String content = readFileForPrompt(scId, filePath, 0, 0);
        if (content.isEmpty()) {
            return "No contents detected for " + filePath;
        }
        String language = LanguageHelpers.detectLanguage(filePath, content);
        return "File: " + filePath + "\n```" + language + "\n" + content + "\n```";
    }

    public static String messageOfFolder(String scId, String folderPath) {
        ProjectPathResolver.ResolvedPath resolved = ProjectPathResolver.resolveForRead(scId, folderPath);
        if (resolved == null) {
            return "Folder not found or outside project scope: " + folderPath;
        }
        File folder = resolved.getFile();
        if (!folder.exists() || !folder.isDirectory()) {
            return "Folder not found: " + folderPath;
        }
        String tree = DirectoryTreeService.getDirectoryStrTool(folder);
        if (tree.length() > MAX_FOLDER_TREE_CHARS) {
            tree = tree.substring(0, MAX_FOLDER_TREE_CHARS) + "\n... folder tree truncated ...";
        }
        return "Folder: " + folderPath + "\n" + tree;
    }

    private static String sliceLines(String content, int startLine, int endLine) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        if (startLine <= 0 && endLine <= 0) {
            return content;
        }
        String[] lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int from = Math.max(1, startLine <= 0 ? 1 : startLine);
        int to = endLine <= 0 ? lines.length : Math.min(endLine, lines.length);
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
}


