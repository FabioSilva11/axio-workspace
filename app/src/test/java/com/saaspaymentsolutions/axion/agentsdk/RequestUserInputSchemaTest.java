package com.saaspaymentsolutions.axion.agentsdk;

import com.saaspaymentsolutions.axion.agentsdk.schema.ToolSchemaNormalizer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests specifically for the {@link RequestUserInputTool} schema structure,
 * verifying that the fix for the HTTP 400 bug is correct.
 */
public class RequestUserInputSchemaTest {

    @Test
    public void requestUserInputSchema_isValid() {
        RequestUserInputTool tool = new RequestUserInputTool(null);
        JSONObject schema = tool.parameters();

        // The schema must be valid according to our normalizer
        ToolSchemaNormalizer.ValidationResult result =
                ToolSchemaNormalizer.normalize(tool.name(), schema);

        assertTrue("RequestUserInputTool schema must be valid: " +
                        (result.isValid() ? "OK" : result.getFullErrorMessage()),
                result.isValid());
    }

    @Test
    public void requestUserInputSchema_rootIsObject() {
        RequestUserInputTool tool = new RequestUserInputTool(null);
        JSONObject schema = tool.parameters();

        assertEquals("Root type must be object", "object", schema.optString("type"));
    }

    @Test
    public void requestUserInputSchema_hasRequiredProperties() {
        RequestUserInputTool tool = new RequestUserInputTool(null);
        JSONObject schema = tool.parameters();

        JSONObject properties = schema.optJSONObject("properties");
        assertNotNull("Schema must have properties", properties);
        assertTrue("Must have question property", properties.has("question"));
        assertTrue("Must have options property", properties.has("options"));
        assertTrue("Must have allow_free_text property", properties.has("allow_free_text"));
    }

    @Test
    public void requestUserInputSchema_questionIsString() {
        RequestUserInputTool tool = new RequestUserInputTool(null);
        JSONObject schema = tool.parameters();

        JSONObject question = schema
                .optJSONObject("properties")
                .optJSONObject("question");

        assertNotNull("question property must exist", question);
        assertEquals("question must be string type", "string", question.optString("type"));
    }

    @Test
    public void requestUserInputSchema_optionsIsArray() {
        RequestUserInputTool tool = new RequestUserInputTool(null);
        JSONObject schema = tool.parameters();

        JSONObject options = schema
                .optJSONObject("properties")
                .optJSONObject("options");

        assertNotNull("options property must exist", options);
        assertEquals("options must be array type", "array", options.optString("type"));
    }

    @Test
    public void requestUserInputSchema_optionsItems_isObject_notArray() {
        // CRITICAL: This is the test that would fail with the old buggy code
        RequestUserInputTool tool = new RequestUserInputTool(null);
        JSONObject schema = tool.parameters();

        JSONObject options = schema
                .optJSONObject("properties")
                .optJSONObject("options");

        Object items = options.opt("items");
        assertNotNull("options.items must exist", items);

        assertTrue("options.items must be a JSONObject, not JSONArray",
                items instanceof JSONObject);
        assertFalse("options.items must NOT be a JSONArray (this was the bug)",
                items instanceof JSONArray);
    }

    @Test
    public void requestUserInputSchema_optionsItemsSchema_isObject() {
        RequestUserInputTool tool = new RequestUserInputTool(null);
        JSONObject schema = tool.parameters();

        JSONObject items = schema
                .optJSONObject("properties")
                .optJSONObject("options")
                .optJSONObject("items");

        assertNotNull("options.items must be a schema object", items);
        assertEquals("options.items must be object type", "object", items.optString("type"));
    }

    @Test
    public void requestUserInputSchema_optionHasLabelAndDescription() {
        RequestUserInputTool tool = new RequestUserInputTool(null);
        JSONObject schema = tool.parameters();

        JSONObject itemProperties = schema
                .optJSONObject("properties")
                .optJSONObject("options")
                .optJSONObject("items")
                .optJSONObject("properties");

        assertNotNull("option schema must have properties", itemProperties);
        assertTrue("option must have label", itemProperties.has("label"));
        assertTrue("option must have description", itemProperties.has("description"));

        JSONObject label = itemProperties.optJSONObject("label");
        assertEquals("label must be string", "string", label.optString("type"));

        JSONObject description = itemProperties.optJSONObject("description");
        assertEquals("description must be string", "string", description.optString("type"));
    }

    @Test
    public void requestUserInputSchema_optionLabelIsRequired() {
        RequestUserInputTool tool = new RequestUserInputTool(null);
        JSONObject schema = tool.parameters();

        JSONObject items = schema
                .optJSONObject("properties")
                .optJSONObject("options")
                .optJSONObject("items");

        JSONArray required = items.optJSONArray("required");
        assertNotNull("options items must have required array", required);
        assertEquals(1, required.length());
        assertEquals("label", required.optString(0));
    }

    @Test
    public void requestUserInputSchema_allowFreeText_isBoolean() {
        RequestUserInputTool tool = new RequestUserInputTool(null);
        JSONObject schema = tool.parameters();

        JSONObject allowFreeText = schema
                .optJSONObject("properties")
                .optJSONObject("allow_free_text");

        assertNotNull("allow_free_text property must exist", allowFreeText);
        assertEquals("allow_free_text must be boolean type", "boolean", allowFreeText.optString("type"));
    }

    @Test
    public void requestUserInputSchema_questionIsRequired() {
        RequestUserInputTool tool = new RequestUserInputTool(null);
        JSONObject schema = tool.parameters();

        JSONArray required = schema.optJSONArray("required");
        assertNotNull("Schema must have required array", required);
        assertEquals(1, required.length());
        assertEquals("question", required.optString(0));
    }

    @Test
    public void requestUserInputSchema_noAdditionalProperties() {
        RequestUserInputTool tool = new RequestUserInputTool(null);
        JSONObject schema = tool.parameters();

        assertFalse("additionalProperties should be false",
                schema.optBoolean("additionalProperties", true));
    }

    @Test
    public void requestUserInputSchema_structure_matchesExpectation() {
        // Final comprehensive test: the structure should be:
        // {
        //   "type": "object",
        //   "properties": {
        //     "question": { "type": "string", ... },
        //     "options": {
        //       "type": "array",
        //       "items": {
        //         "type": "object",
        //         "properties": {
        //           "label": { "type": "string" },
        //           "description": { "type": "string" }
        //         },
        //         "required": ["label"]
        //       }
        //     },
        //     "allow_free_text": { "type": "boolean", ... }
        //   },
        //   "required": ["question"],
        //   "additionalProperties": false
        // }

        RequestUserInputTool tool = new RequestUserInputTool(null);
        JSONObject schema = tool.parameters();

        // Root level
        assertEquals("object", schema.optString("type"));
        assertFalse(schema.optBoolean("additionalProperties"));

        // Properties level
        JSONObject props = schema.optJSONObject("properties");
        assertEquals(3, props.length()); // question, options, allow_free_text

        // question
        assertEquals("string", props.optJSONObject("question").optString("type"));

        // options
        JSONObject options = props.optJSONObject("options");
        assertEquals("array", options.optString("type"));

        // options.items (MUST be object, not array)
        JSONObject items = options.optJSONObject("items");
        assertNotNull("items must be object", items);
        assertEquals("object", items.optString("type"));

        // options.items.properties
        JSONObject itemProps = items.optJSONObject("properties");
        assertEquals(2, itemProps.length()); // label, description
        assertEquals("string", itemProps.optJSONObject("label").optString("type"));
        assertEquals("string", itemProps.optJSONObject("description").optString("type"));

        // options.items.required
        JSONArray itemRequired = items.optJSONArray("required");
        assertEquals(1, itemRequired.length());
        assertEquals("label", itemRequired.optString(0));

        // allow_free_text
        assertEquals("boolean", props.optJSONObject("allow_free_text").optString("type"));

        // Root required
        JSONArray required = schema.optJSONArray("required");
        assertEquals(1, required.length());
        assertEquals("question", required.optString(0));
    }
}
