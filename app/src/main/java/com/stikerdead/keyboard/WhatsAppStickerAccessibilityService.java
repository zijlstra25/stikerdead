package com.stikerdead.keyboard;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public class WhatsAppStickerAccessibilityService extends AccessibilityService {
    private static final String WHATSAPP = "com.whatsapp";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null
                || !WHATSAPP.contentEquals(event.getPackageName())) return;

        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_VIEW_CLICKED
                && type != AccessibilityEvent.TYPE_VIEW_SELECTED) return;

        AccessibilityNodeInfo source = event.getSource();
        if (source == null) return;

        String identity = findUsefulIdentity(source);
        if (identity == null) return;

        String id = findImportedStickerId(identity);
        if (id != null) RecentStickerManager.markUsed(this, id);
    }

    private String findUsefulIdentity(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int depth = 0; current != null && depth < 5; depth++) {
            CharSequence description = current.getContentDescription();
            if (description != null && description.length() > 0) return description.toString();

            CharSequence text = current.getText();
            if (text != null && text.length() > 0) return text.toString();

            String viewId = current.getViewIdResourceName();
            if (viewId != null && !viewId.isEmpty()) return viewId;

            current = current.getParent();
        }
        return null;
    }

    private String findImportedStickerId(String identity) {
        String saved = getSharedPreferences("stickers", MODE_PRIVATE)
                .getString("imported", "");
        if (saved == null || saved.isEmpty()) return null;

        String normalized = identity.toLowerCase();
        for (String record : saved.split("\\n")) {
            String[] parts = record.split("\\|", -1);
            if (parts.length != 4 || !"whatsapp".equals(parts[2])) continue;

            String id = parts[0];
            String path = parts[1];
            String filename = path.substring(path.lastIndexOf('/') + 1);

            if (normalized.contains(id.toLowerCase())
                    || normalized.contains(filename.toLowerCase())) {
                return id;
            }
        }
        return null;
    }

    @Override
    public void onInterrupt() {}
}
