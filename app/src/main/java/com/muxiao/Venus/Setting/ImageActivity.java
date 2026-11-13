package com.muxiao.Venus.Setting;

import com.muxiao.Venus.common.BaseActivity;
import dagger.hilt.android.AndroidEntryPoint;

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

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.ActivityOptionsCompat;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
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
import com.muxiao.Venus.common.ui.compose.ImagePost;
import com.muxiao.Venus.common.ui.compose.ImageScreenView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 图片浏览页：从米游社 API 加载图片列表（日榜/周榜/月榜/热门/同人/COS），支持下载保存到相册与全屏查看。
 * 本类只保留网络、缓存、下载与选择状态机，界面由 Compose 的 {@link ImageScreenView} 承载。
 */
@AndroidEntryPoint
public class ImageActivity extends BaseActivity {
    private ImageScreenView pane; // Compose 宿主
    private List<Map<String, Object>> imageDataList;
    private final List<Integer> selectedItems = new ArrayList<>();
    private boolean isSelectionMode = false;

    private int downloadProgress, totalDownloads, currentImageIndexInPost, currentPostIndex;
    private List<Integer> itemsToDownload = new ArrayList<>();
    private List<String> currentPostImages = new ArrayList<>();

    // 线程管理
    private ExecutorService executorService;
    private CustomTarget<Bitmap> downloadTarget; // 当前下载用的 Glide 目标，便于 onDestroy 清理
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean mDestroyed = false; // onDestroy 后置位，阻止向已关闭的线程池提交任务

    /**
     * 安全提交：Activity 已销毁或线程池已 shutdown 时直接丢弃，避免排队的 mainHandler 回调提交任务时
     * 抛 RejectedExecutionException 崩溃。
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
        }
    }
    private volatile boolean isLoading = false;
    private Map<String, String> lastLoadedParams;

    private HeaderManager headerManager;

    private String[] forumNames; // 游戏内部中文标识，用于 API 调用
    private String[] currentCategories; // 分类名，下标与下拉一一对应
    private Map<String, String> currentForumMap = new HashMap<>(); // 分类名 → cate_id
    private String currentCategoryId = "";
    private boolean isUpdatingInternal = false; // 内部联动更新中：据此抑制回调重入
    private int pendingTabPosition = -1; // 待处理的 Tab 位置
    private int currentTabPosition = 0; // 当前已生效的榜单 Tab 下标（空态重试要按它重新拉取）
    private int[] currentListTypes = new int[0];

    private final Map<String, List<Map<String, Object>>> imageDataCache = new HashMap<>();
    private final Map<String, Long> cacheTimestamps = new HashMap<>();
    private static final long CACHE_EXPIRY_TIME = 5 * 60 * 1000; // 图片列表缓存 5 分钟
    private final Map<String, Map<String, String>> forumCache = new HashMap<>();
    private final Map<String, int[]> listTypeCache = new HashMap<>();
    private final Map<String, Long> forumCacheTimestamps = new HashMap<>();
    private final Map<String, Long> listTypeCacheTimestamps = new HashMap<>();
    private static final long STATIC_CACHE_EXPIRY_TIME = 30 * 60 * 1000; // 分类/榜单类型等静态数据 30 分钟

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SettingsFragment.applyAppTheme(this);
        pane = new ImageScreenView(this);
        setContentView(pane);
        EdgeToEdge.enable(this);

        executorService = Executors.newFixedThreadPool(4);

        // 初始化 HeaderManager（榜单图始终使用国服API）
        headerManager = new HeaderManager(this, false);

        forumNames = new String[]{"原神", "星铁", "崩坏3", "绝区零", "未定事件簿", "崩坏2", "大别野"};
        // 游戏显示名称（已翻译）
        String[] forumDisplayNames = new String[]{
            getString(R.string.genshin_impact),
            getString(R.string.star_rail),
            getString(R.string.honkai_impact_3),
            getString(R.string.zenless_zone_zero),
            getString(R.string.tears_of_themis),
            getString(R.string.honkai_impact_2),
            getString(R.string.dabieye)
        };

        pane.setForums(Arrays.asList(forumDisplayNames), 0);
        pane.setOnForumSelectedListener(this::onForumSelected);
        pane.setOnCategorySelectedListener(this::onCategorySelected);
        pane.setOnTabSelectedListener(this::onTabSelected);
        pane.setOnItemClickListener(this::onItemClick);
        pane.setOnItemLongClickListener(this::onItemLongClick);
        pane.setOnDownloadClickListener(this::onDownloadClick);
        pane.setOnBackClickListener(this::handleBack);
        pane.setOnCancelSelectionListener(this::exitSelectionMode);
        pane.setOnRetryClickListener(this::retryCurrentList);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBack();
            }
        });

        // 默认加载首个游戏的榜单数据
        isUpdatingInternal = true;
        handleForumSelection(forumNames[0]);
    }

    /**
     * 空态「重试」：按当前分类与榜单重新拉取。先清 lastLoadedParams，否则 loadImages 会因「参数与上次相同」短路。
     */
    private void retryCurrentList() {
        if (currentCategoryId == null || currentCategoryId.isEmpty() || currentListTypes.length == 0) {
            // 连分类都没拿到：从游戏维度重来一遍（会重建分类与榜单类型）
            handleForumSelection(forumNames[0]);
            return;
        }
        int position = Math.min(Math.max(currentTabPosition, 0), currentListTypes.length - 1);
        Map<String, String> params = new HashMap<>();
        params.put("forum_id", currentCategoryId);
        params.put("type", String.valueOf(currentListTypes[position]));
        lastLoadedParams = null;
        loadImages(params);
    }

    /** 返回：多选态先退出多选，否则关闭页面。 */
    private void handleBack() {
        if (isSelectionMode) exitSelectionMode();
        else finish();
    }

    /** 游戏下拉选中：内部联动期间忽略，避免重复触发加载。 */
    private void onForumSelected(int position) {
        if (isUpdatingInternal) return;
        if (position < 0 || position >= forumNames.length) return;
        isUpdatingInternal = true;
        handleForumSelection(forumNames[position]);
    }

    /** 分类下拉选中：取出对应 cate_id 后加载榜单类型。 */
    private void onCategorySelected(int position) {
        if (isUpdatingInternal) return;
        isUpdatingInternal = true;
        if (currentCategories == null || position < 0 || position >= currentCategories.length) {
            isUpdatingInternal = false;
            return;
        }
        currentCategoryId = currentForumMap.get(currentCategories[position]);
        if (currentCategoryId != null) {
            handleCategorySelection(currentCategoryId);
        } else {
            isUpdatingInternal = false;
        }
    }

    /** 榜单 Tab 选中：加载中/内部联动期间只记录目标位置，等 setupTabLayout 时落到新的 Tab 集合上。 */
    private void onTabSelected(int position) {
        if (isUpdatingInternal || isLoading) {
            pendingTabPosition = position;
            return;
        }
        if (position < 0 || position >= currentListTypes.length) return;
        currentTabPosition = position;
        Map<String, String> params = new HashMap<>();
        params.put("forum_id", currentCategoryId);
        params.put("type", String.valueOf(currentListTypes[position]));
        loadImages(params);
    }

    /** 处理游戏切换：优先读分类静态缓存，否则异步请求论坛分类映射（同人图/COS），成功后刷新分类选择器。 */
    private void handleForumSelection(String forumName) {
        showCustomSnackbar(this, getString(R.string.snack_loading_types));

        String forumCacheKey = "forum_" + forumName;
        if (isStaticCacheValid(forumCacheKey, forumCacheTimestamps)) {
            Map<String, String> cachedForumMap = forumCache.get(forumCacheKey);
            mainHandler.post(() -> {
                currentForumMap = cachedForumMap;
                setupCategorySelector();
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

            cacheStaticData(forumCacheKey, forumMap, forumCache, forumCacheTimestamps);

            mainHandler.post(() -> {
                currentForumMap = forumMap;
                setupCategorySelector();
                isUpdatingInternal = false;
            });
        });
    }

    /** 用当前游戏的分类映射填充分类下拉框，默认选中首个分类并触发其榜单类型加载与 Tab 构建。 */
    private void setupCategorySelector() {
        currentCategories = currentForumMap.keySet().toArray(new String[0]);
        if (pane != null) pane.setCategories(Arrays.asList(currentCategories), 0);

        String firstCategory = currentForumMap.keySet().iterator().next();
        currentCategoryId = currentForumMap.get(firstCategory);
        handleCategorySelection(currentCategoryId);
    }

    /** 处理分类切换：优先读榜单类型静态缓存，否则异步请求该分类可用类型（日/周/月/热），成功后构建 Tab。 */
    private void handleCategorySelection(String categoryId) {
        showCustomSnackbar(this, getString(R.string.snack_loading_ranking_categories));

        String listTypeCacheKey = "listType_" + categoryId;
        if (isStaticCacheValid(listTypeCacheKey, listTypeCacheTimestamps)) {
            int[] cachedListTypes = listTypeCache.get(listTypeCacheKey);
            mainHandler.post(() -> {
                setupTabLayout(Objects.requireNonNull(cachedListTypes), categoryId);
                isUpdatingInternal = false;
            });
            return;
        }

        safeSubmit(() -> {
            int[] listTypes = getListType(categoryId);

            cacheStaticData(listTypeCacheKey, listTypes, listTypeCache, listTypeCacheTimestamps);

            mainHandler.post(() -> {
                setupTabLayout(listTypes, categoryId);
                isUpdatingInternal = false;
            });
        });
    }

    /**
     * 用榜单类型数组构建 Tab（1=日榜，2=周榜，3=月榜，其他=热榜），按 pendingTabPosition 落定后加载对应图片。
     * Tab 选中态由状态驱动、不再触发 onTabSelected，故这里直接加载（语义等价，少一次无效往返）。
     */
    private void setupTabLayout(int[] listTypes, String categoryId) {
        currentListTypes = listTypes;
        int targetPosition = Math.min(Math.max(pendingTabPosition, 0), Math.max(listTypes.length - 1, 0));
        pendingTabPosition = -1;
        currentTabPosition = targetPosition;

        List<String> labels = new ArrayList<>(listTypes.length);
        for (int serverType : listTypes) labels.add(tabLabel(serverType));
        if (pane != null) pane.setTabs(labels, targetPosition);

        if (listTypes.length > 0) {
            Map<String, String> params = new HashMap<>();
            params.put("forum_id", categoryId);
            params.put("type", String.valueOf(listTypes[targetPosition]));
            loadImages(params);
        }
    }

    /** 榜单类型 → Tab 文案。 */
    private String tabLabel(int serverType) {
        switch (serverType) {
            case 1:
                return getString(R.string.image_tab_daily);
            case 2:
                return getString(R.string.image_tab_weekly);
            case 3:
                return getString(R.string.image_tab_monthly);
            default:
                return getString(R.string.image_tab_hot);
        }
    }

    /** 加载指定参数的图片列表：参数与上次相同、正在加载或命中缓存时直接复用；type=4 走热门接口，其余走常规榜单。 */
    private void loadImages(Map<String, String> params) {
        if (params.equals(lastLoadedParams) || isLoading) return;

        isLoading = true;
        if (pane != null) pane.setLoading(true);
        lastLoadedParams = new HashMap<>(params);

        String cacheKey = generateCacheKey(params);
        if (isCacheValid(cacheKey)) {
            imageDataList = imageDataCache.get(cacheKey);
            mainHandler.post(() -> {
                renderItems();
                isLoading = false;
                if (pane != null) pane.setLoading(false);
            });
            return;
        }

        showCustomSnackbar(this, getString(R.string.snack_loading));

        safeExecute(() -> {
            try {
                // 防御性拷贝，避免修改调用方的 Map
                Map<String, String> taskParams = new HashMap<>(params);
                if (Objects.equals(taskParams.get("type"), "4")) {
                    Map<String, String> headers = headerManager.get_images_headers();
                    taskParams.remove("type");
                    taskParams.put("page", "1");
                    taskParams.put("last_id", "");
                    taskParams.put("page_size", "60");
                    String jsonResponse = tools.sendGetRequest(Constants.Urls.BBS_GAME_HOT_POST_LIST_URL, headers, taskParams);
                    imageDataList = parseImageData(jsonResponse);
                } else {
                    Map<String, String> headers = headerManager.get_images_headers();
                    taskParams.put("cate_id", "0");
                    taskParams.put("last_id", "");
                    taskParams.put("page_size", "60");
                    String jsonResponse = tools.sendGetRequest(Constants.Urls.BBS_IMAGE_URL, headers, taskParams);
                    imageDataList = parseImageData(jsonResponse);
                }

                cacheData(cacheKey, imageDataList);

                mainHandler.post(() -> {
                    renderItems();
                    isLoading = false;
                    if (pane != null) pane.setLoading(false);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    // Activity 已销毁（用户已退出图片页但后台请求才回）时，
                    // window token 失效，弹 Dialog 会抛 BadTokenException，必须跳过。
                    if (mDestroyed) {
                        isLoading = false;
                        return;
                    }
                    if (pane != null) pane.setLoading(false);
                    show_error_dialog(ImageActivity.this, getString(R.string.err_load_failed, tools.describeError(e)));
                    isLoading = false;
                });
            }
        });
    }

    /**
     * 把 imageDataList 映射成 Compose 卡片数据并下发。描述与时间未绑定（空串），与原 Adapter 行为一致；
     * ImageCard 同样跳过空文案，卡片高度与原布局一致。
     */
    private void renderItems() {
        if (pane == null) return;
        List<ImagePost> posts = new ArrayList<>();
        if (imageDataList != null) {
            for (Map<String, Object> item : imageDataList) {
                @SuppressWarnings("unchecked")
                List<String> images = (List<String>) item.get("images");
                posts.add(new ImagePost(
                        safeString(item.get("title")),
                        safeString(item.get("author")),
                        "",
                        "",
                        images != null && !images.isEmpty() ? images.get(0) : null));
            }
        }
        pane.setItems(posts);
    }

    private String safeString(Object value) {
        return value instanceof String ? (String) value : "";
    }

    /** 点击卡片：多选态切换选中，否则打开全屏。 */
    private void onItemClick(int position) {
        if (isSelectionMode) toggleSelection(position);
        else openFullscreen(position);
    }

    /** 长按卡片：进入多选模式并选中该项。 */
    private void onItemLongClick(int position) {
        if (!isSelectionMode) isSelectionMode = true;
        toggleSelection(position);
        syncSelection();
    }

    /** 切换某项的选中状态：选中集清空时自动退出选择模式。 */
    private void toggleSelection(int position) {
        if (selectedItems.contains(position))
            selectedItems.remove(Integer.valueOf(position));
        else
            selectedItems.add(position);

        if (selectedItems.isEmpty()) exitSelectionMode();
        else syncSelection();
    }

    /** 把多选态投影给页面（标题文案与下载图标由 Compose 依此派生）。 */
    private void syncSelection() {
        if (pane != null) pane.setSelection(isSelectionMode, new ArrayList<>(selectedItems));
    }

    /** 退出长按选择下载模式。 */
    private void exitSelectionMode() {
        isSelectionMode = false;
        selectedItems.clear();
        syncSelection();
    }

    /**
     * 打开全屏查看当前帖子。共享元素 transitionName 在 Compose 侧无等价物，降级为从点击矩形放大（缺坐标则淡入）。
     */
    private void openFullscreen(int position) {
        if (imageDataList == null || position < 0 || position >= imageDataList.size()) return;
        Intent intent = new Intent(this, FullscreenImageActivity.class);
        intent.putExtra("imageDataListJson", new Gson().toJson(List.of(imageDataList.get(position))));
        intent.putExtra("position", 0);
        // 卡片是 Compose 节点、没有对应 View，故用点击窗口矩形做 makeScaleUpAnimation；缺坐标时退回淡入。
        int[] bounds = pane == null ? null : pane.getLastClickBounds();
        Bundle options;
        if (bounds != null && bounds.length == 4 && bounds[2] > 0 && bounds[3] > 0) {
            options = ActivityOptionsCompat.makeScaleUpAnimation(
                    getWindow().getDecorView(), bounds[0], bounds[1], bounds[2], bounds[3]).toBundle();
        } else {
            options = ActivityOptionsCompat.makeCustomAnimation(this,
                    android.R.anim.fade_in, android.R.anim.fade_out).toBundle();
        }
        startActivity(intent, options);
    }

    /** 下载图标点击：多选为空时无动作；Android 9 及以下先申请存储权限。 */
    private void onDownloadClick() {
        if (selectedItems.isEmpty()) return;
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    WRITE_PERMISSION_REQUEST_CODE);
        } else {
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
            if (pane != null) pane.setDownloadProgress(0, totalDownloads);
            downloadImage();
        }
    }

    /** 存储权限授予后继续下载已选图片，拒绝则提示需要存储权限。 */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == WRITE_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                onDownloadClick();
            else
                showCustomSnackbar(this, getString(R.string.snack_grant_storage_permission));
        }
    }

    /**
     * 递归串行下载已选帖子的图片到相册：逐帖逐张，进度累加后自我调用下一轮；完成或队列耗尽时提示并退出选择模式。
     * downloadTarget 便于 onDestroy 时取消。
     */
    private void downloadImage() {
        if (downloadProgress >= totalDownloads || itemsToDownload.isEmpty()) {
            if (pane != null) pane.setDownloadProgress(0, 0);
            showCustomSnackbar(this, getString(R.string.snack_download_complete));
            exitSelectionMode();
            return;
        }
        // itemsToDownload 中可能有 images 缺失的条目：totalDownloads 累加时已跳过，但索引仍留在队列里，
        // 原实现会把 null 赋给 currentPostImages 并在 requireNonNull 处 NPE，故先定位下一个「确有图片」的帖子。
        while (currentImageIndexInPost >= currentPostImages.size()) {
            if (currentPostIndex >= itemsToDownload.size()) {
                // 队列已耗尽但仍有未完成计数（数据异常）：直接收尾，避免死循环
                if (pane != null) pane.setDownloadProgress(0, 0);
                showCustomSnackbar(this, getString(R.string.snack_download_complete));
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
                        if (pane != null) pane.setDownloadProgress(downloadProgress, totalDownloads);
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
                if (pane != null) pane.setDownloadProgress(downloadProgress, totalDownloads);
                downloadImage();
            }
        };
        Glide.with(this).asBitmap().load(imageUrl).into(downloadTarget);
    }

    // 本方法在工作线程执行（onResourceReady 的 safeSubmit），而错误对话框是 UI 操作，
    // 必须 runOnUiThread 切回主线程，否则触发线程约束冲突。
    private void saveImageToGallery(Bitmap bitmap, String fileName) {
        try {
            tools.saveBitmapToGallery(this, bitmap, fileName);
        } catch (IOException e) {
            if (mDestroyed) return;
            runOnUiThread(() -> {
                if (mDestroyed) return;
                show_error_dialog(this, getString(R.string.err_save_image_failed, tools.describeError(e)));
            });
        }
    }

    /**
     * 解析米游社帖子列表 JSON，提取标题、作者、封面、时间戳、描述与图片 URL。描述优先取 content.describe
     * 并按「作品描述」字样裁剪；图片依次尝试 images / image_list / cover 兜底。
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
                    JsonObject postObject = itemObject.has("post") ? itemObject.getAsJsonObject("post") : new JsonObject();
                    JsonObject userObject = itemObject.has("user") ? itemObject.getAsJsonObject("user") : new JsonObject();
                    Map<String, Object> postData = new HashMap<>();

                    String title = "";
                    if (postObject.has("subject") && !postObject.get("subject").isJsonNull())
                        title = postObject.get("subject").getAsString();
                    String author = getString(R.string.image_unknown_author);
                    if (userObject.has("nickname") && !userObject.get("nickname").isJsonNull())
                        author = userObject.get("nickname").getAsString();
                    String cover = "";
                    if (postObject.has("cover") && !postObject.get("cover").isJsonNull())
                        cover = postObject.get("cover").getAsString();
                    long createdAt = 0;
                    if (postObject.has("created_at") && !postObject.get("created_at").isJsonNull())
                        createdAt = postObject.get("created_at").getAsLong();
                    String description = "";
                    if (postObject.has("content") && !postObject.get("content").isJsonNull()) {
                        String contentStr = postObject.get("content").getAsString();
                        try {
                            JsonObject content = JsonParser.parseString(contentStr).getAsJsonObject();
                            if (content.has("describe") && !content.get("describe").isJsonNull()) {
                                description = content.get("describe").getAsString();
                                description = description.replace("\n", "").replace("\r", "");
                                // 描述前带「作品描述」标签时，切分只取标签后一段
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

                    List<String> imageUrls = new ArrayList<>();
                    if (postObject.has("images") && !postObject.get("images").isJsonNull()) {
                        JsonArray postImages = postObject.getAsJsonArray("images");
                        for (JsonElement imgElement : postImages)
                            imageUrls.add(imgElement.getAsString());
                    }
                    if (imageUrls.isEmpty() && itemObject.has("image_list") && !itemObject.get("image_list").isJsonNull()) {
                        JsonArray imageList = itemObject.getAsJsonArray("image_list");
                        for (JsonElement imageElement : imageList) {
                            JsonObject imageObj = imageElement.getAsJsonObject();
                            if (imageObj.has("url") && !imageObj.get("url").isJsonNull())
                                imageUrls.add(imageObj.get("url").getAsString());
                        }
                    }
                    if (imageUrls.isEmpty() && !cover.isEmpty())
                        imageUrls.add(cover);
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
     * 请求指定游戏的论坛分类，仅保留「同人图」与「COS」两类并返回其 id 映射；失败时返回空 Map（调用方据此重试或弹错）。
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
     * 请求指定分类可用的榜单类型（1/2/3/4 = 日/周/月/热）；空结果或异常统一降级为 [4]（热门）。
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
        clearAllCache();
        super.onDestroy();
    }

    /** 将请求参数按 key 字典序拼成稳定缓存键，保证相同参数（顺序无关）命中同一缓存。 */
    private String generateCacheKey(Map<String, String> params) {
        StringBuilder keyBuilder = new StringBuilder();
        List<Map.Entry<String, String>> entries = new ArrayList<>(params.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        for (Map.Entry<String, String> entry : entries)
            keyBuilder.append(entry.getKey()).append("=")
                    .append(entry.getValue()).append("&");
        return keyBuilder.toString();
    }

    /** 图片列表缓存是否有效：存在且距写入未超过 CACHE_EXPIRY_TIME（5 分钟）。 */
    private boolean isCacheValid(String cacheKey) {
        if (!imageDataCache.containsKey(cacheKey)) return false;
        Long timestamp = cacheTimestamps.get(cacheKey);
        if (timestamp == null) return false;
        return (System.currentTimeMillis() - timestamp) < CACHE_EXPIRY_TIME;
    }

    /** 写入图片列表缓存（深拷贝列表）并记录时间戳。 */
    private void cacheData(String cacheKey, List<Map<String, Object>> data) {
        imageDataCache.put(cacheKey, new ArrayList<>(data));
        cacheTimestamps.put(cacheKey, System.currentTimeMillis());
    }

    /**
     * 静态数据（分类/榜单类型）缓存是否有效，有效期 STATIC_CACHE_EXPIRY_TIME（30 分钟）；timestampMap 与 cacheMap 成对使用。
     */
    private boolean isStaticCacheValid(String cacheKey, Map<String, Long> timestampMap) {
        if (!timestampMap.containsKey(cacheKey)) return false;
        Long timestamp = timestampMap.get(cacheKey);
        if (timestamp == null) return false;
        return (System.currentTimeMillis() - timestamp) < STATIC_CACHE_EXPIRY_TIME;
    }

    /** 泛型静态缓存写入：数据存入 cacheMap 并记录时间戳。 */
    private <T> void cacheStaticData(String cacheKey, T data, Map<String, T> cacheMap, Map<String, Long> timestampMap) {
        cacheMap.put(cacheKey, data);
        timestampMap.put(cacheKey, System.currentTimeMillis());
    }

    /** 清空全部缓存：图片列表缓存与分类/榜单类型静态缓存及其时间戳。 */
    private void clearAllCache() {
        imageDataCache.clear();
        cacheTimestamps.clear();

        forumCache.clear();
        listTypeCache.clear();
        forumCacheTimestamps.clear();
        listTypeCacheTimestamps.clear();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        com.muxiao.Venus.common.tools.hideKeyboardOnTouchOutside(this, event);
        return super.dispatchTouchEvent(event);
    }
}
