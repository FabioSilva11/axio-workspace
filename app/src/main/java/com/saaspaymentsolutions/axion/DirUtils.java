package com.saaspaymentsolutions.axion;

import java.io.File;

public class DirUtils {
    public void f(String path) {
        File dir = new File(path);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    public void b(String path) {
        File dir = new File(path);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }
}

