package com.stikerdead.keyboard;

import android.content.ClipDescription;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputContentInfo;
import android.view.inputmethod.InputMethodService;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

public class StickerKeyboardService extends InputMethodService {

    private static final List<StickerItem> STICKERS = List.of(
            new StickerItem("heart", "❤️", "sticker_heart.svg", "image/svg+xml"),
            new StickerItem("smile", "😄", "sticker_smile.svg", "image/svg+xml")
    );

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

        try {
            File dir = new File(getCacheDir(), "stickers");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("No se pudo crear el directorio de stickers");
            }

            File outputFile = new File(dir, sticker.id() + ".svg");

            try (InputStream in = getAssets().open(sticker.assetName());
                 OutputStream out = new FileOutputStream(outputFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }

            Uri contentUri = FileProvider.getUriForFile(
                    this,
                    "com.stikerdead.keyboard.fileprovider",
                    outputFile
            );

            ClipDescription description = new ClipDescription(
                    sticker.label(),
                    new String[]{sticker.mimeType()}
            );

            InputContentInfo contentInfo = new InputContentInfo(
                    contentUri,
                    description,
                    null
            );

            boolean accepted = ic.commitContent(
                    contentInfo,
                    InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                    new Bundle()
            );

            if (!accepted) {
                Toast.makeText(
                        this,
                        "La aplicación no acepta este tipo de sticker",
                        Toast.LENGTH_SHORT
                ).show();
            }
        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "Error al enviar sticker: " + e.getMessage(),
                    Toast.LENGTH_SHORT
            ).show();
        }
    }
}
