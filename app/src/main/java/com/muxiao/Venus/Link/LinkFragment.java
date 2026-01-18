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

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textview.MaterialTextView;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.muxiao.Venus.R;
import com.muxiao.Venus.User.UserManager;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;

public class LinkFragment extends Fragment {

    private MaterialTextView errorTextView;
    private LinkAdapter adapter;
    private String currentUserId;
    private ExecutorService executor;
    private UserManager userManager;
    private MaterialAutoCompleteTextView userDropdown;
    private WebView webView;
    private LinearLayout webViewContainer;
    private RecyclerView linkItemsRecyclerView;

    // 按 tab -> userId -> (roleId -> link) 存储
    private final Map<Integer, Map<String, Map<Integer, String>>> tabResults = new HashMap<>();
    private int currentTabPosition = 0;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_link, container, false);

        TabLayout tabLayout = view.findViewById(R.id.tabLayout);
        linkItemsRecyclerView = view.findViewById(R.id.recyclerView);
        CircularProgressIndicator progressBar = view.findViewById(R.id.progressBar);
        errorTextView = view.findViewById(R.id.errorTextView);
        userDropdown = view.findViewById(R.id.user_dropdown);
        MaterialButton getLinkButton = view.findViewById(R.id.get_link_button);
        webViewContainer = view.findViewById(R.id.webViewContainer);
        webView = view.findViewById(R.id.webView);

        // 设置ProgressBar为indeterminate模式以显示旋转动画
        progressBar.setIndeterminate(true);

        // 设置RecyclerView
        linkItemsRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
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
                    linkItemsRecyclerView.setVisibility(View.VISIBLE);
                    errorTextView.setVisibility(View.GONE);
                    return;
                } else if (perUser.containsKey(selectedUser)) {
                    // 已加载但为空
                    adapter.setLinks(new HashMap<>());
                    errorTextView.setText("没有游戏角色或获取链接出错");
                    errorTextView.setVisibility(View.VISIBLE);
                    linkItemsRecyclerView.setVisibility(View.GONE);
                    return;
                }
            }
            // 没有任何已保存结果：清空显示
            adapter.setLinks(new HashMap<>());
            linkItemsRecyclerView.setVisibility(View.GONE);
            errorTextView.setVisibility(View.GONE);
        });

        // 设置Tab选择栏监听器，委托到类级别方法处理
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                currentTabPosition = tab.getPosition();
                if (currentTabPosition == 2) {
                    // 云游戏
                    setViewsVisibility(false, false, false, false, true);
                    setupWebViewForCloudGame();
                } else {
                    // 原神 / 绝区零
                    setViewsVisibility(true, true, true, true, false);

                    Map<String, Map<Integer, String>> perUser = tabResults.get(currentTabPosition);
                    if (perUser != null) {
                        Map<Integer, String> resultsForCurrentUser = perUser.get(currentUserId);
                        if (resultsForCurrentUser != null && !resultsForCurrentUser.isEmpty()) {
                            adapter.setLinks(new HashMap<>(resultsForCurrentUser));
                            linkItemsRecyclerView.setVisibility(View.VISIBLE);
                            errorTextView.setVisibility(View.GONE);
                        } else if (perUser.containsKey(currentUserId)) {
                            // 已加载过但为空
                            adapter.setLinks(new HashMap<>());
                            errorTextView.setText("没有游戏角色或获取链接出错");
                            errorTextView.setVisibility(View.VISIBLE);
                            linkItemsRecyclerView.setVisibility(View.GONE);
                        } else {
                            // 未加载过该标签页+用户的结果
                            adapter.setLinks(new HashMap<>());
                            errorTextView.setVisibility(View.GONE);
                            linkItemsRecyclerView.setVisibility(View.GONE);
                        }
                    } else {
                        // 该 tab 完全没加载过任何用户
                        adapter.setLinks(new HashMap<>());
                        errorTextView.setVisibility(View.GONE);
                        linkItemsRecyclerView.setVisibility(View.GONE);
                    }
                    destroyWebView();
                }
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        // 获取链接按钮点击事件
        getLinkButton.setOnClickListener(v -> {
            if (currentUserId == null || currentUserId.isEmpty()) {
                errorTextView.setText("请先选择一个用户");
                errorTextView.setVisibility(View.VISIBLE);
                return;
            }
            progressBar.setVisibility(View.VISIBLE);
            errorTextView.setVisibility(View.GONE);
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
                        errorTextView.setVisibility(View.VISIBLE);
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
                            errorTextView.setVisibility(View.GONE);
                            linkItemsRecyclerView.setVisibility(View.VISIBLE);
                        }
                    } else {
                        // 空结果，显示错误信息
                        if (currentTabPosition == gameType) {
                            errorTextView.setText("没有游戏角色或获取链接出错");
                            errorTextView.setVisibility(View.VISIBLE);
                            linkItemsRecyclerView.setVisibility(View.GONE);
                        }
                    }
                });
            });
        });

        // 关闭 WebView 按钮
        view.findViewById(R.id.closeWebViewButton).setOnClickListener(v -> {
            setViewsVisibility(true, true, true, true, false);
            Objects.requireNonNull(tabLayout.getTabAt(0)).select();
            destroyWebView();
        });

        return view;
    }

    /**
     * 设置视图可见性
     */
    private void setViewsVisibility(boolean dropdownVisible, boolean buttonVisible,
                                    boolean recyclerViewVisible, boolean errorTextVisible,
                                    boolean webViewVisible) {
        LinearLayout userDropdownLayout = requireView().findViewById(R.id.user_dropdown_layout);
        MaterialButton getLinkButton = requireView().findViewById(R.id.get_link_button);

        if (userDropdownLayout != null)
            userDropdownLayout.setVisibility(dropdownVisible ? View.VISIBLE : View.GONE);
        if (getLinkButton != null)
            getLinkButton.setVisibility(buttonVisible ? View.VISIBLE : View.GONE);
        linkItemsRecyclerView.setVisibility(recyclerViewVisible ? View.VISIBLE : View.GONE);
        errorTextView.setVisibility(errorTextVisible ? View.VISIBLE : View.GONE);
        webViewContainer.setVisibility(webViewVisible ? View.VISIBLE : View.GONE);
    }

    /**
     * 销毁 WebView（安全清理）
     */
    private void destroyWebView() {
        if (webView != null) {
            webViewContainer.removeView(webView);
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

    /**
     * 创建云游戏 WebView 并监听 URL，以便捕捉抽卡链接
     */
    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebViewForCloudGame() {
        linkItemsRecyclerView.setVisibility(View.GONE);
        webViewContainer.setVisibility(View.VISIBLE);

        // 如果 WebView 为 null，则重新创建
        if (webView == null) {
            webView = new WebView(requireContext());
            webView.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
            ));
            webViewContainer.addView(webView);
        }

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);

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
}