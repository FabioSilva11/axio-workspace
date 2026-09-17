package com.saaspaymentsolutions.axion.dependencies;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class GradleDependencyParserTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private GradleDependencyParser parser;

    @Before
    public void setUp() {
        parser = new GradleDependencyParser();
    }

    @Test
    public void testParseStringDependencies() throws IOException {
        File buildGradle = tempFolder.newFile("build.gradle");
        try (FileWriter writer = new FileWriter(buildGradle)) {
            writer.write("dependencies {\n" +
                    "    implementation 'androidx.appcompat:appcompat:1.6.1'\n" +
                    "    api \"com.google.android.material:material:1.8.0\"\n" +
                    "    compileOnly 'javax.servlet:servlet-api:2.5'\n" +
                    "    implementation 'com.squareup.okhttp3:okhttp:4.12.0'\n" +
                    "}\n");
        }

        List<UnsupportedFeature> unsupported = new ArrayList<>();
        List<DeclaredDependency> deps = parser.parse(buildGradle, unsupported);

        // NÃO pula mais appcompat/material: TUDO deve ser detectado
        assertEquals(4, deps.size());
        assertEquals("androidx.appcompat", deps.get(0).groupId);
        assertEquals("appcompat", deps.get(0).artifactId);
        assertEquals("com.google.android.material", deps.get(1).groupId);
        assertEquals("material", deps.get(1).artifactId);
        assertEquals(DependencyConfiguration.COMPILE_ONLY, deps.get(2).configuration);
    }

    @Test
    public void testParseMapNotationGroovy() throws IOException {
        File buildGradle = tempFolder.newFile("build.gradle");
        try (FileWriter writer = new FileWriter(buildGradle)) {
            writer.write("dependencies {\n" +
                    "    implementation group: \"com.squareup.retrofit2\", name: \"retrofit\", version: \"2.11.0\"\n" +
                    "}\n");
        }

        List<UnsupportedFeature> unsupported = new ArrayList<>();
        List<DeclaredDependency> deps = parser.parse(buildGradle, unsupported);

        assertEquals(1, deps.size());
        assertEquals("com.squareup.retrofit2", deps.get(0).groupId);
        assertEquals("retrofit", deps.get(0).artifactId);
        assertEquals("2.11.0", deps.get(0).version);
    }

    @Test
    public void testParseMapNotationKts() throws IOException {
        File buildGradle = tempFolder.newFile("build.gradle.kts");
        try (FileWriter writer = new FileWriter(buildGradle)) {
            writer.write("dependencies {\n" +
                    "    implementation(group = \"com.squareup.okhttp3\", name = \"okhttp\", version = \"4.12.0\")\n" +
                    "}\n");
        }

        List<UnsupportedFeature> unsupported = new ArrayList<>();
        List<DeclaredDependency> deps = parser.parse(buildGradle, unsupported);

        assertEquals(1, deps.size());
        assertEquals("com.squareup.okhttp3", deps.get(0).groupId);
        assertTrue(unsupported.stream().anyMatch(u -> u.kind == UnsupportedFeature.FeatureKind.KOTLIN_DSL));
    }

    @Test
    public void testVariablesAndRangesAreNowAccepted() throws IOException {
        File buildGradle = tempFolder.newFile("build.gradle");
        try (FileWriter writer = new FileWriter(buildGradle)) {
            writer.write("dependencies {\n" +
                    "    implementation 'com.exemplo:biblioteca:$minhaVersao'\n" +
                    "    implementation 'com.exemplo:outra:1.0+'\n" +
                    "    implementation 'com.exemplo:ok:1.2.3'\n" +
                    "}\n");
        }

        List<UnsupportedFeature> unsupported = new ArrayList<>();
        List<DeclaredDependency> deps = parser.parse(buildGradle, unsupported);

        // Agora TUDO é detectado (nunca é descartado por variável/range)
        assertEquals(3, deps.size());
        assertEquals("com.exemplo", deps.get(0).groupId);
        assertEquals("biblioteca", deps.get(0).artifactId);
        assertEquals("$minhaVersao", deps.get(0).version);
        assertEquals("1.0+", deps.get(1).version);
    }

    @Test
    public void testParsesPlatformEnforcedPlatformAndVersionlessDependencies() throws IOException {
        File buildGradle = tempFolder.newFile("build.gradle");
        try (FileWriter writer = new FileWriter(buildGradle)) {
            writer.write("dependencies {\n" +
                    "    implementation platform('com.google.firebase:firebase-bom:33.0.0')\n" +
                    "    api(enforcedPlatform(\"androidx.compose:compose-bom:2024.09.03\"))\n" +
                    "    implementation 'com.google.firebase:firebase-auth'\n" +
                    "    implementation(\"androidx.compose.ui:ui\")\n" +
                    "}\n");
        }

        List<UnsupportedFeature> unsupported = new ArrayList<>();
        List<DeclaredDependency> deps = parser.parse(buildGradle, unsupported);

        assertEquals(4, deps.size());

        DeclaredDependency firebaseBom = deps.get(0);
        assertEquals("com.google.firebase", firebaseBom.groupId);
        assertEquals("firebase-bom", firebaseBom.artifactId);
        assertEquals("33.0.0", firebaseBom.version);
        assertEquals(DependencyConfiguration.IMPLEMENTATION, firebaseBom.configuration);
        assertTrue(firebaseBom.platform);

        DeclaredDependency composeBom = deps.get(1);
        assertEquals("androidx.compose", composeBom.groupId);
        assertEquals("compose-bom", composeBom.artifactId);
        assertEquals("2024.09.03", composeBom.version);
        assertEquals(DependencyConfiguration.API, composeBom.configuration);
        assertTrue(composeBom.platform);
        assertTrue(composeBom.enforcedPlatform);

        DeclaredDependency firebaseAuth = deps.get(2);
        assertEquals("firebase-auth", firebaseAuth.artifactId);
        assertEquals("", firebaseAuth.version);
        assertFalse(firebaseAuth.platform);
        assertFalse(firebaseAuth.enforcedPlatform);

        DeclaredDependency composeUi = deps.get(3);
        assertEquals("ui", composeUi.artifactId);
        assertEquals("", composeUi.version);
        assertFalse(composeUi.platform);
    }

    @Test
    public void testIgnoresTestDependencies() throws IOException {
        File buildGradle = tempFolder.newFile("build.gradle");
        try (FileWriter writer = new FileWriter(buildGradle)) {
            writer.write("dependencies {\n" +
                    "    implementation 'com.squareup.okhttp3:okhttp:4.12.0'\n" +
                    "    testImplementation 'junit:junit:4.13.2'\n" +
                    "    androidTestImplementation 'androidx.test.ext:junit:1.1.5'\n" +
                    "}\n");
        }

        List<UnsupportedFeature> unsupported = new ArrayList<>();
        List<DeclaredDependency> deps = parser.parse(buildGradle, unsupported);

        assertEquals(1, deps.size());
        assertEquals("com.squareup.okhttp3", deps.get(0).groupId);
    }

    @Test
    public void testDetectsKotlinDslWarning() throws IOException {
        File buildGradleKts = tempFolder.newFile("build.gradle.kts");
        try (FileWriter writer = new FileWriter(buildGradleKts)) {
            writer.write("dependencies {\n" +
                    "    implementation(\"com.squareup.okhttp3:okhttp:4.12.0\")\n" +
                    "}\n");
        }
        List<UnsupportedFeature> unsupported = new ArrayList<>();

        List<DeclaredDependency> deps = parser.parse(buildGradleKts, unsupported);

        assertEquals(1, deps.size());
        assertEquals("com.squareup.okhttp3", deps.get(0).groupId);
        assertEquals(1, unsupported.size());
        assertEquals(UnsupportedFeature.FeatureKind.KOTLIN_DSL, unsupported.get(0).kind);
    }

    @Test
    public void testDetectsKaptAndKsp() throws IOException {
        File buildGradle = tempFolder.newFile("build.gradle");
        try (FileWriter writer = new FileWriter(buildGradle)) {
            writer.write("dependencies {\n" +
                    "    kapt 'com.google.dagger:hilt-android-compiler:2.44'\n" +
                    "    ksp 'com.android.tools:ksp:1.0'\n" +
                    "}\n");
        }

        List<UnsupportedFeature> unsupported = new ArrayList<>();
        List<DeclaredDependency> deps = parser.parse(buildGradle, unsupported);

        // Agora são BAIXADOS (detectados) — 2 deps + 2 warnings
        assertEquals(2, deps.size());
        assertEquals(2, unsupported.size());
        assertTrue(unsupported.stream().anyMatch(u -> u.kind == UnsupportedFeature.FeatureKind.KAPT));
        assertTrue(unsupported.stream().anyMatch(u -> u.kind == UnsupportedFeature.FeatureKind.KSP));
    }

    @Test
    public void testRecognizesAllNewConfigurations() throws IOException {
        File buildGradle = tempFolder.newFile("build.gradle");
        try (FileWriter writer = new FileWriter(buildGradle)) {
            writer.write("dependencies {\n" +
                    "    debugImplementation 'com.squareup.leakcanary:leakcanary-android:2.13'\n" +
                    "    releaseImplementation 'com.squareup.okhttp3:okhttp:4.12.0'\n" +
                    "    coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs:2.1.4'\n" +
                    "    runtimeOnly 'com.exemplo:runtime-thing:1.0'\n" +
                    "}\n");
        }

        List<UnsupportedFeature> unsupported = new ArrayList<>();
        List<DeclaredDependency> deps = parser.parse(buildGradle, unsupported);

        assertEquals(4, deps.size());
    }

    @Test
    public void testPreservesVariantConfigurations() throws IOException {
        File buildGradle = tempFolder.newFile("build.gradle");
        try (FileWriter writer = new FileWriter(buildGradle)) {
            writer.write("dependencies {\n" +
                    "    debugImplementation 'com.squareup.leakcanary:leakcanary-android:2.13'\n" +
                    "    releaseImplementation 'com.squareup.okhttp3:okhttp:4.12.0'\n" +
                    "    coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs:2.1.4'\n" +
                    "    annotationProcessor 'com.example:processor:1.0'\n" +
                    "    kapt 'com.example:kapt-processor:1.0'\n" +
                    "    ksp 'com.example:ksp-processor:1.0'\n" +
                    "}\n");
        }

        List<UnsupportedFeature> unsupported = new ArrayList<>();
        List<DeclaredDependency> deps = parser.parse(buildGradle, unsupported);

        assertEquals(6, deps.size());
        // Nenhuma configuracao e convertida em api/compileOnly: cada uma preservada
        assertEquals(DependencyConfiguration.DEBUG_IMPLEMENTATION, deps.get(0).configuration);
        assertEquals(DependencyConfiguration.RELEASE_IMPLEMENTATION, deps.get(1).configuration);
        assertEquals(DependencyConfiguration.CORE_LIBRARY_DESUGARING, deps.get(2).configuration);
        assertEquals(DependencyConfiguration.ANNOTATION_PROCESSOR, deps.get(3).configuration);
        assertEquals(DependencyConfiguration.KAPT, deps.get(4).configuration);
        assertEquals(DependencyConfiguration.KSP, deps.get(5).configuration);
    }

    @Test
    public void testProjectDependencyIsBlocking() throws IOException {
        File buildGradle = tempFolder.newFile("build.gradle");
        try (FileWriter writer = new FileWriter(buildGradle)) {
            writer.write("dependencies {\n" +
                    "    implementation project(':libraryA')\n" +
                    "}\n");
        }

        List<UnsupportedFeature> unsupported = new ArrayList<>();
        List<DeclaredDependency> deps = parser.parse(buildGradle, unsupported);

        assertEquals(0, deps.size());
        assertEquals(1, unsupported.size());
        assertEquals(UnsupportedFeature.FeatureKind.MULTI_MODULE, unsupported.get(0).kind);
        assertTrue(unsupported.get(0).blocksCompilation);
    }

    @Test
    public void testVersionCatalogAliasResolution() throws IOException {
        File catalogFile = tempFolder.newFile("libs.versions.toml");
        try (FileWriter writer = new FileWriter(catalogFile)) {
            writer.write("[versions]\n" +
                    "retrofit = \"2.11.0\"\n" +
                    "\n" +
                    "[libraries]\n" +
                    "retrofit = { module = \"com.squareup.retrofit2:retrofit\", version.ref = \"retrofit\" }\n" +
                    "okhttp = { module = \"com.squareup.okhttp3:okhttp\", version = \"4.12.0\" }\n" +
                    "\n" +
                    "[bundles]\n" +
                    "network = [\"retrofit\", \"okhttp\"]\n");
        }
        VersionCatalog catalog = VersionCatalog.load(catalogFile);

        File buildGradle = tempFolder.newFile("build.gradle");
        try (FileWriter writer = new FileWriter(buildGradle)) {
            writer.write("dependencies {\n" +
                    "    implementation libs.retrofit\n" +
                    "    implementation(libs.bundles.network)\n" +
                    "}\n");
        }

        List<UnsupportedFeature> unsupported = new ArrayList<>();
        List<DeclaredDependency> deps = parser.parse(buildGradle, unsupported, catalog);

        // libs.retrofit + bundle (2 libs) = 3 declaracoes
        assertEquals(3, deps.size());
        assertEquals("com.squareup.retrofit2", deps.get(0).groupId);
        assertEquals("2.11.0", deps.get(0).version);
        assertEquals("com.squareup.okhttp3", deps.get(2).groupId);
    }

    @Test
    public void testRejectsPathTraversalOnly() throws IOException {
        File buildGradle = tempFolder.newFile("build.gradle");
        try (FileWriter writer = new FileWriter(buildGradle)) {
            writer.write("dependencies {\n" +
                    "    implementation 'com.foo:../evil:1.0'\n" +
                    "    implementation 'com.foo:nice-lib:1.0'\n" +
                    "}\n");
        }

        List<UnsupportedFeature> unsupported = new ArrayList<>();
        List<DeclaredDependency> deps = parser.parse(buildGradle, unsupported);

        // Apenas path traversal é barrado; variáveis/ranges/BOMs/built-ins TUDO passa
        assertEquals(1, deps.size());
        assertEquals("nice-lib", deps.get(0).artifactId);
    }
}
