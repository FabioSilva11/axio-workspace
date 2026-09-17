package com.saaspaymentsolutions.axion;

import android.widget.Toast;

import com.saaspaymentsolutions.axion.SketchApplication;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class FileUtil {

    public static void writeFile(String path, String content) {
        try {
            File file = new File(path);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            PrintWriter writer = new PrintWriter(new FileWriter(file));
            writer.print(content);
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static String readFile(String path) {
        StringBuilder content = new StringBuilder();
        File file = new File(path);
        if (!file.exists()) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return content.toString();
    }

    public static void deleteFile(String path) {
        File file = new File(path);
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteFile(child.getAbsolutePath());
                }
            }
        }
        file.delete();
    }

    public static void extractZipTo(ZipInputStream zipInputStream, String destPath) {
        try {
            ZipEntry entry;
            byte[] buffer = new byte[4096];
            while ((entry = zipInputStream.getNextEntry()) != null) {
                File file = new File(destPath, entry.getName());
                if (entry.isDirectory()) {
                    file.mkdirs();
                } else {
                    File parent = file.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    FileOutputStream fos = new FileOutputStream(file);
                    int len;
                    while ((len = zipInputStream.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                    fos.close();
                }
                zipInputStream.closeEntry();
            }
            zipInputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void copyDirectory(File src, File dest) throws IOException {
        if (src.isDirectory()) {
            if (!dest.exists()) dest.mkdirs();
            String[] children = src.list();
            if (children != null) {
                for (String child : children) {
                    copyDirectory(new File(src, child), new File(dest, child));
                }
            }
        } else {
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            java.io.FileInputStream fis = new java.io.FileInputStream(src);
            FileOutputStream fos = new FileOutputStream(dest);
            byte[] buffer = new byte[4096];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
            fis.close();
            fos.close();
        }
    }

    public static void copyFile(String sourcePath, String destPath) throws IOException {
        File src = new File(sourcePath);
        File dest = new File(destPath);
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (java.io.FileInputStream fis = new java.io.FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dest)) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
        }
    }

    public static boolean isExistFile(String path) {
        return new File(path).exists();
    }
}


