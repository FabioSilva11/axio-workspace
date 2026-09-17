package com.saaspaymentsolutions.axion.dependencies;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import static org.junit.Assert.*;

public class DependencyCacheTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private DependencyCache cache;

    @Before
    public void setUp() throws IOException {
        File cacheDir = tempFolder.newFolder("cache");
        cache = new DependencyCache(cacheDir);
    }

    @Test
    public void testArtifactPathResolution() {
        File cachedFile = cache.getCachedFile("androidx.appcompat", "appcompat", "1.6.1", "aar");
        assertTrue(cachedFile.getAbsolutePath().contains("androidx" + File.separator + "appcompat"));
        assertTrue(cachedFile.getName().endsWith("appcompat-1.6.1.aar"));
    }

    @Test
    public void testIsCachedValidatesFileExistsAndNotEmpty() throws IOException {
        String group = "com.google.guava";
        String artifact = "guava";
        String version = "31.1-jre";
        String ext = "jar";

        assertFalse(cache.isCached(group, artifact, version, ext));

        File file = cache.getCachedFile(group, artifact, version, ext);
        file.getParentFile().mkdirs();
        file.createNewFile();

        // 0-byte file should NOT be considered cached
        assertFalse(cache.isCached(group, artifact, version, ext));

        try (FileWriter writer = new FileWriter(file)) {
            writer.write("dummy content");
        }

        // File with content > 0 bytes should be cached
        assertTrue(cache.isCached(group, artifact, version, ext));
    }

    @Test
    public void testCleanPartFiles() throws IOException {
        File partFile = cache.getPartFile("com.test", "artifact", "1.0.0", "jar");
        partFile.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(partFile)) {
            writer.write("incomplete download");
        }

        assertTrue(partFile.exists());
        cache.cleanPartFiles();
        assertFalse(partFile.exists());
    }
}
