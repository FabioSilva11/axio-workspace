package com.saaspaymentsolutions.axion.dependencies;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DependencyGraphResolverTest {

    private static DependencyGraphResolver.Candidate candidate(
            String group, String artifact, String version, boolean direct) {
        return new DependencyGraphResolver.Candidate(
                group, artifact, version, "jar", direct,
                group + ":" + artifact + ":" + version, null);
    }

    @Test
    public void testConflictSelectsHighestVersionDeterministically() {
        // A -> okhttp 4.10, B -> okhttp 4.12 (ordens diferentes)
        List<DependencyGraphResolver.Candidate> order1 = new ArrayList<>();
        order1.add(candidate("com.app", "a", "1.0", true));
        order1.add(candidate("com.squareup.okhttp3", "okhttp", "4.10", false));
        order1.add(candidate("com.app", "b", "1.0", true));
        order1.add(candidate("com.squareup.okhttp3", "okhttp", "4.12", false));

        List<DependencyGraphResolver.Candidate> order2 = new ArrayList<>(order1);
        Collections.reverse(order2);

        List<DependencyGraphResolver.Selection> selection1 =
                DependencyGraphResolver.resolve(order1);
        List<DependencyGraphResolver.Selection> selection2 =
                DependencyGraphResolver.resolve(order2);

        DependencyGraphResolver.Selection okhttp1 = find(selection1, "okhttp");
        DependencyGraphResolver.Selection okhttp2 = find(selection2, "okhttp");
        assertEquals("4.12", okhttp1.selectedVersion);
        assertEquals("4.12", okhttp2.selectedVersion);
        assertTrue(okhttp1.reason.contains("version conflict"));
        // As duas versões solicitadas ficam registradas
        assertEquals(2, okhttp1.requests.size());
    }

    @Test
    public void testSingleDeclarationHasSimpleReason() {
        List<DependencyGraphResolver.Candidate> candidates = new ArrayList<>();
        candidates.add(candidate("com.squareup.retrofit2", "retrofit", "2.11.0", true));
        candidates.add(candidate("com.squareup.okio", "okio", "3.6.0", false));

        List<DependencyGraphResolver.Selection> selections =
                DependencyGraphResolver.resolve(candidates);
        assertEquals(2, selections.size());
        assertEquals("single declaration",
                find(selections, "retrofit").reason);
        assertEquals("single declaration",
                find(selections, "okio").reason);
    }

    @Test
    public void testResultIsSortedByModule() {
        List<DependencyGraphResolver.Candidate> candidates = new ArrayList<>();
        candidates.add(candidate("zzz", "zebra", "1.0", true));
        candidates.add(candidate("aaa", "alpha", "1.0", true));

        List<DependencyGraphResolver.Selection> selections =
                DependencyGraphResolver.resolve(candidates);
        assertEquals("alpha", selections.get(0).artifactId);
        assertEquals("zebra", selections.get(1).artifactId);
    }

    @Test
    public void testTransitiveIsNeverPromotedToDirect() {
        List<DependencyGraphResolver.Candidate> candidates = new ArrayList<>();
        candidates.add(candidate("com.app", "a", "1.0", true));
        candidates.add(candidate("com.squareup.okhttp3", "okhttp", "4.12", false));

        List<DependencyGraphResolver.Selection> selections =
                DependencyGraphResolver.resolve(candidates);
        DependencyGraphResolver.Selection okhttp = find(selections, "okhttp");
        assertFalse(okhttp.requests.get(0).direct);
        // O selection não carrega flag "direct" promovida — transitiva continua transitiva
        assertEquals(1, okhttp.requests.size());
    }

    private static DependencyGraphResolver.Selection find(
            List<DependencyGraphResolver.Selection> selections, String artifactId) {
        for (DependencyGraphResolver.Selection selection : selections) {
            if (artifactId.equals(selection.artifactId)) return selection;
        }
        throw new AssertionError("Selecao nao encontrada: " + artifactId);
    }
}
