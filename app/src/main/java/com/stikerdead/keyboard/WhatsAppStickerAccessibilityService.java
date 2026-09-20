package com.stikerdead.keyboard;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

public class WhatsAppStickerAccessibilityService extends AccessibilityService {
    private static final String TAG = "StickerDeadWA";
    private static final String WHATSAPP = "com.whatsapp";
    private static final String PREFS = "stickers";
    private static final String IMPORTED_KEY = "imported";

    /*
     * Detection has several layers:
     * 1) Accessibility labels/IDs, when WhatsApp exposes them.
     * 2) A cached map of repeated thumbnail-like children in the sticker grid.
     * 3) Parent/sibling analysis at click time.
     * 4) Screenshot of the resolved tile, never of an arbitrary clicked control.
     *
     * The old implementation accepted any clickable ImageView/ViewGroup.
     * That is why the WhatsApp close "X" could end up in Recientes.
     */
    private boolean stickerPickerActive = false;
    private long lastCaptureAt = 0L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<StickerTile> cachedTiles = new ArrayList<>();

    private static final class StickerTile {
        final Rect bounds;
        final long seenAt;

        StickerTile(Rect bounds) {
            this.bounds = new Rect(bounds);
            this.seenAt = android.os.SystemClock.uptimeMillis();
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "Accessibility service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null
                || !WHATSAPP.contentEquals(event.getPackageName())) {
            return;
        }

        int type = event.getEventType();
        AccessibilityNodeInfo source = event.getSource();

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (containsStickerHint(source) || eventContainsStickerHint(event)
                    || rootContainsStickerHint()) {
                stickerPickerActive = true;
                refreshStickerTiles();
                Log.d(TAG, "Sticker picker detected; cached tiles=" + cachedTiles.size());
            }
            return;
        }

        if (source == null) return;

        if (containsStickerHint(source) || rootContainsStickerHint()) {
            stickerPickerActive = true;
            refreshStickerTiles();
        }

        if (type != AccessibilityEvent.TYPE_VIEW_CLICKED
                && type != AccessibilityEvent.TYPE_VIEW_SELECTED
                && type != AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
            return;
        }

        String identity = findUsefulIdentity(source);
        if (identity != null) {
            String id = findImportedStickerId(identity);
            if (id != null) {
                RecentStickerManager.markUsed(this, id);
                Log.d(TAG, "Recent sticker matched by identity: " + id);
                stickerPickerActive = false;
                cachedTiles.clear();
                return;
            }
        }

        if (!stickerPickerActive) return;

        Rect stickerBounds = resolveStickerBounds(source);
        if (stickerBounds == null) {
            Log.d(TAG, "Click ignored: could not prove it belongs to sticker grid. "
                    + describeNode(source));
            return;
        }

        Log.d(TAG, "Sticker tile resolved: " + stickerBounds);

        /*
         * Do not wait 120 ms here. WhatsApp can close the sticker picker
         * immediately after sending. A very small delay lets the click event
         * settle while still keeping the picker visible on most devices.
         */
        handler.postDelayed(() -> captureClickedSticker(stickerBounds), 20L);
    }

    private void refreshStickerTiles() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        List<StickerTile> found = new ArrayList<>();
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        HashSet<AccessibilityNodeInfo> visited = new HashSet<>();
        queue.add(root);

        int scanned = 0;
        while (!queue.isEmpty() && scanned++ < 1200) {
            AccessibilityNodeInfo node = queue.removeFirst();
            if (node == null || !visited.add(node)) continue;

            collectGridChildren(node, found);

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }

        // Keep only the newest valid snapshot. If WhatsApp briefly reports
        // an incomplete tree, retain the previous candidates.
        if (found.size() >= 3) {
            cachedTiles.clear();
            cachedTiles.addAll(dedupeTiles(found));
        }

        recycleNodes(visited);
    }

    private void collectGridChildren(AccessibilityNodeInfo container,
                                     List<StickerTile> out) {
        int count = container.getChildCount();
        if (count < 4 || count > 60) return;

        List<Rect> candidateBounds = new ArrayList<>();
        List<AccessibilityNodeInfo> candidateNodes = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = container.getChild(i);
            if (child == null) continue;

            Rect bounds = new Rect();
            child.getBoundsInScreen(bounds);
            if (!isPlausibleTileBounds(bounds)) continue;
            if (hasControlHint(child)) continue;

            boolean clickable = child.isClickable() || child.isLongClickable();
            boolean imageLike = isImageLike(child);

            /*
             * Some RecyclerView/Compose layouts expose the clickable wrapper
             * one level above the actual image. Accept a non-clickable image
             * only when the child itself looks strongly image-like.
             */
            if (!clickable && !imageLike) continue;

            candidateBounds.add(bounds);
            candidateNodes.add(child);
        }

        if (candidateBounds.size() < 4) return;

        int similar = 0;
        for (int i = 0; i < candidateBounds.size(); i++) {
            for (int j = i + 1; j < candidateBounds.size(); j++) {
                if (similarSize(candidateBounds.get(i), candidateBounds.get(j))) {
                    similar++;
                    break;
                }
            }
        }

        if (similar < 3) return;

        if (!looksLikeGrid(candidateBounds)) return;

        for (Rect bounds : candidateBounds) {
            out.add(new StickerTile(bounds));
        }
    }

    private List<StickerTile> dedupeTiles(List<StickerTile> input) {
        List<StickerTile> result = new ArrayList<>();
        for (StickerTile tile : input) {
            boolean duplicate = false;
            for (StickerTile existing : result) {
                if (sameRect(existing.bounds, tile.bounds)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) result.add(tile);
        }
        return result;
    }

    private boolean looksLikeGrid(List<Rect> bounds) {
        if (bounds.size() < 4) return false;

        int minLeft = Integer.MAX_VALUE;
        int maxRight = Integer.MIN_VALUE;
        int minTop = Integer.MAX_VALUE;
        int maxBottom = Integer.MIN_VALUE;

        int squareish = 0;
        for (Rect r : bounds) {
            minLeft = Math.min(minLeft, r.left);
            maxRight = Math.max(maxRight, r.right);
            minTop = Math.min(minTop, r.top);
            maxBottom = Math.max(maxBottom, r.bottom);

            float ratio = r.width() / (float) Math.max(1, r.height());
            if (ratio >= 0.65f && ratio <= 1.55f) squareish++;
        }

        if (squareish < Math.max(3, bounds.size() / 2)) return false;

        int regionWidth = maxRight - minLeft;
        int regionHeight = maxBottom - minTop;
        if (regionWidth < 220 || regionHeight < 140) return false;

        // Require actual tiling: at least two different rows or columns.
        int distinctRows = 0;
        int distinctCols = 0;
        List<Integer> rows = new ArrayList<>();
        List<Integer> cols = new ArrayList<>();

        for (Rect r : bounds) {
            int cy = r.centerY();
            int cx = r.centerX();

            boolean rowKnown = false;
            for (Integer y : rows) {
                if (Math.abs(y - cy) <= Math.max(12, r.height() / 2)) {
                    rowKnown = true;
                    break;
                }
            }
            if (!rowKnown) rows.add(cy);

            boolean colKnown = false;
            for (Integer x : cols) {
                if (Math.abs(x - cx) <= Math.max(12, r.width() / 2)) {
                    colKnown = true;
                    break;
                }
            }
            if (!colKnown) cols.add(cx);
        }

        distinctRows = rows.size();
        distinctCols = cols.size();
        return distinctRows >= 2 && distinctCols >= 2;
    }

    private Rect resolveStickerBounds(AccessibilityNodeInfo source) {
        Rect sourceBounds = new Rect();
        source.getBoundsInScreen(sourceBounds);
        if (!isPlausibleTileBounds(sourceBounds) || hasControlHint(source)) {
            // The clicked node can be the close button, a wrapper, or a child.
            // We still try its ancestors against the cached grid.
        }

        int centerX = sourceBounds.centerX();
        int centerY = sourceBounds.centerY();

        // Best case: the click point is inside a cached repeated grid tile.
        StickerTile best = findBestCachedTile(centerX, centerY);
        if (best != null) return best.bounds;

        // Second pass: inspect every ancestor and its siblings right now.
        AccessibilityNodeInfo current = source;
        for (int depth = 0; current != null && depth < 8; depth++) {
            AccessibilityNodeInfo parent = current.getParent();
            if (parent == null) break;

            List<Rect> siblings = findGridSiblingBounds(parent);
            if (siblings.size() >= 4) {
                for (Rect r : siblings) {
                    if (r.contains(centerX, centerY)) {
                        return r;
                    }
                }
            }
            current = parent;
        }

        // Third pass: the event source itself may be the tile.
        if (isStrongTileNode(source, sourceBounds)) {
            return sourceBounds;
        }

        return null;
    }

    private StickerTile findBestCachedTile(int x, int y) {
        StickerTile best = null;
        long newest = Long.MIN_VALUE;

        for (StickerTile tile : cachedTiles) {
            if (!tile.bounds.contains(x, y)) continue;

            // Prefer the smallest tile containing the click. This handles
            // nested wrappers where a larger parent was cached accidentally.
            if (best == null
                    || tile.bounds.width() * tile.bounds.height()
                    < best.bounds.width() * best.bounds.height()
                    || tile.seenAt > newest) {
                best = tile;
                newest = tile.seenAt;
            }
        }
        return best;
    }

    private List<Rect> findGridSiblingBounds(AccessibilityNodeInfo parent) {
        List<Rect> result = new ArrayList<>();
        int count = parent.getChildCount();
        if (count < 4 || count > 60) return result;

        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = parent.getChild(i);
            if (child == null) continue;

            Rect bounds = new Rect();
            child.getBoundsInScreen(bounds);

            if (!isPlausibleTileBounds(bounds)) continue;
            if (hasControlHint(child)) continue;
            if (!child.isClickable() && !child.isLongClickable()
                    && !isImageLike(child)) continue;

            result.add(bounds);
        }

        if (result.size() < 4 || !looksLikeGrid(result)) {
            result.clear();
        }
        return result;
    }

    private boolean isStrongTileNode(AccessibilityNodeInfo node, Rect bounds) {
        if (!isPlausibleTileBounds(bounds) || hasControlHint(node)) return false;
        return (node.isClickable() || node.isLongClickable()) && isImageLike(node);
    }

    private boolean isPlausibleTileBounds(Rect bounds) {
        if (bounds == null || bounds.width() < 55 || bounds.height() < 55) return false;
        if (bounds.width() > 360 || bounds.height() > 360) return false;

        float ratio = bounds.width() / (float) Math.max(1, bounds.height());
        return ratio >= 0.55f && ratio <= 1.8f;
    }

    private boolean similarSize(Rect a, Rect b) {
        int aw = a.width();
        int ah = a.height();
        int bw = b.width();
        int bh = b.height();

        float widthRatio = aw / (float) Math.max(1, bw);
        float heightRatio = ah / (float) Math.max(1, bh);
        return widthRatio >= 0.70f && widthRatio <= 1.43f
                && heightRatio >= 0.70f && heightRatio <= 1.43f;
    }

    private boolean sameRect(Rect a, Rect b) {
        return Math.abs(a.left - b.left) <= 2
                && Math.abs(a.top - b.top) <= 2
                && Math.abs(a.right - b.right) <= 2
                && Math.abs(a.bottom - b.bottom) <= 2;
    }

    private boolean isImageLike(AccessibilityNodeInfo node) {
        String className = node.getClassName() == null
                ? "" : node.getClassName().toString();

        return className.contains("ImageView")
                || className.contains("ImageButton")
                || className.contains("FrameLayout")
                || className.contains("ViewGroup")
                || className.contains("Compose");
    }

    private boolean hasControlHint(AccessibilityNodeInfo node) {
        StringBuilder value = new StringBuilder();
        appendNodeText(value, node.getContentDescription());
        appendNodeText(value, node.getText());
        appendNodeText(value, node.getViewIdResourceName());

        String text = value.toString().toLowerCase(Locale.ROOT);

        String[] controls = {
                "close", "cerrar", "dismiss", "cancel", "cancelar",
                "back", "volver", "atrás", "atras", "navigate_up",
                "search", "buscar", "send", "enviar", "keyboard",
                "teclado", "emoji", "emoticon", "gif", "settings",
                "configuración", "configuracion", "camera", "cámara",
                "camara", "more", "más", "mas", "menu", "x"
        };

        for (String control : controls) {
            if (containsWholeControlWord(text, control)) return true;
        }

        return false;
    }

    private boolean containsWholeControlWord(String text, String word) {
        if ("x".equals(word)) {
            return text.equals("x") || text.endsWith("/x") || text.contains(":x");
        }
        return text.contains(word);
    }

    private void appendNodeText(StringBuilder out, CharSequence value) {
        if (value != null && value.length() > 0) {
            if (out.length() > 0) out.append(' ');
            out.append(value);
        }
    }

    private void recycleNodes(HashSet<AccessibilityNodeInfo> nodes) {
        // AccessibilityNodeInfo instances obtained from getChild/getParent are
        // managed by the framework. Explicit recycle() is deprecated on newer
        // Android versions, so deliberately leave lifecycle management to it.
    }

    private boolean rootContainsStickerHint() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        HashSet<AccessibilityNodeInfo> visited = new HashSet<>();
        queue.add(root);

        int scanned = 0;
        while (!queue.isEmpty() && scanned++ < 1200) {
            AccessibilityNodeInfo node = queue.removeFirst();
            if (node == null || !visited.add(node)) continue;

            if (isStickerHint(node.getContentDescription())
                    || isStickerHint(node.getText())
                    || isStickerHint(node.getViewIdResourceName())) {
                return true;
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        return false;
    }

    private boolean eventContainsStickerHint(AccessibilityEvent event) {
        for (CharSequence text : event.getText()) {
            if (isStickerHint(text)) return true;
        }
        return isStickerHint(event.getContentDescription());
    }

    private boolean containsStickerHint(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (isStickerHint(current.getContentDescription())
                    || isStickerHint(current.getText())
                    || isStickerHint(current.getViewIdResourceName())) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private boolean isStickerHint(CharSequence value) {
        if (value == null) return false;
        String text = value.toString().toLowerCase(Locale.ROOT);
        return text.contains("sticker")
                || text.contains("stickers")
                || text.contains("pegatina")
                || text.contains("pegatinas");
    }

    private String findUsefulIdentity(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int depth = 0; current != null && depth < 8; depth++) {
            CharSequence description = current.getContentDescription();
            if (description != null && description.length() > 0) {
                return description.toString();
            }

            CharSequence text = current.getText();
            if (text != null && text.length() > 0) {
                return text.toString();
            }

            String viewId = current.getViewIdResourceName();
            if (viewId != null && !viewId.isEmpty()) {
                return viewId;
            }

            current = current.getParent();
        }
        return null;
    }

    private String findImportedStickerId(String identity) {
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(IMPORTED_KEY, "");
        if (saved == null || saved.isEmpty()) return null;

        String normalized = identity.toLowerCase(Locale.ROOT);
        for (String record : saved.split("\n")) {
            String[] parts = record.split("\|", -1);
            if (parts.length != 4 || !"whatsapp".equals(parts[2])) continue;

            String id = parts[0];
            String path = parts[1];
            int slash = path.lastIndexOf('/');
            String filename = slash >= 0 ? path.substring(slash + 1) : path;

            if (normalized.contains(id.toLowerCase(Locale.ROOT))
                    || normalized.contains(filename.toLowerCase(Locale.ROOT))) {
                return id;
            }
        }
        return null;
    }

    private void captureClickedSticker(Rect bounds) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.d(TAG, "Screenshot fallback requires Android 11+");
            return;
        }

        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastCaptureAt < 1000L) return;

        if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) return;

        lastCaptureAt = now;
        Rect safeBounds = new Rect(bounds);

        Log.d(TAG, "Capturing resolved WhatsApp sticker bounds=" + safeBounds);

        Executor executor = getMainExecutor();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            /*
             * Android 14+: capture WhatsApp's accessibility window itself.
             * This avoids accidentally capturing another overlay/window.
             */
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                int windowId = root.getWindowId();
                try {
                    takeScreenshotOfWindow(windowId, executor,
                            new TakeScreenshotCallback() {
                                @Override
                                public void onSuccess(ScreenshotResult screenshot) {
                                    processScreenshot(screenshot, safeBounds);
                                }

                                @Override
                                public void onFailure(int errorCode) {
                                    Log.w(TAG, "Window screenshot failed: " + errorCode);
                                    captureDisplayFallback(safeBounds);
                                }
                            });
                    return;
                } catch (Exception e) {
                    Log.w(TAG, "Window screenshot unavailable; using display: " + e);
                }
            }
        }

        captureDisplayFallback(safeBounds);
    }

    private void captureDisplayFallback(Rect bounds) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;

        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, getMainExecutor(),
                new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult screenshot) {
                        processScreenshot(screenshot, bounds);
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        Log.w(TAG, "Display screenshot failed: " + errorCode);
                    }
                });
    }

    private void processScreenshot(ScreenshotResult screenshot, Rect screenBounds) {
        Bitmap screen = null;
        HardwareBuffer buffer = null;

        try {
            buffer = screenshot.getHardwareBuffer();
            screen = Bitmap.wrapHardwareBuffer(buffer, screenshot.getColorSpace());
            if (screen == null) return;

            Bitmap copy = screen.copy(Bitmap.Config.ARGB_8888, false);
            Rect scaled = scaleBoundsToBitmap(screenBounds, copy.getWidth(), copy.getHeight());
            if (scaled != null) {
                saveCapturedSticker(copy, scaled);
            }
            copy.recycle();
            stickerPickerActive = false;
            cachedTiles.clear();
        } catch (Exception e) {
            Log.e(TAG, "Could not process sticker screenshot", e);
        } finally {
            if (buffer != null) buffer.close();
        }
    }

    private Rect scaleBoundsToBitmap(Rect screenBounds, int bitmapWidth, int bitmapHeight) {
        if (screenBounds == null || screenBounds.width() <= 0 || screenBounds.height() <= 0) {
            return null;
        }

        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        int displayWidth = metrics.widthPixels;
        int displayHeight = metrics.heightPixels;

        if (displayWidth <= 0 || displayHeight <= 0) return null;

        float sx = bitmapWidth / (float) displayWidth;
        float sy = bitmapHeight / (float) displayHeight;

        Rect scaled = new Rect(
                Math.round(screenBounds.left * sx),
                Math.round(screenBounds.top * sy),
                Math.round(screenBounds.right * sx),
                Math.round(screenBounds.bottom * sy)
        );

        scaled.left = Math.max(0, Math.min(bitmapWidth - 1, scaled.left));
        scaled.top = Math.max(0, Math.min(bitmapHeight - 1, scaled.top));
        scaled.right = Math.max(scaled.left + 1, Math.min(bitmapWidth, scaled.right));
        scaled.bottom = Math.max(scaled.top + 1, Math.min(bitmapHeight, scaled.bottom));

        return scaled;
    }

    private void saveCapturedSticker(Bitmap screen, Rect screenBounds) {
        int left = Math.max(0, screenBounds.left);
        int top = Math.max(0, screenBounds.top);
        int right = Math.min(screen.getWidth(), screenBounds.right);
        int bottom = Math.min(screen.getHeight(), screenBounds.bottom);

        if (right <= left || bottom <= top) return;

        int width = right - left;
        int height = bottom - top;

        Bitmap crop = Bitmap.createBitmap(screen, left, top, width, height);

        int size = Math.max(width, height);
        Bitmap square = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(square);
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
        canvas.drawBitmap(crop, (size - width) / 2f, (size - height) / 2f, null);
        crop.recycle();

        File dir = new File(getFilesDir(), "stickers");
        if (!dir.exists() && !dir.mkdirs()) {
            square.recycle();
            return;
        }

        String id = "whatsapp_auto_" + System.currentTimeMillis();
        File target = new File(dir, id + ".png");

        try (FileOutputStream out = new FileOutputStream(target)) {
            square.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception e) {
            Log.e(TAG, "Could not save captured sticker", e);
            square.recycle();
            return;
        }
        square.recycle();

        String saved = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(IMPORTED_KEY, "");
        String record = id + "|" + target.getAbsolutePath()
                + "|whatsapp|image/png";
        String updated = saved == null || saved.isEmpty()
                ? record : saved + "\n" + record;

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(IMPORTED_KEY, updated)
                .apply();

        RecentStickerManager.markUsed(this, id);
        Log.d(TAG, "WhatsApp sticker added to Recientes: " + id);
    }

    private String describeNode(AccessibilityNodeInfo node) {
        if (node == null) return "null";
        String className = node.getClassName() == null
                ? "" : node.getClassName().toString();
        String desc = node.getContentDescription() == null
                ? "" : node.getContentDescription().toString();
        String id = node.getViewIdResourceName() == null
                ? "" : node.getViewIdResourceName();
        return "class=" + className + ", desc=" + desc + ", id=" + id;
    }

    @Override
    public void onInterrupt() {
    }
}
