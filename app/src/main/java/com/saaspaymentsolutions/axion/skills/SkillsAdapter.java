package com.saaspaymentsolutions.axion.skills;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.materialswitch.MaterialSwitch;

import com.saaspaymentsolutions.axion.R;

import java.util.ArrayList;
import java.util.List;

public class SkillsAdapter extends RecyclerView.Adapter<SkillsAdapter.SkillViewHolder> {

    public interface Listener {
        void onEdit(@NonNull Skill skill);

        void onDelete(@NonNull Skill skill);

        void onToggleEnabled(@NonNull Skill skill, boolean enabled);
    }

    private final List<Skill> items = new ArrayList<>();
    private final Listener listener;

    public SkillsAdapter(Listener listener) {
        this.listener = listener;
    }

    public void updateList(List<Skill> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public SkillViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_skill, parent, false);
        return new SkillViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SkillViewHolder holder, int position) {
        Skill skill = items.get(position);
        holder.name.setText(skill.name.trim().isEmpty() ? "-" : skill.name.trim());
        holder.trigger.setText(skill.trigger.trim().isEmpty()
                ? skill.content.trim()
                : skill.trigger.trim());
        holder.trigger.setVisibility(
                (skill.trigger.trim().isEmpty() && skill.content.trim().isEmpty()) ? View.GONE : View.VISIBLE);

        holder.switchEnabled.setOnCheckedChangeListener(null);
        holder.switchEnabled.setChecked(skill.enabled);
        holder.switchEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (listener != null) listener.onToggleEnabled(skill, isChecked);
        });

        holder.edit.setOnClickListener(v -> {
            if (listener != null) listener.onEdit(skill);
        });
        holder.delete.setOnClickListener(v -> {
            if (listener != null) listener.onDelete(skill);
        });
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onEdit(skill);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class SkillViewHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView trigger;
        final TextView edit;
        final TextView delete;
        final MaterialSwitch switchEnabled;

        SkillViewHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.skill_name);
            trigger = itemView.findViewById(R.id.skill_trigger);
            edit = itemView.findViewById(R.id.skill_edit);
            delete = itemView.findViewById(R.id.skill_delete);
            switchEnabled = itemView.findViewById(R.id.skill_switch);
        }
    }
}
