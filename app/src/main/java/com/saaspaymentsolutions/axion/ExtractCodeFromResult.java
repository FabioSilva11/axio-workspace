package com.saaspaymentsolutions.axion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ExtractCodeFromResult {
    private ExtractCodeFromResult() {
    }

    public static final class SurroundingsRemover {
        public final String originalS;
        private int i;
        private int j;

        public SurroundingsRemover(String s) {
            this.originalS = s == null ? "" : s;
            this.i = 0;
            this.j = this.originalS.length() - 1;
        }

        public String value() {
            return substringClamped(originalS, i, j + 1);
        }

        public boolean removePrefix(String prefix) {
            int offset = 0;
            while (i <= j && offset <= prefix.length() - 1) {
                if (originalS.charAt(i) != prefix.charAt(offset)) {
                    break;
                }
                offset += 1;
                i += 1;
            }
            return offset == prefix.length();
        }

        public boolean removeSuffix(String suffix) {
            String s = value();
            for (int len = Math.min(s.length(), suffix.length()); len >= 1; len -= 1) {
                if (s.endsWith(suffix.substring(0, len))) {
                    j -= len;
                    return len == suffix.length();
                }
            }
            return false;
        }

        public boolean removeFromStartUntilFullMatch(String until, boolean alsoRemoveUntilStr) {
            int index = originalS.indexOf(until, i);
            if (index == -1) {
                return false;
            }
            i = alsoRemoveUntilStr ? index + until.length() : index;
            return true;
        }

        public boolean removeCodeBlock() {
            boolean foundCodeBlock = removePrefix("```");
            if (!foundCodeBlock) {
                return false;
            }

            removeFromStartUntilFullMatch("\n", true);

            int oldJ = j;
            boolean foundCodeBlockEnd = removeSuffix("```");
            if (j == oldJ) {
                foundCodeBlockEnd = removeSuffix("```\n");
            }

            if (!foundCodeBlockEnd) {
                return false;
            }

            removeSuffix("\n");
            return true;
        }

        public DeltaInfo deltaInfo(int recentlyAddedTextLen) {
            int recentlyAddedIdx = originalS.length() - recentlyAddedTextLen;
            String actualDelta = substringClamped(originalS, Math.max(i, recentlyAddedIdx), j + 1);
            String ignoredSuffix = substringClamped(originalS, Math.max(j + 1, recentlyAddedIdx), originalS.length());
            return new DeltaInfo(actualDelta, ignoredSuffix);
        }
    }

    public static final class DeltaInfo {
        public final String actualDelta;
        public final String ignoredSuffix;

        public DeltaInfo(String actualDelta, String ignoredSuffix) {
            this.actualDelta = actualDelta;
            this.ignoredSuffix = ignoredSuffix;
        }
    }

    public static final class Extraction {
        public final String fullText;
        public final String delta;
        public final String ignoredSuffix;

        public Extraction(String fullText, String delta, String ignoredSuffix) {
            this.fullText = fullText;
            this.delta = delta;
            this.ignoredSuffix = ignoredSuffix;
        }
    }

    public enum SearchReplaceState {
        WRITING_ORIGINAL,
        WRITING_FINAL,
        DONE
    }

    public static final class ExtractedSearchReplaceBlock {
        public final SearchReplaceState state;
        public final String orig;
        public final String fin;

        public ExtractedSearchReplaceBlock(SearchReplaceState state, String orig, String fin) {
            this.state = state;
            this.orig = orig;
            this.fin = fin;
        }
    }

    public static Extraction extractCodeFromRegular(String text, int recentlyAddedTextLen) {
        SurroundingsRemover pm = new SurroundingsRemover(text);
        pm.removeCodeBlock();
        String s = pm.value();
        DeltaInfo delta = pm.deltaInfo(recentlyAddedTextLen);
        return new Extraction(s, delta.actualDelta, delta.ignoredSuffix);
    }

    public static Extraction extractCodeFromFIM(String text, int recentlyAddedTextLen, String midTag) {
        SurroundingsRemover pm = new SurroundingsRemover(text);
        pm.removeCodeBlock();

        boolean foundMid = pm.removePrefix("<" + midTag + ">");
        if (foundMid) {
            pm.removeSuffix("\n");
            pm.removeSuffix("</" + midTag + ">");
        }

        String s = pm.value();
        DeltaInfo delta = pm.deltaInfo(recentlyAddedTextLen);
        return new Extraction(s, delta.actualDelta, delta.ignoredSuffix);
    }

    public static String endsWithAnyPrefixOf(String str, String anyPrefix) {
        for (int i = anyPrefix.length(); i >= 1; i--) {
            String prefix = anyPrefix.substring(0, i);
            if (str.endsWith(prefix)) {
                return prefix;
            }
        }
        return null;
    }

    public static List<ExtractedSearchReplaceBlock> extractSearchReplaceBlocks(String str) {
        if (str == null || str.isEmpty()) {
            return Collections.emptyList();
        }

        String originalMarker = PromptConstants.ORIGINAL + "\n";
        String dividerMarker = "\n" + PromptConstants.DIVIDER + "\n";
        List<ExtractedSearchReplaceBlock> blocks = new ArrayList<>();

        int i = 0;
        while (true) {
            int origStart = str.indexOf(originalMarker, i);
            if (origStart == -1) {
                return blocks;
            }
            origStart += originalMarker.length();
            i = origStart;

            int dividerStart = str.indexOf(dividerMarker, i);
            if (dividerStart == -1) {
                String writingDivider = endsWithAnyPrefixOf(str, dividerMarker);
                int writingDividerLen = writingDivider == null ? 0 : writingDivider.length();
                blocks.add(new ExtractedSearchReplaceBlock(
                        SearchReplaceState.WRITING_ORIGINAL,
                        voidSubstr(str, origStart, str.length() - writingDividerLen),
                        ""));
                return blocks;
            }

            String origStrDone = voidSubstr(str, origStart, dividerStart);
            dividerStart += dividerMarker.length();
            i = dividerStart;

            int fullFinalStart = str.indexOf(PromptConstants.FINAL, i);
            int fullFinalStartWithNewline = str.indexOf("\n" + PromptConstants.FINAL, i);
            boolean matchedFullFinalWithNewline = fullFinalStartWithNewline != -1
                    && fullFinalStart == fullFinalStartWithNewline + 1;

            int finalStart = matchedFullFinalWithNewline ? fullFinalStartWithNewline : fullFinalStart;
            if (finalStart == -1) {
                String writingFinal = endsWithAnyPrefixOf(str, PromptConstants.FINAL);
                String writingFinalWithNewline = endsWithAnyPrefixOf(str, "\n" + PromptConstants.FINAL);
                int usingWritingFinalLen = Math.max(
                        writingFinal == null ? 0 : writingFinal.length(),
                        writingFinalWithNewline == null ? 0 : writingFinalWithNewline.length());
                blocks.add(new ExtractedSearchReplaceBlock(
                        SearchReplaceState.WRITING_FINAL,
                        origStrDone,
                        voidSubstr(str, dividerStart, str.length() - usingWritingFinalLen)));
                return blocks;
            }

            String usingFinal = matchedFullFinalWithNewline ? "\n" + PromptConstants.FINAL : PromptConstants.FINAL;
            String finalStrDone = voidSubstr(str, dividerStart, finalStart);
            finalStart += usingFinal.length();
            i = finalStart;

            blocks.add(new ExtractedSearchReplaceBlock(SearchReplaceState.DONE, origStrDone, finalStrDone));
        }
    }

    private static String voidSubstr(String str, int start, int end) {
        return end < start ? "" : substringClamped(str, start, end);
    }

    private static String substringClamped(String str, int start, int end) {
        int safeStart = Math.max(0, Math.min(start, str.length()));
        int safeEnd = Math.max(0, Math.min(end, str.length()));
        if (safeEnd < safeStart) {
            return "";
        }
        return str.substring(safeStart, safeEnd);
    }
}
