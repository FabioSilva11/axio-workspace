package com.saaspaymentsolutions.axion.resources;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.saaspaymentsolutions.axion.BaseAppCompatActivity;
import com.saaspaymentsolutions.axion.ProjectManager;
import com.saaspaymentsolutions.axion.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public class ProjectResourceManagerActivity extends BaseAppCompatActivity {

    public static final String EXTRA_PROJECT_ID = "sc_id";
    public static final String EXTRA_RESOURCE_TYPE = "resource_type";
    public static final String TYPE_IMAGE = "image";
    public static final String TYPE_SOUND = "sound";
    public static final String TYPE_FONT = "font";

    private static final Pattern VALID_RESOURCE_NAME =
            Pattern.compile("[a-z][a-z0-9_]*");

    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
    private final Handler playerHandler = new Handler(Looper.getMainLooper());
    private final ResourcePage[] pages = new ResourcePage[2];

    private String projectId;
    private String resourceType;
    private boolean webProject;
    private ExtendedFloatingActionButton addButton;
    private ViewPager viewPager;
    private AlertDialog playerDialog;
    private AlertDialog addSoundDialog;
    private MediaPlayer mediaPlayer;
    private Runnable playerProgressTask;
    private Uri selectedSoundUri;
    private String selectedSoundDisplayName;
    private ImageView selectedSoundAlbum;
    private View selectedSoundGuide;
    private View selectedSoundControls;
    private TextView selectedSoundFileName;
    private ImageButton selectedSoundPlayPause;
    private SeekBar selectedSoundSeek;
    private TextView selectedSoundCurrent;
    private TextView selectedSoundDuration;
    private TextInputEditText selectedSoundName;
    private TextInputLayout selectedSoundNameLayout;

    private final ActivityResultLauncher<String> filePicker =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    if (isSoundManager()
                            && addSoundDialog != null
                            && addSoundDialog.isShowing()) {
                        bindSelectedSound(uri);
                    } else {
                        showSaveResourceDialog(uri);
                    }
                }
            });

    private final ActivityResultLauncher<Intent> iconImporter =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK) {
                            loadPage(0);
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectId = getIntent().getStringExtra(EXTRA_PROJECT_ID);
        resourceType = getIntent().getStringExtra(EXTRA_RESOURCE_TYPE);
        if (projectId == null || projectId.trim().isEmpty()
                || (!TYPE_IMAGE.equals(resourceType)
                && !TYPE_SOUND.equals(resourceType)
                && !TYPE_FONT.equals(resourceType))) {
            Toast.makeText(this, R.string.resource_project_invalid, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        webProject = ProjectResourcePaths.isWebProject(projectId);
        ProjectResourcePaths.ensureWebFolders(projectId);
        setContentView(R.layout.activity_project_resource_manager);
        configureToolbar();
        configureAddButton();
        configurePager();
    }

    private void configureToolbar() {
        MaterialToolbar toolbar = findViewById(R.id.resource_toolbar);
        if (isImageManager()) {
            toolbar.setTitle(R.string.resource_image_manager_title);
        } else if (isSoundManager()) {
            toolbar.setTitle(R.string.resource_sound_manager_title);
        } else {
            toolbar.setTitle(R.string.resource_font_manager_title);
        }
        if (isImageManager() && !webProject) {
            MenuItem lucide = toolbar.getMenu()
                    .add(R.string.import_icon_lucide)
                    .setIcon(R.drawable.ic_mtrl_grid);
            lucide.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            lucide.setOnMenuItemClickListener(item -> {
                Intent intent = new Intent(this, ImportProjectIconActivity.class);
                intent.putExtra(EXTRA_PROJECT_ID, projectId);
                iconImporter.launch(intent);
                return true;
            });
        }
        toolbar.setNavigationOnClickListener(v -> finish());
    }

    private void configurePager() {
        viewPager = findViewById(R.id.resource_view_pager);
        viewPager.setAdapter(new ResourcePagerAdapter());
        viewPager.setOffscreenPageLimit(2);

        TabLayout tabs = findViewById(R.id.resource_tabs);
        tabs.setupWithViewPager(viewPager);
        viewPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                addButton.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
                stopPlayback();
                loadPage(position);
            }
        });
    }

    private void configureAddButton() {
        addButton = findViewById(R.id.resource_add);
        addButton.setText(addDialogTitle());
        addButton.setOnClickListener(v -> {
            if (isImageManager()) {
                filePicker.launch("image/*");
            } else if (isSoundManager()) {
                showAddSoundDialog();
            } else {
                filePicker.launch("*/*");
            }
        });
    }

    private void showAddSoundDialog() {
        stopPlayback();
        selectedSoundUri = null;
        selectedSoundDisplayName = null;

        View content = LayoutInflater.from(this)
                .inflate(R.layout.dialog_add_sound, null, false);
        View selectCard = content.findViewById(R.id.add_sound_select_card);
        selectedSoundAlbum = content.findViewById(R.id.add_sound_album);
        selectedSoundGuide = content.findViewById(R.id.add_sound_guide);
        selectedSoundControls = content.findViewById(R.id.add_sound_controls);
        selectedSoundFileName = content.findViewById(R.id.add_sound_file_name);
        selectedSoundPlayPause = content.findViewById(R.id.add_sound_play_pause);
        selectedSoundSeek = content.findViewById(R.id.add_sound_seek);
        selectedSoundCurrent = content.findViewById(R.id.add_sound_current_time);
        selectedSoundDuration = content.findViewById(R.id.add_sound_duration);
        selectedSoundName = content.findViewById(R.id.add_sound_name);
        selectedSoundNameLayout = content.findViewById(R.id.add_sound_name_layout);
        CheckBox addToCollection = content.findViewById(R.id.add_sound_collection);

        selectCard.setOnClickListener(v -> filePicker.launch("audio/*"));
        selectedSoundPlayPause.setOnClickListener(v ->
                toggleSoundPlayback(selectedSoundPlayPause));
        selectedSoundSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && mediaPlayer != null) {
                    mediaPlayer.seekTo(progress);
                    selectedSoundCurrent.setText(formatDuration(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        addSoundDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.resource_add_sound)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.resource_save, null)
                .create();
        addSoundDialog.setOnShowListener(ignored ->
                addSoundDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(v -> {
                            if (selectedSoundUri == null || selectedSoundDisplayName == null) {
                                Toast.makeText(
                                        this,
                                        R.string.resource_choose_sound_first,
                                        Toast.LENGTH_SHORT).show();
                                return;
                            }
                            String name = selectedSoundName.getText() == null
                                    ? "" : selectedSoundName.getText().toString().trim();
                            if (!isValidResourceName(name)) {
                                selectedSoundNameLayout.setError(
                                        getString(R.string.resource_name_error));
                                return;
                            }
                            String extension = extensionOf(selectedSoundDisplayName);
                            if (!isSupportedExtension(extension)) {
                                selectedSoundNameLayout.setError(
                                        getString(R.string.resource_type_error));
                                return;
                            }
                            selectedSoundNameLayout.setError(null);
                            Uri source = selectedSoundUri;
                            boolean collection = addToCollection.isChecked();
                            addSoundDialog.dismiss();
                            savePickedResource(source, extension, name, collection);
                        }));
        addSoundDialog.setOnDismissListener(dialog -> {
            releasePlayer();
            addSoundDialog = null;
            selectedSoundUri = null;
        });
        addSoundDialog.show();
    }

    private void bindSelectedSound(Uri uri) {
        releasePlayer();
        selectedSoundUri = uri;
        selectedSoundDisplayName = queryDisplayName(uri);
        selectedSoundGuide.setVisibility(View.GONE);
        selectedSoundAlbum.setVisibility(View.VISIBLE);
        selectedSoundControls.setVisibility(View.VISIBLE);
        selectedSoundFileName.setText(selectedSoundDisplayName);
        selectedSoundName.setText(suggestResourceName(selectedSoundDisplayName));
        selectedSoundName.selectAll();
        selectedSoundNameLayout.setError(null);

        Bitmap album = extractAlbumArt(uri);
        if (album != null) {
            selectedSoundAlbum.setImageTintList(null);
            selectedSoundAlbum.setScaleType(ImageView.ScaleType.CENTER_CROP);
            selectedSoundAlbum.setImageBitmap(album);
        } else {
            selectedSoundAlbum.setScaleType(ImageView.ScaleType.CENTER);
            selectedSoundAlbum.setImageTintList(
                    ColorStateList.valueOf(getColor(R.color.chat_accent)));
            selectedSoundAlbum.setImageResource(R.drawable.ic_mtrl_music);
        }

        mediaPlayer = MediaPlayer.create(this, uri);
        if (mediaPlayer == null) {
            selectedSoundPlayPause.setEnabled(false);
            selectedSoundSeek.setEnabled(false);
            Toast.makeText(this, R.string.resource_sound_play_error, Toast.LENGTH_SHORT).show();
            return;
        }
        selectedSoundPlayPause.setEnabled(true);
        selectedSoundSeek.setEnabled(true);
        selectedSoundSeek.setMax(mediaPlayer.getDuration());
        selectedSoundSeek.setProgress(0);
        selectedSoundCurrent.setText("0:00");
        selectedSoundDuration.setText(formatDuration(mediaPlayer.getDuration()));
        mediaPlayer.setOnCompletionListener(player -> {
            selectedSoundSeek.setProgress(0);
            selectedSoundCurrent.setText("0:00");
            selectedSoundPlayPause.setImageResource(R.drawable.ic_mtrl_circle_play);
        });
        configurePlayerProgress(
                selectedSoundSeek,
                selectedSoundCurrent,
                selectedSoundPlayPause);
    }

    private void toggleSoundPlayback(ImageButton playPause) {
        if (mediaPlayer == null) {
            return;
        }
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            playPause.setImageResource(R.drawable.ic_mtrl_circle_play);
        } else {
            mediaPlayer.start();
            playPause.setImageResource(R.drawable.ic_mtrl_circle_pause);
            if (playerProgressTask != null) {
                playerHandler.removeCallbacks(playerProgressTask);
                playerHandler.post(playerProgressTask);
            }
        }
    }

    private void configurePlayerProgress(
            SeekBar seek,
            TextView current,
            ImageButton playPause
    ) {
        playerProgressTask = new Runnable() {
            @Override
            public void run() {
                if (mediaPlayer != null) {
                    int position = mediaPlayer.getCurrentPosition();
                    seek.setProgress(position);
                    current.setText(formatDuration(position));
                    if (mediaPlayer.isPlaying()) {
                        playerHandler.postDelayed(this, 350L);
                    } else {
                        playPause.setImageResource(R.drawable.ic_mtrl_circle_play);
                    }
                }
            }
        };
    }

    private void loadPage(int position) {
        ResourcePage page = pages[position];
        if (page == null) {
            return;
        }
        File directory = position == 1 ? getCollectionDirectory() : getProjectDirectory();
        if (!directory.exists() && !directory.mkdirs()) {
            Toast.makeText(this, R.string.resource_directory_error, Toast.LENGTH_SHORT).show();
        }

        File[] files = directory.listFiles(file ->
                file.isFile() && isSupportedExtension(extensionOf(file.getName())));
        page.items.clear();
        if (files != null) {
            Arrays.sort(files, Comparator.comparing(
                    File::getName, String.CASE_INSENSITIVE_ORDER));
            for (File file : files) {
                page.items.add(new ResourceItem(file));
            }
        }
        page.adapter.notifyDataSetChanged();
        boolean empty = page.items.isEmpty();
        page.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        page.list.setVisibility(empty ? View.GONE : View.VISIBLE);
        page.emptyIcon.setImageResource(resourceIcon());
        page.emptyText.setText(emptyMessage());
    }

    private void showSaveResourceDialog(Uri sourceUri) {
        View content = LayoutInflater.from(this)
                .inflate(R.layout.dialog_save_project_resource, null, false);
        ImageView preview = content.findViewById(R.id.save_resource_preview);
        TextInputLayout nameLayout = content.findViewById(R.id.save_resource_name_layout);
        TextInputEditText nameInput = content.findViewById(R.id.save_resource_name);
        CheckBox addToCollection = content.findViewById(R.id.save_resource_collection);

        String displayName = queryDisplayName(sourceUri);
        nameInput.setText(suggestResourceName(displayName));
        nameInput.selectAll();
        bindPickedResourcePreview(preview, sourceUri);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(addDialogTitle())
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.resource_save, null)
                .create();
        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String name = nameInput.getText() == null
                            ? "" : nameInput.getText().toString().trim();
                    if (!isValidResourceName(name)) {
                        nameLayout.setError(getString(R.string.resource_name_error));
                        return;
                    }
                    String extension = extensionOf(displayName);
                    if (!isSupportedExtension(extension)) {
                        nameLayout.setError(getString(isFontManager()
                                ? R.string.resource_font_type_error
                                : R.string.resource_type_error));
                        return;
                    }
                    nameLayout.setError(null);
                    dialog.dismiss();
                    savePickedResource(
                            sourceUri, extension, name, addToCollection.isChecked());
                }));
        dialog.show();
    }

    private void bindPickedResourcePreview(ImageView preview, Uri uri) {
        preview.setImageTintList(ColorStateList.valueOf(getColor(R.color.chat_accent)));
        if (isImageManager()) {
            preview.setImageTintList(null);
            preview.setImageURI(uri);
        } else if (isSoundManager()) {
            Bitmap album = extractAlbumArt(uri);
            if (album != null) {
                preview.setImageTintList(null);
                preview.setImageBitmap(album);
            } else {
                preview.setImageResource(R.drawable.ic_mtrl_music);
            }
        } else {
            preview.setImageResource(R.drawable.ic_mtrl_font);
        }
    }

    private void savePickedResource(
            Uri sourceUri,
            String extension,
            String resourceName,
            boolean addToCollection
    ) {
        File projectTarget = new File(
                getProjectDirectory(), resourceName + "." + extension);
        if (projectTarget.exists()) {
            Toast.makeText(this, R.string.resource_name_exists, Toast.LENGTH_SHORT).show();
            return;
        }

        fileExecutor.execute(() -> {
            try {
                copyUri(sourceUri, projectTarget);
                if (addToCollection) {
                    copyFile(projectTarget, new File(
                            getCollectionDirectory(), projectTarget.getName()));
                }
                runOnUiThread(() -> {
                    Toast.makeText(this, R.string.resource_saved, Toast.LENGTH_SHORT).show();
                    loadPage(0);
                    loadPage(1);
                });
            } catch (IOException error) {
                if (projectTarget.exists()) {
                    projectTarget.delete();
                }
                runOnUiThread(() -> Toast.makeText(
                        this, R.string.resource_save_error, Toast.LENGTH_LONG).show());
            }
        });
    }

    private void showSoundPlayer(ResourceItem item) {
        stopPlayback();
        View content = LayoutInflater.from(this)
                .inflate(R.layout.dialog_sound_player, null, false);
        ImageView albumArt = content.findViewById(R.id.sound_album_art);
        TextView fileName = content.findViewById(R.id.sound_file_name);
        ImageButton playPause = content.findViewById(R.id.sound_play_pause);
        SeekBar seek = content.findViewById(R.id.sound_seek);
        TextView current = content.findViewById(R.id.sound_current_time);
        TextView duration = content.findViewById(R.id.sound_duration);

        fileName.setText(item.file.getName());
        Bitmap album = extractAlbumArt(item.file);
        if (album != null) {
            albumArt.setImageTintList(null);
            albumArt.setImageBitmap(album);
        }

        mediaPlayer = MediaPlayer.create(this, Uri.fromFile(item.file));
        if (mediaPlayer == null) {
            Toast.makeText(this, R.string.resource_sound_play_error, Toast.LENGTH_SHORT).show();
            return;
        }
        seek.setMax(mediaPlayer.getDuration());
        duration.setText(formatDuration(mediaPlayer.getDuration()));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && mediaPlayer != null) {
                    mediaPlayer.seekTo(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        playPause.setOnClickListener(v -> {
            if (mediaPlayer == null) {
                return;
            }
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                playPause.setImageResource(R.drawable.ic_mtrl_circle_play);
            } else {
                mediaPlayer.start();
                playPause.setImageResource(R.drawable.ic_mtrl_circle_pause);
                if (playerProgressTask != null) {
                    playerHandler.removeCallbacks(playerProgressTask);
                    playerHandler.post(playerProgressTask);
                }
            }
        });

        playerProgressTask = new Runnable() {
            @Override
            public void run() {
                if (mediaPlayer != null) {
                    int position = mediaPlayer.getCurrentPosition();
                    seek.setProgress(position);
                    current.setText(formatDuration(position));
                    if (mediaPlayer.isPlaying()) {
                        playerHandler.postDelayed(this, 350L);
                    }
                }
            }
        };
        mediaPlayer.setOnCompletionListener(player -> {
            seek.setProgress(0);
            current.setText("0:00");
            playPause.setImageResource(R.drawable.ic_mtrl_circle_play);
        });

        playerDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.resource_sound_player_title)
                .setView(content)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        playerDialog.setOnDismissListener(dialog -> releasePlayer());
        playerDialog.show();
        mediaPlayer.start();
        playPause.setImageResource(R.drawable.ic_mtrl_circle_pause);
        playerHandler.post(playerProgressTask);
    }

    private void confirmDelete(int pageIndex, ResourceItem item) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.resource_delete_title)
                .setMessage(getString(R.string.resource_delete_message, item.displayName))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.resource_delete, (dialog, which) -> {
                    stopPlayback();
                    if (item.file.delete()) {
                        loadPage(pageIndex);
                    } else {
                        Toast.makeText(
                                this, R.string.resource_delete_error, Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void confirmImportFromCollection(ResourceItem item) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.resource_import_title)
                .setMessage(getString(R.string.resource_import_message, item.displayName))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.resource_import, (dialog, which) -> {
                    File target = new File(getProjectDirectory(), item.file.getName());
                    if (target.exists()) {
                        Toast.makeText(
                                this, R.string.resource_name_exists, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    fileExecutor.execute(() -> {
                        try {
                            copyFile(item.file, target);
                            runOnUiThread(() -> {
                                Toast.makeText(
                                        this, R.string.resource_imported, Toast.LENGTH_SHORT).show();
                                loadPage(0);
                            });
                        } catch (IOException error) {
                            runOnUiThread(() -> Toast.makeText(
                                    this, R.string.resource_save_error, Toast.LENGTH_LONG).show());
                        }
                    });
                })
                .show();
    }

    private void copyUri(Uri sourceUri, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs())) {
            throw new IOException("Cannot create " + parent);
        }
        try (InputStream input = getContentResolver().openInputStream(sourceUri);
             FileOutputStream output = new FileOutputStream(target)) {
            if (input == null) {
                throw new IOException("Cannot open selected file");
            }
            input.transferTo(output);
        }
    }

    private static void copyFile(File source, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs())) {
            throw new IOException("Cannot create " + parent);
        }
        try (InputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            input.transferTo(output);
        }
    }

    private Bitmap extractAlbumArt(Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, uri);
            return decodeAlbumArt(retriever.getEmbeddedPicture());
        } catch (RuntimeException ignored) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (IOException ignored) {
            }
        }
    }

    private static Bitmap extractAlbumArt(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            return decodeAlbumArt(retriever.getEmbeddedPicture());
        } catch (RuntimeException ignored) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (IOException ignored) {
            }
        }
    }

    private static Bitmap decodeAlbumArt(byte[] data) {
        if (data == null) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        int sample = 1;
        while (bounds.outWidth / sample > 720 || bounds.outHeight / sample > 720) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, options);
    }

    private void stopPlayback() {
        if (playerDialog != null) {
            AlertDialog dialog = playerDialog;
            playerDialog = null;
            dialog.dismiss();
        } else {
            releasePlayer();
        }
    }

    private void releasePlayer() {
        if (playerProgressTask != null) {
            playerHandler.removeCallbacks(playerProgressTask);
            playerProgressTask = null;
        }
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    private File getProjectDirectory() {
        return ProjectResourcePaths.getCategoryDirectory(projectId, resourceType);
    }

    private boolean isValidResourceName(String name) {
        if (name == null || name.trim().isEmpty() || name.contains("/") || name.contains("\\")) {
            return false;
        }
        if (!webProject) {
            return VALID_RESOURCE_NAME.matcher(name).matches();
        }
        // Web resources are regular files, not Android resource identifiers.
        return name.matches("[A-Za-z0-9][A-Za-z0-9._-]*");
    }

    private File getCollectionDirectory() {
        String folder = isImageManager() ? "images" : isSoundManager() ? "sounds" : "fonts";
        return new File(getFilesDir(), "resource_collections/" + folder);
    }

    private boolean isImageManager() {
        return TYPE_IMAGE.equals(resourceType);
    }

    private boolean isSoundManager() {
        return TYPE_SOUND.equals(resourceType);
    }

    private boolean isFontManager() {
        return TYPE_FONT.equals(resourceType);
    }

    private boolean isSupportedExtension(String extension) {
        if (isImageManager()) {
            return extension.matches(webProject
                    ? "png|jpg|jpeg|webp|gif|svg|avif"
                    : "png|jpg|jpeg|webp|gif|xml");
        }
        if (isSoundManager()) {
            return extension.matches("mp3|wav|ogg|m4a|aac|flac");
        }
        return extension.matches("ttf|otf");
    }

    private int resourceIcon() {
        return isImageManager()
                ? R.drawable.ic_mtrl_image
                : isSoundManager() ? R.drawable.ic_mtrl_music : R.drawable.ic_mtrl_font;
    }

    private int emptyMessage() {
        return isImageManager()
                ? R.string.resource_image_empty
                : isSoundManager()
                ? R.string.resource_sound_empty
                : R.string.resource_font_empty;
    }

    private int addDialogTitle() {
        return isImageManager()
                ? R.string.resource_add_image
                : isSoundManager()
                ? R.string.resource_add_sound
                : R.string.resource_add_font;
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
        String lastSegment = uri.getLastPathSegment();
        return lastSegment == null ? "resource" : lastSegment;
    }

    private static String suggestResourceName(String fileName) {
        String sanitized = stripExtension(fileName).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        if (sanitized.isEmpty()) {
            return "resource";
        }
        return Character.isLetter(sanitized.charAt(0))
                ? sanitized : "resource_" + sanitized;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot < name.length() - 1
                ? name.substring(dot + 1).toLowerCase(Locale.ROOT)
                : "";
    }

    private static String formatDuration(int millis) {
        int totalSeconds = Math.max(0, millis / 1000);
        return String.format(
                Locale.getDefault(), "%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    @Override
    protected void onStop() {
        stopPlayback();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        fileExecutor.shutdownNow();
        releasePlayer();
        super.onDestroy();
    }

    private final class ResourcePagerAdapter extends PagerAdapter {

        @Override
        public int getCount() {
            return 2;
        }

        @Override
        public CharSequence getPageTitle(int position) {
            return getString(position == 0
                    ? R.string.resource_this_project
                    : R.string.resource_my_collection);
        }

        @NonNull
        @Override
        public Object instantiateItem(@NonNull ViewGroup container, int position) {
            View root = LayoutInflater.from(container.getContext())
                    .inflate(R.layout.page_project_resources, container, false);
            RecyclerView list = root.findViewById(R.id.resource_page_list);
            View emptyState = root.findViewById(R.id.resource_page_empty);
            ImageView emptyIcon = root.findViewById(R.id.resource_page_empty_icon);
            TextView emptyText = root.findViewById(R.id.resource_page_empty_text);
            List<ResourceItem> items = new ArrayList<>();
            ResourceAdapter adapter = new ResourceAdapter(items, position);

            if (isFontManager()) {
                list.setLayoutManager(new LinearLayoutManager(
                        ProjectResourceManagerActivity.this));
            } else {
                int widthDp = (int) (getResources().getDisplayMetrics().widthPixels
                        / getResources().getDisplayMetrics().density);
                list.setLayoutManager(new GridLayoutManager(
                        ProjectResourceManagerActivity.this, Math.max(2, widthDp / 112)));
            }
            list.setAdapter(adapter);
            pages[position] = new ResourcePage(
                    list, emptyState, emptyIcon, emptyText, items, adapter);
            container.addView(root);
            loadPage(position);
            return root;
        }

        @Override
        public void destroyItem(
                @NonNull ViewGroup container, int position, @NonNull Object object) {
            pages[position] = null;
            container.removeView((View) object);
        }

        @Override
        public boolean isViewFromObject(
                @NonNull View view, @NonNull Object object) {
            return view == object;
        }
    }

    private static final class ResourcePage {
        final RecyclerView list;
        final View emptyState;
        final ImageView emptyIcon;
        final TextView emptyText;
        final List<ResourceItem> items;
        final ResourceAdapter adapter;

        ResourcePage(
                RecyclerView list,
                View emptyState,
                ImageView emptyIcon,
                TextView emptyText,
                List<ResourceItem> items,
                ResourceAdapter adapter
        ) {
            this.list = list;
            this.emptyState = emptyState;
            this.emptyIcon = emptyIcon;
            this.emptyText = emptyText;
            this.items = items;
            this.adapter = adapter;
        }
    }

    private static final class ResourceItem {
        final File file;
        final String displayName;

        ResourceItem(File file) {
            this.file = file;
            this.displayName = stripExtension(file.getName());
        }
    }

    private final class ResourceAdapter
            extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int VIEW_RESOURCE = 0;
        private static final int VIEW_FONT = 1;

        private final List<ResourceItem> items;
        private final int pageIndex;

        ResourceAdapter(List<ResourceItem> items, int pageIndex) {
            this.items = items;
            this.pageIndex = pageIndex;
        }

        @Override
        public int getItemViewType(int position) {
            return isFontManager() ? VIEW_FONT : VIEW_RESOURCE;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(
                @NonNull ViewGroup parent, int viewType) {
            if (viewType == VIEW_FONT) {
                return new FontViewHolder(LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_project_font, parent, false));
            }
            return new ResourceViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_project_resource, parent, false));
        }

        @Override
        public void onBindViewHolder(
                @NonNull RecyclerView.ViewHolder holder, int position) {
            ResourceItem item = items.get(position);
            if (holder instanceof FontViewHolder) {
                bindFont((FontViewHolder) holder, item);
            } else {
                bindResource((ResourceViewHolder) holder, item);
            }
            holder.itemView.setOnClickListener(v -> {
                if (pageIndex == 1) {
                    confirmImportFromCollection(item);
                } else if (isSoundManager()) {
                    showSoundPlayer(item);
                }
            });
            holder.itemView.setOnLongClickListener(v -> {
                confirmDelete(pageIndex, item);
                return true;
            });
        }

        private void bindResource(ResourceViewHolder holder, ResourceItem item) {
            holder.name.setText(item.displayName);
            holder.preview.setImageTintList(
                    ColorStateList.valueOf(getColor(R.color.chat_accent)));
            if (isImageManager() && !"xml".equals(extensionOf(item.file.getName()))) {
                Bitmap bitmap = BitmapFactory.decodeFile(item.file.getAbsolutePath());
                if (bitmap != null) {
                    holder.preview.setImageTintList(null);
                    holder.preview.setImageBitmap(bitmap);
                    return;
                }
            } else if (isSoundManager()) {
                Bitmap album = extractAlbumArt(item.file);
                if (album != null) {
                    holder.preview.setImageTintList(null);
                    holder.preview.setImageBitmap(album);
                    return;
                }
            }
            holder.preview.setImageResource(resourceIcon());
        }

        private void bindFont(FontViewHolder holder, ResourceItem item) {
            holder.name.setText(item.displayName);
            holder.preview.setTypeface(Typeface.DEFAULT);
            try {
                holder.preview.setTypeface(Typeface.createFromFile(item.file));
            } catch (RuntimeException ignored) {
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private static final class ResourceViewHolder extends RecyclerView.ViewHolder {
        final ImageView preview;
        final TextView name;

        ResourceViewHolder(@NonNull View itemView) {
            super(itemView);
            preview = itemView.findViewById(R.id.resource_item_preview);
            name = itemView.findViewById(R.id.resource_item_name);
        }
    }

    private static final class FontViewHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView preview;

        FontViewHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.font_item_name);
            preview = itemView.findViewById(R.id.font_item_preview);
        }
    }
}
