package com.saaspaymentsolutions.axion;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Native folder picker that navigates the filesystem using File API.
 * Requires MANAGE_EXTERNAL_STORAGE permission to access all folders.
 */
public class FolderPickerActivity extends AppCompatActivity {

    private static final int REQUEST_MANAGE_STORAGE = 2001;

    private RecyclerView recyclerView;
    private TextView textCurrentPath;
    private TextView textEmpty;
    private ExtendedFloatingActionButton fabSelect;
    private FolderAdapter adapter;
    private File currentDir;
    private final List<File> entries = new ArrayList<>();
    private boolean showHidden = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_folder_picker);

        textCurrentPath = findViewById(R.id.text_current_path);
        textEmpty = findViewById(R.id.text_empty);
        recyclerView = findViewById(R.id.recycler_folders);
        fabSelect = findViewById(R.id.fab_select_folder);

        findViewById(R.id.btn_back).setOnClickListener(v -> navigateUp());
        findViewById(R.id.btn_go_home).setOnClickListener(v -> navigateToHome());

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FolderAdapter(entries, this::onEntryClicked, this::onEntryLongClicked);
        recyclerView.setAdapter(adapter);

        fabSelect.setOnClickListener(v -> selectCurrentFolder());

        findViewById(R.id.btn_toggle_hidden).setOnClickListener(v -> {
            showHidden = !showHidden;
            android.widget.Toast.makeText(this, showHidden ? "Showing hidden files" : "Hiding hidden files", android.widget.Toast.LENGTH_SHORT).show();
            navigateTo(currentDir);
        });

        if (!hasManageStoragePermission()) {
            requestManageStoragePermission();
        } else {
            navigateTo(Environment.getExternalStorageDirectory());
        }
    }

    private boolean hasManageStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestManageStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQUEST_MANAGE_STORAGE);
            } catch (Exception e) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivityForResult(intent, REQUEST_MANAGE_STORAGE);
            }
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQUEST_MANAGE_STORAGE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MANAGE_STORAGE) {
            if (hasManageStoragePermission()) {
                navigateTo(Environment.getExternalStorageDirectory());
            } else {
                Toast.makeText(this, "Storage permission required to browse folders", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_MANAGE_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                navigateTo(Environment.getExternalStorageDirectory());
            } else {
                Toast.makeText(this, "Storage permission required", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private void navigateTo(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        currentDir = dir;
        textCurrentPath.setText(dir.getAbsolutePath());

        entries.clear();
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (!showHidden && child.getName().startsWith(".")) continue;
                entries.add(child);
            }
            java.util.Collections.sort(entries, new java.util.Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    if (a.isDirectory() != b.isDirectory()) {
                        return a.isDirectory() ? -1 : 1;
                    }
                    return a.getName().toLowerCase().compareTo(b.getName().toLowerCase());
                }
            });
        }

        textEmpty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
        adapter.notifyDataSetChanged();
    }

    private void navigateUp() {
        if (currentDir != null && currentDir.getParentFile() != null) {
            navigateTo(currentDir.getParentFile());
        } else {
            finish();
        }
    }

    private void navigateToHome() {
        navigateTo(Environment.getExternalStorageDirectory());
    }

    private void onEntryClicked(File entry) {
        if (entry.isDirectory()) {
            navigateTo(entry);
        } else {
            Toast.makeText(this, entry.getName() + " (" + entry.length() + " bytes)", Toast.LENGTH_SHORT).show();
        }
    }

    private void onEntryLongClicked(File folder) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Folder Actions")
                .setItems(new CharSequence[]{"Select this folder", "Create subfolder"}, (dialog, which) -> {
                    if (which == 0) {
                        selectFolder(folder);
                    } else {
                        showCreateSubfolderDialog(folder);
                    }
                })
                .show();
    }

    private void selectCurrentFolder() {
        if (currentDir != null) {
            selectFolder(currentDir);
        }
    }

    private void selectFolder(File folder) {
        if (folder == null || !folder.exists()) return;

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_text_input, null);
        final EditText inputName = dialogView.findViewById(R.id.dialog_edit_text);
        inputName.setText(folder.getName());
        inputName.selectAll();

        new MaterialAlertDialogBuilder(this)
                .setTitle("Workspace Name")
                .setView(dialogView)
                .setPositiveButton(R.string.common_word_ok, (dialog, which) -> {
                    String name = inputName.getText().toString().trim();
                    if (name.isEmpty()) name = folder.getName();

                    com.saaspaymentsolutions.axion.workspace.Workspace ws =
                            com.saaspaymentsolutions.axion.workspace.WorkspaceManager.openLocalFolder(
                                    this, folder, name);

                    Intent resultIntent = new Intent();
                    resultIntent.putExtra("workspace_id", ws.getId());
                    resultIntent.putExtra("workspace_name", ws.getName());
                    setResult(RESULT_OK, resultIntent);
                    finish();
                })
                .setNegativeButton(R.string.common_word_cancel, null)
                .show();
    }

    private void showCreateSubfolderDialog(File parent) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_text_input, null);
        final EditText inputName = dialogView.findViewById(R.id.dialog_edit_text);
        inputName.setHint("Folder name");

        new MaterialAlertDialogBuilder(this)
                .setTitle("Create Subfolder")
                .setView(dialogView)
                .setPositiveButton(R.string.common_word_ok, (dialog, which) -> {
                    String name = inputName.getText().toString().trim();
                    if (!name.isEmpty()) {
                        File newFolder = new File(parent, name);
                        if (newFolder.mkdir()) {
                            navigateTo(newFolder);
                        } else {
                            Toast.makeText(this, "Failed to create folder", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton(R.string.common_word_cancel, null)
                .show();
    }

    // ============================================
    // FOLDER LIST ADAPTER
    // ============================================

    static class FolderAdapter extends RecyclerView.Adapter<FolderAdapter.ViewHolder> {
        private final java.util.List<File> entries;
        private final OnFolderClickListener clickListener;
        private final OnFolderLongClickListener longClickListener;

        interface OnFolderClickListener { void onClick(File folder); }
        interface OnFolderLongClickListener { void onLongClick(File folder); }

        FolderAdapter(java.util.List<File> entries, OnFolderClickListener clickListener, OnFolderLongClickListener longClickListener) {
            this.entries = entries;
            this.clickListener = clickListener;
            this.longClickListener = longClickListener;
        }

        @Override
        public int getItemViewType(int position) {
            return entries.get(position).isDirectory() ? 0 : 1;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.chat_project_item, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            File entry = entries.get(position);
            holder.textAppName.setText(entry.getName());
            holder.textProjectName.setText(entry.getName());
            holder.textPackageName.setText(entry.getAbsolutePath());
            holder.imgPin.setVisibility(View.GONE);
            if (holder.imgKindBadge != null) holder.imgKindBadge.setVisibility(View.GONE);

            if (entry.isDirectory()) {
                holder.imgIcon.setImageResource(R.drawable.ic_mtrl_folder);
            } else {
                holder.imgIcon.setImageResource(R.drawable.kelivo_lucide_file);
            }

            holder.itemView.setOnClickListener(v -> {
                if (clickListener != null) clickListener.onClick(entry);
            });
            holder.itemView.setOnLongClickListener(v -> {
                if (longClickListener != null) longClickListener.onLongClick(entry);
                return true;
            });
        }

        @Override
        public int getItemCount() { return entries.size(); }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView textAppName, textProjectName, textPackageName;
            ImageView imgIcon, imgPin, imgMore, imgKindBadge;
            ViewHolder(@NonNull View itemView) {
                super(itemView);
                textAppName = itemView.findViewById(R.id.app_name);
                textProjectName = itemView.findViewById(R.id.project_name);
                textPackageName = itemView.findViewById(R.id.package_name);
                imgIcon = itemView.findViewById(R.id.img_icon);
                imgPin = itemView.findViewById(R.id.img_pin);
                imgMore = itemView.findViewById(R.id.img_more);
                imgKindBadge = itemView.findViewById(R.id.img_kind_badge);
                if (imgMore != null) imgMore.setVisibility(View.GONE);
            }
        }
    }
}
