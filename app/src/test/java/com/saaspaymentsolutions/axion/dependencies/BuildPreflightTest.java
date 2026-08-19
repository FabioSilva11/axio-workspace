package com.saaspaymentsolutions.axion.dependencies;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;

import static org.junit.Assert.*;

public class BuildPreflightTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private File createValidProjectSkeleton(File root) throws IOException {
        new File(root, "app/src/main/java/com/exemplo").mkdirs();
        File manifest = new File(root, "app/src/main/AndroidManifest.xml");
        if (manifest.getParentFile() != null) manifest.getParentFile().mkdirs();
        try (FileWriter w = new FileWriter(manifest)) {
            w.write("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                    "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
                    "    package=\"com.exemplo\">\n" +
                    "    <application>\n" +
                    "        <activity android:name=\".MainActivity\">\n" +
                    "            <intent-filter>\n" +
                    "                <action android:name=\"android.intent.action.MAIN\"/>\n" +
                    "                <category android:name=\"android.intent.category.LAUNCHER\"/>\n" +
                    "            </intent-filter>\n" +
                    "        </activity>\n" +
                    "    </application>\n" +
                    "</manifest>\n");
        }
        File java = new File(root, "app/src/main/java/com/exemplo/MainActivity.java");
        try (FileWriter w = new FileWriter(java)) {
            w.write("package com.exemplo;\npublic class MainActivity {}\n");
        }
        return root;
    }

    @Test
    public void testCheckFailsWhenProjectDirMissing() {
        BuildPreflightResult result = BuildPreflight.check(
                new File("non_existent_dir_9999_xyz"), null);
        assertFalse(result.ready);
        assertTrue(result.diagnostics.stream().anyMatch(d -> d.code.equals("PF001")));
    }

    @Test
    public void testCheckValidatesMissingDependencies() throws IOException {
        File projectRoot = createValidProjectSkeleton(tempFolder.newFolder("proj_ok"));

        DependencyItem missingItem = new DependencyItem();
        missingItem.groupId = "com.missing";
        missingItem.artifactId = "missing";
        missingItem.version = "1.0.0";
        missingItem.status = DependencyStatus.MISSING;
        missingItem.configuration = DependencyConfiguration.IMPLEMENTATION;

        ArrayList<DependencyItem> items = new ArrayList<>();
        items.add(missingItem);

        DependencyResolutionResult resResult = new DependencyResolutionResult(
                items, new DependencyBuildClasspath(), new ArrayList<>(), false);

        BuildPreflightResult result = BuildPreflight.check(projectRoot, resResult);
        assertFalse(result.ready);
        assertTrue(result.hasMissingDependencies());
        assertTrue(result.diagnostics.stream().anyMatch(d -> d.code.equals("PF006")));
    }

    @Test
    public void testCheckPF008DetectsMissingLauncher() throws IOException {
        File projectRoot = tempFolder.newFolder("proj_nolauncher");
        new File(projectRoot, "app/src/main/java/com/exemplo").mkdirs();
        File manifest = new File(projectRoot, "app/src/main/AndroidManifest.xml");
        manifest.getParentFile().mkdirs();
        try (FileWriter w = new FileWriter(manifest)) {
            w.write("<?xml version=\"1.0\"?>\n" +
                    "<manifest package=\"com.exemplo\">\n" +
                    "  <application>\n" +
                    "    <activity android:name=\".MainActivity\"/>\n" +
                    "  </application>\n" +
                    "</manifest>\n");
        }
        File java = new File(projectRoot, "app/src/main/java/com/exemplo/MainActivity.java");
        try (FileWriter w = new FileWriter(java)) {
            w.write("package com.exemplo;\npublic class MainActivity {}\n");
        }

        BuildPreflightResult result = BuildPreflight.check(projectRoot, null);
        assertFalse(result.ready);
        assertTrue(result.diagnostics.stream().anyMatch(d -> d.code.equals("PF008")));
    }

    @Test
    public void testCheckPF008PassesWithValidLauncher() throws IOException {
        File projectRoot = createValidProjectSkeleton(tempFolder.newFolder("proj_launcher_ok"));

        DependencyResolutionResult emptyRes = new DependencyResolutionResult(
                new ArrayList<>(), new DependencyBuildClasspath(), new ArrayList<>(), true);

        BuildPreflightResult result = BuildPreflight.check(projectRoot, emptyRes);
        // PF001-PF008 passam. Se tem espaço >= 150MB, ready deve ser true.
        assertTrue(result.ready || result.diagnostics.stream()
                .noneMatch(d -> d.code.equals("PF008")));
    }
}
