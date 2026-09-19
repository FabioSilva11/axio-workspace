package com.saaspaymentsolutions.axion.agentsdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.saaspaymentsolutions.axion.toolcalling.DefaultToolCallDetector;
import com.saaspaymentsolutions.axion.toolcalling.ToolCall;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * Tool-call execution contract (Codex ResponseItem parity): the v2 turn
 * parser NEVER derives a tool call from assistant text. Structured
 * (provider-native) calls are the only executable source; text-embedded
 * protocols exist only behind the explicit LEGACY path.
 */
public class AgentTurnParserTest {

    // ------------------------------------------------------------------
    // v2 contract: text is text
    // ------------------------------------------------------------------

    @Test
    public void plainTextYieldsNoToolCalls() {
        AgentTurnParser parser = new AgentTurnParser();
        AgentTurnParser.ParsedTurn turn = parser.parse("Just text.", "", "stop",
                Collections.emptyList());

        assertEquals("Just text.", turn.content());
        assertFalse(turn.hasToolCalls());
    }

    @Test
    public void xmlEmbeddedToolCallStaysText_inV2() {
        String payload = "Before text.\n"
                + "<tool_call name=\"read_file\">\n"
                + "  <parameter name=\"uri\">/tmp/a.html</parameter>\n"
                + "</tool_call>";
        AgentTurnParser parser = new AgentTurnParser();
        AgentTurnParser.ParsedTurn turn = parser.parse(payload, "", "stop",
                Collections.emptyList());

        assertFalse("text-embedded XML must NOT become a tool call in v2",
                turn.hasToolCalls());
        assertEquals("text is preserved verbatim (display contract)", payload, turn.content());
    }

    @Test
    public void functionXmlBlockStaysText_inV2() {
        String payload = "<function name=\"ls_dir\"><parameter name=\"uri\"></parameter></function>";
        AgentTurnParser parser = new AgentTurnParser();
        AgentTurnParser.ParsedTurn turn = parser.parse(payload, "", "stop",
                Collections.emptyList());

        assertFalse(turn.hasToolCalls());
        assertEquals(payload, turn.content());
    }

    @Test
    public void jsonToolLikeBlockStaysText_inV2() {
        String payload = "{\"name\": \"apply_patch\", \"arguments\": {\"patch\": \"*** Begin Patch\"}}";
        AgentTurnParser parser = new AgentTurnParser();
        AgentTurnParser.ParsedTurn turn = parser.parse(payload, "", "stop",
                Collections.emptyList());

        assertFalse("JSON resembling a tool call is text, not a call", turn.hasToolCalls());
        assertEquals(payload, turn.content());
    }

    // ------------------------------------------------------------------
    // LEGACY opt-in path (retired hosts only)
    // ------------------------------------------------------------------

    @Test
    public void xmlEmbeddedToolCall_parsedOnlyViaLegacyPath() {
        String payload = "Before text.\n"
                + "<tool_call name=\"read_file\">\n"
                + "  <parameter name=\"uri\">/tmp/a.html</parameter>\n"
                + "</tool_call>";
        AgentTurnParser legacyParser = new AgentTurnParser(new DefaultToolCallDetector(), true);
        AgentTurnParser.ParsedTurn turn = legacyParser.parseLegacyTextEmbedded(payload, "", "stop",
                Collections.emptyList());

        assertTrue("legacy path still understands text-embedded protocols",
                turn.hasToolCalls());
        assertEquals("read_file", turn.toolCalls().get(0).getName());
        assertFalse(turn.content().contains("read_file"));
    }

    @Test
    public void legacyPathIsDisabledByDefault_evenWhenCalledExplicitly() {
        String payload = "<tool_call name=\"read_file\"></tool_call>";
        AgentTurnParser v2Parser = new AgentTurnParser();
        AgentTurnParser.ParsedTurn turn = v2Parser.parseLegacyTextEmbedded(payload, "", "stop",
                Collections.emptyList());

        assertFalse("the flag gates the legacy detection, not just the method",
                turn.hasToolCalls());
    }

    // ------------------------------------------------------------------
    // structured calls: preserved, deduped by callId
    // ------------------------------------------------------------------

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

    @Test
    public void sameCallIdTwice_deduplicatesToOne() {
        ToolCall first = new ToolCall("apply_patch", "{\"patch\":\"a\"}", "call_1");
        ToolCall duplicate = new ToolCall("apply_patch", "{\"patch\":\"a\"}", "call_1");
        AgentTurnParser parser = new AgentTurnParser();
        AgentTurnParser.ParsedTurn turn = parser.parse("", "", "tool_calls",
                Arrays.asList(first, duplicate));

        assertEquals("dedupe by callId, not by name", 1, turn.toolCalls().size());
    }

    @Test
    public void sameNameDifferentIds_bothPreserved() {
        ToolCall first = new ToolCall("apply_patch", "{\"patch\":\"a\"}", "call_1");
        ToolCall second = new ToolCall("apply_patch", "{\"patch\":\"b\"}", "call_2");
        AgentTurnParser parser = new AgentTurnParser();
        AgentTurnParser.ParsedTurn turn = parser.parse("", "", "tool_calls",
                Arrays.asList(first, second));

        assertEquals("two structured calls, two executions", 2, turn.toolCalls().size());
    }

    @Test
    public void invalidNativeCallIsDropped() {
        ToolCall invalid = new ToolCall("apply_patch", "{not json", "call_bad");
        AgentTurnParser parser = new AgentTurnParser();
        AgentTurnParser.ParsedTurn turn = parser.parse("", "", "tool_calls",
                Collections.singletonList(invalid));

        assertFalse(turn.hasToolCalls());
    }
}
