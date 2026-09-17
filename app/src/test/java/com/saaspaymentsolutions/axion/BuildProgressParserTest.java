package com.saaspaymentsolutions.axion;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class BuildProgressParserTest {
    @Test
    public void stageTwoCompletionIsNotWholeBuildTerminal() {
        BuildProgressParser.State state = BuildProgressParser.parse(Arrays.asList(
                "[11:31:30.679] [2/6] Kotlin/Compose + Java",
                "[11:32:46.146] [2/6] Compilacao concluida em 75486 ms."));
        assertTrue(state.visible);
        assertTrue(state.determinate);
        assertFalse(state.terminal);
        assertEquals(2, state.current);
        assertEquals(6, state.total);
    }

    @Test
    public void d8StageIsVisible() {
        BuildProgressParser.State state = BuildProgressParser.parse(Collections.singletonList(
                "[11:32:46.151] [3/6] D8: gerando DEX do projeto"));
        assertTrue(state.visible);
        assertEquals(3, state.current);
        assertEquals(6, state.total);
        assertTrue(state.message.contains("D8"));
    }

    @Test
    public void finalApkHidesProgress() {
        BuildProgressParser.State state = BuildProgressParser.parse(Collections.singletonList(
                "[11:37:54.191] [OK] APK gerado: /tmp/app.apk"));
        assertFalse(state.visible);
        assertTrue(state.terminal);
    }

    @Test
    public void dependencyProgressIsDeterminate() {
        BuildProgressParser.State state = BuildProgressParser.parse(Collections.singletonList(
                "[11:31:20.449] [PROGRESS] 12/54 androidx.collection:collection-ktx:1.4.0"));
        assertTrue(state.visible);
        assertTrue(state.determinate);
        assertEquals(12, state.current);
        assertEquals(54, state.total);
    }
    @Test
    public void detailedD8LineKeepsMainStageDeterminate() {
        BuildProgressParser.State state = BuildProgressParser.parse(Collections.singletonList(
                "[11:32:47.087] [D8] programFiles=557, libraryFiles=1, minApi=23"));
        assertTrue(state.visible);
        assertTrue(state.determinate);
        assertEquals(3, state.current);
        assertEquals(6, state.total);
        assertEquals("Gerando DEX…", state.message);
    }

}
