package com.saaspaymentsolutions.axion.dependencies;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class VersionCatalogTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private VersionCatalog catalog(String toml) throws Exception {
        File file = tempFolder.newFile("libs.versions.toml");
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(toml);
        }
        return VersionCatalog.load(file);
    }

    @Test
    public void testResolvesModuleWithVersionRef() throws Exception {
        VersionCatalog catalog = catalog(
                "[versions]\n"
                        + "retrofit = \"2.11.0\"\n"
                        + "\n"
                        + "[libraries]\n"
                        + "retrofit = { module = \"com.squareup.retrofit2:retrofit\", version.ref = \"retrofit\" }\n");
        assertEquals("com.squareup.retrofit2:retrofit:2.11.0",
                catalog.resolve("retrofit"));
    }

    @Test
    public void testResolvesInlineVersionAndGroupName() throws Exception {
        VersionCatalog catalog = catalog(
                "[libraries]\n"
                        + "okhttp = { group = \"com.squareup.okhttp3\", name = \"okhttp\", version = \"4.12.0\" }\n"
                        + "gson = \"com.google.code.gson:gson:2.10.1\"\n");
        assertEquals("com.squareup.okhttp3:okhttp:4.12.0", catalog.resolve("okhttp"));
        assertEquals("com.google.code.gson:gson:2.10.1", catalog.resolve("gson"));
    }

    @Test
    public void testNormalizesAliasSeparators() throws Exception {
        VersionCatalog catalog = catalog(
                "[versions]\n"
                        + "retrofitCore = \"2.11.0\"\n"
                        + "\n"
                        + "[libraries]\n"
                        + "retrofit-core = { module = \"com.squareup.retrofit2:retrofit\", version.ref = \"retrofitCore\" }\n");
        // libs.retrofit.core (KTS aninhado) e libs.retrofit-core (Groovy) resolvem igual
        assertEquals("com.squareup.retrofit2:retrofit:2.11.0",
                catalog.resolve("retrofit-core"));
        assertEquals("com.squareup.retrofit2:retrofit:2.11.0",
                catalog.resolve("retrofit.core"));
    }

    @Test
    public void testResolvesBundles() throws Exception {
        VersionCatalog catalog = catalog(
                "[versions]\n"
                        + "retrofit = \"2.11.0\"\n"
                        + "\n"
                        + "[libraries]\n"
                        + "retrofit = { module = \"com.squareup.retrofit2:retrofit\", version.ref = \"retrofit\" }\n"
                        + "okhttp = { module = \"com.squareup.okhttp3:okhttp\", version = \"4.12.0\" }\n"
                        + "\n"
                        + "[bundles]\n"
                        + "network = [\"retrofit\", \"okhttp\"]\n");
        List<String> coordinates = catalog.resolveBundle("network");
        assertNotNull(coordinates);
        assertEquals(2, coordinates.size());
        assertEquals("com.squareup.retrofit2:retrofit:2.11.0", coordinates.get(0));
        assertEquals("com.squareup.okhttp3:okhttp:4.12.0", coordinates.get(1));
    }

    @Test
    public void testUnknownAliasReturnsNull() throws Exception {
        VersionCatalog catalog = catalog("[libraries]\nfoo = \"com.example:foo:1.0\"\n");
        assertNull(catalog.resolve("bar"));
    }
}
