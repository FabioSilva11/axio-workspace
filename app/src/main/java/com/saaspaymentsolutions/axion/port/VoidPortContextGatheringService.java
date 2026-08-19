package com.saaspaymentsolutions.axion.port;

import java.util.ArrayList;
import java.util.List;

import com.saaspaymentsolutions.axion.LanguageHelpers;
import com.saaspaymentsolutions.axion.StringHelpers;
import com.saaspaymentsolutions.axion.SemanticFileSearcher;

/**
 * Android port of browser/contextGatheringService.ts for compact nearby context.
 */
public final class VoidPortContextGatheringService {
    private VoidPortContextGatheringService() {
    }

    public static final class Snippet {
        public final String filePath;
        public final String language;
        public final String relevance;
        public final String preview;

        Snippet(String filePath, String language, String relevance, String preview) {
            this.filePath = filePath == null ? "" : filePath;
            this.language = language == null ? "text" : language;
            this.relevance = relevance == null ? "" : relevance;
            this.preview = preview == null ? "" : preview;
        }
    }

    public static List<Snippet> gatherRelevantSnippets(String scId, String query, int maxResults) {
        List<Snippet> snippets = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) {
            return snippets;
        }
        List<SemanticFileSearcher.SearchResult> results =
                SemanticFileSearcher.searchRelevantFiles(query, scId);
        int limit = Math.max(1, maxResults);
        for (SemanticFileSearcher.SearchResult result : results) {
            if (result == null || result.filePath == null || result.filePath.trim().isEmpty()) {
                continue;
            }
            String language = LanguageHelpers.detectLanguage(result.filePath, result.snippet);
            StringHelpers.FirstLineSplit split =
                    StringHelpers.separateOutFirstLine(safe(result.snippet));
            snippets.add(new Snippet(
                    result.filePath,
                    language,
                    String.valueOf(result.relevance),
                    safe(split.firstLine).trim()
            ));
            if (snippets.size() >= limit) {
                break;
            }
        }
        return snippets;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}


