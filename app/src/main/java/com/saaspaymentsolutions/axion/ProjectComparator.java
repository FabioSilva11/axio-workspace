package com.saaspaymentsolutions.axion;

import java.util.Comparator;
import java.util.HashMap;

public class ProjectComparator implements Comparator<HashMap<String, Object>> {
    public static final int DEFAULT = 0;
    public static final int SORT_BY_NAME = 1;
    public static final int SORT_BY_ID = 2;
    public static final int SORT_ORDER_ASCENDING = 4;
    public static final int SORT_ORDER_DESCENDING = 8;

    private final int sortMode;
    private final String pinnedId;

    public ProjectComparator(int sortMode, String pinnedId) {
        this.sortMode = sortMode;
        this.pinnedId = pinnedId;
    }

    @Override
    public int compare(HashMap<String, Object> a, HashMap<String, Object> b) {
        String idA = String.valueOf(a.get("sc_id"));
        String idB = String.valueOf(b.get("sc_id"));

        // Pinned project always first
        if (pinnedId != null && !pinnedId.isEmpty() && !pinnedId.equals("-1")) {
            boolean aPinned = pinnedId.equals(idA);
            boolean bPinned = pinnedId.equals(idB);
            if (aPinned && !bPinned) return -1;
            if (!aPinned && bPinned) return 1;
        }

        int result = 0;
        if ((sortMode & SORT_BY_NAME) == SORT_BY_NAME) {
            String nameA = String.valueOf(a.get("my_ws_name"));
            String nameB = String.valueOf(b.get("my_ws_name"));
            result = nameA.compareToIgnoreCase(nameB);
        } else if ((sortMode & SORT_BY_ID) == SORT_BY_ID) {
            result = idA.compareTo(idB);
        } else {
            // Default: sort by name
            String nameA = String.valueOf(a.get("my_ws_name"));
            String nameB = String.valueOf(b.get("my_ws_name"));
            result = nameA.compareToIgnoreCase(nameB);
        }

        if ((sortMode & SORT_ORDER_DESCENDING) == SORT_ORDER_DESCENDING) {
            result = -result;
        }

        return result;
    }
}

