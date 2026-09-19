package com.saaspaymentsolutions.axion.agentsdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.saaspaymentsolutions.axion.ChatMessage;
import com.saaspaymentsolutions.axion.workspace.LocalFolderWorkspaceFileSystem;
import com.saaspaymentsolutions.axion.workspace.WorkspaceFileSystem;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Level-3 agent integration eval: the scripted {@link FakeAgentLlmGateway}
 * drives the real {@link AgentRuntime} loop over a real temp workspace
 * (real {@link WorkspaceFileSystem}, real file mutation via apply_patch).
 *
 * <p>Eval case {@code fix_npe_user_repo}: the model must locate the file,
 * patch the NPE and finish with an explanation — asserted on final state,
 * not on "the tool was called".</p>
 */
public class AgentRuntimeEvalTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static final String ORIGINAL =
            "package demo;\n\n"
            + "public class UserRepository {\n"
            + "    private String cache;\n\n"
            + "    public String getUserName(int id) {\n"
            + "        return cache.length() == 0 ? \"anon\" : cache;\n"
            + "    }\n"
            + "}\n";

    private static final String PATCHED =
            "package demo;\n\n"
            + "public class UserRepository {\n"
            + "    private String cache;\n\n"
            + "    public String getUserName(int id) {\n"
            + "        return cache == null || cache.length() == 0 ? \"anon\" : cache;\n"
            + "    }\n"
            + "}\n";

    private static final String NPE_PATCH =
            "*** Begin Patch\n"
            + "*** Update File: src/demo/UserRepository.kt\n"
            + "@@ public String getUserName\n"
            + "-        return cache.length() == 0 ? \"anon\" : cache;\n"
            + "+        return cache == null || cache.length() == 0 ? \"anon\" : cache;\n"
            + "*** End Patch\n";

    private WorkspaceFileSystem fs;
    private String workspacePath;

    @Before
    public void setUp() throws IOException {
        File root = temp.newFolder("workspace");
        workspacePath = root.getAbsolutePath();
        fs = new LocalFolderWorkspaceFileSystem(root);
        fs.writeText("src/demo/UserRepository.kt", ORIGINAL);
    }

    private Agent scriptedAgent() {
        ApplyPatchTool patchTool = new ApplyPatchTool("sc_eval", null, fs);
        AgentTool search = new StubSearchTool();
        AgentTool read = new StubReadTool();
        return Agent.Builder.forName("coordinator", "You fix workspace bugs.")
                .tools(search, read, patchTool)
                .build();
    }

    private List<AgentEvent> runScripted(FakeAgentLlmGateway gateway) {
        EventStream stream = new EventStream(Runnable::run, 128);
        List<AgentEvent> received = new ArrayList<>();
        stream.subscribe(received::add);

        AgentRuntime runtime = new AgentRuntime.Builder(gateway)
                .events(stream)
                .maxTurns(8)
                .build();

        List<ChatMessage> history = new ArrayList<>();
        history.add(new ChatMessage(
                "Corrija o NullPointerException em UserRepository.kt",
                ChatMessage.TYPE_USER, System.currentTimeMillis()));
        RunResult result = runtime.run(scriptedAgent(), history, "sc_eval");
        assertNotNull(result);
        return received;
    }

    @Test
    public void eval_fixNpe_appliesPatchAndReachesFinalState() {
        FakeAgentLlmGateway gateway = new FakeAgentLlmGateway(
                FakeAgentLlmGateway.ScriptedTurn.toolCall("search_for_files",
                        "{\"query\": \"UserRepository\"}"),
                FakeAgentLlmGateway.ScriptedTurn.toolCall("read_file",
                        "{\"uri\": \"src/demo/UserRepository.kt\"}"),
                FakeAgentLlmGateway.ScriptedTurn.toolCall("apply_patch",
                        "{\"patch\": " + org.json.JSONObject.quote(NPE_PATCH) + "}"),
                FakeAgentLlmGateway.ScriptedTurn.text(
                        "Fixed: added a null check on cache before reading its length.")
        );

        List<AgentEvent> events = runScripted(gateway);

        // Final state: the file on disk now contains the fix.
        assertEquals(PATCHED, fs.readText("src/demo/UserRepository.kt"));

        // The run narrated its completion.
        FakeAgentLlmGateway.ScriptedTurn last = null; // (script exhausted; checked via events below)
        boolean sawPatchCompleted = false;
        boolean sawAssistantFinal = false;
        boolean sawRunCompleted = false;
        int patchStarted = 0;
        for (AgentEvent event : events) {
            if (event instanceof AgentEvent.ToolCallStarted
                    && "apply_patch".equals(((AgentEvent.ToolCallStarted) event).getTool())) {
                patchStarted++;
            } else if (event instanceof AgentEvent.ToolCallCompleted
                    && "apply_patch".equals(((AgentEvent.ToolCallCompleted) event).getTool())) {
                sawPatchCompleted = ((AgentEvent.ToolCallCompleted) event).isSuccess();
            } else if (event instanceof AgentEvent.AssistantMessage) {
                sawAssistantFinal = ((AgentEvent.AssistantMessage) event)
                        .getContent().contains("Fixed");
            } else if (event instanceof AgentEvent.RunCompleted) {
                sawRunCompleted = ((AgentEvent.RunCompleted) event).isSuccessful();
            }
        }
        assertEquals(1, patchStarted);
        assertTrue("apply_patch must succeed", sawPatchCompleted);
        assertTrue("assistant must explain the fix", sawAssistantFinal);
        assertTrue("run must complete successfully", sawRunCompleted);
        assertTrue(gateway.wasToolExposed("apply_patch"));
    }

    @Test
    public void eval_policyDeniedMutation_neverTouchesFile() {
        FakeAgentLlmGateway gateway = new FakeAgentLlmGateway(
                FakeAgentLlmGateway.ScriptedTurn.toolCall("apply_patch",
                        "{\"patch\": " + org.json.JSONObject.quote(NPE_PATCH) + "}"),
                FakeAgentLlmGateway.ScriptedTurn.text("I could not modify the file.")
        );

        EventStream stream = new EventStream(Runnable::run, 128);
        List<AgentEvent> received = new ArrayList<>();
        stream.subscribe(received::add);

        // Interactive policy with no handler => fail closed.
        AgentRuntime runtime = new AgentRuntime.Builder(gateway)
                .events(stream)
                .permissions(new PermissionLayer(ToolPolicy.interactive(), null, stream))
                .maxTurns(8)
                .build();

        List<ChatMessage> history = new ArrayList<>();
        history.add(new ChatMessage("fix it", ChatMessage.TYPE_USER, System.currentTimeMillis()));
        RunResult result = runtime.run(scriptedAgent(), history, "sc_eval");

        // The policy blocked the mutation, the model fell back to an
        // explanation, and the run completed — but the file is untouched.
        assertEquals(ORIGINAL, fs.readText("src/demo/UserRepository.kt"));
        assertTrue(result.isSuccessful());

        boolean sawPolicyDenied = false;
        for (AgentEvent event : received) {
            if (event instanceof AgentEvent.PolicyDenied
                    && "apply_patch".equals(((AgentEvent.PolicyDenied) event).getTool())) {
                sawPolicyDenied = true;
            }
        }
        assertTrue("a PolicyDenied event must be emitted", sawPolicyDenied);
    }

    @Test
    public void eval_approvalHandlerAllow_runsMutation() {
        FakeAgentLlmGateway gateway = new FakeAgentLlmGateway(
                FakeAgentLlmGateway.ScriptedTurn.toolCall("apply_patch",
                        "{\"patch\": " + org.json.JSONObject.quote(NPE_PATCH) + "}"),
                FakeAgentLlmGateway.ScriptedTurn.text("Patched with approval.")
        );

        EventStream stream = new EventStream(Runnable::run, 128);
        AgentRuntime runtime = new AgentRuntime.Builder(gateway)
                .events(stream)
                .permissions(new PermissionLayer(ToolPolicy.interactive(),
                        request -> PermissionDecision.ALLOW, stream))
                .maxTurns(8)
                .build();

        List<ChatMessage> history = new ArrayList<>();
        history.add(new ChatMessage("fix it", ChatMessage.TYPE_USER, System.currentTimeMillis()));
        RunResult result = runtime.run(scriptedAgent(), history, "sc_eval");

        assertTrue(result.isSuccessful());
        assertEquals(PATCHED, fs.readText("src/demo/UserRepository.kt"));
    }

    // ------------------------------------------------------------------
    // read-only stub tools (workspace search/read without Android deps)
    // ------------------------------------------------------------------

    private static final class StubSearchTool implements AgentTool {
        @Override
        public String name() {
            return "search_for_files";
        }

        @Override
        public String description() {
            return "Search files by name.";
        }

        @Override
        public org.json.JSONObject parameters() {
            return new org.json.JSONObject();
        }

        @Override
        public AgentToolResult execute(RunContext context, org.json.JSONObject args) {
            return AgentToolResult.success("src/demo/UserRepository.kt");
        }
    }

    private static final class StubReadTool implements AgentTool {
        @Override
        public String name() {
            return "read_file";
        }

        @Override
        public String description() {
            return "Read a file.";
        }

        @Override
        public org.json.JSONObject parameters() {
            return new org.json.JSONObject();
        }

        @Override
        public AgentToolResult execute(RunContext context, org.json.JSONObject args) {
            return AgentToolResult.success("public String getUserName(int id) { ... }");
        }
    }
}
