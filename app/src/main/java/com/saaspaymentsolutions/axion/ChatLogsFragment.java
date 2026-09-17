package com.saaspaymentsolutions.axion;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.List;

public class ChatLogsFragment extends Fragment {
    private static final String ARG_SC_ID = "sc_id";

    private String scId;
    private TextView textLogs;
    private ScrollView scrollLogs;
    private LinearLayout progressContainer;
    private TextView textProgressStatus;
    private ProgressBar progressBuild;

    public static ChatLogsFragment newInstance(String scId) {
        ChatLogsFragment fragment = new ChatLogsFragment();
        Bundle args = new Bundle();
        args.putString(ARG_SC_ID, scId);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        scId = getArguments() == null ? "" : getArguments().getString(ARG_SC_ID, "");
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_chat_logs, container, false);
        textLogs = view.findViewById(R.id.text_compiler_logs);
        scrollLogs = view.findViewById(R.id.scroll_compiler_logs);
        progressContainer = view.findViewById(R.id.progress_container);
        textProgressStatus = view.findViewById(R.id.text_progress_status);
        progressBuild = view.findViewById(R.id.progress_build);
        if (progressContainer != null) progressContainer.setVisibility(View.GONE);
        if (textLogs != null) textLogs.setText("Workspace activity logs will appear here.");
        return view;
    }

    public void appendLog(String line) {
        if (textLogs == null) return;
        textLogs.append("\n" + line);
        if (scrollLogs != null) scrollLogs.post(() -> scrollLogs.fullScroll(View.FOCUS_DOWN));
    }

    public void renderLines(List<String> lines) {
        if (textLogs == null) return;
        textLogs.setText(android.text.TextUtils.join("\n", lines));
        if (scrollLogs != null) scrollLogs.post(() -> scrollLogs.fullScroll(View.FOCUS_DOWN));
    }
}
