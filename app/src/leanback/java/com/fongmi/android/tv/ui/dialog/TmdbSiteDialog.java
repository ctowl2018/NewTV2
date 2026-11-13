package com.fongmi.android.tv.ui.dialog;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogTmdbSiteBinding;
import com.fongmi.android.tv.impl.TmdbListener;
import com.fongmi.android.tv.setting.TmdbSetting;
import com.fongmi.android.tv.setting.TmdbSite;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/** 编辑单个 TMDB 站点（地址 + 该站点专属密钥），同时负责新增与删除。 */
public class TmdbSiteDialog extends BaseAlertDialog {

    private static final String ARG_INDEX = "site_index";
    private static final int ADD = -1;

    private DialogTmdbSiteBinding binding;
    private int index = ADD;

    public static void show(FragmentActivity activity, int index) {
        Bundle args = new Bundle();
        args.putInt(ARG_INDEX, index);
        TmdbSiteDialog dialog = new TmdbSiteDialog();
        dialog.setArguments(args);
        dialog.show(activity.getSupportFragmentManager(), null);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) index = getArguments().getInt(ARG_INDEX, ADD);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogTmdbSiteBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        String title = index == ADD ? getString(R.string.tmdb_add_site) : getString(R.string.tmdb_site_index, index + 1);
        return builder().setTitle(title).setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        List<TmdbSite> sites = TmdbSetting.getSites();
        if (index >= 0 && index < sites.size()) {
            TmdbSite site = sites.get(index);
            binding.url.setText(site.getUrl());
            binding.key.setText(site.getKey());
            binding.key.setSelection(site.getKey().length());
            // 至少留一条站点，删除按钮在这种情况下不出现。
            binding.delete.setVisibility(sites.size() > 1 ? View.VISIBLE : View.GONE);
        } else {
            binding.delete.setVisibility(View.GONE);
        }
    }

    @Override
    protected void initEvent() {
        binding.positive.setOnClickListener(this::onSave);
        binding.negative.setOnClickListener(view -> dismiss());
        binding.delete.setOnClickListener(this::onDelete);
    }

    private void onSave(View view) {
        TmdbSite site = new TmdbSite(binding.url.getText().toString(), binding.key.getText().toString());
        if (!site.isValid()) {
            toast(R.string.tmdb_site_invalid);
            return;
        }
        if (TextUtils.isEmpty(site.getKey())) {
            toast(R.string.tmdb_site_key_required);
            return;
        }

        List<TmdbSite> sites = new ArrayList<>(TmdbSetting.getSites());
        int duplicate = indexOf(sites, site.base());
        if (index >= 0 && index < sites.size()) {
            if (duplicate >= 0 && duplicate != index) {
                // 地址改成了另一条已有的站点：覆盖那一条并删掉当前这条，避免出现两个同址站点。
                sites.set(duplicate, site);
                sites.remove(index);
            } else {
                sites.set(index, site);
            }
        } else if (duplicate >= 0) {
            sites.set(duplicate, site);
        } else {
            if (sites.size() >= TmdbSetting.MAX_SITES) {
                toast(R.string.tmdb_site_max, TmdbSetting.MAX_SITES);
                return;
            }
            sites.add(site);
        }
        apply(sites);
        dismiss();
    }

    private void onDelete(View view) {
        List<TmdbSite> sites = new ArrayList<>(TmdbSetting.getSites());
        if (index < 0 || index >= sites.size()) {
            dismiss();
            return;
        }
        if (sites.size() <= 1) {
            toast(R.string.tmdb_site_min);
            return;
        }
        sites.remove(index);
        apply(sites);
        dismiss();
    }

    private void apply(List<TmdbSite> sites) {
        if (requireActivity() instanceof TmdbListener) {
            ((TmdbListener) requireActivity()).setTmdbSites(sites);
        }
    }

    private int indexOf(List<TmdbSite> sites, String base) {
        for (int i = 0; i < sites.size(); i++) if (TextUtils.equals(sites.get(i).base(), base)) return i;
        return -1;
    }

    private void toast(int resId, Object... args) {
        Toast.makeText(requireContext(), getString(resId, args), Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.6f);
    }
}
