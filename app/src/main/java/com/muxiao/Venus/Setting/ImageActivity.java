package com.muxiao.Venus.Setting;

import static com.muxiao.Venus.common.tools.showCustomSnackbar;
import static com.muxiao.Venus.common.tools.show_error_dialog;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.muxiao.Venus.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ImageActivity extends AppCompatActivity {
    private RecyclerView recyclerView;
    private ImageAdapter imageAdapter;
    private Image imageLoader;
    private List<Map<String, Object>> imageDataList;
    private final List<Integer> selectedItems = new ArrayList<>();
    private boolean isSelectionMode = false;
    private MenuItem downloadMenuItem;
    private View rootView;

    private static final int PERMISSION_REQUEST_CODE = 1001;
    private int downloadProgress = 0;
    private int totalDownloads = 0;
    private List<Integer> itemsToDownload = new ArrayList<>();
    private int currentImageIndexInPost = 0; // 当前帖子中正在下载的图片索引
    private int currentPostIndex = 0; // 当前正在下载的帖子索引
    private List<String> currentPostImages = new ArrayList<>(); // 当前帖子的所有图片URL
    private Map<String, Object> currentPostData; // 当前帖子的数据

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 应用选定的主题
        int selectedTheme = SettingsFragment.getSelectedTheme(this);
        setTheme(selectedTheme);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_image);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        rootView = findViewById(android.R.id.content);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        MaterialAutoCompleteTextView forumSelector = findViewById(R.id.forumSelector);
        MaterialAutoCompleteTextView cateSelector = findViewById(R.id.cateIdSelector);
        TabLayout tabLayout = findViewById(R.id.tabLayout);
        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new GridLayoutManager(this, 2));
        // 设置下拉选项
        String[] forumNames = {"原神", "星穹铁道", "崩三", "绝区零", "未定", "崩二", "大别野"};
        String[] forumIds = {"29", "56", "4", "59", "38", "40", "39"};

        // 为每个forum设置独立的cate列表
        String[][] cateNamesArray = {
                {"插画", "漫画", "Q版", "手工", "cos"},  // 原神的分类
                {"插画", "漫画", "cos"},        // 星穹铁道的分类
                {"插画", "漫画", "cos"},        // 崩坏3的分类
                {"同人"}, // 绝区零的分类
                {"同人"}, // 未定
                {"同人"}, // 崩二
                {"同人", "cos"}, // 大别野的分类
        };

        String[][] cateIdsArray = {
                {"4", "3", "2", "1", "0"},   // 原神的分类ID
                {"4", "3", "0"},        // 星穹铁道的分类ID
                {"4", "3", "17"},         // 崩坏3的分类ID
                {"0"}, // 绝区零的分类ID
                {"0"}, // 未定的分类ID
                {"0"}, // 崩二的分类ID
                {"0", "1"}, // 大别野的分类ID
        };

        ArrayAdapter<String> forumAdapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, forumNames);
        forumSelector.setAdapter(forumAdapter);

        // 跟踪当前选择的值
        final int[] currentForumIndex = {0};
        final int[] currentType = {0}; // 0: 日榜, 1: 周榜, 2: 月榜

        // 初始化TabLayout
        setupTabLayout(tabLayout, false); // 默认显示所有选项卡

        // 创建组合监听器，用于同时处理两个选择器的变化
        forumSelector.setOnItemClickListener((parent, view, position, id) -> {
            currentForumIndex[0] = position;
            // 更新分类下拉列表
            String[] currentCateNames = cateNamesArray[position];
            ArrayAdapter<String> cateAdapter = new ArrayAdapter<>(ImageActivity.this, android.R.layout.simple_dropdown_item_1line, currentCateNames);
            cateSelector.setAdapter(cateAdapter);
            cateSelector.setText(currentCateNames[0], false);

            if (position == 5) { // 崩二
                // 只包含周榜和月榜
                setupTabLayout(tabLayout, true);
                currentType[0] = 1;
            } else {
                setupTabLayout(tabLayout, false);
                currentType[0] = 0;
            }

            // 加载图片
            String forumId = forumIds[position];
            String cateId = cateIdsArray[position][0];
            loadImages("forum_id=" + forumId + "&cate_id=" + cateId + "&type=" + (currentType[0] + 1));
        });

        cateSelector.setOnItemClickListener((parent, view, position, id) -> {
            String forumId = forumIds[currentForumIndex[0]];
            String cateId = cateIdsArray[currentForumIndex[0]][position];
            if (forumId.equals("29") && cateId.equals("0"))
                forumId = "49"; // 原神cos
            if (forumId.equals("39") && cateId.equals("1")) {
                // 大别野cos
                forumId = "47";
                cateId = "0";
            }
            loadImages("forum_id=" + forumId + "&cate_id=" + cateId + "&type=" + (currentType[0] + 1));
        });

        // TabLayout选择监听器
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                currentType[0] = tab.getPosition();
                String forumId = forumIds[currentForumIndex[0]];
                String cateId = cateIdsArray[currentForumIndex[0]][0];
                // 获取当前选中的分类索引
                int selectedCateIndex = 0;
                String selectedCateText = cateSelector.getText().toString();
                String[] currentCateNames = cateNamesArray[currentForumIndex[0]];
                for (int i = 0; i < currentCateNames.length; i++) {
                    if (currentCateNames[i].equals(selectedCateText)) {
                        selectedCateIndex = i;
                        break;
                    }
                }
                if (selectedCateIndex < cateIdsArray[currentForumIndex[0]].length) {
                    cateId = cateIdsArray[currentForumIndex[0]][selectedCateIndex];
                }
                if (forumId.equals("29") && cateId.equals("0"))
                    forumId = "49"; // 原神cos
                if (forumId.equals("39") && cateId.equals("1")) {
                    // 大别野cos
                    forumId = "47";
                    cateId = "0";
                }
                loadImages("forum_id=" + forumId + "&cate_id=" + cateId + "&type=" + (currentType[0] + 1));
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        // 默认选择第一个
        forumSelector.setText(forumNames[0], false);
        String[] initialCateNames = cateNamesArray[0];
        ArrayAdapter<String> initialCateAdapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, initialCateNames);
        cateSelector.setAdapter(initialCateAdapter);
        cateSelector.setText(initialCateNames[0], false);
        loadImages("forum_id=" + forumIds[0] + "&cate_id=" + cateIdsArray[0][0] + "&type=" + (currentType[0] + 1));

        // 设置toolbar
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("图片浏览");
        }
        handleBackPress();
    }

    private void handleBackPress() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isSelectionMode)
                    exitSelectionMode();
                else
                    finish();
            }
        });
    }

    private void loadImages(String params) {
        showCustomSnackbar(rootView, this, "加载图片中...");
        imageLoader = new Image(this);
        imageLoader.getImageAsync(new Image.ImageDataCallback() {
            @Override
            public void onImageLoaded(List<Map<String, Object>> imageData) {
                // 数据加载成功，更新UI
                imageDataList = imageData;
                imageAdapter = new ImageAdapter(imageData);
                recyclerView.setAdapter(imageAdapter);
            }

            @Override
            public void onError(Exception e) {
                // 处理错误
                show_error_dialog(ImageActivity.this, "加载失败: " + e.getMessage());
            }
        }, params);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.image_selection_menu, menu);
        downloadMenuItem = menu.findItem(R.id.action_download);
        downloadMenuItem.setVisible(false);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_download) {
            checkPermissionsAndDownload();
            return true;
        } else if (item.getItemId() == android.R.id.home) {
            if (isSelectionMode) {
                exitSelectionMode();
            } else {
                finish();
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void enterSelectionMode() {
        if (isSelectionMode) return;
        isSelectionMode = true;
        downloadMenuItem.setVisible(true);
        // 更新ActionBar标题和返回按钮
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(selectedItems.size() + " 项已选择");
            getSupportActionBar().setHomeAsUpIndicator(R.drawable.error);
        }
    }

    private void exitSelectionMode() {
        isSelectionMode = false;
        downloadMenuItem.setVisible(false);
        // 清除选择
        List<Integer> previousSelections = new ArrayList<>(selectedItems);
        selectedItems.clear();
        // 更新所有之前选中的项目
        for (int position : previousSelections) {
            RecyclerView.ViewHolder holder = recyclerView.findViewHolderForAdapterPosition(position);
            if (holder instanceof ImageViewHolder)
                updateSelectionState((ImageViewHolder) holder, position);
        }
        // 恢复ActionBar标题和返回按钮
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("图片浏览");
            getSupportActionBar().setHomeAsUpIndicator(null); // 恢复默认的返回图标
        }
    }

    private void updateSelectionState(ImageViewHolder holder, int position) {
        if (selectedItems.contains(position)) {
            // 设置选中状态的视觉效果
            holder.cardView.setStrokeWidth(8); // 设置边框宽度
            holder.cardView.setStrokeColor(ContextCompat.getColor(this, R.color.blue_theme_primary)); // 设置边框颜色
            holder.cardView.setCardBackgroundColor(ContextCompat.getColor(this, R.color.blue_theme_surfaceVariant)); // 设置背景色
        } else {
            // 恢复默认状态
            holder.cardView.setStrokeWidth(0); // 移除边框
            holder.cardView.setCardBackgroundColor(ContextCompat.getColor(this, R.color.blue_theme_surface)); // 恢复默认背景色
        }
    }

    /**
     * 切换选择状态
     *
     * @param holder ViewHolder
     * @param position 索引位置
     */
    private void toggleSelection(ImageViewHolder holder, int position) {
        if (selectedItems.contains(position))
            selectedItems.remove(Integer.valueOf(position));
        else
            selectedItems.add(position);
        // 更新选中状态的视觉效果
        updateSelectionState(holder, position);
        if (isSelectionMode) {
            if (getSupportActionBar() != null)
                getSupportActionBar().setTitle(selectedItems.size() + " 项已选择");
            if (selectedItems.isEmpty())
                exitSelectionMode();
        }
    }

    private class ImageAdapter extends RecyclerView.Adapter<ImageViewHolder> {
        private final List<Map<String, Object>> imageDataList;

        public ImageAdapter(List<Map<String, Object>> imageDataList) {
            this.imageDataList = imageDataList;
        }

        @NonNull
        @Override
        public ImageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = getLayoutInflater().inflate(R.layout.item_image, parent, false);
            return new ImageViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ImageViewHolder holder, int position) {
            Map<String, Object> imageData = imageDataList.get(position);
            // 获取第一张图片作为封面
            @SuppressWarnings("unchecked")
            List<String> images = (List<String>) imageData.get("images");
            String author = (String) imageData.get("author");
            String description = (String) imageData.get("description");
            String title = (String) imageData.get("title");
            Long timestamp = (Long) imageData.get("timestamp");

            if (images != null && !images.isEmpty()) {
                Glide.with(ImageActivity.this)
                        .load(images.get(0)).placeholder(R.drawable.loading)
                        .error(R.drawable.error).into(holder.imageView);
            }
            holder.titleTextView.setText(title != null ? title : "");
            holder.authorTextView.setText(author);
            holder.descriptionTextView.setText(description);
            if (timestamp != null && timestamp > 0) {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault());
                String formattedTime = sdf.format(new java.util.Date(timestamp * 1000));
                holder.timeTextView.setText(formattedTime);
            } else {
                holder.timeTextView.setText("");
            }
            // 更新选中状态
            updateSelectionState(holder, position);
            // 设置点击事件
            holder.itemView.setOnClickListener(v -> {
                if (isSelectionMode)
                    toggleSelection(holder, position);
                else
                    openFullscreenImage(position);
            });
            // 长按进入多选模式
            holder.itemView.setOnLongClickListener(v -> {
                if (!isSelectionMode)
                    enterSelectionMode();
                toggleSelection(holder, position);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return imageDataList != null ? imageDataList.size() : 0;
        }
    }

    private void checkPermissionsAndDownload() {
        if (selectedItems.isEmpty()) {
            showCustomSnackbar(rootView, this, "未选择任何图片");
            return;
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
            // Android 9.0及以下版本需要存储权限
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED)
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST_CODE);
            else
                downloadSelectedImages();
        else
            // Android 10及以上版本使用分区存储，不需要权限
            downloadSelectedImages();
    }

    private void downloadSelectedImages() {
        if (selectedItems.isEmpty()) {
            showCustomSnackbar(rootView, this, "未选择任何图片");
            return;
        }
        // 创建要下载的项目副本，避免在下载过程中selectedItems被修改
        itemsToDownload = new ArrayList<>(selectedItems);
        currentPostIndex = 0;
        currentImageIndexInPost = 0;
        // 计算总图片数量
        totalDownloads = 0;
        for (int position : itemsToDownload) {
            if (imageDataList != null && position >= 0 && position < imageDataList.size()) {
                @SuppressWarnings("unchecked")
                List<String> images = (List<String>) imageDataList.get(position).get("images");
                if (images != null)
                    totalDownloads += images.size();
            }
        }
        downloadProgress = 0;
        showCustomSnackbar(rootView, this, "开始下载 " + totalDownloads + " 张图片");
        // 创建Venus图片目录
        File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        File venusDir = new File(picturesDir, "Venus");
        if (!venusDir.exists())
            venusDir.mkdirs();
        downloadNextImage();
    }

    private void downloadNextImage() {
        // 检查是否还有图片需要下载
        if (downloadProgress >= totalDownloads || itemsToDownload.isEmpty()) {
            showCustomSnackbar(rootView, this, "所有图片已下载到Pictures/Venus文件夹");
            exitSelectionMode(); // 下载完成后退出选择模式
            return;
        }

        // 如果当前帖子的所有图片都已下载完，或者刚开始下载
        if (currentImageIndexInPost >= currentPostImages.size()) {
            // 检查是否还有帖子需要处理
            if (currentPostIndex >= itemsToDownload.size()) {
                showCustomSnackbar(rootView, this, "所有图片已下载到Pictures/Venus文件夹");
                exitSelectionMode(); // 下载完成后退出选择模式
                return;
            }
            // 获取下一个帖子
            int position = itemsToDownload.get(currentPostIndex);
            // 确保position有效
            if (imageDataList == null || position < 0 || position >= imageDataList.size()) {
                showCustomSnackbar(rootView, this, "图片数据无效");
                currentPostIndex++;
                currentImageIndexInPost = 0;
                downloadNextImage(); // 继续下载下一张
                return;
            }
            currentPostData = imageDataList.get(position);
            @SuppressWarnings("unchecked")
            List<String> images = (List<String>) currentPostData.get("images");
            // 更新当前帖子的图片列表
            currentPostImages = (images != null) ? images : new ArrayList<>();
            currentImageIndexInPost = 0;
        }

        // 检查当前帖子是否还有图片需要下载
        if (currentImageIndexInPost < currentPostImages.size()) {
            String imageUrl = currentPostImages.get(currentImageIndexInPost);
            String author = (String) currentPostData.get("author");
            String title = (String) currentPostData.get("title");
            String fileName = "venus_" + (title != null ? title.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "_") : "unknown") +
                    "_" + (author != null ? author.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "_") : "unknown") +
                    "_" + System.currentTimeMillis() + "_" + downloadProgress + ".jpg";
            // 使用Glide下载图片
            Glide.with(this)
                    .asBitmap()
                    .load(imageUrl)
                    .into(new CustomTarget<Bitmap>() {
                        @Override
                        public void onResourceReady(@NonNull Bitmap resource, Transition<? super Bitmap> transition) {
                            saveImageToGallery(resource, fileName);
                            downloadProgress++;
                            currentImageIndexInPost++;
                            downloadNextImage();
                        }

                        @Override
                        public void onLoadCleared(Drawable placeholder) {
                            // 加载被清除
                        }

                        @Override
                        public void onLoadFailed(Drawable errorDrawable) {
                            runOnUiThread(() -> show_error_dialog(ImageActivity.this,"图片下载失败: " + imageUrl));
                            downloadProgress++;
                            currentImageIndexInPost++;
                            downloadNextImage();
                        }
                    });
        } else {
            // 当前帖子的图片已下载完毕，处理下一个帖子
            currentPostIndex++;
            downloadNextImage();
        }
    }

    private void saveImageToGallery(Bitmap bitmap, String fileName) {
        try {
            // 获取公共图片目录
            File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
            File venusDir = new File(picturesDir, "Venus");
            if (!venusDir.exists())
                venusDir.mkdirs();

            File imageFile = new File(venusDir, fileName);

            // 保存图片
            OutputStream fos = new FileOutputStream(imageFile);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);
            fos.flush();
            fos.close();
        } catch (IOException e) {
            runOnUiThread(() -> show_error_dialog(ImageActivity.this,"图片保存失败: " + e.getMessage()));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                downloadSelectedImages();
            else
                showCustomSnackbar(rootView, this, "请授予权限以继续下载");
        }
    }

    private void openFullscreenImage(int position) {
        Intent intent = new Intent(ImageActivity.this, FullscreenImageActivity.class);
        Map<String, Object> currentItemData = imageDataList.get(position);
        List<Map<String, Object>> singleItemList = new ArrayList<>();
        singleItemList.add(currentItemData);
        intent.putExtra("imageDataList", (java.io.Serializable) singleItemList);
        intent.putExtra("position", 0);
        startActivity(intent);
    }

    private static class ImageViewHolder extends RecyclerView.ViewHolder {
        ImageView imageView;
        TextView authorTextView;
        TextView descriptionTextView;
        MaterialCardView cardView;
        TextView titleTextView;
        TextView timeTextView;

        public ImageViewHolder(View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.imageView);
            authorTextView = itemView.findViewById(R.id.authorTextView);
            descriptionTextView = itemView.findViewById(R.id.descriptionTextView);
            cardView = (MaterialCardView) itemView;
            titleTextView = itemView.findViewById(R.id.titleTextView);
            timeTextView = itemView.findViewById(R.id.timeTextView);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 释放资源
        if (imageLoader != null) {
            imageLoader.destroy();
        }
    }

    /**
     * 设置TabLayout的选项卡
     *
     * @param tabLayout      TabLayout实例
     * @param startWithDaily 是否从日榜开始
     */
    private void setupTabLayout(TabLayout tabLayout, boolean startWithDaily) {
        tabLayout.removeAllTabs();
        if (!startWithDaily) {
            tabLayout.addTab(tabLayout.newTab().setText("日榜"));
        }
        tabLayout.addTab(tabLayout.newTab().setText("周榜"));
        tabLayout.addTab(tabLayout.newTab().setText("月榜"));
    }
}