package com.fongmi.android.tv.ui.sheet;

import android.content.Context;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.databinding.ViewSheetItemBinding;
import com.fongmi.android.tv.databinding.ViewSheetListBinding;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 浮层里的单列列表内容（选集 / 倍速共用）。
 *
 * <p><b>滚动交互与「弹幕搜索列表」一致</b>：普通 {@link RecyclerView} + {@link LinearLayoutManager}，
 * 高度由 {@link #setRows(int)} 通过 maxHeight 限制。因此
 * <ol>
 *   <li>焦点框在可视区内逐项移动；</li>
 *   <li>走到可视区上/下边缘后，列表按框架默认行为整列滚动，焦点框停在边缘那一行不动，
 *       到头即停、不回弹（{@code overScrollMode=never}，无 item 动画）；</li>
 *   <li>CH+ / CH-（PAGE_UP / PAGE_DOWN）按整屏翻页，翻完仍是同一套「焦点框 ↔ 列表」联动。</li>
 * </ol>
 *
 * <p><b>初始定位</b>：菜单自下往上弹出，最底下一行离按钮最近，所以打开时把当前选中项
 * （倍速是 1.0x、选集是正在播的那一集）停靠在可视区最底部；内容一屏放得下时不做滚动。
 * 选集列表另有 {@link #setAvoidEmptyRows(boolean)}：选中行上方不足一屏时改为顶部对齐，
 * 让选中行上移并避免容器顶部出现空行。
 *
 * <p><b>选中框</b>：不由行自身绘制（行会被回收，焦点态容易过期而停在初始位置），
 * 而是在列表容器上放唯一 overlay（{@code sheetSelection}）。焦点变化与每次滚动都会重算
 * 它的位置（{@link #syncSelectionFrame()}），并钳制在容器边界内，因此框始终跟随聚焦行、
 * 不会溢出列表容器。
 */
public final class SheetList {

    public static final int ITEM_HEIGHT_DP = 44;
    public static final int ITEM_SPACING_DP = 2;

    public interface OnClickListener {

        void onItemClick(int position);
    }

    private final ViewSheetListBinding binding;
    private final Adapter adapter;
    private int selected;
    /** 选集列表专用：选中行上方不足一屏时改为顶部对齐，避免列表容器顶部出现空行。 */
    private boolean avoidEmptyRows;
    private final ViewTreeObserver.OnGlobalFocusChangeListener focusListener = (oldFocus, newFocus) -> syncSelectionFrame();
    private ViewTreeObserver observer;

    public SheetList(Context context) {
        binding = ViewSheetListBinding.inflate(LayoutInflater.from(context));
        binding.sheetList.setItemAnimator(null);
        binding.sheetList.addItemDecoration(new SpaceItemDecoration(1, ITEM_SPACING_DP));
        binding.sheetList.setAdapter(adapter = new Adapter());
        // 滚动时选中框必须跟着行的位置走；否则它只会停在打开时算出的初始位置
        binding.sheetList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                syncSelectionFrame();
            }
        });
        binding.getRoot().addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(@NonNull View view) {
                registerFocusObserver();
            }

            @Override
            public void onViewDetachedFromWindow(@NonNull View view) {
                unregisterFocusObserver();
                binding.sheetSelection.setVisibility(View.GONE);
            }
        });
    }

    public View getView() {
        return binding.getRoot();
    }

    /**
     * 设置列表上方的标题（「选集」「倍速」），与「播放设置」浮层的标题共用同一套样式。
     *
     * <p>传空则整行 {@code GONE}、不占高度。标题行只影响浮层总高，不参与列表的
     * 行数与滚动计算（{@link #setRows} 回写的 maxHeight 仍然只管列表那一段）。
     */
    public void setTitle(CharSequence title) {
        boolean visible = !TextUtils.isEmpty(title);
        binding.sheetListTitle.setText(title);
        binding.sheetListTitleRow.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    public void setTitle(@StringRes int resId) {
        setTitle(ResUtil.getString(resId));
    }

    public void submit(List<String> texts, int selected, int activated, OnClickListener listener) {
        submit(texts, selected, activated, -1, listener);
    }

    /**
     * @param playing 正在播放的行下标（会在该行前面显示均衡器小图标）；-1 表示这是个没有「播放中」概念的列表（倍速）。
     *                取值 &gt;= 0 时所有行都会预留图标槽位，保证各行文字左边缘对齐。
     */
    public void submit(List<String> texts, int selected, int activated, int playing, OnClickListener listener) {
        adapter.submit(texts, activated, playing, listener);
        this.selected = Math.max(0, Math.min(selected, Math.max(0, texts.size() - 1)));
        // 数据集变了：旧的聚焦行可能已被回收，先让框在下一帧重新定位（或隐藏）
        binding.getRoot().post(this::syncSelectionFrame);
    }

    /**
     * 选集列表专用：选中行上方不足一屏时，不再把选中行钉在底部（那会在顶部留下空行），
     * 改为顶部对齐，让选中行上移、下方行填满容器。倍速列表保持原有的「选中项贴底」。
     */
    public void setAvoidEmptyRows(boolean avoidEmptyRows) {
        this.avoidEmptyRows = avoidEmptyRows;
    }

    /**
     * 浮层展开后调用：先把选中项停靠到最底部，再把焦点交给它。
     *
     * <p>停靠必须等列表完成布局（{@code getHeight()} 有效）才计算，所以放在这里而不是 {@link #submit}：
     * submit 发生在浮层显示之前，此时高度还是 0。
     */
    public void focus() {
        dockToBottom();
        binding.sheetList.post(this::focusSelected);
        binding.getRoot().post(this::syncSelectionFrame);
    }

    private void registerFocusObserver() {
        ViewTreeObserver current = binding.getRoot().getViewTreeObserver();
        if (observer == current && current.isAlive()) return;
        unregisterFocusObserver();
        observer = current;
        if (observer.isAlive()) observer.addOnGlobalFocusChangeListener(focusListener);
        binding.getRoot().post(this::syncSelectionFrame);
    }

    private void unregisterFocusObserver() {
        if (observer == null) return;
        if (observer.isAlive()) observer.removeOnGlobalFocusChangeListener(focusListener);
        observer = null;
    }

    /**
     * 把唯一的选中框移动到「当前聚焦行」的位置。
     *
     * <p>行可能是部分滚出可视区的：直接把框放到行的位置会溢出列表容器，所以 left/top 都要
     * 钳制在 {@code [0, 容器尺寸 - 框尺寸]} 内。焦点不在列表里、或列表还没布局时隐藏框。
     */
    private void syncSelectionFrame() {
        View row = binding.sheetList.getFocusedChild();
        // 钳制基准是「只含列表」的容器，不含上方标题行；否则标题高度会被算进容器，框整体下坠
        int width = binding.sheetListFrame.getWidth();
        int height = binding.sheetListFrame.getHeight();
        int rowWidth = row == null ? 0 : row.getWidth();
        int rowHeight = row == null ? 0 : row.getHeight();
        if (rowWidth <= 0 || rowHeight <= 0 || width <= 0 || height <= 0) {
            binding.sheetSelection.setVisibility(View.GONE);
            return;
        }
        // 跟随聚焦行，并限制在容器边界内（行滚动到一半时也不会伸出容器）
        int left = Math.max(0, Math.min(row.getLeft(), width - rowWidth));
        int top = Math.max(0, Math.min(row.getTop(), height - rowHeight));
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) binding.sheetSelection.getLayoutParams();
        if (params.width != rowWidth || params.height != rowHeight) {
            params.width = rowWidth;
            params.height = rowHeight;
            params.leftMargin = 0;
            params.topMargin = 0;
            binding.sheetSelection.setLayoutParams(params);
        }
        // 用 setX/setY 移动而不是改 margin：滚动时不会触发列表重新布局
        binding.sheetSelection.setX(left);
        binding.sheetSelection.setY(top);
        binding.sheetSelection.setVisibility(View.VISIBLE);
    }

    private void focusSelected() {
        LinearLayoutManager layoutManager = layoutManager();
        View child = layoutManager == null ? null : layoutManager.findViewByPosition(selected);
        if (child != null && child.requestFocus()) return;
        // 目标行还没挂上来：交给 CustomRecyclerView 的滚动+延迟聚焦
        binding.sheetList.scrollToPosition(selected);
    }

    /** 可视行数上限：超过就在浮层内滚动；0 或负数表示不限制。 */
    public void setRows(int rows) {
        int height = rows <= 0 ? 0 : rows * ResUtil.dp2px(ITEM_HEIGHT_DP) + Math.max(0, rows - 1) * ResUtil.dp2px(ITEM_SPACING_DP);
        binding.sheetList.setMaxHeight(height);
        binding.sheetList.requestLayout();
        // 高度变化后聚焦行的位置会变，下一帧重新贴合选中框
        binding.getRoot().post(this::syncSelectionFrame);
    }

    public int getItemCount() {
        return adapter.getItemCount();
    }

    /**
     * 把选中项停靠到可视区最底部；一屏放得下就保持原样。
     *
     * <p>{@link #avoidEmptyRows} 为 true（选集列表）时：若选中行上方不足一屏
     * （少于 {@code 可视行数 - 1} 行），底部停靠会在容器顶部留下空行，改为顶部对齐，
     * 让选中行上移、下方已看过的集数把容器填满。
     */
    private void dockToBottom() {
        LinearLayoutManager layoutManager = layoutManager();
        if (layoutManager == null) return;
        int count = adapter.getItemCount();
        if (count <= 0) return;
        int row = ResUtil.dp2px(ITEM_HEIGHT_DP);
        int content = count * row + Math.max(0, count - 1) * ResUtil.dp2px(ITEM_SPACING_DP);
        int height = binding.sheetList.getHeight();
        if (height <= 0 || content <= height) return;
        // 上方行数不足一屏：改为顶部对齐，避免容器内出现空行（选集播放最新一集时 selected=0）
        if (avoidEmptyRows && selected < visibleRows() - 1) {
            layoutManager.scrollToPositionWithOffset(0, 0);
            return;
        }
        layoutManager.scrollToPositionWithOffset(selected, Math.max(0, height - row));
    }

    private LinearLayoutManager layoutManager() {
        RecyclerView.LayoutManager manager = binding.sheetList.getLayoutManager();
        return manager instanceof LinearLayoutManager linear ? linear : null;
    }

    /** 可视区能放下几行：翻页步长。 */
    private int visibleRows() {
        int row = ResUtil.dp2px(ITEM_HEIGHT_DP) + ResUtil.dp2px(ITEM_SPACING_DP);
        int height = binding.sheetList.getHeight();
        if (row <= 0 || height <= 0) return 1;
        return Math.max(1, (height + ResUtil.dp2px(ITEM_SPACING_DP)) / row);
    }

    /**
     * 条目按键入口。
     *
     * <p><b>边界收束</b>：首行再按上、末行再按时把按键吃掉，焦点停在本行，绝不外逸。
     * 否则方向键在浮层首/末行会把焦点带出弹层（如倍速弹窗往下翻到最后一行后按下会丢焦到控制条）。
     * 其余按键交给 {@link #onKey} 做 CH+/CH- 整屏翻页。
     */
    private boolean onItemKey(RecyclerView.ViewHolder holder, int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        int count = adapter.getItemCount();
        int position = holder.getBindingAdapterPosition();
        if (count <= 0 || position == RecyclerView.NO_POSITION) return false;
        if (position == 0 && keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
        if (position == count - 1 && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
        return onKey(keyCode, event);
    }

    /** CH+ / CH-（PAGE_UP / PAGE_DOWN）整屏翻页。 */
    private boolean onKey(int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        int count = adapter.getItemCount();
        if (count <= 0) return false;
        switch (keyCode) {
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
                return page(-visibleRows(), count);
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return page(visibleRows(), count);
            default:
                return false;
        }
    }

    private boolean page(int delta, int count) {
        int target = Math.min(count - 1, Math.max(0, selected + delta));
        if (target != selected) {
            selected = target;
            // CustomRecyclerView 重写了 scrollToPosition：滚完会把焦点交给目标行
            binding.sheetList.scrollToPosition(target);
        }
        return true;
    }

    private final class Adapter extends RecyclerView.Adapter<Adapter.ViewHolder> {

        private final List<String> items = new ArrayList<>();
        private int activated = -1;
        private int playing = -1;
        private OnClickListener listener;

        void submit(List<String> texts, int activated, int playing, OnClickListener listener) {
            items.clear();
            items.addAll(texts);
            this.activated = activated;
            this.playing = playing;
            this.listener = listener;
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ViewHolder holder = new ViewHolder(ViewSheetItemBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
            // 按键落在被聚焦的条目上，所以监听装在条目文字上（挂在列表容器上收不到）
            holder.binding.sheetItemText.setOnKeyListener((view, keyCode, event) -> onItemKey(holder, keyCode, event));
            return holder;
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            holder.binding.sheetItemText.setActivated(position == activated);
            holder.binding.sheetItemText.setText(items.get(position));
            bindPlaying(holder, position);
            holder.binding.sheetItemText.setOnClickListener(view -> {
                if (listener != null) listener.onItemClick(holder.getBindingAdapterPosition());
            });
        }

        /**
         * 正在播放的指示器：整列预留槽位（未播放行 INVISIBLE 占位，保证文字左边缘对齐），
         * 只有播放中的那一行跑均衡器动画；没有「播放中」概念的列表整块 GONE，不占宽度。
         */
        private void bindPlaying(ViewHolder holder, int position) {
            if (playing < 0) {
                holder.binding.sheetItemPlaying.setVisibility(View.GONE);
                holder.binding.sheetItemPlaying.setPlaying(false);
                return;
            }
            boolean current = position == playing;
            holder.binding.sheetItemPlaying.setVisibility(current ? View.VISIBLE : View.INVISIBLE);
            holder.binding.sheetItemPlaying.setPlaying(current);
        }

        static final class ViewHolder extends RecyclerView.ViewHolder {

            private final ViewSheetItemBinding binding;

            ViewHolder(ViewSheetItemBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}
