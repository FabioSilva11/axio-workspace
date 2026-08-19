package com.saaspaymentsolutions.axion.dependencies;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ZipAlignerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testAlignsMisalignedStoredEntry() throws Exception {
        File misaligned = tempFolder.newFile("misaligned.apk");
        // Entrada STORED com nome de 3 bytes: local header = 33 bytes,
        // offset dos dados = 33 → desalinhado (33 % 4 != 0).
        byte[] content = "hello-world".getBytes(StandardCharsets.UTF_8);
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(misaligned))) {
            ZipEntry entry = new ZipEntry("abc");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(content.length);
            entry.setCompressedSize(content.length);
            CRC32 crc = new CRC32();
            crc.update(content);
            entry.setCrc(crc.getValue());
            zos.putNextEntry(entry);
            zos.write(content);
            zos.closeEntry();
        }

        assertFalse(ZipAligner.isAligned(misaligned, ZipAligner.PAGE_ALIGNMENT));

        File aligned = new File(tempFolder.getRoot(), "aligned.apk");
        ZipAligner.align(misaligned, aligned);
        assertTrue(ZipAligner.isAligned(aligned, ZipAligner.PAGE_ALIGNMENT));

        try (ZipFile zip = new ZipFile(aligned)) {
            assertEquals(1, zip.size());
            assertTrue(zip.getEntry("abc") != null);
            assertEquals(content.length, zip.getEntry("abc").getSize());
        }
    }

    @Test
    public void testPreservesDeflatedAndStoredEntries() throws Exception {
        File source = tempFolder.newFile("source.apk");
        byte[] stored = "stored-data".getBytes(StandardCharsets.UTF_8);
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(source))) {
            ZipEntry storedEntry = new ZipEntry("resources.arsc");
            storedEntry.setMethod(ZipEntry.STORED);
            storedEntry.setSize(stored.length);
            storedEntry.setCompressedSize(stored.length);
            CRC32 crc = new CRC32();
            crc.update(stored);
            storedEntry.setCrc(crc.getValue());
            zos.putNextEntry(storedEntry);
            zos.write(stored);
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("classes.dex"));
            zos.write("dex-bytes-here".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        File aligned = new File(tempFolder.getRoot(), "out.apk");
        ZipAligner.align(source, aligned);
        assertTrue(ZipAligner.isAligned(aligned, ZipAligner.PAGE_ALIGNMENT));

        try (ZipFile zip = new ZipFile(aligned)) {
            assertEquals(2, zip.size());
            assertEquals(ZipEntry.STORED, zip.getEntry("resources.arsc").getMethod());
            assertEquals(ZipEntry.DEFLATED, zip.getEntry("classes.dex").getMethod());
            byte[] read = zip.getInputStream(zip.getEntry("classes.dex")).readAllBytes();
            assertEquals("dex-bytes-here", new String(read, StandardCharsets.UTF_8));
        }
    }

    @Test
    public void testAlignedZipIsDetectedAsAligned() throws Exception {
        File source = tempFolder.newFile("aligned-source.apk");
        // ZipOutputStream escreve a primeira entrada no offset 0; um nome de
        // 2 bytes dá local header de 32 bytes — já alinhado em 4.
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(source))) {
            ZipEntry entry = new ZipEntry("ab");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(content.length);
            entry.setCompressedSize(content.length);
            CRC32 crc = new CRC32();
            crc.update(content);
            entry.setCrc(crc.getValue());
            zos.putNextEntry(entry);
            zos.write(content);
            zos.closeEntry();
        }
        assertTrue(ZipAligner.isAligned(source, ZipAligner.PAGE_ALIGNMENT));
    }
}
