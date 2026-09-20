package com.stikerdead.keyboard;

import android.content.Context;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class RecentStickerManager {
    private static final String PREFS = "stickers";
    private static final String KEY = "recent_ids";
    private static final int MAX_RECENTS = 30;

    private RecentStickerManager() {}

    public static void markUsed(Context context, String id) {
        if (id == null || id.isEmpty()) return;
        Set<String> ordered = new LinkedHashSet<>();
        String saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "");
        if (saved != null && !saved.isEmpty()) {
            for (String value : saved.split(",")) {
                if (!value.isEmpty()) ordered.add(value);
            }
        }
        ordered.remove(id);
        ordered.add(id);

        while (ordered.size() > MAX_RECENTS) {
            String first = ordered.iterator().next();
            ordered.remove(first);
        }

        StringBuilder out = new StringBuilder();
        for (String value : ordered) {
            if (out.length() > 0) out.append(",");
            out.append(value);
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, out.toString()).apply();
    }

    public static List<String> getRecentIds(Context context) {
        List<String> result = new ArrayList<>();
        String saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "");
        if (saved == null || saved.isEmpty()) return result;
        String[] values = saved.split(",");
        for (int i = values.length - 1; i >= 0; i--) {
            if (!values[i].isEmpty()) result.add(values[i]);
        }
        return result;
    }
}
