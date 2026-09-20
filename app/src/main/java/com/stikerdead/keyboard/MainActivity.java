package com.stikerdead.keyboard;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.MediaStore;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final int PICK_STICKERS_REQUEST = 4001;
    private static final int TAKE_STICKER_PHOTO_REQUEST = 4002;
    private Uri pendingPhotoUri;
    private String importCategory = "personal";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setText("StikerDead");
        title.setTextSize(28);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView description = new TextView(this);
        description.setText("\nTeclado Android de stickers.\n\nActivá el teclado desde los ajustes del sistema para probarlo.");
        description.setTextSize(17);
        root.addView(description, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        Button settings = new Button(this);
        settings.setText(getString(R.string.enable_keyboard));
        settings.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        );
        root.addView(settings, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        setContentView(root);

        Intent action = getIntent();
        String stickerAction = action.getStringExtra("sticker_action");
        importCategory = action.getStringExtra("sticker_category");
        if (importCategory == null) importCategory = "personal";

        if ("add".equals(stickerAction)) {
            showAddStickerDialog();
        } else if ("picker".equals(stickerAction)) {
            openStickerPicker();
        } else if ("camera".equals(stickerAction)) {
            takeStickerPhoto();
        }
    }

    private void showAddStickerDialog() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Agregar sticker")
                .setItems(new String[]{"📷 Sacar foto", "📁 Otras apps / archivos"}, (dialog, which) -> {
                    if (which == 0) {
                        takeStickerPhoto();
                    } else {
                        openStickerPicker();
                    }
                })
                .setNegativeButton("Cancelar", (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish())
                .show();
    }

    private void openStickerPicker() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("image/*");
        picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(picker, PICK_STICKERS_REQUEST);
    }

    private void takeStickerPhoto() {
        try {
            File dir = new File(getCacheDir(), "stickers");
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("No se pudo crear la carpeta");
            File photo = new File(dir, "sticker_photo_" + System.currentTimeMillis() + ".jpg");
            pendingPhotoUri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", photo);
            Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            camera.putExtra(MediaStore.EXTRA_OUTPUT, pendingPhotoUri);
            camera.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(camera, TAKE_STICKER_PHOTO_REQUEST);
        } catch (Exception e) {
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) {
            finish();
            return;
        }

        int count = 0;
        if (requestCode == TAKE_STICKER_PHOTO_REQUEST && pendingPhotoUri != null) {
            if (importSticker(pendingPhotoUri)) count = 1;
        } else if (requestCode == PICK_STICKERS_REQUEST && data != null) {
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    if (importSticker(data.getClipData().getItemAt(i).getUri())) count++;
                }
            } else if (data.getData() != null && importSticker(data.getData())) {
                count = 1;
            }
        }

        if (count > 0) {
            getSharedPreferences("stickers", MODE_PRIVATE).edit()
                    .putBoolean("refresh_needed", true).apply();
        }
        finish();
    }

    private boolean importSticker(Uri uri) {
        String mime = getContentResolver().getType(uri);
        if (mime == null || !mime.startsWith("image/")) return false;

        String id = "imported_" + System.currentTimeMillis() + "_" + System.nanoTime();
        String ext = "image/webp".equals(mime) ? ".webp"
                : "image/png".equals(mime) ? ".png" : ".jpg";
        File dir = new File(getFilesDir(), "stickers");
        if (!dir.exists() && !dir.mkdirs()) return false;
        File target = new File(dir, id + ext);

        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(target)) {
            if (in == null) return false;
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        } catch (Exception e) {
            return false;
        }

        String saved = getSharedPreferences("stickers", MODE_PRIVATE)
                .getString("imported", "");
        String record = id + "|" + target.getAbsolutePath() + "|" + importCategory + "|" + mime;
        String updated = saved == null || saved.isEmpty() ? record : saved + "\n" + record;
        getSharedPreferences("stickers", MODE_PRIVATE).edit()
                .putString("imported", updated).apply();
        return true;
    }
}
