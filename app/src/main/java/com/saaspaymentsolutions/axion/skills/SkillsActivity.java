package com.saaspaymentsolutions.axion.skills;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import com.saaspaymentsolutions.axion.R;

import java.util.List;

/**
 * Tela onde o usuário cadastra suas próprias "skills": blocos reutilizáveis de
 * instruções/conhecimento que o chat (agente de IA) pode consultar e aplicar
 * durante a conversa.
 */
public class SkillsActivity extends AppCompatActivity {

    private RecyclerView recyclerSkills;
    private View emptyState;
    private SkillsAdapter adapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_skills);

        com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.skills_toolbar);
        toolbar.setNavigationOnClickListener(v -> onBackPressed());

        recyclerSkills = findViewById(R.id.recycler_skills);
        emptyState = findViewById(R.id.skills_empty_state);
        ExtendedFloatingActionButton fabAdd = findViewById(R.id.fab_add_skill);

        adapter = new SkillsAdapter(new SkillsAdapter.Listener() {
            @Override
            public void onEdit(@androidx.annotation.NonNull Skill skill) {
                showEditDialog(skill);
            }

            @Override
            public void onDelete(@androidx.annotation.NonNull Skill skill) {
                confirmDelete(skill);
            }

            @Override
            public void onToggleEnabled(@androidx.annotation.NonNull Skill skill, boolean enabled) {
                SkillManager.setEnabled(SkillsActivity.this, skill.id, enabled);
            }
        });
        recyclerSkills.setLayoutManager(new LinearLayoutManager(this));
        recyclerSkills.setAdapter(adapter);

        fabAdd.setOnClickListener(v -> showEditDialog(null));

        refreshList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshList();
    }

    private void refreshList() {
        List<Skill> skills = SkillManager.getAll(this);
        adapter.updateList(skills);
        boolean empty = skills.isEmpty();
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerSkills.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void showEditDialog(@Nullable Skill existing) {
        View content = getLayoutInflater().inflate(R.layout.dialog_edit_skill, null);
        EditText inputName = content.findViewById(R.id.input_skill_name);
        EditText inputTrigger = content.findViewById(R.id.input_skill_trigger);
        EditText inputContent = content.findViewById(R.id.input_skill_content);

        boolean isEdit = existing != null;
        if (isEdit) {
            inputName.setText(existing.name);
            inputTrigger.setText(existing.trigger);
            inputContent.setText(existing.content);
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(isEdit ? R.string.skill_dialog_title_edit : R.string.skill_dialog_title_new)
                .setView(content)
                .setNegativeButton(R.string.common_word_cancel, null)
                .setPositiveButton(R.string.common_word_ok, null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = inputName.getText().toString().trim();
            String trigger = inputTrigger.getText().toString().trim();
            String skillContent = inputContent.getText().toString().trim();

            if (name.isEmpty()) {
                Toast.makeText(this, R.string.skill_error_name_required, Toast.LENGTH_SHORT).show();
                return;
            }
            if (skillContent.isEmpty()) {
                Toast.makeText(this, R.string.skill_error_content_required, Toast.LENGTH_SHORT).show();
                return;
            }

            if (isEdit) {
                existing.name = name;
                existing.trigger = trigger;
                existing.content = skillContent;
                existing.updatedAt = System.currentTimeMillis();
                SkillManager.upsert(this, existing);
            } else {
                SkillManager.upsert(this, Skill.create(name, trigger, skillContent));
            }
            refreshList();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void confirmDelete(Skill skill) {
        String name = skill.name.trim().isEmpty() ? "-" : skill.name.trim();
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.skill_delete_title)
                .setMessage(getString(R.string.skill_delete_message, name))
                .setNegativeButton(R.string.common_word_cancel, null)
                .setPositiveButton(R.string.main_delete_project_confirm, (dialog, which) -> {
                    SkillManager.delete(this, skill.id);
                    refreshList();
                })
                .show();
    }
}
