package com.saaspaymentsolutions.axion.skills;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Armazena e recupera as skills cadastradas pelo usuário, e monta o bloco de
 * texto que é injetado no system prompt do agente para que o chat possa
 * consultar e aplicar essas skills durante a conversa.
 */
public final class SkillManager {

    private static final String PREFS_NAME = "axion_skills";
    private static final String KEY_SKILLS = "skills_json";

    /** Limite defensivo para não estourar o orçamento de tokens do prompt. */
    private static final int MAX_CONTENT_CHARS_PER_SKILL = 4000;
    private static final int MAX_SKILLS_IN_PROMPT = 20;

    private SkillManager() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized List<Skill> getAll(Context context) {
        List<Skill> result = new ArrayList<>();
        String raw = prefs(context).getString(KEY_SKILLS, "");
        if (TextUtils.isEmpty(raw)) {
            return result;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                Skill skill = Skill.fromJson(array.optJSONObject(i));
                if (skill != null) {
                    result.add(skill);
                }
            }
        } catch (Exception ignored) {
        }
        Collections.sort(result, new Comparator<Skill>() {
            @Override
            public int compare(Skill a, Skill b) {
                return Long.compare(b.updatedAt, a.updatedAt);
            }
        });
        return result;
    }

    private static synchronized void saveAll(Context context, List<Skill> skills) {
        JSONArray array = new JSONArray();
        for (Skill skill : skills) {
            array.put(skill.toJson());
        }
        prefs(context).edit().putString(KEY_SKILLS, array.toString()).apply();
    }

    public static void upsert(Context context, Skill skill) {
        List<Skill> all = getAll(context);
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(skill.id)) {
                all.set(i, skill);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            all.add(skill);
        }
        saveAll(context, all);
    }

    public static void delete(Context context, String skillId) {
        List<Skill> all = getAll(context);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(skillId)) {
                all.remove(i);
                break;
            }
        }
        saveAll(context, all);
    }

    public static void setEnabled(Context context, String skillId, boolean enabled) {
        List<Skill> all = getAll(context);
        for (Skill skill : all) {
            if (skill.id.equals(skillId)) {
                skill.enabled = enabled;
                skill.updatedAt = System.currentTimeMillis();
                break;
            }
        }
        saveAll(context, all);
    }

    public static int count(Context context) {
        return getAll(context).size();
    }

    /**
     * Monta o bloco de texto com as skills habilitadas para injeção no system
     * prompt. Retorna string vazia quando não há nenhuma skill habilitada.
     */
    public static String buildPromptBlock(Context context) {
        List<Skill> enabled = new ArrayList<>();
        for (Skill skill : getAll(context)) {
            if (skill.enabled && !skill.content.trim().isEmpty()) {
                enabled.add(skill);
            }
        }
        if (enabled.isEmpty()) {
            return "";
        }
        if (enabled.size() > MAX_SKILLS_IN_PROMPT) {
            enabled = enabled.subList(0, MAX_SKILLS_IN_PROMPT);
        }

        StringBuilder builder = new StringBuilder();
        builder.append("The user has registered custom SKILLS: reusable blocks of domain knowledge or ")
                .append("instructions. Check the name/trigger of each skill below; when the user's request ")
                .append("relates to one, read and follow its instructions as if they were part of your own ")
                .append("knowledge. Do not mention the existence of this list unless relevant.\n")
                .append("<skills>\n");
        for (Skill skill : enabled) {
            String name = skill.name.trim().isEmpty() ? "Untitled skill" : skill.name.trim();
            String trigger = skill.trigger.trim();
            String content = skill.content.trim();
            if (content.length() > MAX_CONTENT_CHARS_PER_SKILL) {
                content = content.substring(0, MAX_CONTENT_CHARS_PER_SKILL) + "\n...(truncated)";
            }
            builder.append("<skill name=\"").append(escapeAttr(name)).append("\">\n");
            if (!trigger.isEmpty()) {
                builder.append("When to use: ").append(trigger).append("\n");
            }
            builder.append(content).append("\n");
            builder.append("</skill>\n");
        }
        builder.append("</skills>");
        return builder.toString();
    }

    private static String escapeAttr(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }
}
