package com.saaspaymentsolutions.axion.chat;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import com.saaspaymentsolutions.axion.ProjectManager;
import com.saaspaymentsolutions.axion.ClickGuard;
import com.saaspaymentsolutions.axion.AxionPathUtils;
import com.saaspaymentsolutions.axion.MapUtils;
import com.saaspaymentsolutions.axion.ProjectMapUtils;
import com.saaspaymentsolutions.axion.R;
import com.saaspaymentsolutions.axion.databinding.ChatProjectItemBinding;

public class ChatProjectsAdapter extends RecyclerView.Adapter<ChatProjectsAdapter.ProjectViewHolder> {
    private final ChatFragment chatFragment;
    private final Activity activity;
    private List<HashMap<String, Object>> shownProjects = new ArrayList<>();
    private List<HashMap<String, Object>> allProjects;

    public ChatProjectsAdapter(ChatFragment chatFragment, List<HashMap<String, Object>> allProjects) {
        this.chatFragment = chatFragment;
        activity = chatFragment.requireActivity();
        this.allProjects = allProjects;
        // Inicializar shownProjects com todos os projetos
        this.shownProjects = new ArrayList<>(allProjects);
    }

    public void setAllProjects(List<HashMap<String, Object>> projects) {
        allProjects = projects;
        // Atualizar shownProjects também
        shownProjects = new ArrayList<>(projects);
        notifyDataSetChanged();
    }

    public void filterData(String query) {
        List<HashMap<String, Object>> newProjects = query.isEmpty() ? allProjects : new ArrayList<>();
        if (!query.isEmpty()) {
            for (HashMap<String, Object> project : allProjects) {
                if (matchesQuery(project, query)) {
                    newProjects.add(project);
                }
            }
        }

        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return shownProjects.size();
            }

            @Override
            public int getNewListSize() {
                return newProjects.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                String oldScId = MapUtils.c(shownProjects.get(oldItemPosition), "sc_id");
                String newScId = MapUtils.c(newProjects.get(newItemPosition), "sc_id");
                return oldScId.equalsIgnoreCase(newScId);
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                HashMap<String, Object> oldMap = shownProjects.get(oldItemPosition);
                HashMap<String, Object> newMap = newProjects.get(newItemPosition);
                for (String key : Arrays.asList("my_app_name", "my_ws_name", "sc_ver_name", "sc_ver_code", "my_sc_pkg_name")) {
                    if (!MapUtils.c(oldMap, key).equals(MapUtils.c(newMap, key))) {
                        return false;
                    }
                }
                boolean oldCustomIcon = ProjectMapUtils.getBoolean(oldMap, "custom_icon");
                boolean newCustomIcon = ProjectMapUtils.getBoolean(newMap, "custom_icon");
                return oldCustomIcon == newCustomIcon;
            }
        }, true);
        shownProjects = newProjects;
        result.dispatchUpdatesTo(this);
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return shownProjects.size();
    }

    private boolean matchesQuery(HashMap<String, Object> projectMap, String searchQuery) {
        searchQuery = searchQuery.toLowerCase();
        for (String key : Arrays.asList("sc_id", "my_ws_name", "my_app_name", "my_sc_pkg_name")) {
            if (MapUtils.c(projectMap, key).toLowerCase().contains(searchQuery)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onBindViewHolder(@NonNull ProjectViewHolder holder, int position) {
        HashMap<String, Object> projectMap = shownProjects.get(position);
        String scId = MapUtils.c(projectMap, "sc_id");

        holder.binding.imgIcon.setImageResource(R.drawable.default_icon);

        if (MapUtils.c(projectMap, "sc_ver_code").isEmpty()) {
            projectMap.put("sc_ver_code", "1");
            projectMap.put("sc_ver_name", "1.0");
            ProjectManager.b(scId, projectMap);
        }

        if (MapUtils.b(projectMap, "sketchware_ver") <= 0) {
            projectMap.put("sketchware_ver", 61);
            ProjectManager.b(scId, projectMap);
        }

        if (ProjectMapUtils.getBoolean(projectMap, "custom_icon")) {
            File iconFile = new File(AxionPathUtils.getAndroidStudioProjectPath(scId), "icon.png");
            if (iconFile.exists()) {
                String providerPath = activity.getPackageName() + ".provider";
                holder.binding.imgIcon.setImageURI(FileProvider.getUriForFile(activity, providerPath, iconFile));
            } else {
                holder.binding.imgIcon.setImageResource(R.drawable.default_icon);
            }
        }

        String version = " - " + MapUtils.c(projectMap, "sc_ver_name") + " (" + MapUtils.c(projectMap, "sc_ver_code") + ")";
        holder.binding.appName.setText(MapUtils.c(projectMap, "my_ws_name") + version);
        holder.binding.projectName.setText(MapUtils.c(projectMap, "my_app_name"));
        holder.binding.packageName.setText(MapUtils.c(projectMap, "my_sc_pkg_name"));

        holder.itemView.setTag("custom");

        // Clique abre ChatActivity ao invés de DesignActivity
        holder.binding.getRoot().setOnClickListener(v -> {
            if (!ClickGuard.a()) {
                chatFragment.toChatActivity(scId);
            }
        });

    }

    @NonNull
    @Override
    public ProjectViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ChatProjectItemBinding binding = ChatProjectItemBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new ProjectViewHolder(binding);
    }

    static class ProjectViewHolder extends RecyclerView.ViewHolder {
        final ChatProjectItemBinding binding;

        ProjectViewHolder(@NonNull ChatProjectItemBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}



