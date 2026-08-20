package com.saaspaymentsolutions.axion;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.GravityCompat;
import androidx.core.content.FileProvider;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import com.saaspaymentsolutions.axion.analytics.AxionAnalytics;


import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_PICK_FOLDER = 1001;

    private DrawerLayout drawerLayout;
    private EditText editSearch;
    private SwipeRefreshLayout swipeRefresh;
    private RecyclerView recyclerProjects;
    private FrameLayout loadingContainer;
    private TextView textEmpty;
    private ExtendedFloatingActionButton fabNewProject;
    private LinearLayout drawerItemSkills;
    private LinearLayout drawerItemAiSettings;
    private LinearLayout drawerItemLogout;
    private TextView drawerUserName;

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private WorkspaceListAdapter adapter;
    private PrefsManager preference;
    private String currentFilter = "";
    private boolean accountLoadLogged;
    private List<com.saaspaymentsolutions.axion.workspace.Workspace> allWorkspaces = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        {
        }

        setContentView(R.layout.activity_main);

        preference = new PrefsManager(this, "project");

        initViews();
        setSupportActionBar(findViewById(R.id.toolbar));
        setupDrawer();
        setupSearch();
        setupFab();
        setupDrawerItems();
        setupRecyclerView();
        bindLocalUser();

        loadWorkspaces();
    }

    private void bindLocalUser() {
        drawerUserName.setText(getString(R.string.auth_default_user_name));
    }


    private void showWelcomeDialog(@Nullable String requestedName) {
        String name = requestedName == null || requestedName.trim().isEmpty()
                ? getString(R.string.auth_default_user_name)
                : requestedName.trim();

        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.welcome_title, name))
                .setMessage(R.string.welcome_message)
                .setPositiveButton(R.string.welcome_action, null)
                .show();
    }



    // ============================================
    // INIT
    // ============================================

    private void initViews() {
        drawerLayout = findViewById(R.id.drawer_layout);
        editSearch = findViewById(R.id.edit_search_projects);
        swipeRefresh = findViewById(R.id.swipe_refresh);
        recyclerProjects = findViewById(R.id.recycler_projects);
        loadingContainer = findViewById(R.id.loading_container);
        textEmpty = findViewById(R.id.text_empty);
        fabNewProject = findViewById(R.id.fab_new_project);
        drawerItemSkills = findViewById(R.id.drawer_item_skills);
        drawerItemAiSettings = findViewById(R.id.drawer_item_ai_settings);
        drawerItemLogout = findViewById(R.id.drawer_item_logout);
        drawerUserName = findViewById(R.id.drawer_user_name);
    }


    private void setupDrawer() {
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawerLayout, findViewById(R.id.toolbar),
                R.string.common_word_ok, R.string.common_word_cancel);
        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();

        findViewById(R.id.toolbar).setOnClickListener(v -> {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START);
            } else {
                drawerLayout.openDrawer(GravityCompat.START);
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_overflow_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_about) {
            startActivity(new Intent(this, AboutActivity.class));
            return true;
        }
        if (id == R.id.action_telegram) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/sketcware_ia")));
            return true;
        }
        if (id == R.id.action_share_apk) {
            shareInstalledApk();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void shareInstalledApk() {
        try {
            java.io.File apk = new java.io.File(getApplicationInfo().sourceDir);
            Uri apkUri = FileProvider.getUriForFile(this, getPackageName() + ".provider", apk);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("application/vnd.android.package-archive");
            share.putExtra(Intent.EXTRA_STREAM, apkUri);
            share.putExtra(Intent.EXTRA_TEXT, getString(R.string.share_apk_message));
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, getString(R.string.main_menu_share_apk)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.share_apk_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    private void setupSearch() {
        editSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                currentFilter = s.toString().trim().toLowerCase();
                filterWorkspaces();
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    private void setupFab() {
        fabNewProject.setOnClickListener(v -> {
            Intent intent = new Intent(this, FolderPickerActivity.class);
            startActivityForResult(intent, REQUEST_PICK_FOLDER);
        });
    }

    private void setupDrawerItems() {
        drawerItemSkills.setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            startActivity(new Intent(this, com.saaspaymentsolutions.axion.skills.SkillsActivity.class));
        });
        drawerItemAiSettings.setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            startActivity(new Intent(this, com.saaspaymentsolutions.axion.provider.IaSettingsActivity.class));
        });
        drawerItemLogout.setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.account_logout_title)
                    .setMessage(R.string.account_logout_message)
                    .setNegativeButton(R.string.common_word_cancel, null)
                    .setPositiveButton(R.string.account_logout, (dialog, which) -> {
                        AxionAnalytics.logEvent(this, AxionAnalytics.Events.LOGOUT);
                        AxionAnalytics.clearUser(this);
                    }).show();
        });
    }


    private void setupRecyclerView() {
        adapter = new WorkspaceListAdapter(
                workspace -> openWorkspace(workspace),
                this::showWorkspaceOptions);
        recyclerProjects.setLayoutManager(new LinearLayoutManager(this));
        recyclerProjects.setAdapter(adapter);
        swipeRefresh.setOnRefreshListener(this::loadWorkspaces);
    }

    // ============================================
    // WORKSPACE LIST
    // ============================================

    private void loadWorkspaces() {
        executorService.execute(() -> {
            com.saaspaymentsolutions.axion.workspace.WorkspaceRepository repo =
                    new com.saaspaymentsolutions.axion.workspace.WorkspaceRepository(this);
            List<com.saaspaymentsolutions.axion.workspace.Workspace> loaded = repo.getAll();

            new Handler(Looper.getMainLooper()).post(() -> {
                if (swipeRefresh.isRefreshing()) swipeRefresh.setRefreshing(false);
                if (loadingContainer.getVisibility() == View.VISIBLE) {
                    loadingContainer.setVisibility(View.GONE);
                    swipeRefresh.setVisibility(View.VISIBLE);
                }
                allWorkspaces.clear();
                allWorkspaces.addAll(loaded);
                filterWorkspaces();
            });
        });
    }

    private void filterWorkspaces() {
        if (adapter == null) return;
        if (currentFilter.isEmpty()) {
            adapter.updateList(allWorkspaces);
        } else {
            List<com.saaspaymentsolutions.axion.workspace.Workspace> filtered = new ArrayList<>();
            for (com.saaspaymentsolutions.axion.workspace.Workspace ws : allWorkspaces) {
                if (ws.getName().toLowerCase().contains(currentFilter)
                        || (ws.getDetectedTechnology() != null && ws.getDetectedTechnology().toLowerCase().contains(currentFilter))
                        || ws.getDisplayPath().toLowerCase().contains(currentFilter)) {
                    filtered.add(ws);
                }
            }
            adapter.updateList(filtered);
        }
        updateEmptyState();
    }

    private void updateEmptyState() {
        if (adapter != null && adapter.getItemCount() == 0) {
            textEmpty.setVisibility(View.VISIBLE);
            textEmpty.setText(allWorkspaces.isEmpty()
                    ? "No workspaces yet.\nTap + to open a folder."
                    : "No results found.");
            recyclerProjects.setVisibility(View.GONE);
        } else {
            textEmpty.setVisibility(View.GONE);
            recyclerProjects.setVisibility(View.VISIBLE);
        }
    }

    private void openWorkspace(com.saaspaymentsolutions.axion.workspace.Workspace ws) {
        if (ws == null) return;
        com.saaspaymentsolutions.axion.workspace.WorkspaceManager.openWorkspace(this, ws);
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra("sc_id", ws.getId());
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private void showWorkspaceOptions(com.saaspaymentsolutions.axion.workspace.Workspace ws) {
        if (ws == null) return;

        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View sheet = getLayoutInflater().inflate(R.layout.bottom_sheet_project_options, null);
        dialog.setContentView(sheet);

        TextView title = sheet.findViewById(R.id.title);
        TextView tvProjectId = sheet.findViewById(R.id.tv_project_id);
        View pinProject = sheet.findViewById(R.id.pin_project);
        View projectDelete = sheet.findViewById(R.id.project_delete);
        TextView pinText = sheet.findViewById(R.id.pin_text);

        if (title != null) title.setText(ws.getName());
        if (tvProjectId != null) tvProjectId.setText(ws.getDetectedTechnology() != null && !ws.getDetectedTechnology().isEmpty()
                ? ws.getDetectedTechnology() : ws.getDisplayPath());

        if (pinProject != null) {
            pinText.setText(ws.isPinned() ? R.string.project_options_unpin : R.string.project_options_pin);
            pinProject.setOnClickListener(v -> {
                com.saaspaymentsolutions.axion.workspace.WorkspaceRepository repo =
                        new com.saaspaymentsolutions.axion.workspace.WorkspaceRepository(this);
                repo.togglePin(ws.getId());
                loadWorkspaces();
                dialog.dismiss();
            });
        }

        if (projectDelete != null) {
            projectDelete.setOnClickListener(v -> {
                dialog.dismiss();
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.main_delete_project_title)
                        .setMessage("Remove \"" + ws.getName() + "\" from recent workspaces?\nFiles will not be deleted.")
                        .setNegativeButton(R.string.main_delete_project_cancel, null)
                        .setPositiveButton(R.string.main_delete_project_confirm, (d, w) -> {
                            com.saaspaymentsolutions.axion.workspace.WorkspaceRepository repo =
                                    new com.saaspaymentsolutions.axion.workspace.WorkspaceRepository(this);
                            repo.remove(ws.getId());
                            loadWorkspaces();
                        }).show();
            });
        }

        dialog.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_FOLDER && resultCode == RESULT_OK) {
            loadWorkspaces();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadWorkspaces();
    }

    @Override
    protected void onDestroy() {
        executorService.shutdown();
        super.onDestroy();
    }
}
