package com.muxiao.Venus.Setting;

import dagger.hilt.android.AndroidEntryPoint;

import com.muxiao.Venus.BaseActivity;

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
import android.view.MotionEvent;
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
import androidx.core.app.ActivityCompat;
import androidx.core.app.ActivityOptionsCompat;
import androidx.core.content.ContextCompat;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 图片浏览页：从米游社API加载图片列表（日榜/周榜/月榜/热门/同人/COS），
 * 支持下载保存到相册和全屏查看。
 */
@AndroidEntryPoint
public class ImageActivity extends BaseActivity {
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
    private CustomTarget<Bitmap> downloadTarget; // 当前下载用的 Glide 目标，便于 onDestroy 清理（P2-5）
    private final Handler mainHandler = new Handler(Looper.getMainLooper()); // 主线程处理器
    private boolean mDestroyed = false; // onDestroy 后置位，阻止向已关闭的线程池提交任务

    /**
     * 安全的线程池提交：仅当 Activity 未销毁且线程池仍可用时提交，
     * 否则直接丢弃。避免 onDestroy() 已 shutdown 线程池后，仍在排队的
     * mainHandler 回调尝试提交任务而抛出 RejectedExecutionException 导致崩溃。
     */
    private void safeSubmit(Runnable task) {
        if (mDestroyed || executorService == null || executorService.isShutdown()) return;
        try {
            executorService.submit(task);
        } catch (java.util.concurrent.RejectedExecutionException ignore) {
            // 极端竞态下仍可能被拒，直接忽略（任务非关键）
        }
    }

    /** 同 {@link #safeSubmit(Runnable)}，用于 execute 提交。 */
    private void safeExecute(Runnable task) {
        if (mDestroyed || executorService == null || executorService.isShutdown()) return;
        try {
            executorService.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException ignore) {
            // 极端竞态下仍可能被拒，直接忽略（任务非关键）
        }
    }
    private volatile boolean isLoading = false; // 是否正在加载
    private Map<String, String> lastLoadedParams; // 上次加载的参数

    private HeaderManager headerManager;

    // UI数据缓存
    private String[] forumNames; // 游戏内部标识（用于API调用）
    private String[] forumDisplayNames; // 游戏显示名称（已翻译）
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
        SettingsFragment.applyAppTheme(this);
        setContentView(R.layout.activity_image);
        EdgeToEdge.enable(this);

        // 初始化基础组件
        rootView = findViewById(android.R.id.content);
        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new GridLayoutManager(this, 2)); // 2列网格布局
        executorService = Executors.newFixedThreadPool(4); // 4个线程的线程池

        // 初始化 HeaderManager（榜单图始终使用国服API）
        headerManager = new HeaderManager(this, false);

        // 设置UI组件
        forumNames = new String[]{"原神", "星铁", "崩坏3", "绝区零", "未定事件簿", "崩坏2", "大别野"};
        forumDisplayNames = new String[]{
                getString(R.string.genshin_impact),
                getString(R.string.star_rail),
                getString(R.string.honkai_impact_3),
                getString(R.string.zenless_zone_zero),
                getString(R.string.tears_of_themis),
                getString(R.string.honkai_impact_2),
                getString(R.string.dabieye)
        };
        MaterialAutoCompleteTextView forumSelector = findViewById(R.id.forumSelector);
        forumSelector.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, forumDisplayNames));

        // 设置监听器和默认数据
        setupListenersAndLoadData(forumSelector);

        // 设置ActionBar
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(getString(R.string.toolbar_image_browse));
        }

        // 处理状态栏内边距
        ViewCompat.setOnApplyWindowInsetsListener((View) toolbar.getParent(), (v, insets) -> {
            int statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(v.getPaddingLeft(), statusBarHeight,
                    v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });

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
     * 设置游戏/分类下拉框与榜单 Tab 的点击监听，末尾默认加载首个游戏的榜单数据。
     * 借助 isUpdatingInternal 标记避免监听器之间的联动重复触发加载。
     */
    private void setupListenersAndLoadData(MaterialAutoCompleteTextView forumSelector) {
        MaterialAutoCompleteTextView cateSelector = findViewById(R.id.cateIdSelector);
        TabLayout tabLayout = findViewById(R.id.tabLayout);

        // 游戏选择监听
        forumSelector.setOnItemClickListener((parent, view, position, id) -> {
            if (isUpdatingInternal) return;
            isUpdatingInternal = true;
            String selectedForum = forumNames[position];
            forumSelector.setText(forumDisplayNames[position], false);
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
                Map<String, String> params = new HashMap<>();
                params.put("forum_id", currentCategoryId);
                params.put("type", String.valueOf(currentListTypes[tab.getPosition()]));
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
        forumSelector.setText(forumDisplayNames[0], false);
        handleForumSelection(forumNames[0], cateSelector, tabLayout);
    }

    /**
     * 处理游戏（forum）切换：优先读分类静态缓存，否则异步请求论坛分类映射（同人图/COS），
     * 成功后刷新分类选择器。forumName 为游戏内部中文标识。
     */
    private void handleForumSelection(String forumName, MaterialAutoCompleteTextView cateSelector, TabLayout tabLayout) {
        showCustomSnackbar(rootView, this, getString(R.string.snack_loading_types));

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

        safeSubmit(() -> {
            Map<String, String> forumMap = getForum(forumName);
            if (forumMap.isEmpty()) {
                mainHandler.post(() -> {
                    if (mDestroyed) return;
                    show_error_dialog(ImageActivity.this, getString(R.string.err_get_type_failed));
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
     * 用当前游戏的分类映射填充分类下拉框，默认选中首个分类并触发其榜单类型加载与 Tab 构建。
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
     * 处理分类（cate_id）切换：优先读榜单类型静态缓存，否则异步请求该分类可用榜单类型
     * （日/周/月/热），成功后构建 Tab 布局。categoryId 来自分类映射表。
     */
    private void handleCategorySelection(String categoryId, TabLayout tabLayout) {
        showCustomSnackbar(rootView, this, getString(R.string.snack_loading_ranking_categories));

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

        safeSubmit(() -> {
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
     * 用榜单类型数组构建 Tab（1=日榜，2=周榜，3=月榜，其他=热榜），
     * 并按 pendingTabPosition 选中目标 Tab 后自动加载对应榜单图片。
     */
    private void setupTabLayout(int[] listTypes, TabLayout tabLayout, String categoryId) {
        currentListTypes = listTypes;
        tabLayout.removeAllTabs();
        for (int serverType : listTypes) {
            switch (serverType) {
                case 1:
                    tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.image_tab_daily)));
                    break;
                case 2:
                    tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.image_tab_weekly)));
                    break;
                case 3:
                    tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.image_tab_monthly)));
                    break;
                default:
                    tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.image_tab_hot)));
                    break;
            }
        }
        if (tabLayout.getTabCount() > 0) {
            int targetPosition = Math.min(Math.max(pendingTabPosition, 0), listTypes.length - 1);
            pendingTabPosition = -1;
            TabLayout.Tab targetTab = tabLayout.getTabAt(targetPosition);
            Objects.requireNonNull(targetTab).select();
            Map<String, String> params = new HashMap<>();
            params.put("forum_id", categoryId);
            params.put("type", String.valueOf(listTypes[targetPosition]));
            loadImages(params);
        }
    }

    /**
     * 加载指定参数的图片列表：参数与上次相同、正在加载或命中缓存时直接复用。
     * type=4 走热门接口，其余走常规榜单接口；结果在主线程更新 RecyclerView。
     */
    private void loadImages(Map<String, String> params) {
        if (params.equals(lastLoadedParams) || isLoading) return;

        isLoading = true;
        lastLoadedParams = new HashMap<>(params);

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

        showCustomSnackbar(rootView, this, getString(R.string.snack_loading));

        safeExecute(() -> {
            try {
                // 防御性拷贝，避免修改调用方的 Map
                Map<String, String> taskParams = new HashMap<>(params);
                if (Objects.equals(taskParams.get("type"), "4")) {
                    // 热门图片加载
                    Map<String, String> headers = headerManager.get_images_headers();
                    taskParams.remove("type");
                    taskParams.put("page", "1");
                    taskParams.put("last_id", "");
                    taskParams.put("page_size", "60");
                    String jsonResponse = tools.sendGetRequest(Constants.Urls.BBS_GAME_HOT_POST_LIST_URL, headers, taskParams);
                    imageDataList = parseImageData(jsonResponse);
                } else {
                    // 普通图片加载
                    Map<String, String> headers = headerManager.get_images_headers();
                    taskParams.put("cate_id", "0");
                    taskParams.put("last_id", "");
                    taskParams.put("page_size", "60");
                    String jsonResponse = tools.sendGetRequest(Constants.Urls.BBS_IMAGE_URL, headers, taskParams);
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
                    // Activity 已销毁（用户已退出图片页但后台请求才回）时，
                    // window token 失效，弹 Dialog 会抛 BadTokenException，必须跳过。
                    if (mDestroyed) {
                        isLoading = false;
                        return;
                    }
                    show_error_dialog(ImageActivity.this, getString(R.string.err_load_failed, e.getMessage()));
                    isLoading = false;
                });
            }
        });
    }

    /**
     * 解析米游社帖子列表 JSON，提取每帖的标题、作者、封面、时间戳、描述与图片 URL 列表。
     * 描述优先取 content.describe 并按「作品描述」字样裁剪；图片依次尝试 images/image_list/cover 兜底。
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
                    String author = getString(R.string.image_unknown_author);
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
     * 递归串行下载所有已选帖子的图片到相册：逐帖子、逐张下载，进度累加后自我调用下一轮；
     * 完成或列表耗尽时提示并退出选择模式。downloadTarget 便于 onDestroy 时取消。
     */
    private void downloadImage() {
        if (downloadProgress >= totalDownloads || itemsToDownload.isEmpty()) {
            showCustomSnackbar(rootView, this, getString(R.string.snack_download_complete));
            exitSelectionMode();
            return;
        }
        // 定位下一个「确有图片」的帖子。itemsToDownload 中可能存在 images 缺失的条目：
        // totalDownloads 累加时已跳过它们，但索引仍留在队列里，原实现会把 null 赋给
        // currentPostImages，并在下一行 Objects.requireNonNull 处抛 NPE。
        while (currentImageIndexInPost >= currentPostImages.size()) {
            if (currentPostIndex >= itemsToDownload.size()) {
                // 队列已耗尽但仍有未完成计数（数据异常）：直接收尾，避免死循环
                showCustomSnackbar(rootView, this, getString(R.string.snack_download_complete));
                exitSelectionMode();
                return;
            }
            int position = itemsToDownload.get(currentPostIndex++);
            Object rawImages = (imageDataList != null && position >= 0 && position < imageDataList.size())
                    ? imageDataList.get(position).get("images") : null;
            @SuppressWarnings("unchecked")
            List<String> images = rawImages instanceof List ? (List<String>) rawImages : null;
            currentPostImages = images != null ? images : new ArrayList<>();
            currentImageIndexInPost = 0;
        }

        String imageUrl = Objects.requireNonNull(currentPostImages).get(currentImageIndexInPost);
        String fileName = "venus_" + System.currentTimeMillis() + "_" + downloadProgress + ".jpg";

        downloadTarget = new CustomTarget<>() {
            @Override
            public void onResourceReady(@NonNull Bitmap resource, Transition<? super Bitmap> transition) {
                safeSubmit(() -> {
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
                downloadTarget = null;
            }

            @Override
            public void onLoadFailed(Drawable e) {
                downloadTarget = null;
                downloadProgress++;
                currentImageIndexInPost++;
                downloadImage();
            }
        };
        Glide.with(this).asBitmap().load(imageUrl).into(downloadTarget);
    }

    // 将 Bitmap 持久化到系统相册，失败时弹错误框。
    private void saveImageToGallery(Bitmap bitmap, String fileName) {
        try {
            tools.saveBitmapToGallery(this, bitmap, fileName);
        } catch (IOException e) {
            if (mDestroyed) return;
            show_error_dialog(this, getString(R.string.err_save_image_failed, e.getMessage()));
        }
    }

    /**
     * 请求指定游戏的论坛分类，仅保留「同人图」与「COS」两类并返回其 id 映射；
     * 失败时返回空 Map（调用方据此重试或弹错）。
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
     * 请求指定分类可用的榜单类型，返回整型数组（1/2/3/4 分别代表日/周/月/热榜），
     * 空结果或异常统一降级为 [4]（热门）。
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
            return new int[]{4};
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
            getSupportActionBar().setTitle(getString(R.string.toolbar_image_browse));
            getSupportActionBar().setHomeAsUpIndicator(null);
        }
    }

    /**
     * 展开图片选择菜单：默认隐藏下载项，仅在长按进入选择模式后显示。
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

    // 释放资源：取消进行中的 Glide 下载、关闭线程池、移除主线程回调并清空所有缓存。
    @Override
    protected void onDestroy() {
        mDestroyed = true; // 先于清理置位，确保所有排队的回调/任务不再向线程池提交
        mainHandler.removeCallbacksAndMessages(null);
        if (downloadTarget != null) {
            Glide.with(this).clear(downloadTarget);
            downloadTarget = null;
        }
        if (executorService != null) {
            executorService.shutdownNow(); // 中断进行中的任务，避免泄漏
            executorService = null;
        }
        // 清理所有缓存
        clearAllCache();
        super.onDestroy();
    }

    /**
     * 将请求参数按 key 字典序拼接为稳定的字符串缓存键，确保相同参数（顺序无关）命中同一缓存。
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
     * 判断图片列表缓存是否有效：存在且距写入时间未超过 CACHE_EXPIRY_TIME（5 分钟）。
     */
    private boolean isCacheValid(String cacheKey) {
        if (!imageDataCache.containsKey(cacheKey)) return false;
        Long timestamp = cacheTimestamps.get(cacheKey);
        if (timestamp == null) return false;
        return (System.currentTimeMillis() - timestamp) < CACHE_EXPIRY_TIME;
    }

    /**
     * 写入图片列表缓存（深拷贝列表）并记录当前时间戳。
     */
    private void cacheData(String cacheKey, List<Map<String, Object>> data) {
        imageDataCache.put(cacheKey, new ArrayList<>(data));
        cacheTimestamps.put(cacheKey, System.currentTimeMillis());
    }

    /**
     * 判断静态数据（分类/榜单类型）缓存是否有效，有效期 STATIC_CACHE_EXPIRY_TIME（30 分钟）；
     * timestampMap 与对应 cacheMap 成对使用。
     */
    private boolean isStaticCacheValid(String cacheKey, Map<String, Long> timestampMap) {
        if (!timestampMap.containsKey(cacheKey)) return false;
        Long timestamp = timestampMap.get(cacheKey);
        if (timestamp == null) return false;
        return (System.currentTimeMillis() - timestamp) < STATIC_CACHE_EXPIRY_TIME;
    }

    /**
     * 泛型静态缓存写入：将数据存入 cacheMap 并写入时间戳到 timestampMap。
     */
    private <T> void cacheStaticData(String cacheKey, T data, Map<String, T> cacheMap, Map<String, Long> timestampMap) {
        cacheMap.put(cacheKey, data);
        timestampMap.put(cacheKey, System.currentTimeMillis());
    }

    /**
     * 清空全部缓存：图片列表缓存与分类/榜单类型静态缓存及其时间戳一并清除。
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
     * 图片列表项视图持有者，缓存 imageView、作者/标题文本与卡片视图引用。
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

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        com.muxiao.Venus.common.tools.hideKeyboardOnTouchOutside(this, event);
        return super.dispatchTouchEvent(event);
    }

    /**
     * 图片网格适配器：负责缩略图加载、选中描边状态，以及点击全屏查看 / 长按进入选择模式。
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

            // 每次绑定都按位置稳定设置 transitionName，避免 RecyclerView 复用导致
            // 旧 name 残留/错配（共享元素转场依赖 name 一致，错配会触发渲染层 abort）。
            String transitionName = "image_" + position;
            holder.imageView.setTransitionName(transitionName);

            // 设置点击事件
            holder.itemView.setOnClickListener(v -> {
                int curPos = holder.getBindingAdapterPosition();
                if (curPos == RecyclerView.NO_POSITION) return;

                if (isSelectionMode) {
                    toggleSelection(curPos);
                } else {
                    // 共享元素转场：网格缩略图 -> 全屏大图
                    Intent intent = new Intent(ImageActivity.this, FullscreenImageActivity.class);
                    intent.putExtra("imageDataListJson", new Gson().toJson(List.of(mData.get(curPos))));
                    intent.putExtra("position", 0);
                    intent.putExtra("sharedTransitionName", transitionName);
                    Bundle options = ActivityOptionsCompat
                            .makeSceneTransitionAnimation(ImageActivity.this, holder.imageView, transitionName)
                            .toBundle();
                    startActivity(intent, options);
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
            // 清除复用前的共享元素 transitionName，避免 name 错配
            holder.imageView.setTransitionName(null);
        }

        @Override
        public int getItemCount() {
            return mData != null ? mData.size() : 0;
        }

        /**
         * 切换某项的选中状态：加入/移出选择集并刷新该项；
         * 选中集清空时自动退出选择模式，否则更新标题显示已选数量。
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
                getSupportActionBar().setTitle(getResources().getQuantityString(
                        R.plurals.toolbar_items_selected, selectedItems.size(), selectedItems.size()));
        }
    }
}
