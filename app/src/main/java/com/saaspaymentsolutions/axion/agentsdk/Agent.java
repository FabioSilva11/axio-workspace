package com.saaspaymentsolutions.axion.agentsdk;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * An agent definition, mirroring openai-agents-js {@code Agent}: a name, a
 * system instruction, its own tools and a list of agents it can hand off to.
 *
 * <p>Agents are inert configuration; the {@link Runner} executes them.</p>
 */
public class Agent {

    private final String name;
    private final String instructions;
    private final List<AgentTool> tools;
    private final List<Agent> handoffs;
    private final List<Guardrail> outputGuardrails;

    private Agent(Builder builder) {
        this.name = builder.name;
        this.instructions = builder.instructions;
        this.tools = Collections.unmodifiableList(new ArrayList<>(builder.tools));
        this.handoffs = Collections.unmodifiableList(new ArrayList<>(builder.handoffs));
        this.outputGuardrails = Collections.unmodifiableList(new ArrayList<>(builder.outputGuardrails));
    }

    public String name() {
        return name;
    }

    public String instructions() {
        return instructions;
    }

    public List<AgentTool> tools() {
        return tools;
    }

    /** Agents this agent may transfer control to. */
    public List<Agent> handoffs() {
        return handoffs;
    }

    public List<Guardrail> outputGuardrails() {
        return outputGuardrails;
    }

    public Builder toBuilder() {
        return new Builder(name, instructions)
                .tools(tools.toArray(new AgentTool[0]))
                .handoffs(handoffs.toArray(new Agent[0]))
                .outputGuardrails(outputGuardrails.toArray(new Guardrail[0]));
    }

    public static Builder builder(String name, AgentTool[] tools, Agent[] handoffs,
                                  Guardrail[] guardrails) {
        return new Builder(name, "").tools(tools).handoffs(handoffs).outputGuardrails(guardrails);
    }

    public static final class Builder {
        private final String name;
        private String instructions = "";
        private final List<AgentTool> tools = new ArrayList<>();
        private final List<Agent> handoffs = new ArrayList<>();
        private final List<Guardrail> outputGuardrails = new ArrayList<>();

        private Builder(String name, String instructions) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("Agent name is required");
            }
            this.name = name.trim();
            this.instructions = instructions == null ? "" : instructions.trim();
        }

        public static Builder forName(String name, String instructions) {
            return new Builder(name, instructions);
        }

        public Builder instructions(String instructions) {
            this.instructions = instructions == null ? "" : instructions.trim();
            return this;
        }

        public Builder tools(AgentTool... tools) {
            for (AgentTool tool : tools) {
                if (tool != null) {
                    this.tools.add(tool);
                }
            }
            return this;
        }

        public Builder handoffs(Agent... agents) {
            for (Agent agent : agents) {
                if (agent != null) {
                    this.handoffs.add(agent);
                }
            }
            return this;
        }

        public Builder outputGuardrails(Guardrail... guardrails) {
            for (Guardrail guardrail : guardrails) {
                if (guardrail != null) {
                    this.outputGuardrails.add(guardrail);
                }
            }
            return this;
        }

        public Agent build() {
            return new Agent(this);
        }
    }
}
