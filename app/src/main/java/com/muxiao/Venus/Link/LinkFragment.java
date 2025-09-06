package com.muxiao.Venus.Link;

import static com.muxiao.Venus.common.tools.showCustomSnackbar;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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

    private CircularProgressIndicator progressBar;
    private MaterialTextView errorTextView;
    private LinkAdapter adapter;
    private String currentUserId;
    private ExecutorService executor;
    private UserManager userManager;
    private MaterialAutoCompleteTextView userDropdown;
    private TabLayout tabLayout;
    private WebView webView;
    private LinearLayout webViewContainer;
    private LinearLayout userDropdownLayout;
    private RecyclerView recyclerView;
    private View view;

    // 为每个标签页维护独立的结果
    private final Map<Integer, Map<Integer, String>> tabResults = new HashMap<>();
    private int currentTabPosition = 0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        view = inflater.inflate(R.layout.fragment_link, container, false);

        tabLayout = view.findViewById(R.id.tabLayout);
        recyclerView = view.findViewById(R.id.recyclerView);
        progressBar = view.findViewById(R.id.progressBar);
        errorTextView = view.findViewById(R.id.errorTextView);
        userDropdown = view.findViewById(R.id.user_dropdown);
        userDropdownLayout = view.findViewById(R.id.user_dropdown_layout);
        MaterialButton getLinkButton = view.findViewById(R.id.get_link_button);
        webViewContainer = view.findViewById(R.id.webViewContainer);
        webView = view.findViewById(R.id.webView);

        // 设置ProgressBar为indeterminate模式以显示旋转动画
        progressBar.setIndeterminate(true);

        // 设置RecyclerView
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new LinkAdapter();
        recyclerView.setAdapter(adapter);

        // 初始化用户管理器和线程池
        userManager = new UserManager(requireContext());
        executor = Executors.newSingleThreadExecutor();

        // 初始化下拉框
        updateDropdown();

        // 设置下拉框选择事件
        userDropdown.setOnItemClickListener((parent, view1, position, id) -> {
            String selectedUser = (String) parent.getItemAtPosition(position);
            userManager.setCurrentUser(selectedUser);
            currentUserId = selectedUser;
        });

        // 设置Tab选择监听器
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                handleTabSelection(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                handleTabSelection(tab.getPosition());
            }

            private void handleTabSelection(int position) {
                currentTabPosition = position;
                // 根据选择的Tab显示对应内容
                if (position == 2) {
                    // 云游戏标签页
                    userDropdownLayout.setVisibility(View.GONE);
                    getLinkButton.setVisibility(View.GONE);
                    recyclerView.setVisibility(View.GONE);
                    errorTextView.setVisibility(View.GONE);
                    webViewContainer.setVisibility(View.VISIBLE);
                    setupWebViewForCloudGame();
                } else {
                    // 原神或绝区零标签页
                    userDropdownLayout.setVisibility(View.VISIBLE);
                    getLinkButton.setVisibility(View.VISIBLE);
                    webViewContainer.setVisibility(View.GONE);

                    // 显示当前标签页的结果
                    showTabResults(position);

                    // 销毁WebView
                    if (webView != null) {
                        webViewContainer.removeView(webView);
                        webView.destroy();
                        webView = null;
                    }
                }
            }
        });

        // 设置获取链接按钮点击事件
        getLinkButton.setOnClickListener(v -> loadLinks(tabLayout.getSelectedTabPosition()));

        // 设置关闭WebView按钮点击事件
        view.findViewById(R.id.closeWebViewButton).setOnClickListener(v -> {
            webViewContainer.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            getLinkButton.setVisibility(View.VISIBLE);
            userDropdownLayout.setVisibility(View.VISIBLE);
            Objects.requireNonNull(tabLayout.getTabAt(0)).select();

            // 销毁WebView
            if (webView != null) {
                webViewContainer.removeView(webView);
                webView.destroy();
                webView = null;
            }
        });

        // 设置提取链接按钮点击事件
        view.findViewById(R.id.extractLinkButton).setOnClickListener(v -> {
            if (webView != null) {
                webView.evaluateJavascript(
                        "(function() {" +
                                "  var links = [];" +
                                "  var elements = document.querySelectorAll('a');" +
                                "  for (var i = 0; i < elements.length; i++) {" +
                                "    var href = elements[i].href;" +
                                "    if (href && href.includes('authkey=')) {" +
                                "      links.push(href);" +
                                "    }" +
                                "  }" +
                                "  return links.length > 0 ? links[0] : window.location.href;" +
                                "})();",
                        result -> {
                            // 移除结果字符串的引号
                            String url = result.replaceAll("^\"|\"$", "").replaceAll("\\\\\"", "\"");
                            if (url.contains("authkey=")) {
                                copyToClipboard(url);
                                showCustomSnackbar(view, this, "链接已复制到剪贴板");
                            } else {
                                // 尝试从页面中查找可能的抽卡链接
                                webView.evaluateJavascript(
                                        "(function() {" +
                                                "  var scripts = document.getElementsByTagName('script');" +
                                                "  for (var i = 0; i < scripts.length; i++) {" +
                                                "    var content = scripts[i].innerHTML;" +
                                                "    var match = content.match(/https?:\\\\/\\\\/[^\\s]*authkey=[^\\s'\"]*/g);" +
                                                "    if (match && match.length > 0) {" +
                                                "      return match[0].replace(/\\\\\\\\/g, '/');" +
                                                "    }" +
                                                "  }" +
                                                "  return '';" +
                                                "})();",
                                        result2 -> {
                                            String extractedUrl = result2.replaceAll("^\"|\"$", "").replaceAll("\\\\\"", "\"").replaceAll("\\\\\\\\", "/");
                                            if (extractedUrl.contains("authkey=")) {
                                                copyToClipboard(extractedUrl);
                                                showCustomSnackbar(view, this, "链接已复制到剪贴板");
                                            } else {
                                                showCustomSnackbar(view, this, "未找到有效的抽卡链接");
                                            }
                                        }
                                );
                            }
                        }
                );
            }
        });

        return view;
    }

    private void updateDropdown() {
        // 更新用户下拉框
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                userManager.getUsernames()
        );
        userDropdown.setAdapter(adapter);

        // 设置当前用户为默认选中项
        String currentUser = userManager.getCurrentUser();
        if (!currentUser.isEmpty() && userManager.getUsernames().contains(currentUser)) {
            userDropdown.setText(currentUser, false);
            currentUserId = currentUser;
        } else if (!userManager.getUsernames().isEmpty()) {
            // 如果没有设置当前用户但有用户存在，默认选择第一个
            String firstUser = userManager.getUsernames().get(0);
            userDropdown.setText(firstUser, false);
            userManager.setCurrentUser(firstUser);
            currentUserId = firstUser;
        }
    }

    private void loadLinks(int gameType) {
        new LoadLinksTask(gameType).execute();
    }

    private void showTabResults(int tabPosition) {
        // 云游戏标签页不需要显示结果
        if (tabPosition == 2)
            return;
        Map<Integer, String> results = tabResults.get(tabPosition);
        if (results != null && !results.isEmpty()) {
            adapter.setLinks(results);
            recyclerView.setVisibility(View.VISIBLE);
            errorTextView.setVisibility(View.GONE);
        } else {
            // 检查是否已经加载过但没有结果
            if (tabResults.containsKey(tabPosition)) {
                errorTextView.setText("没有游戏角色或获取链接出错");
                errorTextView.setVisibility(View.VISIBLE);
                recyclerView.setVisibility(View.GONE);
            } else {
                // 未加载过该标签页的结果
                recyclerView.setVisibility(View.GONE);
                errorTextView.setVisibility(View.GONE);
            }
        }
    }

    private void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Gacha Link", text);
        clipboard.setPrimaryClip(clip);
        showCustomSnackbar(view, this, "链接已复制到剪贴板");
    }

    private void setupWebViewForCloudGame() {
        recyclerView.setVisibility(View.GONE);
        webViewContainer.setVisibility(View.VISIBLE);

        // 如果WebView为null，则重新创建
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
                view.evaluateJavascript(
                        "Object.defineProperty(navigator, 'language', {get: function(){return 'zh-CN';}});" +
                                "Object.defineProperty(navigator, 'languages', {get: function(){return ['zh-CN'];}});",
                        null
                );
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();

                // 打印所有请求URL用于调试
                Log.d("WebViewRequest", "URL being loaded: " + url);

                // 检查是否包含原神抽卡链接
                if (url.contains("public-operation-hk4e.mihoyo.com") && url.contains("authkey=")) {
                    requireActivity().runOnUiThread(() -> {
                        copyToClipboard(url);
                        showCustomSnackbar(view, requireParentFragment(), "原神抽卡链接已复制到剪贴板");
                    });
                }
                // 检查是否包含星穹铁道抽卡链接
                else if (url.contains("public-operation-hkrpg.mihoyo.com") && url.contains("authkey=")) {
                    requireActivity().runOnUiThread(() -> {
                        copyToClipboard(url);
                        showCustomSnackbar(view, requireParentFragment(), "星穹铁道抽卡链接已复制到剪贴板");
                    });
                }
                // 检查是否包含绝区零抽卡链接
                else if (url.contains("public-operation-nap.mihoyo.com") && url.contains("authkey=")) {
                    requireActivity().runOnUiThread(() -> {
                        copyToClipboard(url);
                        showCustomSnackbar(view, requireParentFragment(), "绝区零抽卡链接已复制到剪贴板");
                    });
                }

                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }
        });

        // 加载米哈游云游戏页面
        webView.loadUrl("https://mhyy.mihoyo.com/");
    }

    private class LoadLinksTask {
        private final int gameType;

        public LoadLinksTask(int gameType) {
            this.gameType = gameType;
        }

        public void execute() {
            if (currentUserId == null || currentUserId.equals("default_user")) {
                requireActivity().runOnUiThread(() -> {
                    errorTextView.setText("请先选择一个用户");
                    errorTextView.setVisibility(View.VISIBLE);
                });
                return;
            }

            progressBar.setVisibility(View.VISIBLE);
            errorTextView.setVisibility(View.GONE);

            executor.execute(() -> {
                Exception exception = null;
                Map<Integer, String> result = null;

                try {
                    GachaLink gachaLink = new GachaLink(requireContext(), currentUserId);
                    if (gameType == 0) {
                        // 原神
                        result = gachaLink.genshin();
                    } else if (gameType == 1) {
                        // 绝区零
                        result = gachaLink.zzz();
                    } else {
                        // 云游戏
                        requireActivity().runOnUiThread(() -> {
                            progressBar.setVisibility(View.GONE);
                            recyclerView.setVisibility(View.GONE);
                            errorTextView.setVisibility(View.GONE);
                        });
                        return;
                    }
                } catch (Exception e) {
                    exception = e;
                }

                // 切换到主线程更新UI
                Map<Integer, String> finalResult = result;
                Exception finalException = exception;
                requireActivity().runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    if (finalResult != null) {
                        // 保存当前标签页的结果
                        tabResults.put(gameType, finalResult);

                        // 只有当当前标签页是正在加载的标签页时才更新UI
                        if (currentTabPosition == gameType) {
                            if (!finalResult.isEmpty()) {
                                adapter.setLinks(finalResult);
                                recyclerView.setVisibility(View.VISIBLE);
                            } else {
                                errorTextView.setText("没有游戏角色或获取链接出错");
                                errorTextView.setVisibility(View.VISIBLE);
                                recyclerView.setVisibility(View.GONE);
                            }
                        }
                    } else if (gameType != 2) { // 不是云游戏选项
                        // 保存空结果以表示已尝试加载
                        tabResults.put(gameType, new HashMap<>());

                        // 只有当当前标签页是正在加载的标签页时才更新UI
                        if (currentTabPosition == gameType) {
                            errorTextView.setText(finalException != null ? finalException.getMessage() : "获取链接失败");
                            errorTextView.setVisibility(View.VISIBLE);
                            recyclerView.setVisibility(View.GONE);
                        }
                    }
                });
            });
        }
    }

    private class LinkAdapter extends RecyclerView.Adapter<LinkViewHolder> {
        private Map<Integer, String> links;

        public void setLinks(Map<Integer, String> links) {
            this.links = links;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public LinkViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_gacha_link, parent, false);
            return new LinkViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull LinkViewHolder holder, int position) {
            if (links != null) {
                Integer[] uids = links.keySet().toArray(new Integer[0]);
                Integer uid = uids[position];
                String link = links.get(uid);

                holder.uidTextView.setText(new StringBuilder("UID: " + uid));
                holder.linkTextView.setText(link);
                holder.copyButton.setOnClickListener(v -> copyToClipboard(link));
            }
        }

        @Override
        public int getItemCount() {
            return links != null ? links.size() : 0;
        }

        private void copyToClipboard(String text) {
            ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Gacha Link", text);
            clipboard.setPrimaryClip(clip);
            showCustomSnackbar(view, requireParentFragment(), "链接已复制到剪贴板");
        }
    }

    private static class LinkViewHolder extends RecyclerView.ViewHolder {
        MaterialTextView uidTextView;
        MaterialTextView linkTextView;
        com.google.android.material.button.MaterialButton copyButton;

        public LinkViewHolder(@NonNull View itemView) {
            super(itemView);
            uidTextView = itemView.findViewById(R.id.uidTextView);
            linkTextView = itemView.findViewById(R.id.linkTextView);
            copyButton = itemView.findViewById(R.id.copyButton);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
        if (webView != null) {
            webViewContainer.removeView(webView);
            webView.destroy();
            webView = null;
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // 每次恢复Fragment时更新下拉框
        updateDropdown();
    }
}
