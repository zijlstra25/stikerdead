package com.stikerdead.keyboard;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Environment;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;
import java.util.concurrent.Executor;

public class WhatsAppStickerAccessibilityService extends AccessibilityService {
    private static final String TAG = "StickerDeadWA";
    private static final String WHATSAPP = "com.whatsapp";
    private static final String PREFS = "stickers";
    private static final String IMPORTED_KEY = "imported";

    // WhatsApp's accessibility tree is not guaranteed to expose a sticker ID.
    // When it doesn't, we use the clicked sticker thumbnail bounds and capture it
    // immediately from the WhatsApp UI as a fallback.
    private boolean stickerPickerActive = false;
    private long lastCaptureAt = 0L;

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
                || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (containsStickerHint(source) || eventContainsStickerHint(event)) {
                stickerPickerActive = true;
                Log.d(TAG, "Sticker picker detected");
            }
            return;
        }

        if (source == null) return;

        if (containsStickerHint(source)) {
            stickerPickerActive = true;
        }

        if (type != AccessibilityEvent.TYPE_VIEW_CLICKED
                && type != AccessibilityEvent.TYPE_VIEW_SELECTED) {
            return;
        }

        String identity = findUsefulIdentity(source);
        if (identity != null) {
            String id = findImportedStickerId(identity);
            if (id != null) {
                RecentStickerManager.markUsed(this, id);
                Log.d(TAG, "Recent sticker matched by identity: " + id);
                stickerPickerActive = false;
                return;
            }
        }

        // Fallback: if WhatsApp did not expose a sticker ID, capture the
        // selected thumbnail and register that image as a WhatsApp sticker.
        if (stickerPickerActive && looksLikeStickerThumbnail(source)) {
            captureClickedSticker(source);
        }
    }

    private boolean eventContainsStickerHint(AccessibilityEvent event) {
        for (CharSequence text : event.getText()) {
            if (isStickerHint(text)) return true;
        }
        CharSequence description = event.getContentDescription();
        return isStickerHint(description);
    }

    private boolean containsStickerHint(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int depth = 0; current != null && depth < 6; depth++) {
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
        for (int depth = 0; current != null && depth < 6; depth++) {
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
        for (String record : saved.split("\\n")) {
            String[] parts = record.split("\\|", -1);
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

    private boolean looksLikeStickerThumbnail(AccessibilityNodeInfo node) {
        if (!node.isClickable() && !node.isLongClickable()) return false;

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.width() < 40 || bounds.height() < 40) return false;
        if (bounds.width() > 500 || bounds.height() > 500) return false;

        CharSequence text = node.getText();
        CharSequence description = node.getContentDescription();
        String className = node.getClassName() == null ? "" : node.getClassName().toString();

        // A sticker tile is normally an image-like clickable item without text.
        boolean imageLike = className.contains("ImageView")
                || className.contains("FrameLayout")
                || className.contains("ViewGroup");
        return imageLike && (text == null || text.length() == 0)
                && (description == null || description.length() == 0
                    || !isStickerHint(description));
    }

    private void captureClickedSticker(AccessibilityNodeInfo node) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.d(TAG, "Screenshot fallback requires Android 11+");
            return;
        }

        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastCaptureAt < 1200L) return;
        lastCaptureAt = now;

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.width() <= 0 || bounds.height() <= 0) return;

        Log.d(TAG, "Capturing possible WhatsApp sticker bounds=" + bounds);

        Executor executor = getMainExecutor();
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, executor,
                new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult screenshot) {
                        Bitmap screen = null;
                        HardwareBuffer buffer = null;
                        try {
                            buffer = screenshot.getHardwareBuffer();
                            screen = Bitmap.wrapHardwareBuffer(
                                    buffer, screenshot.getColorSpace());
                            if (screen == null) return;

                            Bitmap copy = screen.copy(Bitmap.Config.ARGB_8888, false);
                            saveCapturedSticker(copy, bounds);
                            copy.recycle();
                            stickerPickerActive = false;
                        } catch (Exception e) {
                            Log.e(TAG, "Could not capture sticker", e);
                        } finally {
                            if (buffer != null) {
                                buffer.close();
                            }
                        }
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        Log.w(TAG, "Screenshot failed: " + errorCode);
                    }
                });
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

        // Put the captured thumbnail on a square transparent canvas so the
        // normal StickerDead sender can resize/convert it later.
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

    @Override
    public void onInterrupt() {
    }
}
