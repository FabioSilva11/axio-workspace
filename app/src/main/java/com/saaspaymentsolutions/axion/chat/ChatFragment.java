package com.saaspaymentsolutions.axion.chat;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.DiffUtil;

import com.saaspaymentsolutions.axion.chat.ChatProjectsAdapter;
import com.saaspaymentsolutions.axion.ProjectComparator;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.transition.MaterialFadeThrough;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.saaspaymentsolutions.axion.FragmentCallback;
import com.saaspaymentsolutions.axion.PrefsManager;
import com.saaspaymentsolutions.axion.ProjectManager;
import dev.chrisbanes.insetter.Insetter;
import com.saaspaymentsolutions.axion.R;
import com.saaspaymentsolutions.axion.ChatActivity;
import com.saaspaymentsolutions.axion.databinding.MyprojectsBinding;
import com.saaspaymentsolutions.axion.UI;

public class ChatFragment extends FragmentCallback {
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final List<HashMap<String, Object>> projectsList = new ArrayList<>();
    private MyprojectsBinding binding;
    private ChatProjectsAdapter projectsAdapter;
    private PrefsManager preference;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setEnterTransition(new MaterialFadeThrough());
        setReturnTransition(new MaterialFadeThrough());
        setExitTransition(new MaterialFadeThrough());
        setReenterTransition(new MaterialFadeThrough());
    }

    @Override
    public void b(int requestCode) {
    }

    public void toChatActivity(String sc_id) {
        Intent intent = new Intent(requireContext(), ChatActivity.class);
        intent.putExtra("sc_id", sc_id);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        requireActivity().startActivity(intent);
    }

    @Override
    public void c(int requestCode) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
        startActivity(intent);
    }

    @Override
    public void d() {
    }

    @Override
    public void e() {
    }


    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup parent, Bundle savedInstanceState) {
        binding = MyprojectsBinding.inflate(inflater, parent, false);
        return binding.getRoot();
    }

    @Override
    public void onDestroyView() {
        executorService.shutdown();
        super.onDestroyView();
        binding = null;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        preference = new PrefsManager(requireContext(), "project");

        ExtendedFloatingActionButton fab = requireActivity().findViewById(R.id.create_new_project);
        fab.hide(); // Esconder o FAB na aba de chat

        binding.swipeRefresh.setOnRefreshListener(this::refreshProjectsList);

        // Criar adapter que abre ChatActivity ao invés de DesignActivity
        projectsAdapter = new ChatProjectsAdapter(this, projectsList);
        binding.myprojects.setAdapter(projectsAdapter);
        binding.myprojects.setHasFixedSize(true);

        binding.myprojects.post(this::refreshProjectsList); // wait for RecyclerView to be ready
        UI.addSystemWindowInsetToPadding(binding.loadingContainer, true, false, true, true);
        UI.addSystemWindowInsetToPadding(binding.myprojects, true, false, true, true);

        // Esconder elementos desnecessários
        binding.iconSort.setVisibility(View.GONE);
        binding.specialActionContainer.setVisibility(View.GONE);
        binding.titleContainer.setVisibility(View.GONE);
    }

    public void scrollToBottom() {
        if (binding != null && binding.myprojects.getAdapter() != null) {
            int lastPosition = binding.myprojects.getAdapter().getItemCount() - 1;
            if (lastPosition >= 0) {
                binding.myprojects.scrollToPosition(lastPosition);
            }
        }
    }


    public void refreshProjectsList() {
        // Check if the fragment is still attached to the activity
        if (!isAdded()) return;

        executorService.execute(() -> {
            List<HashMap<String, Object>> loadedProjects = ProjectManager.a();
            loadedProjects.sort(new ProjectComparator(preference.d("sortBy"), preference.getString("pinnedProject", "-1")));
            java.util.Collections.reverse(loadedProjects);

            DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new ProjectDiffCallback(projectsList, loadedProjects));

            requireActivity().runOnUiThread(() -> {
                if (binding.swipeRefresh.isRefreshing()) binding.swipeRefresh.setRefreshing(false);
                if (binding.loadingContainer.getVisibility() == View.VISIBLE) {
                    binding.loadingContainer.setVisibility(View.GONE);
                    binding.myprojects.setVisibility(View.VISIBLE);
                }
                projectsList.clear();
                projectsList.addAll(loadedProjects);
                projectsAdapter.setAllProjects(loadedProjects);
                diffResult.dispatchUpdatesTo(projectsAdapter);
                scrollToBottom();
            });
        });
    }


    private static class ProjectDiffCallback extends DiffUtil.Callback {
        private final List<HashMap<String, Object>> oldList;
        private final List<HashMap<String, Object>> newList;

        public ProjectDiffCallback(List<HashMap<String, Object>> oldList, List<HashMap<String, Object>> newList) {
            this.oldList = oldList;
            this.newList = newList;
        }

        @Override
        public int getOldListSize() {
            return oldList.size();
        }

        @Override
        public int getNewListSize() {
            return newList.size();
        }

        @Override
        public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
            String oldId = (String) oldList.get(oldItemPosition).get("sc_id");
            String newId = (String) newList.get(newItemPosition).get("sc_id");
            return oldId.equals(newId);
        }

        @Override
        public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
            HashMap<String, Object> oldItem = oldList.get(oldItemPosition);
            HashMap<String, Object> newItem = newList.get(newItemPosition);
            return oldItem.equals(newItem);
        }
    }
}





