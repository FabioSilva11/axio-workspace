package com.saaspaymentsolutions.axion.agentsdk.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Serializes {@link ToolSpec}s into the model-facing tool catalog, ported
 * from Codex {@code create_tools_json_for_responses_api} /
 * {@code tool_spec.rs}. Every spec kind keeps its OWN wire shape — function
 * tools stay {@code function}, freeform tools stay {@code freeform},
 * namespace declarations become {@code namespace} entries and the discovery
 * tool becomes {@code tool_search}. Nothing is flattened into
 * {@code "type":"function"}.
 *
 * <pre>
 * FUNCTION  → {"type":"function","function":{"name","description","parameters",...}}
 * FREEFORM  → {"type":"freeform","freeform":{"name","description","format":{"type","syntax","definition"}}}
 * NAMESPACE → {"type":"namespace","name","description","tools":[...]}
 * TOOL_SEARCH → {"type":"tool_search","tool_search":{"name","description","parameters"}}
 * </pre>
 *
 * <p>Namespaced tools (MCP servers, clock namespace) are grouped under their
 * NAMESPACE declaration; plain (root-namespace) tools are emitted as top-level
 * entries in registration order.</p>
 */
public final class ToolSpecSerializer {

    private ToolSpecSerializer() {
    }

    /** Serializes the given registrations into the model-facing catalog. */
    public static JSONArray toCatalog(List<ToolRegistration> registrations) {
        JSONArray catalog = new JSONArray();
        if (registrations == null) {
            return catalog;
        }

        List<ToolRegistration> plainTools = new ArrayList<>();
        Map<String, List<ToolRegistration>> namespaced = new LinkedHashMap<>();
        Map<String, ToolRegistration> namespaceDecls = new LinkedHashMap<>();

        for (ToolRegistration reg : registrations) {
            if (reg.spec().type() == ToolSpec.Type.NAMESPACE) {
                String prefix = reg.spec().name().name();
                namespaceDecls.put(prefix, reg);
                continue;
            }
            String ns = reg.spec().name().namespace();
            if (ns.isEmpty()) {
                plainTools.add(reg);
            } else {
                namespaced.computeIfAbsent(ns, k -> new ArrayList<>()).add(reg);
            }
        }

        for (ToolRegistration reg : plainTools) {
            JSONObject entry = toWireEntry(reg);
            if (entry != null) {
                catalog.put(entry);
            }
        }

        // Namespaces: any declared namespace with children is emitted as a
        // single namespace entry (Codex groups all children under it).
        for (Map.Entry<String, ToolRegistration> decl : namespaceDecls.entrySet()) {
            String prefix = decl.getKey();
            List<ToolRegistration> children = namespaced.get(prefix);
            if (children == null || children.isEmpty()) {
                continue;
            }
            catalog.put(toNamespaceEntry(decl.getValue(), children));
        }

        return catalog;
    }

    /**
     * Serializes to the OpenAI {@code {"type":"function","function":{...}}}
     * envelope still expected by the HTTP boundary conversion layer
     * ({@code OpenAiToolSchemaNormalizer} / Gemini / Anthropic adapters).
     * Freeform tools are mapped to empty-parameters functions at the WIRE
     * only; the faithful kinds live in {@link #toCatalog}. Namespace
     * declarations are skipped — their children are already present as
     * individual registrations in the input list.
     */
    public static JSONArray toFunctionEnvelope(List<ToolRegistration> registrations) {
        JSONArray array = new JSONArray();
        if (registrations == null) {
            return array;
        }
        for (ToolRegistration reg : registrations) {
            if (reg.spec().type() == ToolSpec.Type.NAMESPACE) {
                continue;
            }
            JSONObject function = functionEnvelope(reg);
            if (function != null) {
                array.put(function);
            }
        }
        return array;
    }

    // ------------------------------------------------------------------
    // Wire shapes
    // ------------------------------------------------------------------

    private static JSONObject toWireEntry(ToolRegistration reg) {
        switch (reg.spec().type()) {
            case FUNCTION:
                return functionEntry(reg);
            case FREEFORM:
                return freeformEntry(reg);
            case TOOL_SEARCH:
                return toolSearchEntry(reg);
            default:
                return null;
        }
    }

    private static JSONObject functionEntry(ToolRegistration reg) {
        FunctionToolSpec spec = (FunctionToolSpec) reg.spec();
        try {
            JSONObject function = new JSONObject()
                    .put("name", spec.qualifiedName())
                    .put("description", spec.description())
                    .put("parameters", spec.parameters());
            function.put("strict", false);
            if (spec.outputSchema() != null) {
                function.put("output_schema", spec.outputSchema());
            }
            return new JSONObject().put("type", "function").put("function", function);
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    private static JSONObject freeformEntry(ToolRegistration reg) {
        FreeformToolSpec spec = (FreeformToolSpec) reg.spec();
        try {
            JSONObject format = new JSONObject()
                    .put("type", spec.formatType())
                    .put("syntax", spec.syntax() == null ? "" : spec.syntax());
            if (spec.definition() != null) {
                format.put("definition", spec.definition());
            }
            JSONObject freeform = new JSONObject()
                    .put("name", spec.qualifiedName())
                    .put("description", spec.description())
                    .put("format", format);
            return new JSONObject().put("type", "freeform").put("freeform", freeform);
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    private static JSONObject toolSearchEntry(ToolRegistration reg) {
        ToolSearchToolSpec spec = (ToolSearchToolSpec) reg.spec();
        try {
            JSONObject search = new JSONObject()
                    .put("name", spec.qualifiedName())
                    .put("description", spec.description())
                    .put("execution", spec.execution())
                    .put("parameters", spec.parameters());
            return new JSONObject().put("type", "tool_search").put("tool_search", search);
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    private static JSONObject toNamespaceEntry(ToolRegistration declaration,
                                               List<ToolRegistration> children) {
        NamespaceToolSpec spec = (NamespaceToolSpec) declaration.spec();
        try {
            JSONArray tools = new JSONArray();
            for (ToolRegistration child : children) {
                JSONObject childEntry = toNamespaceChild(child);
                if (childEntry != null) {
                    tools.put(childEntry);
                }
            }
            return new JSONObject()
                    .put("type", "namespace")
                    .put("name", spec.name().name())
                    .put("description", spec.description())
                    .put("tools", tools);
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    private static JSONObject toNamespaceChild(ToolRegistration child) {
        switch (child.spec().type()) {
            case FUNCTION: {
                FunctionToolSpec function = (FunctionToolSpec) child.spec();
                try {
                    return new JSONObject()
                            .put("type", "function")
                            .put("name", function.name().name())
                            .put("description", function.description())
                            .put("parameters", function.parameters());
                } catch (org.json.JSONException ignored) {
                    return null;
                }
            }
            case FREEFORM: {
                FreeformToolSpec freeform = (FreeformToolSpec) child.spec();
                try {
                    return new JSONObject()
                            .put("type", "freeform")
                            .put("name", freeform.name().name())
                            .put("description", freeform.description())
                            .put("format", new JSONObject()
                                    .put("type", freeform.formatType())
                                    .put("syntax", freeform.syntax() == null ? "" : freeform.syntax()));
                } catch (org.json.JSONException ignored) {
                    return null;
                }
            }
            default:
                return null;
        }
    }

    private static JSONObject functionEnvelope(ToolRegistration reg) {
        if (reg.spec().type() == ToolSpec.Type.FREEFORM) {
            FreeformToolSpec freeform = (FreeformToolSpec) reg.spec();
            try {
                JSONObject function = new JSONObject()
                        .put("name", freeform.qualifiedName())
                        .put("description", freeform.description())
                        .put("parameters", new JSONObject().put("type", "object"));
                return new JSONObject().put("type", "function").put("function", function);
            } catch (org.json.JSONException e) {
                return null;
            }
        }
        if (reg.spec().type() != ToolSpec.Type.FUNCTION) {
            return null;
        }
        FunctionToolSpec spec = (FunctionToolSpec) reg.spec();
        try {
            return new JSONObject()
                    .put("type", "function")
                    .put("function", new JSONObject()
                            .put("name", spec.qualifiedName())
                            .put("description", spec.description())
                            .put("parameters", spec.parameters()));
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    /** Snapshot label helper for tests/reporting (type string per entry). */
    public static String catalogTypes(JSONArray catalog) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < catalog.length(); i++) {
            JSONObject entry = catalog.optJSONObject(i);
            if (entry == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(entry.optString("type", "?")).append(':')
                    .append(entry.optString("name",
                            entry.optJSONObject("function") != null
                                    ? entry.optJSONObject("function").optString("name", "?")
                                    : entry.optJSONObject("tool_search") != null
                                    ? entry.optJSONObject("tool_search").optString("name", "?")
                                    : entry.optJSONObject("freeform") != null
                                    ? entry.optJSONObject("freeform").optString("name", "?")
                                    : "?"));
        }
        return sb.toString();
    }
}