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
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import com.saaspaymentsolutions.axion.account.AxionAccount;
import com.saaspaymentsolutions.axion.account.FirebaseAccountStore;
import com.saaspaymentsolutions.axion.account.PlansActivity;
import com.saaspaymentsolutions.axion.account.ProfileActivity;
import com.saaspaymentsolutions.axion.analytics.AxionAnalytics;
import com.saaspaymentsolutions.axion.auth.AuthActivity;

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
    private View toolbarProfileButton;
    private LinearLayout drawerItemPlans;
    private LinearLayout drawerItemLogout;
    private TextView drawerUserName;
    private TextView drawerUserPlan;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private WorkspaceListAdapter adapter;
    private PrefsManager preference;
    private String currentFilter = "";
    private FirebaseAccountStore accountStore;
    private boolean accountLoadLogged;
    private List<com.saaspaymentsolutions.axion.workspace.Workspace> allWorkspaces = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null) {
            openAuthentication();
            return;
        }

        setContentView(R.layout.activity_main);

        preference = new PrefsManager(this, "project");
        accountStore = new FirebaseAccountStore(this);

        initViews();
        setupDrawer();
        setupSearch();
        setupFab();
        setupDrawerItems();
        setupRecyclerView();
        bindAuthenticatedUser(currentUser);
        observeAuthenticatedUser(currentUser);

        loadWorkspaces();

        boolean showWelcome = getIntent().getBooleanExtra("show_welcome", false);
        String welcomeName = getIntent().getStringExtra("user_name");
        if (showWelcome) {
            showWelcomeDialog(welcomeName);
        } else {
            loadRemoteAppConfiguration();
        }
    }

    private void openAuthentication() {
        Intent intent = new Intent(this, AuthActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void bindAuthenticatedUser(FirebaseUser user) {
        String name = getIntent().getStringExtra("user_name");
        if (name == null || name.trim().isEmpty()) {
            name = user.getDisplayName();
        }
        if (name == null || name.trim().isEmpty()) {
            String email = user.getEmail();
            name = email != null && email.contains("@")
                    ? email.substring(0, email.indexOf('@'))
                    : getString(R.string.auth_default_user_name);
        }
        drawerUserName.setText(name.trim());
        drawerUserPlan.setText(R.string.account_plan_loading);
    }

    private void observeAuthenticatedUser(FirebaseUser user) {
        accountStore.start(user, true, new FirebaseAccountStore.Listener() {
            @Override
            public void onAccountChanged(@NonNull AxionAccount account) {
                drawerUserName.setText(account.name);
                drawerUserPlan.setText(account.isPaid()
                        ? R.string.account_plan_paid
                        : R.string.account_plan_free);
                AxionAnalytics.setUser(MainActivity.this, account.uid, account.planId);
            }

            @Override
            public void onError(@NonNull Exception error) {
                drawerUserPlan.setText(R.string.account_load_error);
            }
        });
    }

    private void showWelcomeDialog(@Nullable String requestedName) {
        String name = requestedName == null || requestedName.trim().isEmpty()
                ? getString(R.string.auth_default_user_name)
                : requestedName.trim();
        FrameLayout root = findViewById(android.R.id.content);
        com.saaspaymentsolutions.axion.auth.ConfettiView confetti = new com.saaspaymentsolutions.axion.auth.ConfettiView(this);
        root.addView(confetti, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.welcome_title, name))
                .setMessage(R.string.welcome_message)
                .setPositiveButton(R.string.welcome_action, null)
                .setCancelable(false)
                .create();
        dialog.setOnDismissListener(ignored -> { });
        dialog.show();
        confetti.bringToFront();
        confetti.start();
        root.postDelayed(() -> {
            if (confetti.getParent() == root) root.removeView(confetti);
        }, 3200L);
    }

    private void loadRemoteAppConfiguration() {
        AxionRemoteAppConfig.load(this, new AxionRemoteAppConfig.Listener() {
            @Override
            public void onLoaded(@NonNull AxionRemoteAppConfig.Config config) {
                if (isFinishing() || isDestroyed()) return;
                if (config.dialogEnabled && config.dialogBody != null && !config.dialogBody.trim().isEmpty()) {
                    SharedPreferences prefs = getSharedPreferences("axion_remote_config", MODE_PRIVATE);
                    String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
                    boolean shouldShow = "always".equals(config.dialogFrequency)
                            || ("once_per_day".equals(config.dialogFrequency) && !today.equals(prefs.getString("dialog_day", "")))
                            || prefs.getLong("dialog_revision", -1L) != config.dialogRevision;
                    if (shouldShow) {
                        new MaterialAlertDialogBuilder(MainActivity.this)
                                .setTitle(config.dialogTitle)
                                .setMessage(config.dialogBody)
                                .setCancelable(false)
                                .setPositiveButton(config.dialogButtonLabel == null || config.dialogButtonLabel.trim().isEmpty()
                                        ? getString(R.string.common_understood) : config.dialogButtonLabel, (d, w) -> {
                                    prefs.edit().putLong("dialog_revision", config.dialogRevision).putString("dialog_day", today).apply();
                                    if (config.dialogButtonUrl != null && !config.dialogButtonUrl.trim().isEmpty()) {
                                        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(config.dialogButtonUrl.trim()))); } catch (Exception ignored) {}
                                    }
                                    d.dismiss();
                                }).show();
                    }
                }
            }

            @Override
            public void onError(@NonNull com.google.firebase.database.DatabaseError error) { }
        });
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
        toolbarProfileButton = findViewById(R.id.toolbar_profile_button);
        drawerItemPlans = findViewById(R.id.drawer_item_plans);
        drawerItemLogout = findViewById(R.id.drawer_item_logout);
        drawerUserName = findViewById(R.id.drawer_user_name);
        drawerUserPlan = findViewById(R.id.drawer_user_plan);
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
        toolbarProfileButton.setOnClickListener(v ->
                startActivity(new Intent(this, ProfileActivity.class)));
        drawerItemPlans.setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            startActivity(new Intent(this, PlansActivity.class));
        });
        drawerItemLogout.setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.account_logout_title)
                    .setMessage(R.string.account_logout_message)
                    .setNegativeButton(R.string.common_word_cancel, null)
                    .setPositiveButton(R.string.account_logout, (dialog, which) -> {
                        AxionAnalytics.logEvent(this, AxionAnalytics.Events.LOGOUT);
                        if (accountStore != null) accountStore.stop();
                        FirebaseAuth.getInstance().signOut();
                        AxionAnalytics.clearUser(this);
                        openAuthentication();
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
        if (accountStore != null) accountStore.stop();
        executorService.shutdown();
        super.onDestroy();
    }
}
