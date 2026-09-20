package com.stikerdead.keyboard;

import android.content.ClipDescription;
import android.content.Intent;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
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
import java.util.Locale;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.core.view.inputmethod.InputConnectionCompat;
import androidx.core.view.inputmethod.InputContentInfoCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class StickerKeyboardService extends InputMethodService {

    private static final long LONG_PRESS_MS = 900L;

    private static final List<StickerItem> STICKERS = List.of(
            new StickerItem("heart", "❤️", "heart", "image/webp.wasticker"),
            new StickerItem("smile", "😄", "smile", "image/webp.wasticker")
    );

    private final Handler handler = new Handler(Looper.getMainLooper());
    private EditorInfo currentEditorInfo;
    private boolean stickerMode = true;
    private boolean shiftEnabled = false;
    private boolean capsLock = false;
    private boolean symbolsMode = false;
    private TextView suggestionView;
    private PredictionEngine predictionEngine;
    private final List<TextView> letterKeys = new java.util.ArrayList<>();
    private final Set<String> favoriteStickerIds = new HashSet<>();
    private String selectedStickerCategory = "recent";
    private final List<ImportedSticker> importedStickers = new ArrayList<>();
    private static final int PICK_STICKERS_REQUEST = 4001;
    private static final int TAKE_STICKER_PHOTO_REQUEST = 4002;
    private static final int RESULT_OK = Activity.RESULT_OK;
    private String importCategory = "personal";
    private Uri pendingPhotoUri;

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        currentEditorInfo = attribute;
        shiftEnabled = false;
        capsLock = false;
        symbolsMode = false;
        stickerMode = true;
    }

    @Override
    public void onFinishInput() {
        currentEditorInfo = null;
        super.onFinishInput();
    }

    @Override
    public View onCreateInputView() {
        return buildTypingView();
    }

    private View buildStickerView() {
        stickerMode = true;
        loadFavorites();
        loadImportedStickers();

        LinearLayout root = createRoot();

        // Categorías de stickers: desplazamiento horizontal para que entren todas.
        HorizontalScrollView categoryScroll = new HorizontalScrollView(this);
        categoryScroll.setHorizontalScrollBarEnabled(false);
        categoryScroll.setPadding(6, 2, 6, 4);

        LinearLayout categories = new LinearLayout(this);
        categories.setOrientation(LinearLayout.HORIZONTAL);
        categories.setGravity(Gravity.CENTER_VERTICAL);

        addCategoryTextButton(categories, "＋", "Agregar sticker", v ->
                showImportCategoryDialog());

        addCategoryTextButton(categories, "Recientes", "Stickers recientes", v -> showStickerCategory("recent"));
        addCategoryTextButton(categories, "⭐ Favoritos", "Stickers favoritos", v -> showStickerCategory("favorites"));

        addCategoryIconButton(categories, R.drawable.whatsapp, "Stickers de WhatsApp", "whatsapp");
        addCategoryIconButton(categories, R.drawable.instagram, "Stickers de Instagram", "instagram");
        addCategoryIconButton(categories, R.drawable.facebook, "Stickers de Facebook", "facebook");
        addCategoryIconButton(categories, R.drawable.discordia, "Stickers de Discord", "discord");
        addCategoryTextButton(categories, "Mis stickers", "Mis stickers", v -> showStickerCategory("personal"));

        categoryScroll.addView(categories, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        root.addView(categoryScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 76
        ));

        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(210, 210, 210));
        root.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1
        ));

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(4);
        grid.setPadding(12, 8, 12, 8);
        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ));

        showStickersInGrid(grid);

        TextView switchButton = makeActionButton("⌨  ESCRIBIR");
        switchButton.setOnClickListener(v -> switchToTypingMode());
        root.addView(switchButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 56
        ));

        return root;
    }

    private void showStickersInGrid(GridLayout grid) {
        grid.removeAllViews();
        for (StickerItem sticker : STICKERS) {
            if ("favorites".equals(selectedStickerCategory) && !favoriteStickerIds.contains(sticker.id())) continue;
            if (!"recent".equals(selectedStickerCategory) && !"favorites".equals(selectedStickerCategory)
                    && !"whatsapp".equals(selectedStickerCategory) && !"personal".equals(selectedStickerCategory)) continue;
            addStickerButton(grid, sticker);
        }
        for (ImportedSticker sticker : importedStickers) {
            if ("favorites".equals(selectedStickerCategory)) {
                if (!favoriteStickerIds.contains(sticker.id)) continue;
            } else if ("recent".equals(selectedStickerCategory)) {
                // mostrar
            } else if (!selectedStickerCategory.equals(sticker.category)) {
                continue;
            }
            addImportedStickerButton(grid, sticker);
        }
    }

    private void addImportedStickerButton(GridLayout grid, ImportedSticker sticker) {
        FrameLayout cell = new FrameLayout(this);
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        image.setImageDrawable(Drawable.createFromPath(sticker.path));
        cell.addView(image, new FrameLayout.LayoutParams(-1, -1));

        final boolean[] longPress = {false};
        final Runnable[] action = {null};
        image.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                longPress[0] = false;
                action[0] = () -> { longPress[0] = true; sendImportedSticker(sticker, false); };
                handler.postDelayed(action[0], LONG_PRESS_MS);
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                if (action[0] != null) handler.removeCallbacks(action[0]);
                if (!longPress[0]) sendImportedSticker(sticker, true);
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_CANCEL && action[0] != null) handler.removeCallbacks(action[0]);
            return true;
        });

        TextView favorite = new TextView(this);
        favorite.setText(favoriteStickerIds.contains(sticker.id) ? "★" : "☆");
        favorite.setTextSize(20);
        favorite.setGravity(Gravity.CENTER);
        favorite.setBackgroundColor(Color.WHITE);
        favorite.setOnClickListener(v -> {
            toggleFavorite(sticker.id);
            favorite.setText(favoriteStickerIds.contains(sticker.id) ? "★" : "☆");
        });
        cell.addView(favorite, new FrameLayout.LayoutParams(44, 44, Gravity.TOP | Gravity.RIGHT));

        GridLayout.LayoutParams p = new GridLayout.LayoutParams();
        p.width = 0; p.height = 120;
        p.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        p.setMargins(6, 6, 6, 6);
        grid.addView(cell, p);
    }

    private void sendImportedSticker(ImportedSticker sticker, boolean direct) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || currentEditorInfo == null) return;
        try {
            File source = new File(sticker.path);
            File dir = new File(getCacheDir(), "stickers");
            if (!dir.exists()) dir.mkdirs();
            File output = new File(dir, sticker.id + (direct ? ".webp" : ".png"));
            Bitmap bitmap = BitmapFactory.decodeFile(source.getAbsolutePath());
            if (bitmap == null) throw new IOException("No se pudo leer el sticker");
            try (FileOutputStream out = new FileOutputStream(output)) {
                bitmap.compress(direct ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.PNG, 90, out);
            } finally {
                bitmap.recycle();
            }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", output);
            String mime = direct ? "image/webp.wasticker" : "image/png";
            InputContentInfoCompat info = new InputContentInfoCompat(uri,
                    new ClipDescription("sticker", new String[]{mime}), null);
            InputConnectionCompat.commitContent(ic, currentEditorInfo, info,
                    InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, new Bundle());
        } catch (Exception e) {
            Toast.makeText(this, "Error preparando sticker: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showImportCategoryDialog() {
        final String[] names = {"📷 Sacar foto", "📁 Otras apps / archivos"};
        new android.app.AlertDialog.Builder(this)
                .setTitle("Agregar sticker")
                .setItems(names, (d, which) -> {
                    if (which == 0) {
                        takeStickerPhoto();
                    } else {
                        // Los stickers de las categorías propias se agregan automáticamente
                        // desde sus respectivos selectores. Esta opción es para fuentes
                        // que todavía no tienen una categoría propia.
                        importCategory = "personal";
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("image/*");
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                        launchExternalActivity(intent, PICK_STICKERS_REQUEST);
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void takeStickerPhoto() {
        try {
            File dir = new File(getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "stickers");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("No se pudo crear la carpeta");
            File photo = new File(dir, "sticker_photo_" + System.currentTimeMillis() + ".jpg");
            pendingPhotoUri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    photo
            );

            Intent intent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, pendingPhotoUri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            launchExternalActivity(intent, TAKE_STICKER_PHOTO_REQUEST);
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo abrir la cámara: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void launchExternalActivity(Intent intent, int requestCode) {
        Activity host = getHostActivity();
        if (host == null) {
            Toast.makeText(this, "No se pudo abrir esta función desde el teclado", Toast.LENGTH_SHORT).show();
            return;
        }
        host.startActivityForResult(intent, requestCode);
    }

    private Activity getHostActivity() {
        try {
            android.content.Context context = this;
            while (context instanceof android.content.ContextWrapper) {
                if (context instanceof Activity) return (Activity) context;
                context = ((android.content.ContextWrapper) context).getBaseContext();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void handleStickerActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == TAKE_STICKER_PHOTO_REQUEST) {
            if (resultCode == RESULT_OK && pendingPhotoUri != null) {
                if (importSticker(pendingPhotoUri)) {
                    selectedStickerCategory = "personal";
                    setInputView(buildStickerView());
                    Toast.makeText(this, "Sticker agregado a Mis stickers", Toast.LENGTH_SHORT).show();
                }
            }
            pendingPhotoUri = null;
            return;
        }

        if (requestCode != PICK_STICKERS_REQUEST || resultCode != RESULT_OK || data == null) return;

        ArrayList<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }

        int count = 0;
        for (Uri uri : uris) if (importSticker(uri)) count++;

        if (count > 0) {
            selectedStickerCategory = "personal";
            setInputView(buildStickerView());
            Toast.makeText(this, count + " sticker importado" + (count == 1 ? "" : "s") + " a Mis stickers", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean importSticker(Uri uri) {
        String mime = getContentResolver().getType(uri);
        if (mime == null || !mime.startsWith("image/")) return false;
        String id = "imported_" + System.currentTimeMillis() + "_" + importedStickers.size();
        String ext = "image/webp".equals(mime) ? ".webp" : "image/png".equals(mime) ? ".png" : ".jpg";
        File dir = new File(getFilesDir(), "stickers");
        if (!dir.exists() && !dir.mkdirs()) return false;
        File target = new File(dir, id + ext);
        try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(target)) {
            if (in == null) return false;
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        } catch (Exception e) { return false; }
        importedStickers.add(new ImportedSticker(id, target.getAbsolutePath(), importCategory, mime));
        saveImportedStickers();
        return true;
    }

    private void loadImportedStickers() {
        importedStickers.clear();
        String saved = getSharedPreferences("stickers", MODE_PRIVATE).getString("imported", "");
        if (saved == null || saved.isEmpty()) return;
        for (String record : saved.split("\\n")) {
            String[] p = record.split("\\|", -1);
            if (p.length == 4 && new File(p[1]).exists()) importedStickers.add(new ImportedSticker(p[0], p[1], p[2], p[3]));
        }
    }

    private void saveImportedStickers() {
        StringBuilder saved = new StringBuilder();
        for (ImportedSticker s : importedStickers) {
            if (saved.length() > 0) saved.append("\\n");
            saved.append(s.id).append("|").append(s.path).append("|").append(s.category).append("|").append(s.mimeType);
        }
        getSharedPreferences("stickers", MODE_PRIVATE).edit().putString("imported", saved.toString()).apply();
    }

    private static class ImportedSticker {
        final String id, path, category, mimeType;
        ImportedSticker(String id, String path, String category, String mimeType) { this.id=id; this.path=path; this.category=category; this.mimeType=mimeType; }
    }

    private void showStickerCategory(String category) {
        selectedStickerCategory = category;
        setInputView(buildStickerView());
    }

    private void showAllStickers() {
        showStickerCategory("recent");
    }

    private void loadFavorites() {
        String saved = getSharedPreferences("stickers", MODE_PRIVATE)
                .getString("favorites", "");
        favoriteStickerIds.clear();
        if (saved == null || saved.isEmpty()) return;
        for (String id : saved.split(",")) {
            if (!id.isEmpty()) favoriteStickerIds.add(id);
        }
    }

    private void toggleFavorite(String id) {
        if (favoriteStickerIds.contains(id)) {
            favoriteStickerIds.remove(id);
        } else {
            favoriteStickerIds.add(id);
        }
        StringBuilder saved = new StringBuilder();
        for (String favoriteId : favoriteStickerIds) {
            if (saved.length() > 0) saved.append(",");
            saved.append(favoriteId);
        }
        getSharedPreferences("stickers", MODE_PRIVATE)
                .edit()
                .putString("favorites", saved.toString())
                .apply();
    }

    private void addCategoryTextButton(
            LinearLayout parent,
            String text,
            String contentDescription,
            View.OnClickListener listener
    ) {
        TextView button = makeKeyButton(text);
        button.setTextSize(text.length() > 8 ? 12 : 13);
        button.setContentDescription(contentDescription);
        button.setGravity(Gravity.CENTER);
        button.setPadding(12, 0, 12, 0);
        button.setOnClickListener(listener);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 68
        );
        params.setMargins(3, 0, 3, 0);
        parent.addView(button, params);
    }

    private void addCategoryIconButton(
            LinearLayout parent,
            int drawableRes,
            String contentDescription,
            String category
    ) {
        ImageButton icon = new ImageButton(this);
        icon.setImageResource(drawableRes);
        icon.setScaleType(ImageButton.ScaleType.FIT_CENTER);
        icon.setPadding(7, 7, 7, 7);
        icon.setBackgroundColor(Color.WHITE);
        icon.setContentDescription(contentDescription);

        icon.setOnClickListener(v -> showStickerCategory(category));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(58, 58);
        params.setMargins(3, 2, 3, 2);
        parent.addView(icon, params);
    }

    private void addStickerButton(GridLayout grid, StickerItem sticker) {
        FrameLayout cell = new FrameLayout(this);
        cell.setBackgroundColor(Color.WHITE);

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
                    if (longPressAction[0] != null) handler.removeCallbacks(longPressAction[0]);
                    if (!longPressTriggered[0]) sendSticker(sticker, true);
                    v.performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (longPressAction[0] != null) handler.removeCallbacks(longPressAction[0]);
                    return true;
                default:
                    return true;
            }
        });

        cell.addView(button, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        TextView favorite = new TextView(this);
        favorite.setText(favoriteStickerIds.contains(sticker.id()) ? "★" : "☆");
        favorite.setTextSize(20);
        favorite.setGravity(Gravity.CENTER);
        favorite.setTextColor(Color.rgb(70, 70, 70));
        favorite.setBackgroundColor(Color.WHITE);
        favorite.setContentDescription("Favorito");
        favorite.setOnClickListener(v -> {
            toggleFavorite(sticker.id());
            favorite.setText(favoriteStickerIds.contains(sticker.id()) ? "★" : "☆");
        });

        FrameLayout.LayoutParams favoriteParams =
                new FrameLayout.LayoutParams(44, 44, Gravity.TOP | Gravity.RIGHT);
        cell.addView(favorite, favoriteParams);

        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = 120;
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(6, 6, 6, 6);
        grid.addView(cell, params);
    }
    // Teclado de escritura inspirado en el diseño de los teclados Android modernos.
    // El botón de stickers queda arriba a la derecha para volver al panel de stickers.
    private View buildTypingView() {
        stickerMode = false;
        shiftEnabled = false;
        capsLock = false;
        symbolsMode = false;
        letterKeys.clear();

        LinearLayout root = createRoot();

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(6, 4, 6, 4);

        // Barra superior: sugerencias predictivas a la izquierda y ES + stickers a la derecha.
        TextView suggestions = makeToolbarButton("");
        suggestionView = suggestions;
        predictionEngine = new PredictionEngine(this);
        handler.postDelayed(() -> updateSuggestionFromCursor(getCurrentInputConnection()), 100);
        suggestions.setContentDescription("Sugerencia predictiva");
        suggestions.setTextSize(15);
        suggestions.setGravity(Gravity.CENTER);
        suggestions.setPadding(8, 0, 8, 0);
        suggestions.setOnClickListener(v -> acceptSuggestion());
        toolbar.addView(suggestions, new LinearLayout.LayoutParams(
                0, 46, 1f
        ));

        TextView language = makeToolbarButton("ES");
        language.setContentDescription("Idioma español");
        toolbar.addView(language, toolbarSquareParams());

        ImageButton stickers = new ImageButton(this);
        stickers.setContentDescription("Stickers");
        stickers.setImageResource(R.drawable.sticker_icon);
        stickers.setScaleType(ImageButton.ScaleType.FIT_CENTER);
        stickers.setPadding(0, 0, 0, 0);
        stickers.setBackgroundColor(Color.WHITE);
        stickers.setOnClickListener(v -> switchToStickerMode());
        toolbar.addView(stickers, toolbarSquareParams());

        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 54
        ));

        // Línea divisoria entre las sugerencias, idioma/stickers y las teclas.
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(190, 190, 190));
        root.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 2
        ));

        LinearLayout keys = new LinearLayout(this);
        keys.setOrientation(LinearLayout.VERTICAL);
        keys.setGravity(Gravity.CENTER);
        keys.setPadding(2, 0, 2, 0);
        root.addView(keys, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ));

        addKeyRow(keys, new String[]{"Q","W","E","R","T","Y","U","I","O","P"});
        addKeyRow(keys, new String[]{"A","S","D","F","G","H","J","K","L","Ñ"});

        LinearLayout row3 = new LinearLayout(this);
        row3.setGravity(Gravity.CENTER);
        row3.setPadding(1, 0, 1, 0);

        TextView shift = makeKeyButton("⇧");
        shift.setOnClickListener(v -> {
            if (symbolsMode) return;
            if (capsLock) {
                capsLock = false;
                shiftEnabled = false;
            } else if (shiftEnabled) {
                capsLock = true;
                shiftEnabled = true;
            } else {
                shiftEnabled = true;
            }
            updateShiftIcon(shift);
            updateLetterKeyLabels();
        });
        row3.addView(shift, keyParams(1.15f));

        for (String letter : new String[]{"Z","X","C","V","B","N","M"}) {
            TextView key = makeKeyButton(letter);
            key.setOnClickListener(v -> typeLetter(letter));
            letterKeys.add(key);
            row3.addView(key, keyParams(1f));
        }

        TextView backspace = makeKeyButton("⌫");
        backspace.setOnClickListener(v -> deleteText());
        row3.addView(backspace, keyParams(1.15f));
        keys.addView(row3, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(2, 2, 2, 2);

        TextView symbols = makeKeyButton("?123");
        symbols.setTextSize(14);
        symbols.setOnClickListener(v -> toggleSymbolsMode());
        bottom.addView(symbols, keyParams(1.2f));

        TextView comma = makeKeyButton(",");
        comma.setOnClickListener(v -> typeText(","));
        bottom.addView(comma, keyParams(0.9f));

        TextView space = makeKeyButton("Espacio");
        space.setOnClickListener(v -> typeText(" "));
        bottom.addView(space, keyParams(4.0f));

        TextView period = makeKeyButton(".");
        period.setOnClickListener(v -> typeText("."));
        bottom.addView(period, keyParams(0.9f));

        TextView enter = makeKeyButton("↵");
        enter.setTextSize(28);
        enter.setOnClickListener(v -> sendEnter());
        bottom.addView(enter, keyParams(1.15f));

        keys.addView(bottom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ));

        return root;
    }

    private void addKeyRow(LinearLayout parent, String[] letters) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.setPadding(letters.length == 10 ? 12 : 1, 0, letters.length == 10 ? 12 : 1, 0);

        for (String letter : letters) {
            TextView key = makeKeyButton(letter);
            key.setOnClickListener(v -> typeLetter(letter));
            letterKeys.add(key);
            row.addView(key, keyParams(1f));
        }

        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ));
    }

    private void updateShiftIcon(TextView shift) {
        if (capsLock) {
            shift.setText("⬆");
            shift.setTextSize(24);
            shift.setTypeface(null, android.graphics.Typeface.BOLD);
        } else if (shiftEnabled) {
            shift.setText("↑");
            shift.setTextSize(24);
            shift.setTypeface(null, android.graphics.Typeface.NORMAL);
        } else {
            shift.setText("⇧");
            shift.setTextSize(22);
            shift.setTypeface(null, android.graphics.Typeface.NORMAL);
        }
    }

    private void updateLetterKeyLabels() {
        for (TextView key : letterKeys) {
            String letter = key.getText().toString();
            if (letter.length() == 1 && Character.isLetter(letter.charAt(0))) {
                key.setText((shiftEnabled || capsLock)
                        ? letter.toUpperCase(Locale.ROOT)
                        : letter.toLowerCase(Locale.ROOT));
            }
        }
    }

    private void typeLetter(String letter) {
        if (symbolsMode) return;
        String value = (shiftEnabled || capsLock)
                ? letter.toUpperCase(Locale.ROOT)
                : letter.toLowerCase(Locale.ROOT);
        typeText(value);

        // Como Gboard, Shift de un toque sirve para una sola mayúscula.
        if (shiftEnabled && !capsLock) {
            shiftEnabled = false;
        }
    }

    private void toggleSymbolsMode() {
        symbolsMode = !symbolsMode;
        shiftEnabled = false;
        capsLock = false;
        setInputView(symbolsMode ? buildSymbolsView() : buildTypingView());
    }

    private View buildSymbolsView() {
        LinearLayout root = createRoot();

        // La barra superior es exactamente la misma que en el teclado de letras.
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        toolbar.setPadding(6, 4, 6, 4);

        TextView suggestions = makeToolbarButton("");
        suggestionView = suggestions;
        predictionEngine = new PredictionEngine(this);
        updateSuggestionFromCursor(getCurrentInputConnection());
        suggestions.setContentDescription("Sugerencia predictiva");
        suggestions.setTextSize(15);
        suggestions.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        suggestions.setPadding(8, 0, 0, 0);
        suggestions.setOnClickListener(v -> acceptSuggestion());
        toolbar.addView(suggestions, new LinearLayout.LayoutParams(0, 46, 1f));

        TextView language = makeToolbarButton("ES");
        language.setContentDescription("Idioma español");
        toolbar.addView(language, toolbarSquareParams());

        ImageButton stickers = new ImageButton(this);
        stickers.setContentDescription("Stickers");
        stickers.setImageResource(R.drawable.sticker_icon);
        stickers.setScaleType(ImageButton.ScaleType.FIT_CENTER);
        stickers.setPadding(0, 0, 0, 0);
        stickers.setBackgroundColor(Color.WHITE);
        stickers.setOnClickListener(v -> switchToStickerMode());
        toolbar.addView(stickers, toolbarSquareParams());

        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 54
        ));

        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(190, 190, 190));
        root.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 2
        ));

        LinearLayout keys = new LinearLayout(this);
        keys.setOrientation(LinearLayout.VERTICAL);
        keys.setGravity(Gravity.CENTER);
        keys.setPadding(2, 0, 2, 0);
        root.addView(keys, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ));

        addSymbolRow(keys, new String[]{"1","2","3","4","5","6","7","8","9","0"});
        addSymbolRow(keys, new String[]{"@","#","$","%","&","*","-","+","/","="});
        addSymbolRow(keys, new String[]{"(",")","\"","'",";",":","!","?","¿","¡"});

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(2, 2, 2, 2);

        TextView letters = makeKeyButton("ABC");
        letters.setTextSize(14);
        letters.setOnClickListener(v -> {
            symbolsMode = false;
            setInputView(buildTypingView());
        });
        bottom.addView(letters, keyParams(1.2f));

        TextView comma = makeKeyButton(",");
        comma.setOnClickListener(v -> typeText(","));
        bottom.addView(comma, keyParams(0.9f));

        TextView space = makeKeyButton("Espacio");
        space.setOnClickListener(v -> typeText(" "));
        bottom.addView(space, keyParams(4.0f));

        TextView period = makeKeyButton(".");
        period.setOnClickListener(v -> typeText("."));
        bottom.addView(period, keyParams(0.9f));

        TextView enter = makeKeyButton("↵");
        enter.setTextSize(20);
        enter.setOnClickListener(v -> sendEnter());
        bottom.addView(enter, keyParams(1.15f));

        keys.addView(bottom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ));

        return root;
    }

    private void addSymbolRow(LinearLayout parent, String[] symbols) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.setPadding(1, 0, 1, 0);
        for (String symbol : symbols) {
            TextView key = makeKeyButton(symbol);
            key.setOnClickListener(v -> typeText(symbol));
            row.addView(key, keyParams(1f));
        }
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ));
    }

    private void sendEnter() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.sendKeyEvent(new android.view.KeyEvent(
                android.view.KeyEvent.ACTION_DOWN,
                android.view.KeyEvent.KEYCODE_ENTER
        ));
        ic.sendKeyEvent(new android.view.KeyEvent(
                android.view.KeyEvent.ACTION_UP,
                android.view.KeyEvent.KEYCODE_ENTER
        ));
        updateSuggestionFromCursor(ic);
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
        button.setTextSize(19);
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
        params.setMargins(1, 1, 1, 1);
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
        if (ic == null) return;
        if (" ".equals(text)) learnCurrentWord(ic);
        ic.commitText(text, 1);
        updateSuggestionFromCursor(ic);
    }

    private void deleteText() {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.deleteSurroundingText(1, 0);
            updateSuggestionFromCursor(ic);
        }
    }

    private void updateSuggestionFromCursor(InputConnection ic) {
        if (ic == null || suggestionView == null || predictionEngine == null) return;
        CharSequence before = ic.getTextBeforeCursor(128, 0);
        String text = before == null ? "" : before.toString();
        int end = text.length();
        int start = end;
        while (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) start--;
        String current = text.substring(start, end);
        String previous = "";
        if (start > 0) {
            int pEnd = start, pStart = start;
            while (pStart > 0 && !Character.isWhitespace(text.charAt(pStart - 1))) pStart--;
            previous = text.substring(pStart, pEnd);
        }
        String suggestion = predictionEngine.suggest(current, previous);
        suggestionView.setText(suggestion.isEmpty() ? current : suggestion);
    }

    private void acceptSuggestion() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || suggestionView == null) return;
        String suggestion = suggestionView.getText().toString().trim();
        if (suggestion.isEmpty()) return;
        CharSequence before = ic.getTextBeforeCursor(128, 0);
        String text = before == null ? "" : before.toString();
        int end = text.length(), start = end;
        while (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) start--;
        int length = end - start;
        if (length > 0) ic.deleteSurroundingText(length, 0);
        ic.commitText(suggestion + " ", 1);
        updateSuggestionFromCursor(ic);
    }

    private void learnCurrentWord(InputConnection ic) {
        if (predictionEngine == null) return;
        CharSequence before = ic.getTextBeforeCursor(128, 0);
        String text = before == null ? "" : before.toString().trim();
        if (text.isEmpty()) return;
        int end = text.length(), start = end;
        while (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) start--;
        String word = text.substring(start, end).replaceAll("[^\\p{L}ÁÉÍÓÚÜÑáéíóúüñ]", "");
        if (word.isEmpty()) return;
        String previous = "";
        if (start > 0) {
            String beforeWord = text.substring(0, start).trim();
            int pEnd = beforeWord.length(), pStart = pEnd;
            while (pStart > 0 && !Character.isWhitespace(beforeWord.charAt(pStart - 1))) pStart--;
            previous = beforeWord.substring(pStart, pEnd);
        }
        predictionEngine.learn(word, previous);
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
