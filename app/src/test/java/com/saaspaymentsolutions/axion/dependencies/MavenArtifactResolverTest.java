package com.saaspaymentsolutions.axion.dependencies;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class MavenArtifactResolverTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private DependencyCache cache;

    @Before
    public void setUp() throws Exception {
        File cacheDir = tempFolder.newFolder("cache");
        cache = new DependencyCache(cacheDir);
    }

    @Test
    public void testResolverHandlesCancellation() {
        List<RepositoryDefinition> repos = new ArrayList<>();
        repos.add(RepositoryDefinition.mavenCentral());

        AtomicBoolean cancelled = new AtomicBoolean(true); // already cancelled
        MavenArtifactResolver resolver = new MavenArtifactResolver(cache, repos, cancelled);

        List<DeclaredDependency> declared = new ArrayList<>();
        declared.add(new DeclaredDependency("com.google.guava", "guava", "31.1-jre",
                DependencyConfiguration.IMPLEMENTATION, null, 1));

        DependencyResolutionResult result = resolver.resolveAll(declared, null);

        assertTrue(result.cancelled);
        assertFalse(result.isFullyResolved());
    }

    @Test
    public void testResolverDetectsLocalDependencies() throws Exception {
        File localJar = tempFolder.newFile("sample.jar");
        List<RepositoryDefinition> repos = new ArrayList<>();
        AtomicBoolean cancelled = new AtomicBoolean(false);

        MavenArtifactResolver resolver = new MavenArtifactResolver(cache, repos, cancelled);

        List<DeclaredDependency> declared = new ArrayList<>();
        declared.add(new DeclaredDependency(localJar.getAbsolutePath(),
                DependencyConfiguration.IMPLEMENTATION, null, 1));

        DependencyResolutionResult result = resolver.resolveAll(declared, null);

        assertFalse(result.cancelled);
        assertEquals(1, result.resolvedItems.size());
        assertEquals(DependencyStatus.LOCAL, result.resolvedItems.get(0).status);
    }
}
