package com.saaspaymentsolutions.axion.dependencies;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class JavaCompilationEngineTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void compilesJava8Source() throws Exception {
        File source = source("src/demo/Hello.java",
                "package demo; public class Hello { public String value(){ return \"ok\"; } }");
        File output = temporaryFolder.newFolder("classes8");

        JavaCompilationEngine.Result result = JavaCompilationEngine.compile(
                Collections.singletonList(source), output, "", "8");

        assertTrue(result.diagnostics, result.success);
        assertEquals("ECJ 3.44.0 Android", result.compiler);
        assertTrue(new File(output, "demo/Hello.class").isFile());
    }

    @Test
    public void compilesJava17Record() throws Exception {
        File source = source("src17/demo/Point.java",
                "package demo; public record Point(int x, int y) {}");
        File output = temporaryFolder.newFolder("classes17");

        JavaCompilationEngine.Result result = JavaCompilationEngine.compile(
                Collections.singletonList(source), output, "", "17");

        assertTrue(result.diagnostics, result.success);
        assertTrue(new File(output, "demo/Point.class").isFile());
    }

    @Test
    public void returnsDiagnosticsForInvalidSource() throws Exception {
        File source = source("broken/demo/Broken.java",
                "package demo; public class Broken { public void run( { } }");
        File output = temporaryFolder.newFolder("broken-classes");

        JavaCompilationEngine.Result result = JavaCompilationEngine.compile(
                Collections.singletonList(source), output, "", "8");

        assertFalse(result.success);
        assertTrue(result.diagnostics.contains("Broken.java"));
    }

    @Test
    public void bundledEcjParserHasNoJvmOnlyRuntimeVersionReference() throws Exception {
        try (InputStream input = JavaCompilationEngineTest.class.getResourceAsStream(
                "/org/eclipse/jdt/internal/compiler/parser/Parser.class")) {
            assertTrue("Parser.class ausente do ECJ", input != null);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            String constants = new String(output.toByteArray(), StandardCharsets.ISO_8859_1);
            assertFalse(constants.contains("java/lang/Runtime$Version"));
        }
    }

    @Test
    public void detectsConfiguredJavaVersions() throws Exception {
        File root8 = temporaryFolder.newFolder("project8");
        write(new File(root8, "app/build.gradle"),
                "sourceCompatibility JavaVersion.VERSION_1_8");
        assertEquals("8", JavaCompilationEngine.detectLanguageLevel(root8));

        File root11 = temporaryFolder.newFolder("project11");
        write(new File(root11, "build.gradle.kts"),
                "sourceCompatibility = JavaVersion.VERSION_11");
        assertEquals("11", JavaCompilationEngine.detectLanguageLevel(root11));

        File root17 = temporaryFolder.newFolder("project17");
        write(new File(root17, "app/build.gradle"), "jvmTarget = \"17\"");
        assertEquals("17", JavaCompilationEngine.detectLanguageLevel(root17));
    }

    private File source(String relativePath, String content) throws Exception {
        File file = new File(temporaryFolder.getRoot(), relativePath);
        write(file, content);
        return file;
    }

    private static void write(File file, String content) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Falha ao criar " + parent);
        }
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }
}
