package com.saaspaymentsolutions.axion;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.Test;

public class ProjectTemplateArchiveTest {
    @Test
    public void androidTemplateIsComposeOnlyAndSelfBuildable() throws Exception {
        try (ZipFile zip = openAsset("template_studio/androidx.zip")) {
            requireEntry(zip, "gradlew.bat");
            requireEntry(zip, "gradle/wrapper/gradle-wrapper.jar");
            requireEntry(zip, "app/src/main/java/$package_name$/MainActivity.kt");
            requireEntry(zip, "app/src/main/java/$package_name$/ui/App.kt");
            requireEntry(zip, "app/src/main/java/$package_name$/ui/home/HomeScreen.kt");
            requireEntry(zip, "app/src/main/java/$package_name$/ui/theme/Theme.kt");

            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                assertFalse("XML layout leaked into Compose template: " + name,
                        name.startsWith("app/src/main/res/layout/") && name.endsWith(".xml"));
                assertFalse("Java source leaked into Compose template: " + name, name.endsWith(".java"));
                assertFalse("Build output leaked into template: " + name,
                        name.contains("/.gradle/") || name.contains("/build/") || name.equals("local.properties"));
            }

            String rootBuild = read(zip, "build.gradle");
            String appBuild = read(zip, "app/build.gradle");
            String manifest = read(zip, "app/src/main/AndroidManifest.xml");
            String mainActivity = read(zip, "app/src/main/java/$package_name$/MainActivity.kt");
            assertTrue(rootBuild.contains("org.jetbrains.kotlin.plugin.compose"));
            assertTrue(rootBuild.contains("version '2.1.21'"));
            assertTrue(appBuild.contains("compose true"));
            assertTrue(appBuild.contains("compose-bom:2024.09.03"));
            assertTrue(appBuild.contains("kotlin-stdlib:2.1.21"));
            assertFalse("Modern source manifest must not persist a package attribute",
                    manifest.matches("(?s).*<manifest[^>]*\\spackage\\s*=.*"));
            assertTrue(mainActivity.contains("setContent"));
            assertFalse(mainActivity.contains("setContentView"));
        }
    }

    @Test
    public void webTemplateKeepsMainAsBootstrapAndSplitsResponsibilities() throws Exception {
        try (ZipFile zip = openAsset("theejs_template/theejs.zip")) {
            requireEntry(zip, "js/config.js");
            requireEntry(zip, "js/core/createThreeApp.js");
            requireEntry(zip, "js/core/createScene.js");
            requireEntry(zip, "js/entities/createCube.js");
            requireEntry(zip, "js/systems/animationSystem.js");
            requireEntry(zip, "js/systems/resizeSystem.js");

            String index = read(zip, "index.html");
            String main = read(zip, "js/main.js");
            assertTrue(index.contains("https://cdn.jsdelivr.net/npm/three@0.180.0/build/three.module.js"));
            assertTrue(main.lines().count() <= 10);
            assertTrue(main.contains("createThreeApp"));
            assertFalse(main.contains("requestAnimationFrame"));
            assertFalse(main.contains("PerspectiveCamera"));
            assertFalse(main.contains("import * as THREE"));
        }
    }

    private static ZipFile openAsset(String relativePath) throws IOException {
        File asset = new File("src/main/assets", relativePath);
        if (!asset.exists()) {
            asset = new File("app/src/main/assets", relativePath);
        }
        assertTrue("Template asset not found: " + asset.getAbsolutePath(), asset.isFile());
        return new ZipFile(asset);
    }

    private static void requireEntry(ZipFile zip, String name) {
        assertNotNull("Missing ZIP entry: " + name, zip.getEntry(name));
    }

    private static String read(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        assertNotNull("Missing ZIP entry: " + name, entry);
        try (InputStream input = zip.getInputStream(entry)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
