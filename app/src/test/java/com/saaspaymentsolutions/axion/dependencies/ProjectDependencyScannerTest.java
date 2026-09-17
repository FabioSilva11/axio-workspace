package com.saaspaymentsolutions.axion.dependencies;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

import static org.junit.Assert.*;

public class ProjectDependencyScannerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private ProjectDependencyScanner scanner;

    @Before
    public void setUp() {
        scanner = new ProjectDependencyScanner();
    }

    @Test
    public void testFindGradleFiles() throws IOException {
        File projectRoot = tempFolder.newFolder("project");
        File appDir = new File(projectRoot, "app");
        appDir.mkdirs();

        File appBuildGradle = new File(appDir, "build.gradle");
        appBuildGradle.createNewFile();

        File rootBuildGradle = new File(projectRoot, "build.gradle");
        rootBuildGradle.createNewFile();

        List<File> gradleFiles = scanner.findGradleFiles(projectRoot);

        assertEquals(2, gradleFiles.size());
        assertTrue(gradleFiles.contains(appBuildGradle));
        assertTrue(gradleFiles.contains(rootBuildGradle));
    }

    @Test
    public void testFindLocalLibraries() throws IOException {
        File projectRoot = tempFolder.newFolder("project2");
        File libsDir = new File(projectRoot, "app/libs");
        libsDir.mkdirs();

        File jarFile = new File(libsDir, "library1.jar");
        try (FileWriter w = new FileWriter(jarFile)) { w.write("jar"); }

        File aarFile = new File(libsDir, "library2.aar");
        try (FileWriter w = new FileWriter(aarFile)) { w.write("aar"); }

        List<LocalDependency> localLibs = scanner.findLocalLibraries(projectRoot);

        assertEquals(2, localLibs.size());
        assertTrue(localLibs.stream().anyMatch(l -> l.type == DependencyType.LOCAL_JAR));
        assertTrue(localLibs.stream().anyMatch(l -> l.type == DependencyType.LOCAL_AAR));
    }

    @Test
    public void testScanReturnsDefaultRepositories() throws IOException {
        File projectRoot = tempFolder.newFolder("project3");
        DependencyScanResult scanResult = scanner.scan(projectRoot);

        assertNotNull(scanResult.repositories);
        assertFalse(scanResult.repositories.isEmpty());
        assertTrue(scanResult.repositories.stream()
                .anyMatch(r -> r.type == RepositoryDefinition.RepositoryType.MAVEN_CENTRAL));
    }

    @Test
    public void testScanDiscoversCustomHttpsMavenRepositories() throws IOException {
        File projectRoot = tempFolder.newFolder("project4");
        File appDir = new File(projectRoot, "app");
        assertTrue(appDir.mkdirs());

        File settingsGradle = new File(projectRoot, "settings.gradle");
        try (FileWriter writer = new FileWriter(settingsGradle)) {
            writer.write("dependencyResolutionManagement {\n" +
                    "  repositories {\n" +
                    "    maven { url = uri('https://packages.example.com/android/') }\n" +
                    "    maven { url 'http://insecure.example.com/maven' }\n" +
                    "  }\n" +
                    "}\n");
        }

        File appBuildGradle = new File(appDir, "build.gradle");
        try (FileWriter writer = new FileWriter(appBuildGradle)) {
            writer.write("repositories {\n" +
                    "  maven(\"https://repo.example.org/releases\")\n" +
                    "  maven { url = 'https://packages.example.com/android' }\n" +
                    "}\n");
        }

        DependencyScanResult scanResult = scanner.scan(projectRoot);

        assertTrue(scanResult.repositories.stream().anyMatch(repository ->
                repository.type == RepositoryDefinition.RepositoryType.CUSTOM_MAVEN
                        && "https://packages.example.com/android/".equals(repository.url)));
        assertTrue(scanResult.repositories.stream().anyMatch(repository ->
                repository.type == RepositoryDefinition.RepositoryType.CUSTOM_MAVEN
                        && "https://repo.example.org/releases/".equals(repository.url)));
        assertEquals(1, scanResult.repositories.stream().filter(repository ->
                "https://packages.example.com/android/".equals(repository.url)).count());
        assertFalse(scanResult.repositories.stream().anyMatch(repository ->
                repository.url != null && repository.url.startsWith("http://")));
    }
}
