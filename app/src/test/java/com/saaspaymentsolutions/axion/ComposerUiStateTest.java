package com.saaspaymentsolutions.axion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ComposerUiStateTest {

    private static void assertIdle(ComposerUiState state) {
        assertTrue("SEND deve estar VISIBLE no IDLE", state.sendVisible);
        assertFalse("STOP deve estar GONE no IDLE", state.stopVisible);
        assertTrue("campo de mensagem deve estar ENABLED no IDLE", state.messageInputEnabled);
        assertTrue("btnAttach deve estar ENABLED no IDLE", state.attachEnabled);
        assertEquals("btnAttach deve ter alpha 1f no IDLE", 1f, state.attachAlpha, 0f);
    }

    private static void assertRunning(ComposerUiState state) {
        assertFalse("SEND deve estar GONE durante RUNNING", state.sendVisible);
        assertTrue("STOP deve estar VISIBLE durante RUNNING", state.stopVisible);
        assertFalse("campo de mensagem deve estar DISABLED durante RUNNING", state.messageInputEnabled);
        assertFalse("btnAttach deve estar DISABLED durante RUNNING", state.attachEnabled);
        assertEquals("btnAttach deve ter alpha reduzido durante RUNNING",
                ComposerUiState.RUNNING_ALPHA, state.attachAlpha, 0f);
    }

    private static void assertExclusive(ComposerUiState state) {
        assertTrue("SEND e STOP não podem estar VISIBLE simultaneamente (SEND="
                + state.sendVisible + ", STOP=" + state.stopVisible + ")",
                state.sendAndStopCompatible());
    }

    private static void runScenario(boolean[] processingSteps) {
        for (boolean processing : processingSteps) {
            ComposerUiState state = ComposerUiState.forRunState(processing);
            assertExclusive(state);
            if (processing) {
                assertRunning(state);
            } else {
                assertIdle(state);
            }
        }
        assertFalse("ESTADO FINAL deve voltar ao IDLE (SEND visível, STOP oculto)",
                processingSteps[processingSteps.length - 1]);
    }

    @Test
    public void idle_is_composer_normal_state() {
        assertIdle(ComposerUiState.idle());
        assertIdle(ComposerUiState.forRunState(false));
    }

    @Test
    public void running_hides_send_and_shows_stop() {
        assertRunning(ComposerUiState.running());
        assertRunning(ComposerUiState.forRunState(true));
    }

    @Test
    public void send_never_visible_while_processing() {
        assertFalse(ComposerUiState.running().sendVisible);
    }

    @Test
    public void stop_never_visible_outside_processing() {
        assertFalse(ComposerUiState.idle().stopVisible);
    }

    @Test
    public void send_and_stop_never_visible_simultaneously() {
        assertExclusive(ComposerUiState.idle());
        assertExclusive(ComposerUiState.running());
    }

    @Test
    public void send_returns_after_completion() {
        runScenario(new boolean[] { false, true, true, false });
    }

    @Test
    public void send_returns_after_error() {
        runScenario(new boolean[] { false, true, true, true, false });
    }

    @Test
    public void send_returns_after_cancel() {
        runScenario(new boolean[] { false, true, false });
    }

    @Test
    public void full_run_with_tool_approvals_ends_idle() {
        runScenario(new boolean[] { false, true, true, true, true, false });
    }

    @Test
    public void idle_actions_do_not_change_state() {
        boolean[] idleOnly = { false, false, false, false, false };
        for (boolean processing : idleOnly) {
            assertIdle(ComposerUiState.forRunState(processing));
            assertExclusive(ComposerUiState.forRunState(processing));
        }
    }

    @Test
    public void stop_disappears_in_all_terminal_states() {
        boolean[][] terminalFlows = {
                { false, true, false },
                { false, true, true, false },
                { false, true, true, true, false },
                { false, false },
        };
        for (boolean[] flow : terminalFlows) {
            ComposerUiState terminal = ComposerUiState.forRunState(flow[flow.length - 1]);
            assertFalse("STOP deve desaparecer no estado final", terminal.stopVisible);
            assertTrue("SEND deve reaparecer no estado final", terminal.sendVisible);
            assertIdle(terminal);
        }
    }

    @Test
    public void returning_to_idle_restores_every_control_without_residue() {
        ComposerUiState running = ComposerUiState.running();
        assertRunning(running);
        ComposerUiState canonicalIdle = ComposerUiState.idle();
        ComposerUiState recycledIdle = ComposerUiState.forRunState(false);
        assertEquals(canonicalIdle.sendVisible, recycledIdle.sendVisible);
        assertEquals(canonicalIdle.stopVisible, recycledIdle.stopVisible);
        assertEquals(canonicalIdle.messageInputEnabled, recycledIdle.messageInputEnabled);
        assertEquals(canonicalIdle.attachEnabled, recycledIdle.attachEnabled);
        assertEquals(canonicalIdle.attachAlpha, recycledIdle.attachAlpha, 0f);
    }

    @Test
    public void no_residual_state_after_long_cycles() {
        boolean[] steps = { false, true, true, false, false, false, true, true, false };
        ComposerUiState canonicalIdle = ComposerUiState.idle();
        for (boolean processing : steps) {
            ComposerUiState state = ComposerUiState.forRunState(processing);
            assertExclusive(state);
            if (!processing) {
                assertEquals(canonicalIdle.sendVisible, state.sendVisible);
                assertEquals(canonicalIdle.stopVisible, state.stopVisible);
                assertEquals(canonicalIdle.messageInputEnabled, state.messageInputEnabled);
                assertEquals(canonicalIdle.attachEnabled, state.attachEnabled);
                assertEquals(canonicalIdle.attachAlpha, state.attachAlpha, 0f);
            }
        }
    }

    @Test
    public void repeated_callback_refresh_never_flips_state() {
        for (int i = 0; i < 10; i++) {
            assertIdle(ComposerUiState.forRunState(false));
            assertRunning(ComposerUiState.forRunState(true));
        }
    }
}