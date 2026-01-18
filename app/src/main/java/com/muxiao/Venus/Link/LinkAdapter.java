package com.muxiao.Venus.Link;

import static com.muxiao.Venus.common.tools.copyToClipboard;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.muxiao.Venus.R;

import java.util.HashMap;
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

    private final Map<Integer, String> uid_link_map = new HashMap<>();

    public void setLinks(Map<Integer, String> uid_link_map) {
        // 保存链接大小
        int oldSize = this.uid_link_map.size();
        this.uid_link_map.clear();
        if (uid_link_map != null)
            this.uid_link_map.putAll(uid_link_map);
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
        Integer[] uids = uid_link_map.keySet().toArray(new Integer[0]);
        Integer uid = uids[position];
        String link = uid_link_map.get(uid);
        holder.uidTextView.setText(new StringBuilder("UID: " + uid));
        holder.linkTextView.setText(link);
        holder.copyButton.setOnClickListener(v -> copyToClipboard(view, context, link));
        // 设置当前用户
        if (!currentUser.isEmpty()) {
            holder.currentUserTextView.setText(new StringBuilder("用户: " + currentUser));
            holder.currentUserTextView.setVisibility(View.VISIBLE);
        } else {
            holder.currentUserTextView.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return uid_link_map.size();
    }

}