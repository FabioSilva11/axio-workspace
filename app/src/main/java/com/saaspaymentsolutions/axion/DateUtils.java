package com.saaspaymentsolutions.axion;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class DateUtils {
    public String a(String pattern) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat(pattern, Locale.US);
            return sdf.format(new Date());
        } catch (Exception e) {
            return "";
        }
    }
}

