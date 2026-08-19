package com.saaspaymentsolutions.axion.resources;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Picture;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.caverock.androidsvg.SVG;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.saaspaymentsolutions.axion.BaseAppCompatActivity;
import com.saaspaymentsolutions.axion.ProjectManager;
import com.saaspaymentsolutions.axion.R;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ImportProjectIconActivity extends BaseAppCompatActivity {

    private static final String TAG = "AxionLucide";
    private static final int PAGE_SIZE = 80;
    private static final int PNG_SIZE = 512;
    private static final Pattern VALID_RESOURCE_NAME =
            Pattern.compile("[a-z][a-z0-9_]*");

    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final List<LucideIcon> allIcons = new ArrayList<>();
    private final List<LucideIcon> filteredIcons = new ArrayList<>();
    private final List<LucideIcon> visibleIcons = new ArrayList<>();

    private IconAdapter adapter;
    private String projectId;
    private String filterQuery = "";
    private int selectedColor = Color.BLACK;
    private boolean loadingMore;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectId = getIntent().getStringExtra(
                ProjectResourceManagerActivity.EXTRA_PROJECT_ID);
        if (projectId == null || projectId.trim().isEmpty()) {
            Toast.makeText(this, R.string.resource_project_invalid, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        setContentView(R.layout.activity_import_project_icon);
        MaterialToolbar toolbar = findViewById(R.id.icon_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        findViewById(R.id.icon_tabs).setVisibility(View.GONE);
        findViewById(R.id.icon_filter).setOnClickListener(v -> showFilterDialog());
        configureGrid();
        extractAndLoadLucideIcons();
    }

    private void configureGrid() {
        RecyclerView list = findViewById(R.id.icon_list);
        int widthDp = (int) (getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density);
        GridLayoutManager layoutManager = new GridLayoutManager(
                this, Math.max(2, widthDp / 96));
        list.setLayoutManager(layoutManager);
        adapter = new IconAdapter();
        list.setAdapter(adapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                int last = layoutManager.findLastVisibleItemPosition();
                if (!loadingMore && last >= visibleIcons.size() - 16
                        && visibleIcons.size() < filteredIcons.size()) {
                    appendNextPage();
                }
            }
        });
    }

    private void extractAndLoadLucideIcons() {
        executor.execute(() -> {
            try {
                File iconDirectory = new File(getFilesDir(), "lucide_icons_v1/icons");
                File marker = new File(iconDirectory.getParentFile(), ".complete");
                if (!marker.isFile() || !containsSvgFiles(iconDirectory)) {
                    if (marker.exists()) {
                        marker.delete();
                    }
                    extractLucideZip(iconDirectory.getParentFile());
                    if (!containsSvgFiles(iconDirectory)) {
                        throw new IOException(
                                "lucide_icons.zip was extracted without SVG files");
                    }
                    File parent = marker.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    try (FileOutputStream output = new FileOutputStream(marker)) {
                        output.write("1".getBytes(StandardCharsets.UTF_8));
                    }
                }

                File[] files = iconDirectory.listFiles(
                        file -> file.isFile() && file.getName().endsWith(".svg"));
                List<LucideIcon> loaded = new ArrayList<>();
                if (files != null) {
                    for (File file : files) {
                        loaded.add(new LucideIcon(
                                file.getName().substring(0, file.getName().length() - 4),
                                file));
                    }
                }
                loaded.sort(Comparator.comparing(icon -> icon.name));
                Log.i(TAG, "Pacote Lucide carregado: " + loaded.size()
                        + " SVGs em " + iconDirectory.getAbsolutePath());
                runOnUiThread(() -> {
                    allIcons.clear();
                    allIcons.addAll(loaded);
                    applyFilter(filterQuery);
                });
            } catch (IOException error) {
                Log.e(TAG, "Falha ao extrair lucide_icons.zip", error);
                runOnUiThread(() -> Toast.makeText(
                        this, R.string.import_icon_extract_error, Toast.LENGTH_LONG).show());
            }
        });
    }

    private static boolean containsSvgFiles(File directory) {
        File[] files = directory.listFiles(
                file -> file.isFile() && file.getName().endsWith(".svg"));
        return files != null && files.length > 0;
    }

    private void extractLucideZip(File destinationRoot) throws IOException {
        if (destinationRoot.exists() && !deleteRecursively(destinationRoot)) {
            throw new IOException("Cannot clear old Lucide extraction");
        }
        if (!destinationRoot.exists() && !destinationRoot.mkdirs()) {
            throw new IOException("Cannot create Lucide destination");
        }
        String rootPath = destinationRoot.getCanonicalPath() + File.separator;
        try (InputStream asset = getAssets().open("icons/lucide_icons.zip");
             ZipInputStream zip = new ZipInputStream(asset)) {
            ZipEntry entry;
            byte[] buffer = new byte[16 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                String entryName = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || !entryName.endsWith(".svg")) {
                    zip.closeEntry();
                    continue;
                }
                File target = new File(destinationRoot, entryName);
                String targetPath = target.getCanonicalPath();
                if (!targetPath.startsWith(rootPath)) {
                    throw new IOException("Invalid ZIP entry: " + entryName);
                }
                File parent = target.getParentFile();
                if (parent == null || (!parent.exists() && !parent.mkdirs())) {
                    throw new IOException("Cannot create " + parent);
                }
                try (FileOutputStream output = new FileOutputStream(target)) {
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static boolean deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) {
                        return false;
                    }
                }
            }
        }
        return file.delete();
    }

    private void showFilterDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(R.string.import_icon_search_hint);
        input.setText(filterQuery);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding / 2, padding, padding / 2);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.import_icon_filter)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.import_icon_choose_color,
                        (dialog, which) -> showColorFilter())
                .setPositiveButton(R.string.import_icon_apply,
                        (dialog, which) -> applyFilter(input.getText().toString()))
                .show();
    }

    private void applyFilter(String query) {
        filterQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        filteredIcons.clear();
        for (LucideIcon icon : allIcons) {
            if (filterQuery.isEmpty() || icon.name.contains(filterQuery)) {
                filteredIcons.add(icon);
            }
        }
        visibleIcons.clear();
        adapter.notifyDataSetChanged();
        appendNextPage();
    }

    private void appendNextPage() {
        loadingMore = true;
        int start = visibleIcons.size();
        int end = Math.min(start + PAGE_SIZE, filteredIcons.size());
        if (start < end) {
            visibleIcons.addAll(filteredIcons.subList(start, end));
            adapter.notifyItemRangeInserted(start, end - start);
        } else {
            adapter.notifyDataSetChanged();
        }
        loadingMore = false;
    }

    private void showColorFilter() {
        int[] colors = {
                Color.BLACK,
                Color.WHITE,
                Color.parseColor("#6B5CE7"),
                Color.parseColor("#0B57D0"),
                Color.parseColor("#22C55E")
        };
        String[] labels = {
                getString(R.string.import_icon_color_black),
                getString(R.string.import_icon_color_white),
                getString(R.string.import_icon_color_purple),
                getString(R.string.import_icon_color_blue),
                getString(R.string.import_icon_color_green)
        };
        int checked = 0;
        for (int index = 0; index < colors.length; index++) {
            if (colors[index] == selectedColor) {
                checked = index;
                break;
            }
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.import_icon_choose_color)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    selectedColor = colors[which];
                    adapter.notifyDataSetChanged();
                    dialog.dismiss();
                })
                .show();
    }

    private void showSaveDialog(LucideIcon icon) {
        View content = LayoutInflater.from(this)
                .inflate(R.layout.dialog_save_project_resource, null, false);
        ImageView preview = content.findViewById(R.id.save_resource_preview);
        TextInputLayout nameLayout = content.findViewById(R.id.save_resource_name_layout);
        TextInputEditText nameInput = content.findViewById(R.id.save_resource_name);
        CheckBox collection = content.findViewById(R.id.save_resource_collection);
        collection.setVisibility(View.GONE);
        loadSvg(preview, icon);
        nameInput.setText("icon_" + icon.name
                .replace('-', '_').toLowerCase(Locale.ROOT));
        nameInput.selectAll();

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.import_icon_save)
                .setView(content)
                .setMessage(R.string.import_icon_license)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.resource_save, null)
                .create();
        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String name = nameInput.getText() == null
                            ? "" : nameInput.getText().toString().trim();
                    if (!VALID_RESOURCE_NAME.matcher(name).matches()) {
                        nameLayout.setError(getString(R.string.resource_name_error));
                        return;
                    }
                    if (savePng(icon, name)) {
                        dialog.dismiss();
                        setResult(RESULT_OK, new Intent().putExtra("iconName", name));
                        finish();
                    }
                }));
        dialog.show();
    }

    private void loadSvg(ImageView imageView, LucideIcon icon) {
        String bindKey = icon.file.getAbsolutePath() + ":" + selectedColor;
        imageView.setTag(bindKey);
        imageView.setImageTintList(null);
        imageView.setImageDrawable(null);
        executor.execute(() -> {
            try {
                String svgText = readText(icon.file)
                        .replace("currentColor", colorHex(selectedColor));
                SVG svg = SVG.getFromString(svgText);
                svg.setDocumentWidth(128f);
                svg.setDocumentHeight(128f);
                Picture picture = svg.renderToPicture(128, 128);
                Bitmap bitmap = Bitmap.createBitmap(
                        128, 128, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                canvas.drawPicture(picture);
                runOnUiThread(() -> {
                    if (bindKey.equals(imageView.getTag())) {
                        imageView.setImageBitmap(bitmap);
                    }
                });
            } catch (Exception error) {
                Log.e(TAG, "Falha no preview SVG: " + icon.file.getName(), error);
                runOnUiThread(() -> {
                    if (bindKey.equals(imageView.getTag())) {
                        imageView.setImageResource(R.drawable.ic_mtrl_image);
                    }
                });
            }
        });
    }

    private boolean savePng(LucideIcon icon, String name) {
        File drawableDirectory = new File(
                ProjectManager.getProjectDir(projectId), "app/src/main/res/drawable");
        if (!drawableDirectory.exists() && !drawableDirectory.mkdirs()) {
            Toast.makeText(this, R.string.resource_directory_error, Toast.LENGTH_SHORT).show();
            return false;
        }
        File target = new File(drawableDirectory, name + ".png");
        if (target.exists()) {
            Toast.makeText(this, R.string.resource_name_exists, Toast.LENGTH_SHORT).show();
            return false;
        }

        Bitmap bitmap = null;
        try {
            String svgText = readText(icon.file)
                    .replace("currentColor", colorHex(selectedColor));
            SVG svg = SVG.getFromString(svgText);
            svg.setDocumentWidth(PNG_SIZE);
            svg.setDocumentHeight(PNG_SIZE);
            Picture picture = svg.renderToPicture(PNG_SIZE, PNG_SIZE);
            bitmap = Bitmap.createBitmap(
                    PNG_SIZE, PNG_SIZE, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.TRANSPARENT);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawPicture(picture);

            try (FileOutputStream output = new FileOutputStream(target)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw new IOException("PNG compression failed");
                }
            }

            Toast.makeText(this, R.string.resource_saved, Toast.LENGTH_SHORT).show();
            return true;
        } catch (Exception error) {
            if (target.exists()) {
                target.delete();
            }
            Log.e(TAG, "Falha ao salvar Lucide como PNG: " + icon.file.getName(), error);
            Toast.makeText(this, R.string.import_icon_convert_error, Toast.LENGTH_LONG).show();
            return false;
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }

    private static String readText(File file) throws IOException {
        try (InputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String colorHex(int color) {
        return String.format(Locale.ROOT, "#%06X", 0xFFFFFF & color);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private static final class LucideIcon {
        final String name;
        final File file;

        LucideIcon(String name, File file) {
            this.name = name;
            this.file = file;
        }
    }

    private final class IconAdapter extends RecyclerView.Adapter<IconViewHolder> {

        @NonNull
        @Override
        public IconViewHolder onCreateViewHolder(
                @NonNull ViewGroup parent, int viewType) {
            return new IconViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_project_icon, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull IconViewHolder holder, int position) {
            LucideIcon icon = visibleIcons.get(position);
            holder.name.setText("lucide_" + icon.name.replace('-', '_'));
            loadSvg(holder.preview, icon);
            holder.itemView.setOnClickListener(v -> showSaveDialog(icon));
        }

        @Override
        public int getItemCount() {
            return visibleIcons.size();
        }
    }

    private static final class IconViewHolder extends RecyclerView.ViewHolder {
        final ImageView preview;
        final TextView name;

        IconViewHolder(@NonNull View itemView) {
            super(itemView);
            preview = itemView.findViewById(R.id.icon_item_preview);
            name = itemView.findViewById(R.id.icon_item_name);
        }
    }
}
