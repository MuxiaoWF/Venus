package com.muxiao.Venus.Setting;

import static com.muxiao.Venus.common.Constants.WRITE_PERMISSION_REQUEST_CODE;
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
import android.os.Handler;
import android.os.Looper;
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
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muxiao.Venus.R;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.HeaderManager;
import com.muxiao.Venus.common.MiHoYoBBSConstants;
import com.muxiao.Venus.common.tools;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ImageActivity extends AppCompatActivity {
    private RecyclerView recyclerView; // 图片列表视图
    private List<Map<String, Object>> imageDataList; // 图片数据列表
    private final List<Integer> selectedItems = new ArrayList<>(); // 已选择的项目索引
    private boolean isSelectionMode = false; // 是否处于选择模式
    private MenuItem downloadMenuItem; // 下载菜单项
    private View rootView; // 根视图

    // 下载状态变量
    private int downloadProgress, totalDownloads, currentImageIndexInPost, currentPostIndex;
    private List<Integer> itemsToDownload = new ArrayList<>(); // 待下载项目列表
    private List<String> currentPostImages = new ArrayList<>(); // 当前帖子的图片列表

    // 线程管理
    private ExecutorService executorService; // 线程池
    private final Handler mainHandler = new Handler(Looper.getMainLooper()); // 主线程处理器
    private volatile boolean isLoading = false; // 是否正在加载
    private Map<String, String> lastLoadedParams; // 上次加载的参数

    private HeaderManager headerManager;

    // UI数据缓存
    private String[] forumNames; // 游戏名称数组
    private Map<String, String> currentForumMap = new HashMap<>(); // 当前游戏的分类映射
    private String currentCategoryId = ""; // 当前分类ID
    private boolean isUpdatingInternal = false; // 是否正在内部更新
    private int pendingTabPosition = -1; // 待处理的Tab位置
    private int[] currentListTypes = new int[0]; // 当前可用的榜单类型
    
    // 缓存机制
    private final Map<String, List<Map<String, Object>>> imageDataCache = new HashMap<>(); // 图片数据缓存
    private final Map<String, Long> cacheTimestamps = new HashMap<>(); // 缓存时间戳
    private static final long CACHE_EXPIRY_TIME = 5 * 60 * 1000; // 缓存过期时间5分钟
    private final Map<String, Map<String, String>> forumCache = new HashMap<>(); // 游戏分类缓存
    private final Map<String, int[]> listTypeCache = new HashMap<>(); // 榜单类型缓存
    private final Map<String, Long> forumCacheTimestamps = new HashMap<>(); // 游戏分类缓存时间戳
    private final Map<String, Long> listTypeCacheTimestamps = new HashMap<>(); // 榜单类型缓存时间戳
    private static final long STATIC_CACHE_EXPIRY_TIME = 30 * 60 * 1000; // 静态数据缓存30分钟

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(SettingsFragment.getSelectedTheme(this));
        setContentView(R.layout.activity_image);
        EdgeToEdge.enable(this);

        // 初始化基础组件
        rootView = findViewById(android.R.id.content);
        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new GridLayoutManager(this, 2)); // 2列网格布局
        executorService = Executors.newFixedThreadPool(4); // 4个线程的线程池

        // 初始化 HeaderManager
        headerManager = new HeaderManager(this);

        // 设置UI组件
        forumNames = new String[]{"原神", "星铁", "崩坏3", "绝区零", "未定事件簿", "崩坏2", "大别野"};
        MaterialAutoCompleteTextView forumSelector = findViewById(R.id.forumSelector);
        forumSelector.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, forumNames));

        // 设置监听器和默认数据
        setupListenersAndLoadData(forumSelector);

        // 设置ActionBar
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("图片浏览");
        }

        // 返回键处理
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isSelectionMode) exitSelectionMode();
                else finish();
            }
        });
    }

    /**
     * 设置所有监听器并加载默认数据
     */
    private void setupListenersAndLoadData(MaterialAutoCompleteTextView forumSelector) {
        MaterialAutoCompleteTextView cateSelector = findViewById(R.id.cateIdSelector);
        TabLayout tabLayout = findViewById(R.id.tabLayout);

        // 游戏选择监听
        forumSelector.setOnItemClickListener((parent, view, position, id) -> {
            if (isUpdatingInternal) return;
            isUpdatingInternal = true;
            String selectedForum = forumNames[position];
            forumSelector.setText(selectedForum, false);
            handleForumSelection(selectedForum, cateSelector, tabLayout);
        });

        // 分类选择监听
        cateSelector.setOnItemClickListener((parent, view, position, id) -> {
            if (isUpdatingInternal) return;
            isUpdatingInternal = true;
            String selectedCategory = cateSelector.getText().toString();
            cateSelector.setText(selectedCategory, false);
            currentCategoryId = currentForumMap.get(selectedCategory);
            if (currentCategoryId != null) {
                handleCategorySelection(currentCategoryId, tabLayout);
            } else {
                isUpdatingInternal = false;
            }
        });

        // Tab选择监听
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                if (isUpdatingInternal || isLoading) {
                    pendingTabPosition = tab.getPosition();
                    return;
                }
                Map<String, String> params = new HashMap<>() {{
                    put("forum_id", currentCategoryId);
                    put("type", String.valueOf(currentListTypes[tab.getPosition()]));
                }};
                loadImages(params);
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        // 加载默认数据
        isUpdatingInternal = true;
        forumSelector.setText(forumNames[0], false);
        handleForumSelection(forumNames[0], cateSelector, tabLayout);
    }

    /**
     * 处理游戏选择
     */
    private void handleForumSelection(String forumName, MaterialAutoCompleteTextView cateSelector, TabLayout tabLayout) {
        showCustomSnackbar(rootView, this, "加载类型数据中...");
        
        // 检查游戏分类缓存
        String forumCacheKey = "forum_" + forumName;
        if (isStaticCacheValid(forumCacheKey, forumCacheTimestamps)) {
            Map<String, String> cachedForumMap = forumCache.get(forumCacheKey);
            mainHandler.post(() -> {
                currentForumMap = cachedForumMap;
                setupCategorySelector(cateSelector, tabLayout);
                isUpdatingInternal = false;
            });
            return;
        }
        
        executorService.submit(() -> {
            Map<String, String> forumMap = getForum(forumName);
            if (forumMap.isEmpty()) {
                mainHandler.post(() -> {
                    show_error_dialog(ImageActivity.this, "获取类型失败");
                    isUpdatingInternal = false;
                });
                return;
            }

            // 缓存游戏分类数据
            cacheStaticData(forumCacheKey, forumMap, forumCache, forumCacheTimestamps);
            
            mainHandler.post(() -> {
                currentForumMap = forumMap;
                setupCategorySelector(cateSelector, tabLayout);
                isUpdatingInternal = false;
            });
        });
    }

    /**
     * 设置分类选择器
     */
    private void setupCategorySelector(MaterialAutoCompleteTextView cateSelector, TabLayout tabLayout) {
        String[] categories = currentForumMap.keySet().toArray(new String[0]);
        cateSelector.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, categories));

        String firstCategory = currentForumMap.keySet().iterator().next();
        cateSelector.setText(firstCategory, false);
        currentCategoryId = currentForumMap.get(firstCategory);
        handleCategorySelection(currentCategoryId, tabLayout);
    }

    /**
     * 处理分类选择
     */
    private void handleCategorySelection(String categoryId, TabLayout tabLayout) {
        showCustomSnackbar(rootView, this, "加载榜单分类数据中...");
        
        // 检查榜单类型缓存
        String listTypeCacheKey = "listType_" + categoryId;
        if (isStaticCacheValid(listTypeCacheKey, listTypeCacheTimestamps)) {
            int[] cachedListTypes = listTypeCache.get(listTypeCacheKey);
            mainHandler.post(() -> {
                setupTabLayout(Objects.requireNonNull(cachedListTypes), tabLayout, categoryId);
                isUpdatingInternal = false;
            });
            return;
        }
        
        executorService.submit(() -> {
            int[] listTypes = getListType(categoryId);
            
            // 缓存榜单类型数据
            cacheStaticData(listTypeCacheKey, listTypes, listTypeCache, listTypeCacheTimestamps);
            
            mainHandler.post(() -> {
                setupTabLayout(listTypes, tabLayout, categoryId);
                isUpdatingInternal = false;
            });
        });
    }

    /**
     * 设置Tab布局
     */
    private void setupTabLayout(int[] listTypes, TabLayout tabLayout, String categoryId) {
        currentListTypes = listTypes;
        tabLayout.removeAllTabs();
        for (int serverType : listTypes) {
            switch (serverType) {
                case 1:
                    tabLayout.addTab(tabLayout.newTab().setText("日榜"));
                    break;
                case 2:
                    tabLayout.addTab(tabLayout.newTab().setText("周榜"));
                    break;
                case 3:
                    tabLayout.addTab(tabLayout.newTab().setText("月榜"));
                    break;
                default:
                    tabLayout.addTab(tabLayout.newTab().setText("热门"));
                    break;
            }
        }
        if (tabLayout.getTabCount() > 0) {
            int targetPosition = Math.max(pendingTabPosition, 0);
            pendingTabPosition = -1;
            TabLayout.Tab targetTab = tabLayout.getTabAt(targetPosition);
            Objects.requireNonNull(targetTab).select();
            Map<String, String> params = new HashMap<>() {{
                put("forum_id", categoryId);
                put("type", String.valueOf(listTypes[targetPosition]));
            }};
            loadImages(params);
        }
    }

    /**
     * 加载图片数据
     */
    private void loadImages(Map<String, String> params) {
        if (params.equals(lastLoadedParams) || isLoading) return;

        isLoading = true;
        lastLoadedParams = params;
        
        // 生成缓存键并检查缓存
        String cacheKey = generateCacheKey(params);
        if (isCacheValid(cacheKey)) {
            imageDataList = imageDataCache.get(cacheKey);
            mainHandler.post(() -> {
                recyclerView.setAdapter(new ImageAdapter(imageDataList));
                isLoading = false;
            });
            return;
        }

        showCustomSnackbar(rootView, this, "加载中...");

        executorService.execute(() -> {
            try {
                if (Objects.equals(params.get("type"), "4")) {
                    // 热门图片加载
                    Map<String, String> headers = headerManager.get_images_headers();
                    params.remove("type");
                    params.put("page", "1");
                    params.put("last_id", "");
                    params.put("page_size", "60");
                    String jsonResponse = tools.sendGetRequest(Constants.Urls.BBS_GAME_HOT_POST_LIST_URL, headers, params);
                    imageDataList = parseImageData(jsonResponse);
                } else {
                    // 普通图片加载
                    Map<String, String> headers = headerManager.get_images_headers();
                    params.put("cate_id", "0");
                    params.put("last_id", "");
                    params.put("page_size", "60");
                    String jsonResponse = tools.sendGetRequest(Constants.Urls.BBS_IMAGE_URL, headers, params);
                    imageDataList = parseImageData(jsonResponse);
                }
                
                // 缓存数据
                cacheData(cacheKey, imageDataList);
                
                // 在主线程更新UI
                mainHandler.post(() -> {
                    recyclerView.setAdapter(new ImageAdapter(imageDataList));
                    isLoading = false;
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    show_error_dialog(ImageActivity.this, "加载失败: " + e.getMessage());
                    isLoading = false;
                });
            }
        });
    }

    /**
     * 解析图片数据
     */
    private List<Map<String, Object>> parseImageData(String jsonResponse) {
        List<Map<String, Object>> imagePosts = new ArrayList<>();
        JsonObject jsonObject = JsonParser.parseString(jsonResponse).getAsJsonObject();
        if (jsonObject.has("data") && !jsonObject.get("data").isJsonNull()) {
            JsonObject data = jsonObject.getAsJsonObject("data");
            if (data.has("list") && !data.get("list").isJsonNull()) {
                JsonArray list = data.getAsJsonArray("list");
                for (JsonElement element : list) {
                    JsonObject itemObject = element.getAsJsonObject();
                    // 获取post对象
                    JsonObject postObject = itemObject.has("post") ? itemObject.getAsJsonObject("post") : new JsonObject();
                    // 获取user对象
                    JsonObject userObject = itemObject.has("user") ? itemObject.getAsJsonObject("user") : new JsonObject();
                    // 创建用于存储帖子信息的Map
                    Map<String, Object> postData = new HashMap<>();

                    // 提取标题信息
                    String title = "";
                    if (postObject.has("subject") && !postObject.get("subject").isJsonNull())
                        title = postObject.get("subject").getAsString();
                    // 提取作者信息
                    String author = "未知作者";
                    if (userObject.has("nickname") && !userObject.get("nickname").isJsonNull())
                        author = userObject.get("nickname").getAsString();
                    // 提取基础信息
                    String cover = "";
                    if (postObject.has("cover") && !postObject.get("cover").isJsonNull())
                        cover = postObject.get("cover").getAsString();
                    long createdAt = 0;
                    if (postObject.has("created_at") && !postObject.get("created_at").isJsonNull())
                        createdAt = postObject.get("created_at").getAsLong();
                    // 提取描述信息
                    String description = "";
                    if (postObject.has("content") && !postObject.get("content").isJsonNull()) {
                        String contentStr = postObject.get("content").getAsString();
                        try {
                            JsonObject content = JsonParser.parseString(contentStr).getAsJsonObject();
                            if (content.has("describe") && !content.get("describe").isJsonNull()) {
                                description = content.get("describe").getAsString();
                                // 去除换行符
                                description = description.replace("\n", "").replace("\r", "");
                                // 如果describe中包含"作品描述"字段，则切分取后段
                                if (description.contains("作品描述：")) {
                                    String[] parts = description.split("作品描述：");
                                    description = parts.length > 1 ? parts[1].trim() : "";
                                } else if (description.contains("作品描述:")) {
                                    String[] parts = description.split("作品描述:");
                                    description = parts.length > 1 ? parts[1].trim() : "";
                                } else if (description.contains("作品描述")) {
                                    String[] parts = description.split("作品描述");
                                    description = parts.length > 1 ? parts[1].trim() : "";
                                } else {
                                    description = "";
                                }
                            }
                        } catch (Exception e) {
                            // 如果content不是JSON对象，则直接使用整个内容作为描述
                            description = contentStr;
                        }
                    }

                    // 提取图片URL列表
                    List<String> imageUrls = new ArrayList<>();
                    // 尝试从images字段获取
                    if (postObject.has("images") && !postObject.get("images").isJsonNull()) {
                        JsonArray postImages = postObject.getAsJsonArray("images");
                        for (JsonElement imgElement : postImages)
                            imageUrls.add(imgElement.getAsString());
                    }
                    // 如果没有图片，尝试从image_list字段获取
                    if (imageUrls.isEmpty() && itemObject.has("image_list") && !itemObject.get("image_list").isJsonNull()) {
                        JsonArray imageList = itemObject.getAsJsonArray("image_list");
                        for (JsonElement imageElement : imageList) {
                            JsonObject imageObj = imageElement.getAsJsonObject();
                            if (imageObj.has("url") && !imageObj.get("url").isJsonNull())
                                imageUrls.add(imageObj.get("url").getAsString());
                        }
                    }
                    // 如果仍然没有图片，使用cover作为唯一图片
                    if (imageUrls.isEmpty() && !cover.isEmpty())
                        imageUrls.add(cover);
                    // 将数据放入Map中
                    postData.put("title", title);
                    postData.put("description", description);
                    postData.put("author", author);
                    postData.put("timestamp", createdAt);
                    postData.put("images", imageUrls);
                    postData.put("cover", cover);
                    imagePosts.add(postData);
                }
            }
        }
        return imagePosts;
    }

    /**
     * 下载图片
     */
    private void downloadImage() {
        if (downloadProgress >= totalDownloads || itemsToDownload.isEmpty()) {
            showCustomSnackbar(rootView, this, "下载完成");
            exitSelectionMode();
            return;
        }
        // 准备下一张图片
        if (currentImageIndexInPost >= currentPostImages.size()) {
            if (currentPostIndex >= itemsToDownload.size()) return;

            int position = itemsToDownload.get(currentPostIndex);
            Map<String, Object> currentPostData = imageDataList.get(position);
            @SuppressWarnings("unchecked")
            List<String> t = (List<String>) currentPostData.get("images");
            currentPostImages = t;
            currentImageIndexInPost = 0;
        }

        String imageUrl = Objects.requireNonNull(currentPostImages).get(currentImageIndexInPost);
        String fileName = "venus_" + System.currentTimeMillis() + "_" + downloadProgress + ".jpg";

        Glide.with(this).asBitmap().load(imageUrl).into(new CustomTarget<Bitmap>() {
            @Override
            public void onResourceReady(@NonNull Bitmap resource, Transition<? super Bitmap> transition) {
                executorService.submit(() -> {
                    saveImageToGallery(resource, fileName);
                    mainHandler.post(() -> {
                        downloadProgress++;
                        currentImageIndexInPost++;
                        downloadImage();
                    });
                });
            }

            @Override
            public void onLoadCleared(Drawable p) {
            }

            @Override
            public void onLoadFailed(Drawable e) {
                downloadProgress++;
                currentImageIndexInPost++;
                downloadImage();
            }
        });
    }

    /**
     * 保存图片到相册
     */
    private void saveImageToGallery(Bitmap bitmap, String fileName) {
        try {
            File venusDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Venus");
            if (!venusDir.exists()) venusDir.mkdirs();
            try (OutputStream fos = new FileOutputStream(new File(venusDir, fileName))) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);
            }
        } catch (IOException e) {
            show_error_dialog(this, "保存图片失败：" + e.getMessage());
        }
    }

    /**
     * 获取游戏论坛代号
     */
    private Map<String, String> getForum(String forumName) {
        try {
            Map<String, String> lists = new HashMap<>();
            Map<String, String> forums_id_headers = headerManager.get_forums_id();
            Map<String, String> params = new HashMap<>();
            params.put("gids", Objects.requireNonNull(MiHoYoBBSConstants.name_to_forum_id(forumName)).get("id"));
            params.put("version", "3");
            String response = tools.sendGetRequest(Constants.Urls.BBS_GAME_FORUM, forums_id_headers, params);
            JsonObject res = JsonParser.parseString(response).getAsJsonObject();
            if (res.get("retcode").getAsInt() != 0) return lists;
            JsonArray forums = res.getAsJsonObject("data").getAsJsonObject("discussion").getAsJsonArray("forums");
            for (JsonElement forum : forums) {
                JsonObject obj = forum.getAsJsonObject();
                String name = obj.get("name").getAsString();
                if (name.equals("同人图") || name.equals("COS"))
                    lists.put(name, obj.get("id").getAsString());
            }
            return lists;
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    /**
     * 获取是否含有日榜、周榜、月榜或热榜
     */
    private int[] getListType(String categoryId) {
        try {
            String response = tools.sendGetRequest(Constants.Urls.BBS_GAME_FORUM_POST_LIST_TYPE_URL, headerManager.get_forums_id(), Map.of("forum_id", categoryId));
            JsonArray listArray = JsonParser.parseString(response).getAsJsonObject().getAsJsonObject("data").getAsJsonArray("list");
            int[] result = new int[listArray.size()];
            for (int i = 0; i < listArray.size(); i++) result[i] = listArray.get(i).getAsInt();
            if (result.length == 0) result = new int[]{4};
            return result;
        } catch (Exception e) {
            return new int[0];
        }
    }

    /**
     * 退出长按选择下载模式
     */
    private void exitSelectionMode() {
        isSelectionMode = false;
        downloadMenuItem.setVisible(false);
        selectedItems.clear();
        if (recyclerView.getAdapter() != null)
            recyclerView.getAdapter().notifyItemRangeChanged(0, recyclerView.getAdapter().getItemCount());
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("图片浏览");
            getSupportActionBar().setHomeAsUpIndicator(null);
        }
    }

    /**
     * 创建顶栏的选项菜单
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.image_selection_menu, menu);
        downloadMenuItem = menu.findItem(R.id.action_download);
        downloadMenuItem.setVisible(false);
        return true;
    }

    /**
     * 处理顶栏的选项菜单点击事件
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_download) {
            if (!selectedItems.isEmpty()) {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                        ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this,
                            new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                            WRITE_PERMISSION_REQUEST_CODE);
                } else {
                    // 开始下载
                    itemsToDownload = new ArrayList<>(selectedItems);
                    totalDownloads = 0;
                    for (int pos : itemsToDownload) {
                        @SuppressWarnings("unchecked")
                        List<String> imgs = (List<String>) imageDataList.get(pos).get("images");
                        if (imgs != null) totalDownloads += imgs.size();
                    }
                    downloadProgress = 0;
                    currentPostIndex = 0;
                    currentImageIndexInPost = 0;
                    downloadImage();
                }
            }
            return true;
        }

        if (item.getItemId() == android.R.id.home) {
            if (isSelectionMode) exitSelectionMode();
            else finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executorService != null) executorService.shutdown();
        mainHandler.removeCallbacksAndMessages(null);
        // 清理所有缓存
        clearAllCache();
    }

    /**
     * 生成缓存键
     */
    private String generateCacheKey(Map<String, String> params) {
        StringBuilder keyBuilder = new StringBuilder();
        List<Map.Entry<String, String>> entries = new ArrayList<>(params.entrySet());
        Collections.sort(entries, (o1, o2) -> o1.getKey().compareTo(o2.getKey()));
        // 遍历添加
        for (Map.Entry<String, String> entry : entries)
            keyBuilder.append(entry.getKey()).append("=")
                    .append(entry.getValue()).append("&");
        return keyBuilder.toString();
    }

    /**
     * 检查缓存是否有效
     */
    private boolean isCacheValid(String cacheKey) {
        if (!imageDataCache.containsKey(cacheKey)) return false;
        Long timestamp = cacheTimestamps.get(cacheKey);
        if (timestamp == null) return false;
        return (System.currentTimeMillis() - timestamp) < CACHE_EXPIRY_TIME;
    }

    /**
     * 缓存数据
     */
    private void cacheData(String cacheKey, List<Map<String, Object>> data) {
        imageDataCache.put(cacheKey, new ArrayList<>(data));
        cacheTimestamps.put(cacheKey, System.currentTimeMillis());
    }

    /**
     * 检查静态缓存是否有效
     */
    private boolean isStaticCacheValid(String cacheKey, Map<String, Long> timestampMap) {
        if (!timestampMap.containsKey(cacheKey)) return false;
        Long timestamp = timestampMap.get(cacheKey);
        if (timestamp == null) return false;
        return (System.currentTimeMillis() - timestamp) < STATIC_CACHE_EXPIRY_TIME;
    }

    /**
     * 缓存静态数据
     */
    private <T> void cacheStaticData(String cacheKey, T data, Map<String, T> cacheMap, Map<String, Long> timestampMap) {
        cacheMap.put(cacheKey, data);
        timestampMap.put(cacheKey, System.currentTimeMillis());
    }

    /**
     * 清理所有缓存
     */
    private void clearAllCache() {
        // 清理图片数据缓存
        imageDataCache.clear();
        cacheTimestamps.clear();
        
        // 清理静态数据缓存
        forumCache.clear();
        listTypeCache.clear();
        forumCacheTimestamps.clear();
        listTypeCacheTimestamps.clear();
    }

    /**
     * 图片视图持有者
     */
    private static class ImageViewHolder extends RecyclerView.ViewHolder {
        private final ImageView imageView; // 图片视图
        private final TextView authorTextView, titleTextView; // 作者和标题文本
        private final MaterialCardView cardView; // 卡片视图

        public ImageViewHolder(View v) {
            super(v);
            imageView = v.findViewById(R.id.imageView);
            authorTextView = v.findViewById(R.id.authorTextView);
            titleTextView = v.findViewById(R.id.titleTextView);
            cardView = (MaterialCardView) v;
        }
    }

    /**
     * 图片适配器
     */
    private class ImageAdapter extends RecyclerView.Adapter<ImageViewHolder> {
        private final List<Map<String, Object>> mData; // 数据源

        public ImageAdapter(List<Map<String, Object>> data) {
            this.mData = data;
        }

        @NonNull
        @Override
        public ImageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ImageViewHolder(getLayoutInflater().inflate(R.layout.item_image, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ImageViewHolder holder, int position) {
            // 绑定图片数据
            Map<String, Object> item = mData.get(position);
            @SuppressWarnings("unchecked")
            List<String> images = (List<String>) item.get("images");

            if (images != null && !images.isEmpty()) {
                Glide.with(holder.itemView.getContext())
                        .load(images.get(0))
                        .placeholder(R.drawable.ic_loading)
                        .error(R.drawable.ic_error)
                        .into(holder.imageView);
            } else {
                Glide.with(holder.itemView.getContext()).clear(holder.imageView);
                holder.imageView.setImageResource(R.drawable.ic_error);
            }

            holder.titleTextView.setText((String) item.get("title"));
            holder.authorTextView.setText((String) item.get("author"));

            // 更新选择状态
            if (selectedItems.contains(position)) {
                holder.cardView.setStrokeWidth(8);
                holder.cardView.setStrokeColor(ContextCompat.getColor(ImageActivity.this, R.color.blue_theme_primary));
            } else {
                holder.cardView.setStrokeWidth(0);
            }

            // 设置点击事件
            holder.itemView.setOnClickListener(v -> {
                int curPos = holder.getBindingAdapterPosition();
                if (curPos == RecyclerView.NO_POSITION) return;

                if (isSelectionMode) {
                    toggleSelection(curPos);
                } else {
                    Intent intent = new Intent(ImageActivity.this, FullscreenImageActivity.class);
                    intent.putExtra("imageDataListJson", new Gson().toJson(List.of(mData.get(curPos))));
                    intent.putExtra("position", 0);
                    startActivity(intent);
                }
            });

            holder.itemView.setOnLongClickListener(v -> {
                int curPos = holder.getBindingAdapterPosition();
                if (curPos == RecyclerView.NO_POSITION) return false;

                if (!isSelectionMode) {
                    isSelectionMode = true;
                    downloadMenuItem.setVisible(true);
                    if (getSupportActionBar() != null)
                        getSupportActionBar().setHomeAsUpIndicator(R.drawable.ic_error);
                }
                toggleSelection(curPos);
                return true;
            });
        }

        @Override
        public void onViewRecycled(@NonNull ImageViewHolder holder) {
            super.onViewRecycled(holder);
            Glide.with(holder.itemView.getContext()).clear(holder.imageView);
        }

        @Override
        public int getItemCount() {
            return mData != null ? mData.size() : 0;
        }

        /**
         * 切换选择状态
         */
        private void toggleSelection(int position) {
            if (selectedItems.contains(position))
                selectedItems.remove(Integer.valueOf(position));
            else
                selectedItems.add(position);

            notifyItemChanged(position);

            if (selectedItems.isEmpty())
                exitSelectionMode();
            else if (getSupportActionBar() != null)
                getSupportActionBar().setTitle(selectedItems.size() + " 项已选择");
        }
    }
}
