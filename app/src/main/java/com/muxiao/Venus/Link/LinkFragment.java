package com.muxiao.Venus.Link;

import static com.muxiao.Venus.common.tools.copyToClipboard;
import static com.muxiao.Venus.common.tools.showCustomSnackbar;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textview.MaterialTextView;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.muxiao.Venus.MainActivity;
import com.muxiao.Venus.R;
import com.muxiao.Venus.User.UserManager;
import com.muxiao.Venus.common.ScaleInItemAnimator;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;

public class LinkFragment extends Fragment {

    {
        setEnterTransition(new android.transition.Fade(android.transition.Fade.IN).setDuration(300));
    }

    private MaterialTextView errorTextView;
    private LinkAdapter adapter;
    private String currentUserId;
    private ExecutorService executor;
    private UserManager userManager;
    private MaterialAutoCompleteTextView userDropdown;
    private WebView webView;
    private LinearLayout webViewContainer;
    private RecyclerView linkItemsRecyclerView;
    private MaterialCardView recyclerCard;

    // 按 tab -> userId -> (roleId -> link) 存储
    private final Map<Integer, Map<String, Map<Integer, String>>> tabResults = new HashMap<>();
    private int currentTabPosition = 0;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_link, container, false);

        TabLayout tabLayout = view.findViewById(R.id.tabLayout);
        linkItemsRecyclerView = view.findViewById(R.id.recyclerView);
        recyclerCard = view.findViewById(R.id.recycler_card);
        CircularProgressIndicator progressBar = view.findViewById(R.id.progressBar);
        errorTextView = view.findViewById(R.id.errorTextView);
        userDropdown = view.findViewById(R.id.user_dropdown);
        MaterialButton getLinkButton = view.findViewById(R.id.get_link_button);
        webViewContainer = view.findViewById(R.id.webViewContainer);
        webView = view.findViewById(R.id.webView);

        View bottomPaddingView = view.findViewById(R.id.bottom_padding_view);
        // 设置底部空白高度
        if (getActivity() instanceof MainActivity) {
            MainActivity mainActivity = (MainActivity) getActivity();
            int bottomNavHeight = mainActivity.bottomNavigationView.getHeight();
            if (bottomNavHeight > 0 && bottomPaddingView != null) {
                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) bottomPaddingView.getLayoutParams();
                params.height = bottomNavHeight + (int) (32 * getResources().getDisplayMetrics().density);
                bottomPaddingView.setLayoutParams(params);
            }
        }

        // 设置ProgressBar为indeterminate模式以显示旋转动画
        progressBar.setIndeterminate(true);

        // 设置RecyclerView
        linkItemsRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        linkItemsRecyclerView.setItemAnimator(new ScaleInItemAnimator());
        adapter = new LinkAdapter(view, requireContext());
        linkItemsRecyclerView.setAdapter(adapter);

        userManager = new UserManager(requireContext());
        executor = Executors.newSingleThreadExecutor();

        updateDropdown();

        userDropdown.setOnItemClickListener((parent, view1, position, id) -> {
            String selectedUser = (String) parent.getItemAtPosition(position);
            userManager.setCurrentUser(selectedUser);
            currentUserId = selectedUser;
            // 更新 adapter 中的当前用户
            adapter.setCurrentUser(selectedUser);

            // 切换用户时尝试恢复当前 tab + 当前用户 的已保存结果
            Map<String, Map<Integer, String>> perUser = tabResults.get(currentTabPosition);
            if (perUser != null) {
                Map<Integer, String> resultsForUser = perUser.get(selectedUser);
                if (resultsForUser != null && !resultsForUser.isEmpty()) {
                    // 传入拷贝，避免引用共享
                    adapter.setLinks(new HashMap<>(resultsForUser));
                    setRecyclerVisible(true);
                    setErrorTextVisible(false);
                    return;
                } else if (perUser.containsKey(selectedUser)) {
                    // 已加载但为空
                    adapter.setLinks(new HashMap<>());
                    errorTextView.setText("没有游戏角色或获取链接出错");
                    setErrorTextVisible(true);
                    setRecyclerVisible(false);
                    return;
                }
            }
            // 没有任何已保存结果：清空显示
            adapter.setLinks(new HashMap<>());
            setRecyclerVisible(false);
            setErrorTextVisible(false);
        });

        // 设置Tab选择栏监听器，委托到类级别方法处理
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                currentTabPosition = tab.getPosition();
                if (currentTabPosition == 2) {
                    // 云游戏
                    adapter.setLinksSilently(new HashMap<>());
                    setRecyclerVisible(false);
                    setErrorTextVisible(false);
                    setTopCardVisible(false);
                    setWebViewContainerVisible(true);
                    setupWebViewForCloudGame();
                } else {
                    // 原神 / 绝区零 — 先设置数据，再设置可见性
                    destroyWebView();
                    setWebViewContainerVisible(false);
                    setTopCardVisible(true);

                    Map<String, Map<Integer, String>> perUser = tabResults.get(currentTabPosition);
                    if (perUser != null) {
                        Map<Integer, String> resultsForCurrentUser = perUser.get(currentUserId);
                        if (resultsForCurrentUser != null && !resultsForCurrentUser.isEmpty()) {
                            adapter.setLinksSilently(new HashMap<>(resultsForCurrentUser));
                            setRecyclerVisible(true);
                            setErrorTextVisible(false);
                        } else if (perUser.containsKey(currentUserId)) {
                            // 已加载过但为空
                            adapter.setLinksSilently(new HashMap<>());
                            errorTextView.setText("没有游戏角色或获取链接出错");
                            setRecyclerVisible(false);
                            setErrorTextVisible(true);
                        } else {
                            // 未加载过该标签页+用户的结果
                            adapter.setLinksSilently(new HashMap<>());
                            setRecyclerVisible(false);
                            setErrorTextVisible(false);
                        }
                    } else {
                        // 该 tab 完全没加载过任何用户
                        adapter.setLinksSilently(new HashMap<>());
                        setRecyclerVisible(false);
                        setErrorTextVisible(false);
                    }
                }
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        // 恢复当前 tab 的可见性状态（处理 view 重建的情况）
        if (currentTabPosition == 2) {
            setTopCardVisible(false);
            setRecyclerVisible(false);
            setErrorTextVisible(false);
            setWebViewContainerVisible(true);
            setupWebViewForCloudGame();
        }

        // 获取链接按钮点击事件
        getLinkButton.setOnClickListener(v -> {
            if (currentUserId == null || currentUserId.isEmpty()) {
                errorTextView.setText("请先选择一个用户");
                setErrorTextVisible(true);
                return;
            }
            progressBar.setVisibility(View.VISIBLE);
            setErrorTextVisible(false);
            int gameType = tabLayout.getSelectedTabPosition();
            executor.execute(() -> {
                Map<Integer, String> result;
                GachaLink gachaLink = new GachaLink(requireContext(), currentUserId);
                try {
                    if (gameType == 0) // 原神
                        result = gachaLink.genshin();
                    else // 绝区零（不存在是云游戏的情形）
                        result = gachaLink.zzz();
                } catch (Exception e) {
                    requireActivity().runOnUiThread(() -> {
                        errorTextView.setText(e.getMessage());
                        progressBar.setVisibility(View.GONE);
                        setErrorTextVisible(true);
                    });
                    return;
                }
                // 获取到结果并更新UI
                requireActivity().runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);

                    // 将 result 按 tab 和 userId 存储（注意做一次拷贝）
                    Map<String, Map<Integer, String>> perUser = tabResults.get(gameType);
                    if (perUser == null) {
                        perUser = new HashMap<>();
                        tabResults.put(gameType, perUser);
                    }
                    perUser.put(currentUserId, new HashMap<>(result)); // 存拷贝，避免引用共享

                    // 更新对应标签页的数据
                    if (!result.isEmpty()) {
                        // 直接更新UI
                        if (currentTabPosition == gameType) {
                            adapter.setLinks(new HashMap<>(result)); // 传入拷贝到 adapter
                            setErrorTextVisible(false);
                            setRecyclerVisible(true);
                        }
                    } else {
                        // 空结果，显示错误信息
                        if (currentTabPosition == gameType) {
                            errorTextView.setText("没有游戏角色或获取链接出错");
                            setErrorTextVisible(true);
                            setRecyclerVisible(false);
                        }
                    }
                });
            });
        });

        // 关闭 WebView 按钮
        view.findViewById(R.id.closeWebViewButton).setOnClickListener(v -> Objects.requireNonNull(tabLayout.getTabAt(0)).select());

        return view;
    }

    /**
     * 设置顶部操作区卡片（用户下拉框 + 按钮）可见性
     */
    private void setTopCardVisible(boolean visible) {
        LinearLayout userDropdownLayout = requireView().findViewById(R.id.user_dropdown_layout);
        if (userDropdownLayout != null) {
            View card = (View) userDropdownLayout.getParent().getParent();
            if (card != null)
                card.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * 创建云游戏 WebView 并监听 URL，以便捕捉抽卡链接
     */
    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebViewForCloudGame() {
        setRecyclerVisible(false);
        setWebViewContainerVisible(true);

        webView = new WebView(requireContext());
        webView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
        ));
        webViewContainer.addView(webView);
        // 配置WebView设置
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setCacheMode(android.webkit.WebSettings.LOAD_DEFAULT);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setUseWideViewPort(true);
        webView.getSettings().setSupportZoom(false);
        webView.getSettings().setBuiltInZoomControls(false);
        webView.getSettings().setDisplayZoomControls(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 修改 navigator.language 和 navigator.languages 属性
                view.evaluateJavascript(
                        "Object.defineProperty(navigator, 'language', {get: function(){return 'zh-CN';}});" +
                                "Object.defineProperty(navigator, 'languages', {get: function(){return ['zh-CN'];}});",
                        null
                );
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                // 检查是否包含各类抽卡链接并复制到剪贴板
                if (url.contains("public-operation-hk4e.mihoyo.com") && url.contains("authkey=")) {
                    requireActivity().runOnUiThread(() -> {
                        copyToClipboard(view, requireContext(), url);
                        showCustomSnackbar(view, requireContext(), "原神抽卡链接已复制到剪贴板");
                    });
                } else if (url.contains("public-operation-hkrpg.mihoyo.com") && url.contains("authkey=")) {
                    requireActivity().runOnUiThread(() -> {
                        copyToClipboard(view, requireContext(), url);
                        showCustomSnackbar(view, requireContext(), "星穹铁道抽卡链接已复制到剪贴板");
                    });
                } else if (url.contains("public-operation-nap.mihoyo.com") && url.contains("authkey=")) {
                    requireActivity().runOnUiThread(() -> {
                        copyToClipboard(view, requireContext(), url);
                        showCustomSnackbar(view, requireContext(), "绝区零抽卡链接已复制到剪贴板");
                    });
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                view.loadUrl(request.getUrl().toString());
                return true;
            }
        });

        // 加载米哈游云游戏页面
        webView.loadUrl("https://mhyy.mihoyo.com/");
    }

    /**
     * 销毁 WebView（安全清理）
     */
    private void destroyWebView() {
        if (webView != null) {
            // 在销毁前清除缓存和历史记录
            webView.clearHistory();
            webView.clearCache(true);
            webView.loadUrl("about:blank"); // 加载空白页以停止当前页面的执行
            webViewContainer.removeView(webView);
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
    }

    /**
     * 更新用户下拉框
     */
    private void updateDropdown() {
        android.widget.ArrayAdapter<String> dropdownAdapter = new android.widget.ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                userManager.getUsernames()
        );
        userDropdown.setAdapter(dropdownAdapter);
        // 设置当前用户为默认选中项
        String currentUser = userManager.getCurrentUser();
        if (currentUser != null && !currentUser.isEmpty() && userManager.getUsernames().contains(currentUser)) {
            userDropdown.setText(currentUser, false);
            currentUserId = currentUser;
            this.adapter.setCurrentUser(currentUser);
        } else if (!userManager.getUsernames().isEmpty()) {
            // 如果没有设置当前用户但有用户存在，默认选择第一个
            String firstUser = userManager.getUsernames().get(0);
            userDropdown.setText(firstUser, false);
            userManager.setCurrentUser(firstUser);
            currentUserId = firstUser;
            this.adapter.setCurrentUser(firstUser);
        } // 否则什么都不干
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executor != null && !executor.isShutdown())
            executor.shutdown();
        destroyWebView();
    }

    @Override
    public void onResume() {
        super.onResume();
        // 每次恢复 Fragment 时更新下拉框（并保持当前选择）
        updateDropdown();
    }

    private void setRecyclerVisible(boolean visible) {
        int vis = visible ? View.VISIBLE : View.GONE;
        recyclerCard.setVisibility(vis);
        linkItemsRecyclerView.setVisibility(vis);
    }

    private void setErrorTextVisible(boolean visible) {
        errorTextView.animate().cancel();
        if (visible && errorTextView.getVisibility() != View.VISIBLE) {
            errorTextView.setVisibility(View.VISIBLE);
            errorTextView.setAlpha(0f);
            errorTextView.animate()
                    .alpha(1f)
                    .setDuration(300)
                    .setInterpolator(android.view.animation.AnimationUtils.loadInterpolator(
                            getContext(), android.R.interpolator.fast_out_slow_in))
                    .start();
        } else if (!visible) {
            errorTextView.setVisibility(View.GONE);
        }
    }

    private void setWebViewContainerVisible(boolean visible) {
        webViewContainer.animate().cancel();
        webViewContainer.setTranslationY(0f);
        webViewContainer.setAlpha(1f);
        if (visible && webViewContainer.getVisibility() != View.VISIBLE) {
            webViewContainer.setVisibility(View.VISIBLE);
            webViewContainer.setAlpha(0f);
            webViewContainer.setTranslationY(60f);
            webViewContainer.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(400)
                    .setInterpolator(android.view.animation.AnimationUtils.loadInterpolator(
                            getContext(), android.R.interpolator.fast_out_slow_in))
                    .start();
        } else if (!visible) {
            webViewContainer.setVisibility(View.GONE);
        }
    }
}