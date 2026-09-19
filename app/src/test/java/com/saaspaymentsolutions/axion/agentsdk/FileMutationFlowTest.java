package com.saaspaymentsolutions.axion.agentsdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.saaspaymentsolutions.axion.FileChangeTracker;
import com.saaspaymentsolutions.axion.FileChangeTrackerWorkspaceTest;
import com.saaspaymentsolutions.axion.Tool;
import com.saaspaymentsolutions.axion.ToolManager;
import com.saaspaymentsolutions.axion.ToolExecResult;
import com.saaspaymentsolutions.axion.port.VoidToolWrapper;
import com.saaspaymentsolutions.axion.workspace.Workspace;
import com.saaspaymentsolutions.axion.workspace.WorkspaceManager;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

/**
 * One approval, one real mutation: file tools mutate through the active
 * {@link com.saaspaymentsolutions.axion.workspace.WorkspaceFileSystem}, the
 * PermissionLayer classifies them from their metadata, the tracker records
 * what was already applied, and the tool result reflects the real filesystem
 * outcome (no fake successes on failed deletes).
 */
public class FileMutationFlowTest {

    private FileChangeTrackerWorkspaceTest.FakeWorkspaceFileSystem fs;

    @Before
    public void setUp() {
        fs = new FileChangeTrackerWorkspaceTest.FakeWorkspaceFileSystem();
        WorkspaceManager.INSTANCE.setCustomFileSystemForTesting(fs, fakeWorkspace());
        FileChangeTracker.clearChanges("sc_mutation");
    }

    @After
    public void tearDown() {
        FileChangeTracker.clearChanges("sc_mutation");
        WorkspaceManager.INSTANCE.setCustomFileSystemForTesting(
                new FileChangeTrackerWorkspaceTest.FakeWorkspaceFileSystem(), fakeWorkspace());
    }

    // ------------------------------------------------------------------
    // delete_file_or_folder through the workspace filesystem
    // ------------------------------------------------------------------

    @Test
    public void delete_viaWorkspace_removesTheFileAndReportsSuccess() throws Exception {
        fs.writeText("src/old.txt", "obsolete");
        String output = executeVoidTool("delete_file_or_folder",
                new JSONObject().put("uri", "src/old.txt"));

        assertFalse("delete must succeed for an existing file: " + output,
                output.contains("Error"));
        assertFalse("the file must be gone from the workspace filesystem",
                fs.exists("src/old.txt"));
        assertTrue("delete must run through the workspace",
                fs.deleteCalls.contains("src/old.txt"));
    }

    @Test
    public void delete_whenFilesystemSaysNo_returnsError_neverSuccess() throws Exception {
        fs.writeText("src/stuck.txt", "content");
        fs.failDeletes = true;

        String output = executeVoidTool("delete_file_or_folder",
                new JSONObject().put("uri", "src/stuck.txt"));

        assertTrue("a failed delete must be an error result", output.contains("Error"));
        assertTrue("the file must still exist after a failed delete", fs.exists("src/stuck.txt"));
        assertFalse("failed delete must not be tracked as an applied change",
                FileChangeTracker.getAllRecentChanges("sc_mutation").containsKey("src/stuck.txt"));
    }

    @Test
    public void delete_missingFile_reportsNotFound() throws Exception {
        String output = executeVoidTool("delete_file_or_folder",
                new JSONObject().put("uri", "src/nope.txt"));

        assertTrue(output.contains("Error"));
    }

    @Test
    public void delete_rejectsParentTraversal() throws Exception {
        String output = executeVoidTool("delete_file_or_folder",
                new JSONObject().put("uri", "../outside.txt"));

        assertTrue(output.contains("Error"));
        assertTrue(fs.deleteCalls.isEmpty());
    }

    @Test
    public void delete_tracksAuditEntryForDeletedFile() throws Exception {
        fs.writeText("src/trackme.txt", "to be deleted");
        executeVoidTool("delete_file_or_folder",
                new JSONObject().put("uri", "src/trackme.txt"));

        FileChangeTracker.FileChange change = FileChangeTracker
                .getAllRecentChanges("sc_mutation").get("src/trackme.txt");
        assertNotNull("the audit trail must record the deletion", change);
        assertEquals("to be deleted", change.beforeContent);
    }

    // ------------------------------------------------------------------
    // apply_patch: validated delete + rollback after partial failure
    // ------------------------------------------------------------------

    @Test
    public void applyPatch_delete_validatesTheFilesystemResult() throws Exception {
        fs.writeText("src/Target.kt", "val a = 1\n");
        ApplyPatchTool tool = new ApplyPatchTool("sc_mutation", null, fs);

        AgentToolResult result = tool.execute(null, new JSONObject().put("patch",
                "*** Begin Patch\n*** Delete File: src/Target.kt\n*** End Patch"));

        assertTrue(result.output().contains("Deleted src/Target.kt"));
        assertFalse("delete must be real before it is reported", fs.exists("src/Target.kt"));
    }

    @Test
    public void applyPatch_delete_failedFilesystemNeverReportsSuccess() throws Exception {
        fs.writeText("src/Target.kt", "val a = 1\n");
        fs.failDeletes = true;
        ApplyPatchTool tool = new ApplyPatchTool("sc_mutation", null, fs);

        AgentToolResult result = tool.execute(null, new JSONObject().put("patch",
                "*** Begin Patch\n*** Delete File: src/Target.kt\n*** End Patch"));

        assertTrue("the tool must not claim success when the delete failed",
                result.isError());
        assertTrue("the file must still exist after the failed delete",
                fs.exists("src/Target.kt"));
    }

    @Test
    public void applyPatch_rollsBackEarlierWrites_whenALaterDeleteFails() throws Exception {
        fs.writeText("src/B.kt", "val b = 2\n");
        fs.failDeletes = true; // the DELETE op will fail mid-application
        ApplyPatchTool tool = new ApplyPatchTool("sc_mutation", null, fs);

        AgentToolResult result = tool.execute(null, new JSONObject().put("patch",
                "*** Begin Patch\n"
                        + "*** Update File: src/B.kt\n"
                        + "@@\n"
                        + "-val b = 2\n"
                        + "+val b = 42\n"
                        + "*** Delete File: src/C.kt\n"
                        + "*** End Patch"));

        assertTrue(result.isError());
        assertEquals("earlier writes must be rolled back to the pre-patch content",
                "val b = 2\n", fs.readText("src/B.kt"));
        assertFalse("the rolled-back error must be honest about the failure",
                result.output().contains("Deleted"));
    }

    @Test
    public void applyPatch_addUpdateDelete_appliesEverything() throws Exception {
        fs.writeText("src/Update.java", "int a = 1;\nint b = 2;\n");
        fs.writeText("src/Delete.java", "obsolete\n");
        ApplyPatchTool tool = new ApplyPatchTool("sc_mutation", null, fs);

        AgentToolResult result = tool.execute(null, new JSONObject().put("patch",
                "*** Begin Patch\n"
                        + "*** Add File: src/New.java\n"
                        + "+class New {}\n"
                        + "*** Update File: src/Update.java\n"
                        + "@@\n"
                        + "-int a = 1;\n"
                        + "+int a = 10;\n"
                        + "*** Delete File: src/Delete.java\n"
                        + "*** End Patch"));

        assertFalse(result.isError());
        assertEquals("class New {}\n", fs.readText("src/New.java"));
        assertEquals("int a = 10;\nint b = 2;\n", fs.readText("src/Update.java"));
        assertFalse(fs.exists("src/Delete.java"));
    }

    // ------------------------------------------------------------------
    // RegistryToolAdapter metadata preservation + PermissionLayer routing
    // ------------------------------------------------------------------

    @Test
    public void registryAdapter_preservesMutationDestructiveAndApprovalMetadata() {
        Tool deleteTool = new VoidToolWrapper("delete_file_or_folder", "Delete a file.",
                new JSONObject(), true, true, true);
        Tool readTool = new VoidToolWrapper("read_file", "Reads a file.",
                new JSONObject(), false, false, false);
        ToolManager manager = new ToolManager();
        manager.registerTool(deleteTool);
        manager.registerTool(readTool);

        AgentTool adaptedDelete = WorkspaceAgents.fromRegistryTool(deleteTool, manager);
        AgentTool adaptedRead = WorkspaceAgents.fromRegistryTool(readTool, manager);

        assertTrue(adaptedDelete.isFileMutation());
        assertTrue(adaptedDelete.isDestructive());
        assertTrue(adaptedDelete.requiresApproval());
        assertFalse(adaptedRead.isFileMutation());
        assertFalse(adaptedRead.isDestructive());
    }

    @Test
    public void permissionLayer_classifiesMutatingToolsFromMetadata_notUnknown() {
        // A mutation-only tool (non-destructive) must follow the mutation rule.
        AgentTool edit = adapt("edit_file", false, false, true);
        AgentTool delete = adapt("delete_file_or_folder", true, true, true);
        AgentTool patch = new ApplyPatchTool("sc_mutation", null);
        AgentTool read = adapt("read_file", false, false, false);

        PermissionLayer layer = new PermissionLayer(
                new ToolPolicy.Builder()
                        .mutation(ToolPolicy.Rule.ALLOW)
                        .destructive(ToolPolicy.Rule.ASK_USER)
                        .shell(ToolPolicy.Rule.ASK_USER)
                        .network(ToolPolicy.Rule.ALLOW)
                        .unknown(ToolPolicy.Rule.DENY)
                        .build(),
                null, null);

        assertEquals(ToolPolicy.Rule.ALLOW, layer.ruleForPublicForTest(edit));
        assertEquals("destructive metadata must win over mutation",
                ToolPolicy.Rule.ASK_USER, layer.ruleForPublicForTest(delete));
        assertEquals(ToolPolicy.Rule.ASK_USER, layer.ruleForPublicForTest(patch));
        assertEquals("read-only tools must not be classified by the name fallback",
                ToolPolicy.Rule.DENY, layer.ruleForPublicForTest(read));
    }

    @Test
    public void permissionLayer_nameFallbackCoversMetadataLessAdapters() {
        AgentTool metadataLess = metadataLessTool("delete_file_or_folder");

        PermissionLayer layer = new PermissionLayer(
                new ToolPolicy.Builder()
                        .mutation(ToolPolicy.Rule.ALLOW)
                        .destructive(ToolPolicy.Rule.ASK_USER)
                        .unknown(ToolPolicy.Rule.DENY)
                        .build(),
                null, null);

        assertEquals("a metadata-less mutating adapter must not fall through to unknown",
                ToolPolicy.Rule.ASK_USER, layer.ruleForPublicForTest(metadataLess));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static Workspace fakeWorkspace() {
        return new Workspace("ws-mutation", "Mutation", "/", "/", false,
                Workspace.PermissionState.GRANTED, 0L, "");
    }

    /** Runs a Void registry tool end-to-end through the ToolManager. */
    private static String executeVoidTool(String name, JSONObject args) {
        ToolManager manager = new ToolManager();
        manager.registerTool(new VoidToolWrapper(name, name, new JSONObject(),
                false, false, false));
        ToolExecResult exec = manager.executeTool("sc_mutation", name, args.toString());
        return exec.ok ? exec.output : "Error: " + exec.output;
    }

    /** Adapts a Void registry tool through the production adapter. */
    private static AgentTool adapt(String name, boolean approval, boolean destructive,
                                   boolean mutation) {
        ToolManager manager = new ToolManager();
        return WorkspaceAgents.fromRegistryTool(
                new VoidToolWrapper(name, name, new JSONObject(),
                        approval, destructive, mutation),
                manager);
    }

    /** AgentTool without metadata flags, simulating an adapter that lost them. */
    private static AgentTool metadataLessTool(String name) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public JSONObject parameters() { return new JSONObject(); }
            @Override public boolean isFileMutation() { return false; }
            @Override public boolean isDestructive() { return false; }
            @Override public boolean requiresApproval() { return false; }
            @Override public AgentToolResult execute(RunContext context, JSONObject args) {
                return AgentToolResult.success("");
            }
        };
    }
}
