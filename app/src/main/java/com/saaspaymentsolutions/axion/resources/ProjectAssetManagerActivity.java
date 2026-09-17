package com.saaspaymentsolutions.axion.resources;

import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ProjectAssetManagerActivity extends BaseAppCompatActivity {

    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
    private final List<File> files = new ArrayList<>();
    private AssetAdapter adapter;
    private File assetsRoot;
    private File currentDirectory;
    private boolean webProject;
    private TextView pathView;
    private View emptyState;

    private final ActivityResultLauncher<String[]> filePicker =
            registerForActivityResult(
                    new ActivityResultContracts.OpenMultipleDocuments(),
                    uris -> {
                        if (uris != null && !uris.isEmpty()) {
                            importAssets(uris);
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String projectId = getIntent().getStringExtra(
                ProjectResourceManagerActivity.EXTRA_PROJECT_ID);
        if (projectId == null || projectId.trim().isEmpty()) {
            Toast.makeText(this, R.string.resource_project_invalid, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        webProject = ProjectResourcePaths.isWebProject(projectId);
        ProjectResourcePaths.ensureWebFolders(projectId);
        assetsRoot = ProjectResourcePaths.getAssetManagerRoot(projectId);
        currentDirectory = assetsRoot;
        if (!assetsRoot.exists() && !assetsRoot.mkdirs()) {
            Toast.makeText(this, R.string.resource_directory_error, Toast.LENGTH_SHORT).show();
        }

        setContentView(R.layout.activity_project_assets);
        configureToolbar();
        configureList();
        findViewById(R.id.assets_add).setOnClickListener(v -> showAddMenu());
        refresh();
    }

    private void configureToolbar() {
        MaterialToolbar toolbar = findViewById(R.id.assets_toolbar);
        toolbar.setNavigationOnClickListener(v -> handleBack());
        pathView = findViewById(R.id.assets_path);
        emptyState = findViewById(R.id.assets_empty);
    }

    private void configureList() {
        RecyclerView list = findViewById(R.id.assets_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AssetAdapter();
        list.setAdapter(adapter);
    }

    private void refresh() {
        File[] children = currentDirectory.listFiles();
        files.clear();
        if (children != null) {
            files.addAll(Arrays.asList(children));
            files.sort(Comparator
                    .comparing((File file) -> !file.isDirectory())
                    .thenComparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        }
        adapter.notifyDataSetChanged();
        emptyState.setVisibility(files.isEmpty() ? View.VISIBLE : View.GONE);
        pathView.setText(relativePath(currentDirectory));
    }

    private void showAddMenu() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.resource_asset_add)
                .setItems(new CharSequence[]{
                                getString(R.string.resource_asset_import),
                                getString(R.string.resource_asset_create_file),
                                getString(R.string.resource_asset_create_folder)
                        },
                        (dialog, which) -> {
                            if (which == 0) {
                                filePicker.launch(new String[]{"*/*"});
                            } else {
                                showCreateDialog(which == 2);
                            }
                        })
                .show();
    }

    private void showCreateDialog(boolean folder) {
        EditText input = createNameInput();
        new MaterialAlertDialogBuilder(this)
                .setTitle(folder
                        ? R.string.resource_asset_create_folder
                        : R.string.resource_asset_create_file)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.resource_asset_create, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (!isSafeName(name)) {
                        Toast.makeText(
                                this, R.string.resource_asset_name_error, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    File target = new File(currentDirectory, name);
                    if (target.exists()) {
                        Toast.makeText(
                                this, R.string.resource_name_exists, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    try {
                        boolean created;
                        if (folder) {
                            created = target.mkdir();
                        } else {
                            File parent = target.getParentFile();
                            created = parent != null && parent.isDirectory()
                                    && target.createNewFile();
                        }
                        if (!created) {
                            throw new IOException("Create failed");
                        }
                        refresh();
                    } catch (IOException error) {
                        Toast.makeText(
                                this, R.string.resource_asset_create_error, Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void importAssets(List<Uri> uris) {
        fileExecutor.execute(() -> {
            int imported = 0;
            for (Uri uri : uris) {
                String name = queryDisplayName(uri);
                if (!isSafeName(name)) {
                    continue;
                }
                File target = uniqueTarget(currentDirectory, name);
                try (InputStream input = getContentResolver().openInputStream(uri);
                     FileOutputStream output = new FileOutputStream(target)) {
                    if (input == null) {
                        continue;
                    }
                    input.transferTo(output);
                    imported++;
                } catch (IOException ignored) {
                    if (target.exists()) {
                        target.delete();
                    }
                }
            }
            int importedCount = imported;
            runOnUiThread(() -> {
                refresh();
                Toast.makeText(
                        this,
                        getString(R.string.resource_asset_imported, importedCount),
                        Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void openItem(File file) {
        if (file.isDirectory()) {
            currentDirectory = file;
            refresh();
            return;
        }
        if (isImageFile(file)) {
            showImagePreview(file);
        } else if (isTextFile(file)) {
            showTextPreview(file);
        } else {
            showActions(file);
        }
    }

    private void showImagePreview(File file) {
        ImageView image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setPadding(24, 12, 24, 12);
        image.setImageBitmap(BitmapFactory.decodeFile(file.getAbsolutePath()));
        new MaterialAlertDialogBuilder(this)
                .setTitle(file.getName())
                .setView(image)
                .setNeutralButton(R.string.resource_asset_options,
                        (dialog, which) -> showActions(file))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showTextPreview(File file) {
        fileExecutor.execute(() -> {
            String text;
            try {
                text = readText(file, 128 * 1024);
            } catch (IOException error) {
                text = getString(R.string.resource_asset_preview_error);
            }
            String content = text;
            runOnUiThread(() -> {
                TextView view = new TextView(this);
                int padding = (int) (20 * getResources().getDisplayMetrics().density);
                view.setPadding(padding, padding / 2, padding, padding);
                view.setText(content);
                view.setTextColor(getColor(R.color.chat_text_primary));
                view.setTextIsSelectable(true);
                new MaterialAlertDialogBuilder(this)
                        .setTitle(file.getName())
                        .setView(view)
                        .setNeutralButton(R.string.resource_asset_options,
                                (dialog, which) -> showActions(file))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        });
    }

    private void showActions(File file) {
        if (isProtectedResourceDirectory(file)) {
            Toast.makeText(
                    this,
                    R.string.resource_directory_protected,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(file.getName())
                .setItems(new CharSequence[]{
                                getString(R.string.resource_asset_rename),
                                getString(R.string.resource_delete)
                        },
                        (dialog, which) -> {
                            if (which == 0) {
                                showRenameDialog(file);
                            } else {
                                confirmDelete(file);
                            }
                        })
                .show();
    }

    private void showRenameDialog(File file) {
        if (isProtectedResourceDirectory(file)) {
            Toast.makeText(
                    this,
                    R.string.resource_directory_protected,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        EditText input = createNameInput();
        input.setText(file.getName());
        input.selectAll();
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.resource_asset_rename)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.resource_asset_rename, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (!isSafeName(name)) {
                        Toast.makeText(
                                this, R.string.resource_asset_name_error, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    File target = new File(currentDirectory, name);
                    if (target.exists() || !file.renameTo(target)) {
                        Toast.makeText(
                                this, R.string.resource_asset_rename_error, Toast.LENGTH_SHORT).show();
                    } else {
                        refresh();
                    }
                })
                .show();
    }

    private void confirmDelete(File file) {
        if (isProtectedResourceDirectory(file)) {
            Toast.makeText(
                    this,
                    R.string.resource_directory_protected,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.resource_delete_title)
                .setMessage(getString(R.string.resource_delete_message, file.getName()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.resource_delete, (dialog, which) -> {
                    try {
                        if (!isInsideAssetsRoot(file) || !deleteRecursively(file)) {
                            throw new IOException("Delete failed");
                        }
                        refresh();
                    } catch (IOException error) {
                        Toast.makeText(
                                this, R.string.resource_delete_error, Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private EditText createNameInput() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding / 2, padding, padding / 2);
        return input;
    }

    private void handleBack() {
        if (!currentDirectory.equals(assetsRoot)) {
            File parent = currentDirectory.getParentFile();
            currentDirectory = parent == null ? assetsRoot : parent;
            refresh();
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    private String relativePath(File directory) {
        try {
            String root = assetsRoot.getCanonicalPath();
            String path = directory.getCanonicalPath();
            return (webProject ? "assets" : "res")
                    + path.substring(root.length()).replace(File.separatorChar, '/');
        } catch (IOException error) {
            return directory.getAbsolutePath();
        }
    }

    private boolean isInsideAssetsRoot(File file) throws IOException {
        String root = assetsRoot.getCanonicalPath() + File.separator;
        String target = file.getCanonicalPath();
        return target.startsWith(root);
    }

    private boolean isProtectedResourceDirectory(File file) {
        if (!file.isDirectory()) {
            return false;
        }
        try {
            File parent = file.getCanonicalFile().getParentFile();
            return parent != null && parent.equals(assetsRoot.getCanonicalFile());
        } catch (IOException error) {
            return true;
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

    private static boolean isSafeName(String name) {
        return name != null
                && !name.trim().isEmpty()
                && !name.equals(".")
                && !name.equals("..")
                && name.indexOf('/') < 0
                && name.indexOf('\\') < 0
                && name.indexOf('\0') < 0;
    }

    private static File uniqueTarget(File directory, String name) {
        File target = new File(directory, name);
        if (!target.exists()) {
            return target;
        }
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        int suffix = 2;
        while (target.exists()) {
            target = new File(directory, stem + "_" + suffix + extension);
            suffix++;
        }
        return target;
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String value = cursor.getString(index);
                    if (value != null && !value.isEmpty()) {
                        return value;
                    }
                }
            }
        }
        String segment = uri.getLastPathSegment();
        return segment == null ? "asset" : segment;
    }

    private static boolean isImageFile(File file) {
        String extension = extensionOf(file.getName());
        return extension.matches("png|jpg|jpeg|webp|gif|bmp");
    }

    private static boolean isTextFile(File file) {
        String extension = extensionOf(file.getName());
        return extension.matches(
                "txt|xml|json|csv|html|css|js|md|log|sql|yml|yaml|properties|ini|"
                        + "kt|kts|java|py|ts|sh|c|h|hpp|cpp|toml");
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot < name.length() - 1
                ? name.substring(dot + 1).toLowerCase(Locale.ROOT)
                : "";
    }

    private static String readText(File file, int maxBytes) throws IOException {
        try (InputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int remaining = maxBytes;
            int count;
            while (remaining > 0
                    && (count = input.read(buffer, 0, Math.min(buffer.length, remaining))) != -1) {
                output.write(buffer, 0, count);
                remaining -= count;
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024f);
        }
        return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024f * 1024f));
    }

    @Override
    protected void onDestroy() {
        fileExecutor.shutdownNow();
        super.onDestroy();
    }

    private final class AssetAdapter extends RecyclerView.Adapter<AssetViewHolder> {

        @NonNull
        @Override
        public AssetViewHolder onCreateViewHolder(
                @NonNull ViewGroup parent, int viewType) {
            return new AssetViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_project_asset, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull AssetViewHolder holder, int position) {
            File file = files.get(position);
            holder.name.setText(file.getName());
            holder.details.setText(file.isDirectory()
                    ? getString(isProtectedResourceDirectory(file)
                    ? R.string.resource_project_folder
                    : R.string.resource_asset_folder)
                    : formatSize(file.length()));
            holder.icon.setImageResource(file.isDirectory()
                    ? R.drawable.ic_mtrl_folder
                    : isImageFile(file) ? R.drawable.ic_mtrl_image : R.drawable.ic_mtrl_file);
            holder.itemView.setOnClickListener(v -> openItem(file));
            boolean protectedDirectory = isProtectedResourceDirectory(file);
            holder.more.setVisibility(protectedDirectory ? View.INVISIBLE : View.VISIBLE);
            holder.more.setOnClickListener(protectedDirectory ? null : v -> showActions(file));
            holder.itemView.setOnLongClickListener(v -> {
                if (protectedDirectory) {
                    Toast.makeText(
                            ProjectAssetManagerActivity.this,
                            R.string.resource_directory_protected,
                            Toast.LENGTH_SHORT).show();
                    return true;
                }
                showActions(file);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return files.size();
        }
    }

    private static final class AssetViewHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name;
        final TextView details;
        final ImageButton more;

        AssetViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.asset_item_icon);
            name = itemView.findViewById(R.id.asset_item_name);
            details = itemView.findViewById(R.id.asset_item_details);
            more = itemView.findViewById(R.id.asset_item_more);
        }
    }
}
