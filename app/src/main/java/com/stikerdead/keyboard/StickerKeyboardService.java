package com.stikerdead.keyboard;

import android.content.ClipDescription;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputConnection;
import android.inputmethodservice.InputMethodService;
import android.view.inputmethod.EditorInfo;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.core.view.inputmethod.InputConnectionCompat;
import androidx.core.view.inputmethod.InputContentInfoCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

public class StickerKeyboardService extends InputMethodService {

    private static final long LONG_PRESS_MS = 900L;

    private static final List<StickerItem> STICKERS = List.of(
            new StickerItem("heart", "❤️", "heart", "image/webp.wasticker"),
            new StickerItem("smile", "😄", "smile", "image/webp.wasticker")
    );

    private final Handler handler = new Handler(Looper.getMainLooper());
    private EditorInfo currentEditorInfo;
    private boolean stickerMode = true;
    private boolean shiftEnabled = true;

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        currentEditorInfo = attribute;
        shiftEnabled = true;
        stickerMode = true;
    }

    @Override
    public void onFinishInput() {
        currentEditorInfo = null;
        super.onFinishInput();
    }

    @Override
    public View onCreateInputView() {
        return buildStickerView();
    }

    private View buildStickerView() {
        stickerMode = true;

        LinearLayout root = createRoot();

        TextView header = new TextView(this);
        header.setText("  😀  EMOJIS       STICKERS");
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setTextSize(16);
        header.setPadding(12, 8, 12, 8);
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 56
        ));

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(4);
        grid.setPadding(12, 12, 12, 12);
        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ));

        for (StickerItem sticker : STICKERS) {
            addStickerButton(grid, sticker);
        }

        TextView switchButton = makeActionButton("⌨  ESCRIBIR");
        switchButton.setOnClickListener(v -> switchToTypingMode());
        root.addView(switchButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 56
        ));

        return root;
    }

    private void addStickerButton(GridLayout grid, StickerItem sticker) {
        TextView button = new TextView(this);
        button.setText(sticker.label());
        button.setTextSize(42);
        button.setGravity(Gravity.CENTER);
        button.setBackgroundColor(Color.WHITE);

        final boolean[] longPressTriggered = {false};
        final Runnable[] longPressAction = {null};

        button.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    longPressTriggered[0] = false;
                    Runnable runnable = () -> {
                        longPressTriggered[0] = true;
                        sendSticker(sticker, false);
                    };
                    longPressAction[0] = runnable;
                    handler.postDelayed(runnable, LONG_PRESS_MS);
                    return true;

                case MotionEvent.ACTION_UP:
                    if (longPressAction[0] != null) {
                        handler.removeCallbacks(longPressAction[0]);
                    }
                    if (!longPressTriggered[0]) {
                        sendSticker(sticker, true);
                    }
                    v.performClick();
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    if (longPressAction[0] != null) {
                        handler.removeCallbacks(longPressAction[0]);
                    }
                    return true;

                default:
                    return true;
            }
        });

        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = 120;
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(6, 6, 6, 6);
        grid.addView(button, params);
    }

    // Teclado de escritura inspirado en el diseño de los teclados Android modernos.
    // El botón de stickers queda arriba a la derecha para volver al panel de stickers.
    private View buildTypingView() {
        stickerMode = false;
        shiftEnabled = true;

        LinearLayout root = createRoot();

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        toolbar.setPadding(6, 4, 6, 4);

        // Dos botones cuadrados arriba a la derecha: idioma y stickers.
        TextView language = makeToolbarButton("ES");
        toolbar.addView(language, toolbarSquareParams());

        TextView stickers = makeToolbarButton("🖼");
        stickers.setContentDescription("Stickers");
        stickers.setOnClickListener(v -> switchToStickerMode());
        toolbar.addView(stickers, toolbarSquareParams());

        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 54
        ));

        LinearLayout keys = new LinearLayout(this);
        keys.setOrientation(LinearLayout.VERTICAL);
        keys.setGravity(Gravity.CENTER);
        keys.setPadding(4, 3, 4, 3);
        root.addView(keys, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ));

        addKeyRow(keys, new String[]{"Q","W","E","R","T","Y","U","I","O","P"});
        addKeyRow(keys, new String[]{"A","S","D","F","G","H","J","K","L"});
        addKeyRow(keys, new String[]{"Z","X","C","V","B","N","M"});

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(2, 2, 2, 2);

        TextView shift = makeKeyButton("⇧");
        shift.setOnClickListener(v -> {
            shiftEnabled = !shiftEnabled;
            shift.setText(shiftEnabled ? "⇧" : "⇩");
        });
        bottom.addView(shift, keyParams(1f));

        TextView comma = makeKeyButton(",");
        comma.setOnClickListener(v -> typeText(","));
        bottom.addView(comma, keyParams(0.9f));

        TextView space = makeKeyButton("Espacio");
        space.setOnClickListener(v -> typeText(" "));
        bottom.addView(space, keyParams(4.2f));

        TextView period = makeKeyButton(".");
        period.setOnClickListener(v -> typeText("."));
        bottom.addView(period, keyParams(0.9f));

        TextView backspace = makeKeyButton("⌫");
        backspace.setOnClickListener(v -> deleteText());
        bottom.addView(backspace, keyParams(1f));

        keys.addView(bottom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 66
        ));

        return root;
    }

    private void addKeyRow(LinearLayout parent, String[] letters) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.setPadding(2, 2, 2, 2);

        for (String letter : letters) {
            TextView key = makeKeyButton(letter);
            key.setOnClickListener(v -> {
                String value = letter;
                if (!shiftEnabled) {
                    value = value.toLowerCase();
                }
                typeText(value);
                if (shiftEnabled) {
                    shiftEnabled = false;
                }
            });
            row.addView(key, keyParams(1f));
        }

        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 58
        ));
    }

    private LinearLayout createRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(245, 245, 245));

        // El teclado de escritura busca ocupar aproximadamente un tercio de la pantalla.
        if (!stickerMode) {
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            int targetHeight = (int) (metrics.heightPixels * 0.33f);
            root.setMinimumHeight(targetHeight);
        }

        return root;
    }

    private TextView makeKeyButton(String text) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextSize(18);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(Color.rgb(30, 30, 30));
        button.setBackgroundColor(Color.WHITE);
        return button;
    }

    private TextView makeToolbarButton(String text) {
        TextView button = makeKeyButton(text);
        button.setTextSize(17);
        return button;
    }

    private LinearLayout.LayoutParams toolbarSquareParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(54, 46);
        params.setMargins(2, 0, 2, 0);
        return params;
    }

    private TextView makeActionButton(String text) {
        TextView button = makeKeyButton(text);
        button.setTextSize(15);
        button.setGravity(Gravity.CENTER);
        return button;
    }

    private LinearLayout.LayoutParams keyParams(float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, weight
        );
        params.setMargins(2, 2, 2, 2);
        return params;
    }

    private void switchToTypingMode() {
        setInputView(buildTypingView());
    }

    private void switchToStickerMode() {
        setInputView(buildStickerView());
    }

    private void typeText(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.commitText(text, 1);
        }
    }

    private void deleteText() {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.deleteSurroundingText(1, 0);
        }
    }

    private void sendSticker(StickerItem sticker, boolean directStickerMode) {
        InputConnection ic = getCurrentInputConnection();

        if (ic == null) {
            Toast.makeText(this, "No hay un campo de texto activo", Toast.LENGTH_SHORT).show();
            return;
        }

        if (currentEditorInfo == null) {
            Toast.makeText(this, "No se pudo obtener información del campo", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            final Uri contentUri;
            final String mimeType;

            if (directStickerMode) {
                contentUri = createStickerWebp(sticker);
                mimeType = "image/webp.wasticker";
            } else {
                contentUri = createStickerPng(sticker);
                mimeType = "image/png";
            }

            ClipDescription description = new ClipDescription(
                    sticker.label(),
                    new String[]{mimeType}
            );

            InputContentInfoCompat contentInfo = new InputContentInfoCompat(
                    contentUri,
                    description,
                    null
            );

            boolean accepted = InputConnectionCompat.commitContent(
                    ic,
                    currentEditorInfo,
                    contentInfo,
                    InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                    new Bundle()
            );

            if (!accepted) {
                Toast.makeText(
                        this,
                        directStickerMode
                                ? "La app no aceptó el sticker directo"
                                : "La app rechazó la imagen",
                        Toast.LENGTH_SHORT
                ).show();
            }
        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "Error preparando sticker: " + e.getMessage(),
                    Toast.LENGTH_SHORT
            ).show();
        }
    }

    private Uri createStickerWebp(StickerItem sticker) throws IOException {
        File outputFile = createStickerImage(sticker, ".webp");

        try (FileOutputStream out = new FileOutputStream(outputFile)) {
            Bitmap bitmap = buildStickerBitmap(sticker);
            try {
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, out);
            } finally {
                bitmap.recycle();
            }
        }

        return FileProvider.getUriForFile(
                this,
                getPackageName() + ".fileprovider",
                outputFile
        );
    }

    private Uri createStickerPng(StickerItem sticker) throws IOException {
        File outputFile = createStickerImage(sticker, ".png");

        try (FileOutputStream out = new FileOutputStream(outputFile)) {
            Bitmap bitmap = buildStickerBitmap(sticker);
            try {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            } finally {
                bitmap.recycle();
            }
        }

        return FileProvider.getUriForFile(
                this,
                getPackageName() + ".fileprovider",
                outputFile
        );
    }

    private File createStickerImage(StickerItem sticker, String extension) throws IOException {
        File dir = new File(getCacheDir(), "stickers");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("No se pudo crear el directorio de stickers");
        }
        return new File(dir, sticker.id() + extension);
    }

    private Bitmap buildStickerBitmap(StickerItem sticker) {
        Bitmap bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        if ("heart".equals(sticker.id())) {
            paint.setColor(Color.rgb(255, 77, 109));
            android.graphics.Path heart = new android.graphics.Path();
            heart.moveTo(256, 455);
            heart.cubicTo(70, 330, 55, 210, 105, 140);
            heart.cubicTo(145, 83, 225, 90, 256, 150);
            heart.cubicTo(287, 90, 367, 83, 407, 140);
            heart.cubicTo(457, 210, 442, 330, 256, 455);
            canvas.drawPath(heart, paint);
        } else {
            paint.setColor(Color.rgb(255, 212, 59));
            canvas.drawCircle(256, 256, 200, paint);

            paint.setColor(Color.rgb(34, 34, 34));
            canvas.drawCircle(180, 210, 24, paint);
            canvas.drawCircle(332, 210, 24, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(22);
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawArc(150, 230, 362, 365, 10, 160, false, paint);
        }

        return bitmap;
    }
}
