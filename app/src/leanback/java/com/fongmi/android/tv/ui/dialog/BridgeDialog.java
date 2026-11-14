package com.fongmi.android.tv.ui.dialog;

import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogTmdbSiteBinding;
import com.fongmi.android.tv.impl.TmdbListener;
import com.fongmi.android.tv.setting.TmdbSetting;
import com.fongmi.android.tv.utils.ArtCooldown;
import com.fongmi.android.tv.utils.DoubanBridge;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 编辑豆瓣桥：地址 + 密钥。
 *
 * <p>豆瓣桥是海报 / 评分 / 简介的豆瓣数据来源，地址留空表示停用；
 * 密钥由桥服务方签发，可在设置页随时替换。</p>
 */
public class BridgeDialog extends BaseAlertDialog {

    private DialogTmdbSiteBinding binding;

    public static void show(FragmentActivity activity) {
        new BridgeDialog().show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogTmdbSiteBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setTitle(R.string.tmdb_bridge).setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        binding.url.setHint(R.string.tmdb_bridge_url_hint);
        binding.key.setHint(R.string.tmdb_bridge_key_hint);
        // 豆瓣桥没有「多条记录」与「远程输入」，这两个按钮不出现。
        binding.delete.setVisibility(View.GONE);
        binding.phone.setVisibility(View.GONE);
        binding.url.setText(TmdbSetting.getBridgeUrl());
        binding.key.setText(TmdbSetting.getBridgeKey());
        binding.key.setSelection(binding.key.getText().length());
    }

    @Override
    protected void initEvent() {
        binding.positive.setOnClickListener(this::onSave);
        binding.negative.setOnClickListener(view -> dismiss());
    }

    private void onSave(View view) {
        String url = binding.url.getText().toString().trim();
        String key = binding.key.getText().toString().trim();
        if (!TextUtils.isEmpty(url) && !url.startsWith("http://") && !url.startsWith("https://")) {
            toast(R.string.tmdb_bridge_invalid);
            return;
        }
        if (!TextUtils.isEmpty(url) && TextUtils.isEmpty(key)) {
            toast(R.string.tmdb_bridge_key_required);
            return;
        }
        TmdbSetting.putBridge(url, key);
        // 地址或密钥变了：丢掉榜单缓存与失败冷却，让新配置立刻生效。
        DoubanBridge.reset();
        ArtCooldown.clear();
        if (requireActivity() instanceof TmdbListener) {
            ((TmdbListener) requireActivity()).setBridge();
        }
        dismiss();
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
