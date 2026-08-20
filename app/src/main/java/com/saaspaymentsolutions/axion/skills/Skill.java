package com.saaspaymentsolutions.axion.skills;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/**
 * Representa uma "skill" cadastrada pelo usuário: um bloco reutilizável de
 * conhecimento/instruções que o chat (agente de IA) pode consultar e aplicar
 * quando o pedido do usuário for relacionado ao gatilho descrito.
 */
public class Skill {

    public final String id;
    public String name;
    /** Descreve QUANDO essa skill deve ser usada (gatilho/trigger). */
    public String trigger;
    /** Conteúdo/instruções completas da skill que serão injetadas no prompt. */
    public String content;
    public boolean enabled;
    public long createdAt;
    public long updatedAt;

    public Skill(String id, String name, String trigger, String content, boolean enabled,
                 long createdAt, long updatedAt) {
        this.id = id;
        this.name = name == null ? "" : name;
        this.trigger = trigger == null ? "" : trigger;
        this.content = content == null ? "" : content;
        this.enabled = enabled;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Skill create(String name, String trigger, String content) {
        long now = System.currentTimeMillis();
        return new Skill(UUID.randomUUID().toString(), name, trigger, content, true, now, now);
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("id", id);
            obj.put("name", name);
            obj.put("trigger", trigger);
            obj.put("content", content);
            obj.put("enabled", enabled);
            obj.put("createdAt", createdAt);
            obj.put("updatedAt", updatedAt);
        } catch (JSONException ignored) {
        }
        return obj;
    }

    public static Skill fromJson(JSONObject obj) {
        if (obj == null) return null;
        String id = obj.optString("id", UUID.randomUUID().toString());
        String name = obj.optString("name", "");
        String trigger = obj.optString("trigger", "");
        String content = obj.optString("content", "");
        boolean enabled = obj.optBoolean("enabled", true);
        long createdAt = obj.optLong("createdAt", System.currentTimeMillis());
        long updatedAt = obj.optLong("updatedAt", createdAt);
        return new Skill(id, name, trigger, content, enabled, createdAt, updatedAt);
    }
}
