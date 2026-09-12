package com.muxiao.Venus.Link;

import dagger.hilt.android.AndroidEntryPoint;

import static com.muxiao.Venus.common.tools.copyToClipboard;
import static com.muxiao.Venus.common.tools.showCustomSnackbar;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.textview.MaterialTextView;
import com.muxiao.Venus.R;
import com.muxiao.Venus.User.UserManager;
import com.muxiao.Venus.common.MiHoYoBBSConstants;
import com.muxiao.Venus.common.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.widget.ImageView;
import android.widget.LinearLayout;

/**
 * 抽卡链接页（命令台来源列表）：原神/绝区零为「点行即获取」，结果与复制长在行下；
 * 云游戏获取为导航行，打开内置 WebView 访问米游社抽卡记录页面，自动拦截含 authkey 的 URL。
 */
@AndroidEntryPoint
public class LinkFragment extends Fragment {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tools.setupFragmentTransitions(this);
    }

    private String currentUserId;
    private ExecutorService executor;
    private UserManager userManager;
    private com.google.android.material.textfield.MaterialAutoCompleteTextView userDropdown;

    // 按 gameType -> userId -> (uid -> link) 存储（0=原神, 1=绝区零）
    private final Map<Integer, Map<String, Map<Integer, String>>> tabResults = new HashMap<>();

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_link, container, false);

        userDropdown = view.findViewById(R.id.user_dropdown);

        userManager = new UserManager(requireContext());

        updateDropdown();

        // 点击整行（含图标）展开用户下拉
        View userSection = view.findViewById(R.id.user_section);
        userSection.setOnClickListener(v -> userDropdown.showDropDown());
        userDropdown.setOnItemClickListener((parent, view1, position, id) -> {
            String selectedUser = (String) parent.getItemAtPosition(position);
            userManager.setCurrentUser(selectedUser);
            currentUserId = selectedUser;
            // 切换用户后按新用户重绘两游戏的行内结果；结果区重建时，上一次的行内错误随之清除
            renderGameResult(0);
            renderGameResult(1);
        });

        // 原神 / 绝区零：点行或刷新图标即获取
        View srcGenshin = view.findViewById(R.id.src_genshin);
        View srcZzz = view.findViewById(R.id.src_zzz);
        srcGenshin.setOnClickListener(v -> fetchGame(0));
        srcZzz.setOnClickListener(v -> fetchGame(1));
        view.findViewById(R.id.refresh_genshin).setOnClickListener(v -> fetchGame(0));
        view.findViewById(R.id.refresh_zzz).setOnClickListener(v -> fetchGame(1));

        // 云游戏获取：打开独立 WebView 页面（导航行）
        view.findViewById(R.id.src_cloud).setOnClickListener(v ->
                startActivity(new android.content.Intent(requireContext(), CloudGachaActivity.class)));

        return view;
    }

    /** 按 gameType（0=原神, 1=绝区零）拉取抽卡链接：成功行内展示结果，失败则在同一行内就地报错。 */
    private void fetchGame(int gameType) {
        if (currentUserId == null || currentUserId.isEmpty()) {
            showInlineError(gameType, getString(R.string.msg_select_user_first));
            return;
        }
        // 重绘结果区，顺带清掉上一次的行内错误；再进入获取态
        renderGameResult(gameType);
        setChipState(gameType, ChipState.FETCHING);

        // 预先在 UI 线程取出上下文与用户快照：后台线程调用 requireContext()/requireActivity()
        // 在 Fragment detach（切页/返回）后会抛 IllegalStateException 导致请求结果丢失。
        final android.content.Context appContext = requireContext().getApplicationContext();
        final android.app.Activity activity = getActivity();
        final String userId = currentUserId;
        executor.execute(() -> {
            Map<Integer, String> result;
            GachaLink gachaLink = new GachaLink(appContext, userId);
            try {
                result = gameType == 0 ? gachaLink.genshin() : gachaLink.zzz();
            } catch (Exception e) {
                if (activity == null) return;
                activity.runOnUiThread(() -> {
                    showInlineError(gameType, e.getMessage());
                    setChipState(gameType, ChipState.PENDING);
                });
                return;
            }
            if (activity == null) return;
            final Map<Integer, String> fetched = result;
            activity.runOnUiThread(() -> {
                Map<String, Map<Integer, String>> perUser =
                        tabResults.computeIfAbsent(gameType, k -> new HashMap<>());
                perUser.put(userId, new HashMap<>(fetched));
                View root = getView();
                if (root == null) return;
                if (fetched.isEmpty()) {
                    // 无角色 / 未取到链接：直接把本行结果区替换成错误提示
                    renderGameResult(gameType);
                    showInlineError(gameType, getString(R.string.msg_no_game_role_or_error));
                    setChipState(gameType, ChipState.PENDING);
                } else {
                    renderGameResult(gameType);
                    copyToClipboard(root, appContext, fetched.values().iterator().next());
                    showCustomSnackbar(root, appContext, getString(R.string.snack_link_copied));
                }
            });
        });
    }

    /** 行内渲染某游戏的全部链接（uid 分组，每条带复制按钮）。 */
    private void renderGameResult(int gameType) {
        View root = requireView();
        LinearLayout container = root.findViewById(gameType == 0 ? R.id.result_genshin : R.id.result_zzz);
        ImageView refresh = root.findViewById(gameType == 0 ? R.id.refresh_genshin : R.id.refresh_zzz);
        Map<String, Map<Integer, String>> perUser = tabResults.get(gameType);
        Map<Integer, String> links = perUser == null ? null : perUser.get(currentUserId);

        hideInlineError(gameType);
        container.removeAllViews();
        if (links == null || links.isEmpty()) {
            container.setVisibility(View.GONE);
            setChipState(gameType, ChipState.PENDING);
            refresh.setVisibility(View.VISIBLE);
            return;
        }
        for (Map.Entry<Integer, String> e : links.entrySet())
            container.addView(buildLinkRow(e.getKey(), e.getValue()));
        container.setVisibility(View.VISIBLE);
        setChipState(gameType, ChipState.FETCHED);
        refresh.setVisibility(View.VISIBLE);
    }

    /** 构造单条链接行：UID 小字 + 截断链接 + 复制按钮。 */
    private View buildLinkRow(int uid, String url) {
        android.content.Context ctx = requireContext();
        float density = ctx.getResources().getDisplayMetrics().density;

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (8 * density), 0, (int) (8 * density));

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textColParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(textCol, textColParams);

        MaterialTextView uidView = new MaterialTextView(ctx);
        uidView.setText(getString(R.string.gacha_uid_label, uid));
        uidView.setTextSize(10);
        uidView.setTypeface(Typeface.MONOSPACE);
        uidView.setLetterSpacing(0.05f);
        uidView.setTextColor(MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF757575));
        textCol.addView(uidView);

        MaterialTextView linkView = new MaterialTextView(ctx);
        linkView.setText(url);
        linkView.setTextSize(11);
        linkView.setMaxLines(1);
        linkView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        linkView.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams linkParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        linkParams.topMargin = (int) (4 * density);
        textCol.addView(linkView, linkParams);

        ImageView copyBtn = new ImageView(ctx);
        copyBtn.setImageResource(R.drawable.ic_copy);
        copyBtn.setBackground(AppCompatResources.getDrawable(ctx, R.drawable.bg_surface_button));
        // 图标着次要色：ic_copy 原始为白色，浅色表面上不彩色会与底融为一体（视觉上像透明）
        copyBtn.setImageTintList(android.content.res.ColorStateList.valueOf(
                MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF757575)));
        int pad = (int) (7 * density);
        copyBtn.setPadding(pad, pad, pad, pad);
        copyBtn.setContentDescription(getString(R.string.copy));
        copyBtn.setOnClickListener(v -> copyToClipboard(v, ctx, url));
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(
                (int) (32 * density), (int) (32 * density));
        copyParams.setMarginStart((int) (12 * density));
        row.addView(copyBtn, copyParams);
        return row;
    }

    /** 来源行状态（对齐设计稿 chip 语义：待获取/获取中 = 中性 chip，已获取 = 语义成功 chip）。 */
    private enum ChipState {PENDING, FETCHING, FETCHED}

    /** 同步某游戏行的状态 chip（文案/底色/文字色）与头像圈（未获取=表面圆灰图标，已获取=主色圆主色图标）。 */
    private void setChipState(int gameType, ChipState state) {
        View root = requireView();
        MaterialTextView chip = root.findViewById(gameType == 0 ? R.id.chip_genshin : R.id.chip_zzz);
        ImageView icon = root.findViewById(gameType == 0 ? R.id.icon_genshin : R.id.icon_zzz);
        MaterialTextView status = root.findViewById(gameType == 0 ? R.id.status_genshin : R.id.status_zzz);
        android.content.Context ctx = requireContext();
        int gray = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF757575);
        int primary = MaterialColors.getColor(ctx, androidx.appcompat.R.attr.colorPrimary, 0xFF415F91);
        // chip 合并后唯一底图 bg_chip，填充色由 tint 按状态给定（中性=凸面，成功=语义成功容器）
        int chipNeutral = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFFF3F3FA);
        int chipSuccess = ContextCompat.getColor(ctx, com.muxiao.Venus.R.color.status_success_container);
        switch (state) {
            case FETCHING:
                chip.setVisibility(View.VISIBLE);
                chip.setText(R.string.gacha_fetching);
                chip.setBackgroundResource(R.drawable.bg_chip);
                chip.setBackgroundTintList(android.content.res.ColorStateList.valueOf(chipNeutral));
                chip.setTextColor(primary);
                status.setText(R.string.gacha_fetching);
                status.setTextColor(primary);
                icon.setBackgroundResource(R.drawable.bg_surface_circle);
                icon.setImageTintList(android.content.res.ColorStateList.valueOf(gray));
                break;
            case FETCHED:
                chip.setVisibility(View.VISIBLE);
                chip.setText(R.string.gacha_fetched);
                chip.setBackgroundResource(R.drawable.bg_chip);
                chip.setBackgroundTintList(android.content.res.ColorStateList.valueOf(chipSuccess));
                chip.setTextColor(ContextCompat.getColor(ctx, com.muxiao.Venus.R.color.status_on_success_container));
                status.setText(R.string.gacha_fetched);
                status.setTextColor(ContextCompat.getColor(ctx, com.muxiao.Venus.R.color.status_success));
                icon.setBackgroundResource(R.drawable.bg_icon_circle);
                icon.setImageTintList(android.content.res.ColorStateList.valueOf(primary));
                break;
            default: // PENDING
                chip.setVisibility(View.VISIBLE);
                chip.setText(R.string.gacha_chip_pending);
                chip.setBackgroundResource(R.drawable.bg_chip);
                chip.setBackgroundTintList(android.content.res.ColorStateList.valueOf(chipNeutral));
                chip.setTextColor(gray);
                status.setText(R.string.gacha_not_fetched);
                status.setTextColor(gray);
                icon.setBackgroundResource(R.drawable.bg_surface_circle);
                icon.setImageTintList(android.content.res.ColorStateList.valueOf(gray));
                break;
        }
    }

    /**
     * 就地错误：把错误文案写进该来源行的错误位，并收起结果行 —— 错误「顶替」结果的位置出现，
     * 不在页面下方另起一块独立提示卡。错误跟着出事的这一行走，两个来源互不干扰。
     */
    private void showInlineError(int gameType, @Nullable String message) {
        View root = requireView();
        LinearLayout result = root.findViewById(gameType == 0 ? R.id.result_genshin : R.id.result_zzz);
        MaterialTextView errorView = root.findViewById(gameType == 0 ? R.id.error_genshin : R.id.error_zzz);
        result.removeAllViews();
        result.setVisibility(View.GONE);
        errorView.setText(message != null && !message.isEmpty()
                ? message
                : getString(R.string.msg_no_game_role_or_error));
        errorView.setVisibility(View.VISIBLE);
    }

    /** 收起某来源行的就地错误（重绘结果时调用）。 */
    private void hideInlineError(int gameType) {
        View root = requireView();
        root.findViewById(gameType == 0 ? R.id.error_genshin : R.id.error_zzz)
                .setVisibility(View.GONE);
    }

    /**
     * 按当前服务器类型填充用户下拉框，并选定当前/首个用户；无用户则清空选择。
     */
    private void updateDropdown() {
        boolean isOversea = MiHoYoBBSConstants.is_oversea(requireContext());
        List<String> usernames = userManager.getUsernamesByServerType(isOversea);
        android.widget.ArrayAdapter<String> dropdownAdapter = new android.widget.ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                usernames
        );
        userDropdown.setAdapter(dropdownAdapter);
        // 行内展示带服务器后缀（如「t [国服]」，与设计稿一致）
        boolean finalIsOversea = isOversea;
        java.util.function.Function<String, String> label = u ->
                u + " [" + getString(finalIsOversea ? R.string.server_os : R.string.server_cn) + "]";
        // 设置当前用户为默认选中项
        String currentUser = userManager.getCurrentUser();
        if (currentUser != null && !currentUser.isEmpty() && usernames.contains(currentUser)) {
            userDropdown.setText(label.apply(currentUser), false);
            currentUserId = currentUser;
        } else if (!usernames.isEmpty()) {
            // 如果没有设置当前用户但有用户存在，默认选择第一个
            String firstUser = usernames.get(0);
            userDropdown.setText(label.apply(firstUser), false);
            userManager.setCurrentUser(firstUser);
            currentUserId = firstUser;
        } else {
            // 当前服务器没有用户，清空选择
            userDropdown.setText("", false);
            currentUserId = null;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executor != null && !executor.isShutdown())
            executor.shutdown();
    }

    @Override
    public void onResume() {
        super.onResume();
        // 每次恢复 Fragment 时更新下拉框（并保持当前选择）
        updateDropdown();
        if (getView() != null) {
            // 恢复后按当前用户重绘行内结果
            renderGameResult(0);
            renderGameResult(1);
        }
    }
}
