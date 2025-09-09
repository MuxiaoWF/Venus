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

public class LinkAdapter extends RecyclerView.Adapter<LinkViewHolder> {
    private final View view;
    private final Context context;
    private String currentUser = ""; // 当前用户

    public LinkAdapter(View view, Context context) {
        this.view = view;
        this.context = context;
    }

    public void setCurrentUser(String currentUser) {
        this.currentUser = currentUser;
    }

    private final Map<Integer, String> links = new HashMap<>();

    public void setLinks(Map<Integer, String> links) {
        // 保存链接大小
        int oldSize = this.links.size();
        this.links.clear();
        if (links != null)
            this.links.putAll(links);
        int newSize = this.links.size();
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

    @NonNull
    @Override
    public LinkViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_gacha_link, parent, false);
        return new LinkViewHolder(view);
    }

    @Override
    public void onBindViewHolder(LinkViewHolder holder, int position) {
        Integer[] uids = links.keySet().toArray(new Integer[0]);
        Integer uid = uids[position];
        String link = links.get(uid);
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
        return links.size();
    }
}