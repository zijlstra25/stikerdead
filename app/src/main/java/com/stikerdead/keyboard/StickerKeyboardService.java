package com.stikerdead.keyboard;

import android.content.ClipDescription;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.inputmethodservice.InputMethodService;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.core.view.inputmethod.EditorInfoCompat;
import androidx.core.view.inputmethod.InputConnectionCompat;
import androidx.core.view.inputmethod.InputContentInfoCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

public class StickerKeyboardService extends InputMethodService {

    private static final List<StickerItem> STICKERS = List.of(
            new StickerItem("heart", "❤️", "heart", "image/png"),
            new StickerItem("smile", "😄", "smile", "image/png")
    );

    private EditorInfo currentEditorInfo;

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        currentEditorInfo = attribute;
    }

    @Override
    public void onFinishInput() {
        currentEditorInfo = null;
        super.onFinishInput();
    }

    @Override
    public View onCreateInputView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(245, 245, 245));

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
            TextView button = new TextView(this);
            button.setText(sticker.label());
            button.setTextSize(42);
            button.setGravity(Gravity.CENTER);
            button.setBackgroundColor(Color.WHITE);
            button.setOnClickListener(v -> sendSticker(sticker));

            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = 120;
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            params.setMargins(6, 6, 6, 6);
            grid.addView(button, params);
        }

        return root;
    }

    private void sendSticker(StickerItem sticker) {
        InputConnection ic = getCurrentInputConnection();

        if (ic == null) {
            Toast.makeText(this, "No hay un campo de texto activo", Toast.LENGTH_SHORT).show();
            return;
        }

        if (currentEditorInfo == null) {
            Toast.makeText(this, "No se pudo obtener información del campo", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] acceptedMimeTypes = EditorInfoCompat.getContentMimeTypes(currentEditorInfo);

        if (acceptedMimeTypes == null || acceptedMimeTypes.length == 0) {
            ic.commitText("[sticker:" + sticker.id() + "]", 1);
            Toast.makeText(
                    this,
                    "Esta app no acepta imágenes desde el teclado",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        boolean acceptsPng = false;
        for (String type : acceptedMimeTypes) {
            if (ClipDescription.compareMimeTypes(sticker.mimeType(), type)
                    || ClipDescription.compareMimeTypes(type, sticker.mimeType())) {
                acceptsPng = true;
                break;
            }
        }

        if (!acceptsPng) {
            ic.commitText("[sticker:" + sticker.id() + "]", 1);
            Toast.makeText(
                    this,
                    "La app no declara soporte para PNG",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        try {
            Uri contentUri = createStickerPng(sticker);

            ClipDescription description = new ClipDescription(
                    sticker.label(),
                    new String[]{"image/png"}
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
                ic.commitText("[sticker:" + sticker.id() + "]", 1);
                Toast.makeText(
                        this,
                        "La app rechazó el sticker",
                        Toast.LENGTH_SHORT
                ).show();
            } else {
                Toast.makeText(this, "Sticker enviado", Toast.LENGTH_SHORT).show();
            }

        } catch (Exception e) {
            ic.commitText("[sticker:" + sticker.id() + "]", 1);
            Toast.makeText(
                    this,
                    "Error preparando sticker: " + e.getMessage(),
                    Toast.LENGTH_SHORT
            ).show();
        }
    }

    private Uri createStickerPng(StickerItem sticker) throws IOException {
        File dir = new File(getCacheDir(), "stickers");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("No se pudo crear el directorio de stickers");
        }

        File outputFile = new File(dir, sticker.id() + ".png");

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

        try (FileOutputStream out = new FileOutputStream(outputFile)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } finally {
            bitmap.recycle();
        }

        return FileProvider.getUriForFile(
                this,
                getPackageName() + ".fileprovider",
                outputFile
        );
    }
}
