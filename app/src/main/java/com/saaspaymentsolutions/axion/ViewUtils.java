package com.saaspaymentsolutions.axion;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

public class ViewUtils {
    public static View a(Context context, int layoutRes) {
        LayoutInflater inflater = LayoutInflater.from(context);
        return inflater.inflate(layoutRes, null, false);
    }

    public static void a(Context context, ViewGroup parent, int layoutRes) {
        LayoutInflater inflater = LayoutInflater.from(context);
        inflater.inflate(layoutRes, parent, true);
    }

    public static View a(Context context, ViewGroup parent, int layoutRes, boolean attachToRoot) {
        LayoutInflater inflater = LayoutInflater.from(context);
        return inflater.inflate(layoutRes, parent, attachToRoot);
    }
}

