package com.saaspaymentsolutions.axion;

import android.content.Context;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

public abstract class AsyncTaskBase extends AsyncTask<Void, String, Void> {
    private static final List<AsyncTaskBase> tasks = new ArrayList<>();
    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    protected AsyncTaskBase(Context context) {
        this.context = context;
    }

    public static void addTask(AsyncTaskBase task) {
        tasks.add(task);
    }

    public void k() {
        execute();
    }

    public void h() {
        // Dismiss progress dialog if any
    }

    protected Context getContext() {
        return context;
    }

    @Override
    protected Void doInBackground(Void... voids) {
        try {
            b();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    protected void onPostExecute(Void result) {
        super.onPostExecute(result);
        a();
    }

    @Override
    protected void onProgressUpdate(String... values) {
        super.onProgressUpdate(values);
        if (values != null && values.length > 0) {
            a(values[0]);
        }
    }

    public abstract void a();
    public abstract void b();
    public abstract void a(String str);
}


