package com.saaspaymentsolutions.axion.agentsdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.saaspaymentsolutions.axion.toolcalling.ToolCall;

import org.junit.Test;

import java.util.Collections;

public class AgentTurnParserTest {

    @Test
    public void plainTextYieldsNoToolCalls() {
        AgentTurnParser parser = new AgentTurnParser();
        AgentTurnParser.ParsedTurn turn = parser.parse("Just text.", "", "stop",
                Collections.emptyList());

        assertEquals("Just text.", turn.content());
        assertTrue(!turn.hasToolCalls());
    }

    @Test
    public void xmlEmbeddedToolCallIsDetected() {
        String payload = "Before text.\n"
                + "<tool_call name=\"read_file\">\n"
                + "  <parameter name=\"uri\">/tmp/a.html</parameter>\n"
                + "</tool_call>";
        AgentTurnParser parser = new AgentTurnParser();
        AgentTurnParser.ParsedTurn turn = parser.parse(payload, "", "stop",
                Collections.emptyList());

        assertTrue(turn.hasToolCalls());
        assertEquals("read_file", turn.toolCalls().get(0).getName());
        assertTrue(!turn.content().contains("read_file"));
    }

    @Test
    public void functionXmlBlockIsDetected() {
        String payload = "<function name=\"ls_dir\"><parameter name=\"uri\"></parameter></function>";
        AgentTurnParser parser = new AgentTurnParser();
        AgentTurnParser.ParsedTurn turn = parser.parse(payload, "", "stop",
                Collections.emptyList());

        assertTrue(turn.hasToolCalls());
        assertEquals("ls_dir", turn.toolCalls().get(0).getName());
    }

    @Test
    public void nativeCallsArePreservedAndValidated() {
        ToolCall nativeCall = new ToolCall("ls_dir", "{\"uri\":\"\"}", "native-1");
        AgentTurnParser parser = new AgentTurnParser();
        AgentTurnParser.ParsedTurn turn = parser.parse("some text", "", "tool_calls",
                Collections.singletonList(nativeCall));

        assertTrue(turn.hasToolCalls());
        assertEquals("ls_dir", turn.toolCalls().get(0).getName());
        assertEquals("some text", turn.content());
    }
}
