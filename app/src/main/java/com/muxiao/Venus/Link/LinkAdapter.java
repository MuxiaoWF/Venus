package com.muxiao.Venus.Link;

import static com.muxiao.Venus.common.tools.copyToClipboard;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.muxiao.Venus.R;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 抽卡链接适配器，管理实际内容
 */
public class LinkAdapter extends RecyclerView.Adapter<LinkViewHolder> {
    private final View view;
    private final Context context;
    private String currentUser = "";

    public LinkAdapter(View view, Context context) {
        this.view = view;
        this.context = context;
    }

    public void setCurrentUser(String currentUser) {
        this.currentUser = currentUser;
    }

    private final Map<Integer, String> uid_link_map = new LinkedHashMap<>();
    private Integer[] cachedKeys = new Integer[0];

    private void rebuildKeyCache() {
        cachedKeys = uid_link_map.keySet().toArray(new Integer[0]);
    }

    public void setLinks(Map<Integer, String> uid_link_map) {
        int oldSize = this.uid_link_map.size();
        this.uid_link_map.clear();
        if (uid_link_map != null)
            this.uid_link_map.putAll(uid_link_map);
        rebuildKeyCache();
        int newSize = this.uid_link_map.size();
        if (oldSize == 0 && newSize > 0) {
            // 从空到有数据，插入所有项
            notifyItemRangeInserted(0, newSize);
        } else if (oldSize > 0 && newSize == 0) {
            // 从有数据到空，删除所有项
            notifyItemRangeRemoved(0, oldSize);
        } else if (oldSize > 0) {
            // 都有数据，先更新现有项
            int minSize = Math.min(oldSize, newSize);
            notifyItemRangeChanged(0, minSize);
            // 如果新数据更多，插入额外项
            if (newSize > oldSize)
                notifyItemRangeInserted(minSize, newSize - oldSize);
                // 如果旧数据更多，删除多余项
            else if (oldSize > newSize)
                notifyItemRangeRemoved(minSize, oldSize - newSize);
        }
    }

    /**
     * 静默更新数据，不触发 item 动画（用于 tab 切换等需要避免交叉淡入的场景）
     */
    public void setLinksSilently(Map<Integer, String> newLinks) {
        int oldSize = this.uid_link_map.size();
        this.uid_link_map.clear();
        if (newLinks != null)
            this.uid_link_map.putAll(newLinks);
        rebuildKeyCache();
        int newSize = this.uid_link_map.size();
        int minSize = Math.min(oldSize, newSize);
        if (minSize > 0)
            notifyItemRangeChanged(0, minSize);
        if (newSize > oldSize)
            notifyItemRangeInserted(minSize, newSize - oldSize);
        else if (oldSize > newSize)
            notifyItemRangeRemoved(minSize, oldSize - newSize);
    }

    /**
     * 创建ViewHolder
     */
    @NonNull
    @Override
    public LinkViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_link_gacha_link, parent, false);
        return new LinkViewHolder(view);
    }

    /**
     * 绑定ViewHolder
     */
    @Override
    public void onBindViewHolder(LinkViewHolder holder, int position) {
        Integer uid = cachedKeys[position];
        String link = uid_link_map.get(uid);
        holder.uidTextView.setText("UID: " + uid);
        holder.linkTextView.setText(link);
        holder.copyButton.setOnClickListener(v -> copyToClipboard(view, context, link));
        if (currentUser != null && !currentUser.isEmpty()) {
            holder.currentUserTextView.setText(context.getString(R.string.image_user_label) + currentUser);
            holder.currentUserTextView.setVisibility(View.VISIBLE);
        } else {
            holder.currentUserTextView.setVisibility(View.GONE);
        }

        // 重置展开状态
        holder.linkTextView.setMaxLines(3);
        holder.expandButton.setText(context.getString(R.string.show_more));
        holder.expandButton.setVisibility(View.GONE);

        // 延迟检测文本是否被截断，布局完成后判断
        holder.linkTextView.post(() -> {
            android.text.Layout layout = holder.linkTextView.getLayout();
            if (layout != null) {
                int lastLine = holder.linkTextView.getMaxLines() - 1;
                if (lastLine >= 0 && lastLine < layout.getLineCount()) {
                    int end = layout.getLineEnd(lastLine);
                    if (end < holder.linkTextView.getText().length()) {
                        holder.expandButton.setVisibility(View.VISIBLE);
                    }
                }
            }
        });

        holder.expandButton.setOnClickListener(v -> {
            boolean expanded = holder.linkTextView.getMaxLines() == Integer.MAX_VALUE;
            if (expanded) {
                holder.linkTextView.setMaxLines(3);
                holder.expandButton.setText(context.getString(R.string.show_more));
            } else {
                holder.linkTextView.setMaxLines(Integer.MAX_VALUE);
                holder.expandButton.setText(context.getString(R.string.show_less));
            }
        });
    }

    @Override
    public void onViewRecycled(@NonNull LinkViewHolder holder) {
        super.onViewRecycled(holder);
        holder.expandButton.setOnClickListener(null);
        holder.expandButton.setVisibility(View.GONE);
    }

    @Override
    public int getItemCount() {
        return uid_link_map.size();
    }

}