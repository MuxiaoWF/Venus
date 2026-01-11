package com.muxiao.Venus.Setting;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewGroup;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.muxiao.Venus.R;
import com.ortiz.touchview.TouchImageView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ImagePagerAdapter extends RecyclerView.Adapter<ImagePagerAdapter.ImageViewHolder> {
    private final Context context;
    private final List<ImageItem> imageItems; // 修改为ImageItem列表

    // 创建一个内部类来保存图片信息
    public static class ImageItem {
        private final String imageUrl;
        private final Map<String, Object> imageData;

        public ImageItem(String imageUrl, Map<String, Object> imageData) {
            this.imageUrl = imageUrl;
            this.imageData = imageData;
        }

        public String getImageUrl() {
            return imageUrl;
        }

        public Map<String, Object> getImageData() {
            return imageData;
        }
    }

    public ImagePagerAdapter(Context context, List<Map<String, Object>> imageDataList) {
        this.context = context;
        this.imageItems = new ArrayList<>();
        // 为每个图片URL创建一个ImageItem
        for (Map<String, Object> imageData : imageDataList) {
            @SuppressWarnings("unchecked")
            List<String> images = (List<String>) imageData.get("images");

            if (images != null && !images.isEmpty())
                for (String imageUrl : images)
                    this.imageItems.add(new ImageItem(imageUrl, imageData));
            else
                // 如果没有图片URL，则添加一个ImageItem
                this.imageItems.add(new ImageItem(null, imageData));
        }
    }

    @NonNull
    @Override
    public ImageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_fullscreen_image, parent, false);
        return new ImageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ImageViewHolder holder, int position) {
        ImageItem imageItem = imageItems.get(position);
        String imageUrl = imageItem.getImageUrl();

        if (imageUrl != null && !imageUrl.isEmpty()) {
            // 显示加载进度条
            holder.progressBar.setVisibility(View.VISIBLE);

            // 使用CustomTarget方式加载图片
            Glide.with(context)
                    .load(imageUrl)
                    .into(new com.bumptech.glide.request.target.CustomTarget<android.graphics.drawable.Drawable>() {
                        @Override
                        public void onResourceReady(@NonNull android.graphics.drawable.Drawable resource,
                                                    @androidx.annotation.Nullable com.bumptech.glide.request.transition.Transition<? super android.graphics.drawable.Drawable> transition) {
                            // 加载成功，隐藏进度条并设置图片
                            holder.progressBar.setVisibility(View.GONE);
                            holder.imageView.setImageDrawable(resource);
                        }

                        @Override
                        public void onLoadCleared(@androidx.annotation.Nullable android.graphics.drawable.Drawable placeholder) {
                            // 加载被清除时的处理
                            holder.progressBar.setVisibility(View.GONE);
                        }

                        @Override
                        public void onLoadFailed(@androidx.annotation.Nullable android.graphics.drawable.Drawable errorDrawable) {
                            // 加载失败时的处理
                            holder.progressBar.setVisibility(View.GONE);
                            // 设置错误图片
                            holder.imageView.setImageResource(R.drawable.ic_error);
                        }
                    });
        } else {
            // 如果没有图片URL，隐藏进度条并设置默认错误图片
            holder.progressBar.setVisibility(View.GONE);
            holder.imageView.setImageResource(R.drawable.ic_error);
        }
    }

    @Override
    public int getItemCount() {
        return imageItems != null ? imageItems.size() : 0;
    }

    // 添加方法获取指定位置的ImageItem
    public ImageItem getItem(int position) {
        if (position >= 0 && position < imageItems.size())
            return imageItems.get(position);
        return null;
    }

    public static class ImageViewHolder extends RecyclerView.ViewHolder {
        TouchImageView imageView;
        ProgressBar progressBar;

        @SuppressLint("ClickableViewAccessibility")
        public ImageViewHolder(@NonNull View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.fullscreenImageView);
            progressBar = itemView.findViewById(R.id.progressBar);
            // 设置TouchImageView的触摸监听器来处理与ViewPager2的手势冲突
            imageView.setOnTouchListener((v, event) -> {
                ViewParent parent = imageView.getParent();
                if (parent == null)
                    return false;
                // 检查是否是多点触控或者图片可以水平滚动
                if (event.getPointerCount() >= 2 || imageView.canScrollHorizontally(1) && imageView.canScrollHorizontally(-1)) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                        case MotionEvent.ACTION_MOVE:
                            // 禁止RecyclerView拦截触摸事件
                            parent.requestDisallowInterceptTouchEvent(true);
                            // 允许TouchImageView处理触摸事件
                            return false;
                        case MotionEvent.ACTION_UP:
                            // 允许RecyclerView拦截触摸事件
                            parent.requestDisallowInterceptTouchEvent(false);
                            return true;
                    }
                }
                return false;
            });
        }
    }
}
