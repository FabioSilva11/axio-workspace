package com.saaspaymentsolutions.axion;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import java.util.List;

public class ThemePresetAdapter extends RecyclerView.Adapter<ThemePresetAdapter.ViewHolder> {
    private final Activity activity;
    private final List<ThemeManager.ThemePreset> presets;
    private final OnThemeSelectedListener listener;
    private int selectedPosition = -1;

    public interface OnThemeSelectedListener {
        void onThemeSelected(ThemeManager.ThemePreset theme, int position);
    }

    public ThemePresetAdapter(Activity activity, List<ThemeManager.ThemePreset> presets, OnThemeSelectedListener listener) {
        this.activity = activity;
        this.presets = presets;
        this.listener = listener;
    }

    public void unselectThePreviousTheme(int newPosition) {
        int oldPosition = selectedPosition;
        selectedPosition = newPosition;
        if (oldPosition >= 0) notifyItemChanged(oldPosition);
        if (newPosition >= 0) notifyItemChanged(newPosition);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.theme_preset_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ThemeManager.ThemePreset preset = presets.get(position);
        holder.name.setText(preset.name);

        holder.colorAccent.setBackgroundColor(preset.colorAccent);
        holder.colorPrimary.setBackgroundColor(preset.colorPrimary);
        holder.colorPrimaryDark.setBackgroundColor(preset.colorPrimaryDark);
        holder.colorControlHighlight.setBackgroundColor(preset.colorControlHighlight);
        holder.colorControlNormal.setBackgroundColor(preset.colorControlNormal);

        boolean isSelected = selectedPosition == position;
        holder.card.setStrokeColor(isSelected ? preset.colorAccent : 0);
        holder.card.setStrokeWidth(isSelected ? 3 : 0);

        holder.itemView.setOnClickListener(v -> {
            int prev = selectedPosition;
            selectedPosition = holder.getAdapterPosition();
            if (prev >= 0) notifyItemChanged(prev);
            notifyItemChanged(selectedPosition);
            listener.onThemeSelected(preset, holder.getAdapterPosition());
        });
    }

    @Override
    public int getItemCount() {
        return presets.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView name;
        View colorAccent;
        View colorPrimary;
        View colorPrimaryDark;
        View colorControlHighlight;
        View colorControlNormal;
        MaterialCardView card;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.theme_name);
            card = itemView.findViewById(R.id.theme_colors_preview) != null
                    ? (MaterialCardView) ((View) itemView.findViewById(R.id.theme_colors_preview).getParent())
                    : (MaterialCardView) itemView;
            colorAccent = itemView.findViewById(R.id.color_accent);
            colorPrimary = itemView.findViewById(R.id.color_primary);
            colorPrimaryDark = itemView.findViewById(R.id.color_primary_dark);
            colorControlHighlight = itemView.findViewById(R.id.color_control_highlight);
            colorControlNormal = itemView.findViewById(R.id.color_control_normal);
        }
    }
}
