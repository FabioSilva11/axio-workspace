package com.saaspaymentsolutions.axion;

import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;

import com.saaspaymentsolutions.axion.R;

public class ChatPagerAdapter extends FragmentStatePagerAdapter {
    private final ChatActivity activity;
    private final ChatMessagesFragment messagesFragment;
    private final ChatDiffFragment diffFragment;
    private final ChatArtifactsFragment artifactsFragment;
    private final ChatPlanFragment planFragment;
    private final ChatLogsFragment logsFragment;
    private final boolean webProject;

    public ChatPagerAdapter(@NonNull ChatActivity activity,
                            @NonNull ChatMessagesFragment messagesFragment,
                            @NonNull ChatDiffFragment diffFragment,
                            @NonNull ChatArtifactsFragment artifactsFragment,
                            @NonNull ChatPlanFragment planFragment,
                            @NonNull ChatLogsFragment logsFragment) {
        super(activity.getSupportFragmentManager(), FragmentStatePagerAdapter.BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT);
        this.activity = activity;
        this.messagesFragment = messagesFragment;
        this.diffFragment = diffFragment;
        this.artifactsFragment = artifactsFragment;
        this.planFragment = planFragment;
        this.logsFragment = logsFragment;
        this.webProject = false;
    }

    @NonNull
    @Override
    public Fragment getItem(int position) {
        return switch (position) {
            case 1 -> diffFragment;
            case 2 -> artifactsFragment;
            case 3 -> planFragment;
            case 4 -> logsFragment;
            default -> messagesFragment;
        };
    }

    @NonNull
    @Override
    public Object instantiateItem(@NonNull ViewGroup container, int position) {
        Object item = super.instantiateItem(container, position);
        if (item instanceof Fragment) {
            activity.onChatPageInstantiated(position, (Fragment) item);
        }
        return item;
    }

    @Override
    public int getCount() {
        return 5;
    }

    @Nullable
    @Override
    public CharSequence getPageTitle(int position) {
        return switch (position) {
            case 1 -> activity.getString(R.string.chat_page_diffs);
            case 2 -> activity.getString(R.string.chat_page_artifacts);
            case 3 -> activity.getString(R.string.chat_page_plan);
            case 4 -> activity.getString(R.string.chat_page_logs);
            default -> activity.getString(R.string.chat_page_chat);
        };
    }
}
