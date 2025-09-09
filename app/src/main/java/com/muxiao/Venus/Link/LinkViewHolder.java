package com.muxiao.Venus.Link;

import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textview.MaterialTextView;
import com.muxiao.Venus.R;

public class LinkViewHolder extends RecyclerView.ViewHolder {
    MaterialTextView uidTextView;
    MaterialTextView linkTextView;
    MaterialTextView currentUserTextView;
    com.google.android.material.button.MaterialButton copyButton;

    public LinkViewHolder(View itemView) {
        super(itemView);
        currentUserTextView = itemView.findViewById(R.id.currentUserTextView);
        uidTextView = itemView.findViewById(R.id.uidTextView);
        linkTextView = itemView.findViewById(R.id.linkTextView);
        copyButton = itemView.findViewById(R.id.copyButton);
    }
}
