package com.saaspaymentsolutions.axion;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class ProjectsListAdapter extends RecyclerView.Adapter<ProjectsListAdapter.ViewHolder> {

    public interface OnProjectClickListener {
        void onProjectClick(HashMap<String, Object> project);
    }

    public interface OnProjectMenuClickListener {
        void onProjectMenuClick(HashMap<String, Object> project);
    }

    private List<HashMap<String, Object>> projects = new ArrayList<>();
    private final OnProjectClickListener clickListener;
    private final OnProjectMenuClickListener menuClickListener;
    private String pinnedProjectId;

    public ProjectsListAdapter(List<HashMap<String, Object>> projects,
                               OnProjectClickListener clickListener,
                               OnProjectMenuClickListener menuClickListener,
                               String pinnedProjectId) {
        this.projects = new ArrayList<>(projects);
        this.clickListener = clickListener;
        this.menuClickListener = menuClickListener;
        this.pinnedProjectId = pinnedProjectId;
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
        HashMap<String, Object> project = projects.get(position);

        String name = getStringSafe(project.get("my_ws_name"));
        String packageName = getStringSafe(project.get("my_sc_pkg_name"));
        String scId = getStringSafe(project.get("sc_id"));
        String appName = getStringSafe(project.get("my_app_name"));

        if (name.isEmpty()) name = scId;
        if (appName.isEmpty()) appName = name;

        holder.textAppName.setText(appName);
        holder.textProjectName.setText(name);
        holder.textPackageName.setText(packageName);
        holder.imgPin.setVisibility(scId.equals(pinnedProjectId) ? View.VISIBLE : View.GONE);

        // Selo que identifica o tipo do projeto: web (Three.js) ou nativo (Android).
        if (holder.imgKindBadge != null) {
            boolean isWeb = ProjectManager.isWebProject(project);
            holder.imgKindBadge.setImageResource(isWeb ? R.drawable.ic_mtrl_web : R.drawable.ic_mtrl_android);
            holder.imgKindBadge.setBackgroundResource(isWeb ? R.drawable.bg_badge_web : R.drawable.bg_badge_native);
            holder.imgKindBadge.setContentDescription(holder.itemView.getContext().getString(
                    isWeb ? R.string.project_kind_web : R.string.project_kind_native));
            holder.imgKindBadge.setVisibility(View.VISIBLE);
        }

        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onProjectClick(project);
            }
        });

        holder.imgMore.setOnClickListener(v -> {
            if (menuClickListener != null) {
                menuClickListener.onProjectMenuClick(project);
            }
        });
    }

    @Override
    public int getItemCount() {
        return projects.size();
    }

    public void updateList(List<HashMap<String, Object>> newList) {
        this.projects = new ArrayList<>(newList);
        notifyDataSetChanged();
    }

    public void setPinnedProjectId(String pinnedProjectId) {
        this.pinnedProjectId = pinnedProjectId;
        notifyDataSetChanged();
    }

    private String getStringSafe(Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView textAppName;
        TextView textProjectName;
        TextView textPackageName;
        ImageView imgIcon;
        ImageView imgPin;
        ImageView imgMore;
        ImageView imgKindBadge;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            textAppName = itemView.findViewById(R.id.app_name);
            textProjectName = itemView.findViewById(R.id.project_name);
            textPackageName = itemView.findViewById(R.id.package_name);
            imgIcon = itemView.findViewById(R.id.img_icon);
            imgPin = itemView.findViewById(R.id.img_pin);
            imgMore = itemView.findViewById(R.id.img_more);
            imgKindBadge = itemView.findViewById(R.id.img_kind_badge);
        }
    }
}
