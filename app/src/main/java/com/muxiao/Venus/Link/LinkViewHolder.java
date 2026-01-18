package com.muxiao.Venus.Link;

import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textview.MaterialTextView;
import com.muxiao.Venus.R;

/**
 * 链接视图持有者，绑定列表项中的UI组件
 */
public class LinkViewHolder extends RecyclerView.ViewHolder {
    final MaterialTextView uidTextView;
    final MaterialTextView linkTextView;
    final MaterialTextView currentUserTextView;
    final com.google.android.material.button.MaterialButton copyButton;

    public LinkViewHolder(View itemView) {
        super(itemView);
        currentUserTextView = itemView.findViewById(R.id.currentUserTextView);
        uidTextView = itemView.findViewById(R.id.uidTextView);
        linkTextView = itemView.findViewById(R.id.linkTextView);
        copyButton = itemView.findViewById(R.id.copyButton);
    }
}
