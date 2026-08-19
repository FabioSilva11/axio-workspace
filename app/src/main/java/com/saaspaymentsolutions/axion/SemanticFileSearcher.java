package com.saaspaymentsolutions.axion;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class SemanticFileSearcher {

    public static class SearchResult {
        public String filePath;
        public String snippet;
        public double relevance;

        public SearchResult(String filePath, String snippet, double relevance) {
            this.filePath = filePath;
            this.snippet = snippet;
            this.relevance = relevance;
        }
    }

    public static List<SearchResult> searchRelevantFiles(String query, String scId) {
        List<SearchResult> results = new ArrayList<>();
        File root = ProjectPathResolver.getPrimaryReadableRoot(scId);
        if (!root.exists() || !root.isDirectory()) return results;
        searchRecursive(root, query.toLowerCase(), results, 50);
        return results;
    }

    public static List<SearchResult> searchByFilename(String query, String scId) {
        List<SearchResult> results = new ArrayList<>();
        File root = ProjectPathResolver.getPrimaryReadableRoot(scId);
        if (!root.exists() || !root.isDirectory()) return results;
        searchByFilenameRecursive(root, query.toLowerCase(), results, 50);
        return results;
    }

    public static List<SearchResult> searchByContent(String query, String scId) {
        List<SearchResult> results = new ArrayList<>();
        File root = ProjectPathResolver.getPrimaryReadableRoot(scId);
        if (!root.exists() || !root.isDirectory()) return results;
        searchByContentRecursive(root, query.toLowerCase(), results, 50);
        return results;
    }

    public static List<SearchResult> searchByContentRegex(String regex, String scId) {
        List<SearchResult> results = new ArrayList<>();
        File root = ProjectPathResolver.getPrimaryReadableRoot(scId);
        if (!root.exists() || !root.isDirectory()) return results;
        searchByContentRegexRecursive(root, regex, results, 50);
        return results;
    }

    private static void searchRecursive(File dir, String query, List<SearchResult> results, int maxResults) {
        if (results.size() >= maxResults) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (results.size() >= maxResults) return;
            if (file.isDirectory()) {
                searchRecursive(file, query, results, maxResults);
            } else if (file.getName().toLowerCase().contains(query)) {
                results.add(new SearchResult(file.getAbsolutePath(), file.getName(), 1.0));
            }
        }
    }

    private static void searchByFilenameRecursive(File dir, String query, List<SearchResult> results, int maxResults) {
        if (results.size() >= maxResults) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (results.size() >= maxResults) return;
            if (file.isDirectory()) {
                searchByFilenameRecursive(file, query, results, maxResults);
            } else if (file.getName().toLowerCase().contains(query)) {
                results.add(new SearchResult(file.getAbsolutePath(), file.getName(), 1.0));
            }
        }
    }

    private static void searchByContentRecursive(File dir, String query, List<SearchResult> results, int maxResults) {
        if (results.size() >= maxResults) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (results.size() >= maxResults) return;
            if (file.isDirectory()) {
                searchByContentRecursive(file, query, results, maxResults);
            } else if (isTextFile(file)) {
                try {
                    String content = new String(Files.readAllBytes(file.toPath()));
                    if (content.toLowerCase().contains(query)) {
                        int idx = content.toLowerCase().indexOf(query);
                        int start = Math.max(0, idx - 40);
                        int end = Math.min(content.length(), idx + query.length() + 40);
                        String snippet = content.substring(start, end).replace("\n", " ");
                        results.add(new SearchResult(file.getAbsolutePath(), snippet, 1.0));
                    }
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void searchByContentRegexRecursive(File dir, String regex, List<SearchResult> results, int maxResults) {
        if (results.size() >= maxResults) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (results.size() >= maxResults) return;
            if (file.isDirectory()) {
                searchByContentRegexRecursive(file, regex, results, maxResults);
            } else if (isTextFile(file)) {
                try {
                    String content = new String(Files.readAllBytes(file.toPath()));
                    if (content.matches("(?s).*" + regex + ".*")) {
                        results.add(new SearchResult(file.getAbsolutePath(), content.substring(0, Math.min(80, content.length())), 1.0));
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static boolean isTextFile(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".java") || name.endsWith(".xml") || name.endsWith(".json")
                || name.endsWith(".gradle") || name.endsWith(".kt") || name.endsWith(".txt")
                || name.endsWith(".properties") || name.endsWith(".cfg");
    }
}


