package com.saaspaymentsolutions.axion.agentsdk;

import com.saaspaymentsolutions.axion.agentsdk.schema.ToolSchemaNormalizer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Integration test that validates the complete tool payload structure that
 * would be sent to the LLM provider. This test ensures that no tool in the
 * default toolset generates the invalid {@code "items": [...]} pattern.
 */
public class ToolPayloadIntegrationTest {

    @Test
    public void defaultToolset_allSchemasValid() {
        List<AgentTool> tools = createDefaultToolset();

        // Validate using the normalizer
        List<String> errors = ToolSchemaNormalizer.validateToolset(tools);

        if (!errors.isEmpty()) {
            StringBuilder message = new StringBuilder("Tool validation failed:\n");
            for (String error : errors) {
                message.append(error).append("\n");
            }
            fail(message.toString());
        }

        // All tools passed validation
        assertTrue("All default tools should be valid", errors.isEmpty());
    }

    @Test
    public void requestUserInputTool_payloadStructure() throws Exception {
        RequestUserInputTool tool = new RequestUserInputTool(null);

        // Build the payload structure as it would be sent to the provider
        JSONObject functionDeclaration = new JSONObject()
                .put("type", "function")
                .put("function", new JSONObject()
                        .put("name", tool.name())
                        .put("description", tool.description())
                        .put("parameters", tool.parameters()));

        // Extract and validate the critical part
        JSONObject parameters = functionDeclaration
                .getJSONObject("function")
                .getJSONObject("parameters");

        // Validate root
        assertEquals("object", parameters.getString("type"));

        // Validate options array
        JSONObject options = parameters
                .getJSONObject("properties")
                .getJSONObject("options");
        assertEquals("array", options.getString("type"));

        // CRITICAL: items must be a JSONObject, not JSONArray
        Object items = options.get("items");
        assertTrue("items must be JSONObject, not JSONArray",
                items instanceof JSONObject);

        // Validate items structure
        JSONObject itemsObj = (JSONObject) items;
        assertEquals("object", itemsObj.getString("type"));

        // Validate the complete payload can be serialized
        String json = functionDeclaration.toString();
        assertNotNull("Payload must be serializable", json);
        assertTrue("Payload must contain function name",
                json.contains("request_user_input"));

        // Verify the invalid pattern is NOT present
        assertFalse("Payload must NOT contain items array pattern",
                json.contains("\"items\":["));
    }

    @Test
    public void applyPatchTool_payloadStructure() throws Exception {
        ApplyPatchTool tool = new ApplyPatchTool("sc1", null);

        JSONObject functionDeclaration = new JSONObject()
                .put("type", "function")
                .put("function", new JSONObject()
                        .put("name", tool.name())
                        .put("description", tool.description())
                        .put("parameters", tool.parameters()));

        // Validate
        JSONObject parameters = functionDeclaration
                .getJSONObject("function")
                .getJSONObject("parameters");

        assertEquals("object", parameters.getString("type"));
        assertTrue("Must have patch property",
                parameters.getJSONObject("properties").has("patch"));

        // Verify serialization
        String json = functionDeclaration.toString();
        assertNotNull(json);
        assertTrue(json.contains("apply_patch"));
    }

    @Test
    public void contextRemainingTool_payloadStructure() throws Exception {
        ContextRemainingTool tool = new ContextRemainingTool();

        JSONObject functionDeclaration = new JSONObject()
                .put("type", "function")
                .put("function", new JSONObject()
                        .put("name", tool.name())
                        .put("description", tool.description())
                        .put("parameters", tool.parameters()));

        // Validate
        JSONObject parameters = functionDeclaration
                .getJSONObject("function")
                .getJSONObject("parameters");

        assertEquals("object", parameters.getString("type"));
        assertFalse("Should not allow additional properties",
                parameters.optBoolean("additionalProperties", true));

        // Verify serialization
        String json = functionDeclaration.toString();
        assertNotNull(json);
        assertTrue(json.contains("get_context_remaining"));
    }

    @Test
    public void fullToolsArray_canBeSerialized() throws Exception {
        List<AgentTool> tools = createDefaultToolset();

        // Build the tools array as it would be sent to the provider
        JSONArray toolsArray = new JSONArray();
        for (AgentTool tool : tools) {
            // Normalize schema
            ToolSchemaNormalizer.ValidationResult result =
                    ToolSchemaNormalizer.normalize(tool.name(), tool.parameters());

            assertTrue("Tool " + tool.name() + " must have valid schema: " +
                            (result.isValid() ? "OK" : result.getFullErrorMessage()),
                    result.isValid());

            JSONObject functionDeclaration = new JSONObject()
                    .put("type", "function")
                    .put("function", new JSONObject()
                            .put("name", tool.name())
                            .put("description", tool.description())
                            .put("parameters", result.getSchema()));

            toolsArray.put(functionDeclaration);
        }

        // Verify the complete payload can be serialized
        String json = toolsArray.toString();
        assertNotNull("Tools array must be serializable", json);

        // Verify no invalid patterns
        assertFalse("Must NOT contain the buggy items array pattern",
                json.contains("\"items\":[{"));

        // Verify expected tools are present
        assertTrue("Must contain apply_patch", json.contains("apply_patch"));
        assertTrue("Must contain request_user_input", json.contains("request_user_input"));
        assertTrue("Must contain get_context_remaining", json.contains("get_context_remaining"));
    }

    @Test
    public void fullPayload_asItWouldBeSentToProvider() throws Exception {
        // This test simulates the exact payload structure that would be sent
        // to a provider like custom_mocklocal (the one that was failing with HTTP 400)

        List<AgentTool> tools = createDefaultToolset();

        JSONObject request = new JSONObject()
                .put("model", "gpt-oss-120b-medium")
                .put("messages", new JSONArray()
                        .put(new JSONObject()
                                .put("role", "system")
                                .put("content", "You are a helpful assistant."))
                        .put(new JSONObject()
                                .put("role", "user")
                                .put("content", "Hello")));

        // Build tools array
        JSONArray toolsArray = new JSONArray();
        for (AgentTool tool : tools) {
            ToolSchemaNormalizer.ValidationResult result =
                    ToolSchemaNormalizer.normalize(tool.name(), tool.parameters());

            if (!result.isValid()) {
                fail("Tool " + tool.name() + " has invalid schema: " +
                        result.getFullErrorMessage());
            }

            toolsArray.put(new JSONObject()
                    .put("type", "function")
                    .put("function", new JSONObject()
                            .put("name", tool.name())
                            .put("description", tool.description())
                            .put("parameters", result.getSchema())));
        }

        request.put("tools", toolsArray);

        // Serialize the complete request
        String json = request.toString(2); // Pretty print
        assertNotNull("Request must be serializable", json);

        // Log for manual inspection if needed
        System.out.println("Complete payload structure:");
        System.out.println(json.substring(0, Math.min(2000, json.length())));

        // Verify critical constraints
        assertFalse("Must NOT have invalid items array pattern",
                json.contains("\"items\":[{"));
        assertTrue("Must have valid items object pattern",
                json.replaceAll("\\s+", "").contains("\"items\":{"));
    }

    // Helper to create default toolset
    private List<AgentTool> createDefaultToolset() {
        List<AgentTool> tools = new ArrayList<>();
        tools.add(new ApplyPatchTool("sc1", null));
        tools.add(new RequestUserInputTool(null));
        tools.add(new ContextRemainingTool());
        return tools;
    }
}
