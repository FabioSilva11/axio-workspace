package com.saaspaymentsolutions.axion.dependencies;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SyncSnapshotTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testWriteAndReadRoundTrip() throws Exception {
        File projectRoot = tempFolder.newFolder("project");
        List<SyncSnapshot.Artifact> artifacts = new ArrayList<>();
        artifacts.add(new SyncSnapshot.Artifact(
                "com.squareup.retrofit2", "retrofit", "2.11.0", "jar", true,
                "IMPLEMENTATION", "app -> retrofit", "abc123", "google", "/cache/retrofit.jar"));
        artifacts.add(new SyncSnapshot.Artifact(
                "com.squareup.okhttp3", "okhttp", "4.12.0", "jar", false,
                "IMPLEMENTATION", "app -> retrofit -> okhttp", "def456", "google", "/cache/okhttp.jar"));

        SyncSnapshot snapshot = SyncSnapshot.create(
                projectRoot, "debug", 21, 35, "fingerprint-1", artifacts,
                Arrays.asList("/cache/retrofit.jar", "/cache/okhttp.jar"),
                Arrays.asList("/cache/retrofit.jar", "/cache/okhttp.jar"),
                new ArrayList<>(),
                new ArrayList<>());
        snapshot.write();

        SyncSnapshot loaded = SyncSnapshot.read(projectRoot);
        assertNotNull(loaded);
        assertEquals("fingerprint-1", loaded.getDeclarationFingerprint());
        assertEquals(2, loaded.getArtifacts().size());
        assertEquals("retrofit", loaded.getArtifacts().get(0).artifactId);
        assertTrue(loaded.getArtifacts().get(0).direct);
        assertEquals("4.12.0", loaded.getArtifacts().get(1).version);
        assertFalse(loaded.getArtifacts().get(1).direct);
    }

    @Test
    public void testDirtyDetection() throws Exception {
        File projectRoot = tempFolder.newFolder("project");
        SyncSnapshot snapshot = SyncSnapshot.create(
                projectRoot, "debug", 21, 35, "fingerprint-A",
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>());
        snapshot.write();

        SyncSnapshot loaded = SyncSnapshot.read(projectRoot);
        assertNotNull(loaded);
        assertFalse(loaded.isStale("fingerprint-A"));
        assertTrue(loaded.isStale("fingerprint-B"));
    }

    @Test
    public void testMissingSnapshotIsNull() {
        File projectRoot = tempFolder.getRoot();
        assertNull(SyncSnapshot.read(new File(projectRoot, "no-such-project")));
    }
}
