package com.saaspaymentsolutions.axion.agentsdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.saaspaymentsolutions.axion.AiOperationContext;
import com.saaspaymentsolutions.axion.ChatMessage;
import com.saaspaymentsolutions.axion.toolcalling.ToolCall;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class RunnerTest {

    /** Records calls made to the gateway. */
    private static final class FakeGateway implements AgentLlmGateway {
        final List<LlmTurnOutput> scripted = new ArrayList<>();
        final List<String> receivedSystemPrompts = new ArrayList<>();
        final List<List<ChatMessage>> receivedHistories = new ArrayList<>();
        int index;

        @Override
        public LlmTurnOutput completeTurn(String systemPrompt, JSONArray tools,
                                          List<ChatMessage> messages,
                                          AiOperationContext operationContext) {
            receivedSystemPrompts.add(systemPrompt);
            receivedHistories.add(new ArrayList<>(messages));
            if (index < scripted.size()) {
                return scripted.get(index++);
            }
            return new LlmTurnOutput("done", "", "stop", Collections.emptyList());
        }
    }

    private static ToolCall call(String name, String argsJson) {
        return new ToolCall(name, argsJson, "call_1");
    }

    private static AgentTool echoTool() {
        return new AgentTool() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public String description() {
                return "Echoes the input.";
            }

            @Override
            public JSONObject parameters() {
                return new JSONObject();
            }

            @Override
            public AgentToolResult execute(RunContext context, JSONObject args) {
                return AgentToolResult.success("echo:" + args.optString("text", ""));
            }
        };
    }

    @Test
    public void simpleTextRunReturnsFinalOutput() {
        FakeGateway gateway = new FakeGateway();
        gateway.scripted.add(new LlmTurnOutput("Hello!", "", "stop", Collections.emptyList()));
        Runner runner = new Runner.Builder(gateway).build();
        Agent agent = Agent.Builder.forName("a", "instructions").build();

        RunResult result = runner.run(agent, "hi", "sc1");

        assertTrue(result.isSuccessful());
        assertEquals("Hello!", result.getOutput());
        assertEquals(1, gateway.receivedSystemPrompts.size());
        assertEquals("instructions", gateway.receivedSystemPrompts.get(0));
    }

    @Test
    public void toolCallIsExecutedAndResultAppendedToHistory() {
        FakeGateway gateway = new FakeGateway();
        gateway.scripted.add(new LlmTurnOutput("", "", "tool_calls",
                Collections.singletonList(call("echo", "{\"text\":\"x\"}"))));
        gateway.scripted.add(new LlmTurnOutput("finished", "", "stop", Collections.emptyList()));
        Runner runner = new Runner.Builder(gateway).build();
        Agent agent = Agent.Builder.forName("a", "i").tools(echoTool()).build();

        RunResult result = runner.run(agent, "use the tool", "sc1");

        assertTrue(result.isSuccessful());
        assertEquals("finished", result.getOutput());
        // Turn 2's history must contain the tool result.
        List<ChatMessage> secondTurnHistory = gateway.receivedHistories.get(1);
        ChatMessage last = secondTurnHistory.get(secondTurnHistory.size() - 1);
        assertEquals(ChatMessage.TYPE_TOOL, last.getType());
        assertEquals("echo:x", last.getToolResult());
    }

    @Test
    public void unknownToolReturnsErrorResultToModel() {
        FakeGateway gateway = new FakeGateway();
        gateway.scripted.add(new LlmTurnOutput("", "", "tool_calls",
                Collections.singletonList(call("nope", "{}"))));
        gateway.scripted.add(new LlmTurnOutput("ok", "", "stop", Collections.emptyList()));
        Runner runner = new Runner.Builder(gateway).build();
        Agent agent = Agent.Builder.forName("a", "i").build();

        RunResult result = runner.run(agent, "go", "sc1");

        assertTrue(result.isSuccessful());
        List<ChatMessage> secondTurnHistory = gateway.receivedHistories.get(1);
        ChatMessage last = secondTurnHistory.get(secondTurnHistory.size() - 1);
        assertTrue(last.getToolResult().contains("unknown tool"));
    }

    @Test
    public void handoffSwitchesActiveAgentForNextTurn() {
        FakeGateway gateway = new FakeGateway();
        gateway.scripted.add(new LlmTurnOutput("", "", "tool_calls",
                Collections.singletonList(call("transfer_to_helper", "{\"reason\":\"delegation\"}"))));
        gateway.scripted.add(new LlmTurnOutput("helper finished", "", "stop", Collections.emptyList()));
        Runner runner = new Runner.Builder(gateway).build();

        Agent helper = Agent.Builder.forName("helper", "helper instructions").build();
        Agent main = Agent.Builder.forName("main", "main instructions")
                .handoffs(helper)
                .tools(new HandoffTool(helper))
                .build();

        RunResult result = runner.run(main, "delegate please", "sc1");

        assertTrue(result.isSuccessful());
        assertEquals("helper finished", result.getOutput());
        // After the handoff the next turn must run with the helper's instructions.
        assertEquals("helper instructions", gateway.receivedSystemPrompts.get(1));
        assertEquals(1, result.getHandoffTrail().size());
        assertTrue(result.getHandoffTrail().get(0).contains("helper"));
    }

    @Test
    public void inputGuardrailBlocksBeforeFirstLlmCall() {
        FakeGateway gateway = new FakeGateway();
        Runner runner = new Runner.Builder(gateway)
                .inputGuardrails(new Guardrail() {
                    @Override
                    public GuardrailResult checkInput(String userInput) {
                        return GuardrailResult.block("banned");
                    }
                })
                .build();
        Agent agent = Agent.Builder.forName("a", "i").build();

        RunResult result = runner.run(agent, "bad input", "sc1");

        assertFalse(result.isSuccessful());
        assertTrue(result.isBlockedByGuardrail());
        assertEquals("banned", result.getGuardrailResult().info());
        assertEquals(0, gateway.receivedSystemPrompts.size());
    }

    @Test
    public void maxTurnsCeilingStopsRun() {
        FakeGateway gateway = new FakeGateway();
        gateway.scripted.add(new LlmTurnOutput("", "", "tool_calls",
                Collections.singletonList(call("echo", "{}"))));
        // No further scripted turns: the fake returns "done" text anyway, so
        // force tools forever via a scripted loop instead:
        Runner runner = new Runner.Builder(gateway).maxTurns(2).build();
        Agent agent = Agent.Builder.forName("a", "i").tools(echoTool()).build();

        // Script alternating tool turns; with maxTurns=2 the run must stop.
        gateway.scripted.clear();
        gateway.scripted.add(new LlmTurnOutput("", "", "tool_calls",
                Collections.singletonList(call("echo", "{}"))));
        gateway.scripted.add(new LlmTurnOutput("", "", "tool_calls",
                Collections.singletonList(call("echo", "{}"))));

        RunResult result = runner.run(agent, "loop", "sc1");

        assertFalse(result.isSuccessful());
        assertTrue(result.isMaxTurnsReached());
    }

    @Test
    public void rejectedApprovalSkipsExecution() {
        FakeGateway gateway = new FakeGateway();
        gateway.scripted.add(new LlmTurnOutput("", "", "tool_calls",
                Collections.singletonList(call("echo", "{\"text\":\"x\"}"))));
        gateway.scripted.add(new LlmTurnOutput("done", "", "stop", Collections.emptyList()));
        Runner runner = new Runner.Builder(gateway)
                .listeners(new RunListeners() {
                    @Override
                    public boolean onToolApproval(AgentTool tool, ToolCall call) {
                        return false;
                    }
                })
                .build();
        Agent agent = Agent.Builder.forName("a", "i")
                .tools(new AgentTool() {
                    @Override
                    public String name() {
                        return "echo";
                    }

                    @Override
                    public String description() {
                        return "echo";
                    }

                    @Override
                    public JSONObject parameters() {
                        return new JSONObject();
                    }

                    @Override
                    public boolean requiresApproval() {
                        return true;
                    }

                    @Override
                    public AgentToolResult execute(RunContext context, JSONObject args) {
                        return AgentToolResult.success("should not run");
                    }
                })
                .build();

        RunResult result = runner.run(agent, "go", "sc1");

        assertTrue(result.isSuccessful());
        List<ChatMessage> secondTurnHistory = gateway.receivedHistories.get(1);
        ChatMessage last = secondTurnHistory.get(secondTurnHistory.size() - 1);
        assertTrue(last.getToolResult().contains("rejected"));
    }
}
