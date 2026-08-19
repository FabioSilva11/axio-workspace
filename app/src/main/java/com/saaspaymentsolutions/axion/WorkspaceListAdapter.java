package com.saaspaymentsolutions.axion;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter that shows workspace items in the main list.
 */
public class WorkspaceListAdapter extends RecyclerView.Adapter<WorkspaceListAdapter.ViewHolder> {

    public interface OnWorkspaceClickListener {
        void onWorkspaceClick(com.saaspaymentsolutions.axion.workspace.Workspace workspace);
    }

    public interface OnWorkspaceMenuClickListener {
        void onWorkspaceMenuClick(com.saaspaymentsolutions.axion.workspace.Workspace workspace);
    }

    private List<com.saaspaymentsolutions.axion.workspace.Workspace> items = new ArrayList<>();
    private final OnWorkspaceClickListener clickListener;
    private final OnWorkspaceMenuClickListener menuClickListener;

    public WorkspaceListAdapter(OnWorkspaceClickListener clickListener,
                                OnWorkspaceMenuClickListener menuClickListener) {
        this.clickListener = clickListener;
        this.menuClickListener = menuClickListener;
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
        com.saaspaymentsolutions.axion.workspace.Workspace ws = items.get(position);

        holder.textAppName.setText(ws.getName());
        holder.textProjectName.setText(ws.getName());
        holder.imgIcon.setImageResource(R.drawable.ic_mtrl_folder);
        holder.textPackageName.setText(ws.getDetectedTechnology() != null && !ws.getDetectedTechnology().isEmpty()
                ? ws.getDetectedTechnology() : ws.getDisplayPath());
        holder.imgPin.setVisibility(ws.isPinned() ? View.VISIBLE : View.GONE);

        if (holder.imgKindBadge != null) holder.imgKindBadge.setVisibility(View.GONE);

        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) clickListener.onWorkspaceClick(ws);
        });

        holder.imgMore.setOnClickListener(v -> {
            if (menuClickListener != null) menuClickListener.onWorkspaceMenuClick(ws);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public void updateList(List<com.saaspaymentsolutions.axion.workspace.Workspace> newList) {
        this.items = new ArrayList<>(newList);
        notifyDataSetChanged();
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
