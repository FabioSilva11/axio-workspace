package com.saaspaymentsolutions.axion;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ToolExecResultTest {
    @Test
    public void mcpFailuresAreNeverRecordedAsSuccessfulTools() {
        assertFalse(ToolExecResult.fromLegacyString("MCP error: timeout").ok);
        assertFalse(ToolExecResult.fromLegacyString("MCP HTTP call failed for server/tool: timeout").ok);
        assertFalse(ToolExecResult.fromLegacyString("MCP server 'local' uses stdio").ok);
    }

    @Test
    public void ordinaryContentContainingErrorWordIsStillSuccess() {
        assertTrue(ToolExecResult.fromLegacyString(
                "The source code contains an error message string, but read_file succeeded.").ok);
    }
}
