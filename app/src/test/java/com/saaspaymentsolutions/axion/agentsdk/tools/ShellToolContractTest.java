package com.saaspaymentsolutions.axion.agentsdk.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.saaspaymentsolutions.axion.agentsdk.AgentToolResult;
import com.saaspaymentsolutions.axion.agentsdk.tools.ToolSpec.Type;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * shell parity (Codex shell_spec.rs): exec_command requires 'cmd';
 * write_stdin requires 'session_id'; both carry workdir/tty/yield_time_ms and
 * chars/yield_time_ms/max_output_tokens respectively. Executors fail with a
 * structured error (never crash) on missing contract fields.
 */
public class ShellToolContractTest {

    private AxionToolRegistry coreRegistry() {
        AxionToolRegistry registry = new AxionToolRegistry();
        WorkspaceToolProvider.registerCoreTools(registry, null);
        return registry;
    }

    @Test
    public void execCommandRegistrationMatchesCodexContract() {
        AxionToolRegistry registry = coreRegistry();
        ToolRegistration exec = registry.get("exec_command");
        assertNotNull(exec);
        assertEquals(Type.FUNCTION, exec.spec().type());
        assertEquals("exec_command", exec.spec().qualifiedName());
        JSONObject params = exec.spec().parameters();
        assertTrue(params.has("required"));
        JSONArray required = params.optJSONArray("required");
        assertNotNull(required);
        assertTrue(arrayHas(required, "cmd"));
        JSONObject props = params.optJSONObject("properties");
        assertNotNull(props);
        assertTrue(props.has("workdir"));
        assertTrue(props.has("tty"));
        assertTrue(props.has("yield_time_ms"));
        assertTrue(props.has("max_output_tokens"));
    }

    @Test
    public void writeStdinRegistrationMatchesCodexContract() {
        AxionToolRegistry registry = coreRegistry();
        ToolRegistration stdin = registry.get("write_stdin");
        assertNotNull(stdin);
        JSONObject params = stdin.spec().parameters();
        JSONArray required = params.optJSONArray("required");
        assertNotNull(required);
        assertTrue(arrayHas(required, "session_id"));
        JSONObject props = params.optJSONObject("properties");
        assertTrue(props.has("session_id"));
        assertTrue(props.has("chars"));
        assertTrue(props.has("yield_time_ms"));
        assertTrue(props.has("max_output_tokens"));
    }

    @Test
    public void execMissingCmdIsErrorNotCrash() throws Exception {
        AxionToolRegistry registry = coreRegistry();
        ToolRegistration real = registry.get("exec_command");
        ToolExecutionContext ctx = new ToolExecutionContext(real, "sc", "call", null,
                new JSONObject("{}"), null, "{}");
        AgentToolResult result = real.executor().execute(ctx);
        assertTrue(result.isError());
        assertTrue(result.output().contains("cmd"));
    }

    @Test
    public void writeStdinMissingSessionIsErrorNotCrash() throws Exception {
        AxionToolRegistry registry = coreRegistry();
        ToolRegistration real = registry.get("write_stdin");
        ToolExecutionContext ctx = new ToolExecutionContext(real, "sc", "call", null,
                new JSONObject("{}"), null, "{}");
        AgentToolResult result = real.executor().execute(ctx);
        assertTrue(result.isError());
        assertTrue(result.output().contains("session_id"));
    }

    @Test
    public void execWithoutHostServiceFailsGracefully() throws Exception {
        AxionToolRegistry registry = coreRegistry();
        ToolRegistration real = registry.get("exec_command");
        ToolExecutionContext ctx = new ToolExecutionContext(real, "sc", "call", null,
                new JSONObject("{\"cmd\":\"echo hi\"}"), null, "{\"cmd\":\"echo hi\"}");
        AgentToolResult result = real.executor().execute(ctx);
        // In a JVM test there is no Void port backing the command: the adapter
        // must convert that failure into an error result, never a crash.
        assertTrue(result.isError() || !result.output().isEmpty());
    }

    private static boolean arrayHas(JSONArray array, String value) {
        for (int i = 0; i < array.length(); i++) {
            if (value.equals(array.optString(i))) {
                return true;
            }
        }
        return false;
    }
}