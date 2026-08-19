package com.saaspaymentsolutions.axion.dependencies;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DependencyResolutionResultTest {

    @Test
    public void testErrorDiagnosticPreventsFullyResolvedResult() {
        DependencyResolutionResult result = new DependencyResolutionResult(
                new ArrayList<>(),
                new DependencyBuildClasspath(),
                Collections.singletonList(BuildDiagnostic.error(
                        "RESOLVE_001", "A versão da dependência não pôde ser resolvida")),
                false);

        assertFalse(result.isFullyResolved());
    }

    @Test
    public void testWarningDiagnosticDoesNotPreventFullyResolvedResult() {
        DependencyResolutionResult result = new DependencyResolutionResult(
                new ArrayList<>(),
                new DependencyBuildClasspath(),
                Collections.singletonList(BuildDiagnostic.warning(
                        "RESOLVE_WARN", "Repositório alternativo foi usado")),
                false);

        assertTrue(result.isFullyResolved());
    }
}
