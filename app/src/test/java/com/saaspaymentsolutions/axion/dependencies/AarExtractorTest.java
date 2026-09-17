package com.saaspaymentsolutions.axion.dependencies;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class AarExtractorTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private AarExtractor extractor;

    @Before
    public void setUp() {
        extractor = new AarExtractor();
    }

    @Test
    public void testExtractionOfValidAar() throws IOException {
        File aarFile = tempFolder.newFile("sample.aar");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(aarFile))) {
            // Add classes.jar
            zos.putNextEntry(new ZipEntry("classes.jar"));
            zos.write("dummy jar content".getBytes());
            zos.closeEntry();

            // Add AndroidManifest.xml
            zos.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            zos.write("<manifest/>".getBytes());
            zos.closeEntry();
        }

        File outputDir = tempFolder.newFolder("extracted");
        boolean success = extractor.extract(aarFile, outputDir);

        assertTrue(success);
        assertTrue(new File(outputDir, "classes.jar").exists());
        assertTrue(new File(outputDir, "AndroidManifest.xml").exists());
    }

    @Test
    public void testZipSlipPrevention() throws IOException {
        File maliciousAar = tempFolder.newFile("malicious.aar");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(maliciousAar))) {
            // Attempt Zip Slip path traversal
            zos.putNextEntry(new ZipEntry("../evil.txt"));
            zos.write("hacked".getBytes());
            zos.closeEntry();
        }

        File outputDir = tempFolder.newFolder("extracted_safe");
        extractor.extract(maliciousAar, outputDir);

        File evilFile = new File(outputDir.getParentFile(), "evil.txt");
        assertFalse(evilFile.exists());
    }
}
