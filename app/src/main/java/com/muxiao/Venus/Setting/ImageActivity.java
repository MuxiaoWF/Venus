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
 * 图片浏览页：从米游社API加载图片列表（日榜/周榜/月榜/热门/同人/COS），
 * 支持下载保存到相册和全屏查看。
 *
 * <p>界面已迁移为 Compose（{@link ImageScreenView}）：`RecyclerView` + `ImageAdapter` →
 * `LazyVerticalGrid` + `ImageCard`，ActionBar 的标题与下载菜单改由页面状态派生。
 * 本类只保留网络、缓存、下载与选择状态机。</p>
 */
@AndroidEntryPoint
public class ImageActivity extends BaseActivity {
    private ImageScreenView pane; // Compose 宿主
    private List<Map<String, Object>> imageDataList; // 图片数据列表
    private final List<Integer> selectedItems = new ArrayList<>(); // 已选择的项目索引
    private boolean isSelectionMode = false; // 是否处于选择模式

    // 下载状态变量
    private int downloadProgress, totalDownloads, currentImageIndexInPost, currentPostIndex;
    private List<Integer> itemsToDownload = new ArrayList<>(); // 待下载项目列表
    private List<String> currentPostImages = new ArrayList<>(); // 当前帖子的图片列表

    // 线程管理
    private ExecutorService executorService; // 线程池
    private CustomTarget<Bitmap> downloadTarget; // 当前下载用的 Glide 目标，便于 onDestroy 清理
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
    private String[] currentCategories; // 当前游戏的分类名（与下拉下标一一对应）
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
        pane = new ImageScreenView(this);
        setContentView(pane);
        EdgeToEdge.enable(this);

        // 初始化基础组件
        executorService = Executors.newFixedThreadPool(4); // 4个线程的线程池

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

        // 页面回调：筛选、榜单、卡片点击/长按、下载与返回
        pane.setForums(Arrays.asList(forumDisplayNames), 0);
        pane.setOnForumSelectedListener(this::onForumSelected);
        pane.setOnCategorySelectedListener(this::onCategorySelected);
        pane.setOnTabSelectedListener(this::onTabSelected);
        pane.setOnItemClickListener(this::onItemClick);
        pane.setOnItemLongClickListener(this::onItemLongClick);
        pane.setOnDownloadClickListener(this::onDownloadClick);
        pane.setOnBackClickListener(this::handleBack);

        // 返回键处理
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBack();
            }
        });

        // 默认加载首个游戏的榜单数据（原 setupListenersAndLoadData 的收尾动作）
        isUpdatingInternal = true;
        handleForumSelection(forumNames[0]);
    }

    /** 返回：多选态先退出多选，否则关闭页面。 */
    private void handleBack() {
        if (isSelectionMode) exitSelectionMode();
        else finish();
    }

    /* ===================== 筛选联动 ===================== */

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
        Map<String, String> params = new HashMap<>();
        params.put("forum_id", currentCategoryId);
        params.put("type", String.valueOf(currentListTypes[position]));
        loadImages(params);
    }

    /**
     * 处理游戏（forum）切换：优先读分类静态缓存，否则异步请求论坛分类映射（同人图/COS），
     * 成功后刷新分类选择器。forumName 为游戏内部中文标识。
     */
    private void handleForumSelection(String forumName) {
        showCustomSnackbar(this, getString(R.string.snack_loading_types));

        // 检查游戏分类缓存
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

            // 缓存游戏分类数据
            cacheStaticData(forumCacheKey, forumMap, forumCache, forumCacheTimestamps);

            mainHandler.post(() -> {
                currentForumMap = forumMap;
                setupCategorySelector();
                isUpdatingInternal = false;
            });
        });
    }

    /**
     * 用当前游戏的分类映射填充分类下拉框，默认选中首个分类并触发其榜单类型加载与 Tab 构建。
     */
    private void setupCategorySelector() {
        currentCategories = currentForumMap.keySet().toArray(new String[0]);
        if (pane != null) pane.setCategories(Arrays.asList(currentCategories), 0);

        String firstCategory = currentForumMap.keySet().iterator().next();
        currentCategoryId = currentForumMap.get(firstCategory);
        handleCategorySelection(currentCategoryId);
    }

    /**
     * 处理分类（cate_id）切换：优先读榜单类型静态缓存，否则异步请求该分类可用榜单类型
     * （日/周/月/热），成功后构建 Tab 布局。categoryId 来自分类映射表。
     */
    private void handleCategorySelection(String categoryId) {
        showCustomSnackbar(this, getString(R.string.snack_loading_ranking_categories));

        // 检查榜单类型缓存
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

            // 缓存榜单类型数据
            cacheStaticData(listTypeCacheKey, listTypes, listTypeCache, listTypeCacheTimestamps);

            mainHandler.post(() -> {
                setupTabLayout(listTypes, categoryId);
                isUpdatingInternal = false;
            });
        });
    }

    /**
     * 用榜单类型数组构建 Tab（1=日榜，2=周榜，3=月榜，其他=热榜），
     * 按 pendingTabPosition 落定目标 Tab 后自动加载对应榜单图片。
     * <p>
     * 与原实现的差异：原来靠 `Tab.select()` 触发 `onTabSelected` 再加载，而该回调在
     * `isUpdatingInternal` 期间只会记下 pendingTabPosition、不加载，真正的加载是方法末尾直调。
     * 迁移后 Tab 选中态由状态驱动（不触发回调），这里直接加载，语义等价且少一次无效往返。
     */
    private void setupTabLayout(int[] listTypes, String categoryId) {
        currentListTypes = listTypes;
        int targetPosition = Math.min(Math.max(pendingTabPosition, 0), Math.max(listTypes.length - 1, 0));
        pendingTabPosition = -1;

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

    /**
     * 加载指定参数的图片列表：参数与上次相同、正在加载或命中缓存时直接复用。
     * type=4 走热门接口，其余走常规榜单接口；结果在主线程渲染到网格。
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
                renderItems();
                isLoading = false;
            });
            return;
        }

        showCustomSnackbar(this, getString(R.string.snack_loading));

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
                    renderItems();
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
                    show_error_dialog(ImageActivity.this, getString(R.string.err_load_failed, tools.describeError(e)));
                    isLoading = false;
                });
            }
        });
    }

    /**
     * 把当前的 `imageDataList` 映射成 Compose 卡片数据并下发。
     * <p>
     * 描述与时间沿用原 Adapter 的行为（未绑定 → 空串）；XML 里对应 TextView 文本为空时高度为 0，
     * `ImageCard` 同样会跳过空文案，卡片高度与原布局一致。
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

    /* ===================== 选择与全屏 ===================== */

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

    /**
     * 切换某项的选中状态：加入/移出选择集；选中集清空时自动退出选择模式。
     * 原来由 Adapter 内的 toggleSelection 完成并 notifyItemChanged，现在只更新状态。
     */
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

    /**
     * 退出长按选择下载模式
     */
    private void exitSelectionMode() {
        isSelectionMode = false;
        selectedItems.clear();
        syncSelection();
    }

    /**
     * 打开全屏查看当前帖子。
     * <p>
     * 转场说明：原实现用 `transitionName` 做共享元素缩放转场，Compose 侧没有 View 共享元素的
     * 等价物（2A 方案），这里降级为淡入淡出——图片本身、可缩放、左右翻页等能力不受影响。
     */
    private void openFullscreen(int position) {
        if (imageDataList == null || position < 0 || position >= imageDataList.size()) return;
        Intent intent = new Intent(this, FullscreenImageActivity.class);
        intent.putExtra("imageDataListJson", new Gson().toJson(List.of(imageDataList.get(position))));
        intent.putExtra("position", 0);
        Bundle options = ActivityOptionsCompat.makeCustomAnimation(this,
                android.R.anim.fade_in, android.R.anim.fade_out).toBundle();
        startActivity(intent, options);
    }

    /**
     * 下载图标点击（原 ActionBar 的 action_download）：多选为空时无动作，
     * Android 9 及以下先要存储权限。
     */
    private void onDownloadClick() {
        if (selectedItems.isEmpty()) return;
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

    /**
     * 存储权限授予后继续下载已选图片，拒绝则提示需要存储权限。
     */
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
     * 递归串行下载所有已选帖子的图片到相册：逐帖子、逐张下载，进度累加后自我调用下一轮；
     * 完成或列表耗尽时提示并退出选择模式。downloadTarget 便于 onDestroy 时取消。
     */
    private void downloadImage() {
        if (downloadProgress >= totalDownloads || itemsToDownload.isEmpty()) {
            showCustomSnackbar(this, getString(R.string.snack_download_complete));
            exitSelectionMode();
            return;
        }
        // 定位下一个「确有图片」的帖子。itemsToDownload 中可能存在 images 缺失的条目：
        // totalDownloads 累加时已跳过它们，但索引仍留在队列里，原实现会把 null 赋给
        // currentPostImages，并在下一行 Objects.requireNonNull 处抛 NPE。
        while (currentImageIndexInPost >= currentPostImages.size()) {
            if (currentPostIndex >= itemsToDownload.size()) {
                // 队列已耗尽但仍有未完成计数（数据异常）：直接收尾，避免死循环
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

    // 将 Bitmap 持久化到系统相册，失败时切回主线程弹错误框。
    // 本方法在工作线程执行（见 onResourceReady 的 safeSubmit），而错误对话框是 UI 操作，
    // 必须 runOnUiThread 切回主线程，否则在工作线程调用主线程方法会触发线程约束冲突。
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

    /* ===================== 数据解析 ===================== */

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
        entries.sort(Map.Entry.comparingByKey());
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

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        com.muxiao.Venus.common.tools.hideKeyboardOnTouchOutside(this, event);
        return super.dispatchTouchEvent(event);
    }
}
