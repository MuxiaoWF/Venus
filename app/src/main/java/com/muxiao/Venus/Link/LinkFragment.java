package com.muxiao.Venus.Link;

import dagger.hilt.android.AndroidEntryPoint;

import android.animation.ObjectAnimator;

import static com.muxiao.Venus.common.tools.copyToClipboard;
import static com.muxiao.Venus.common.tools.showCustomSnackbar;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.net.VpnService;
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textview.MaterialTextView;
import com.muxiao.Venus.R;
import com.muxiao.Venus.User.AvatarStore;
import com.muxiao.Venus.User.UserManager;
import com.muxiao.Venus.common.BatteryHelper;
import com.muxiao.Venus.common.MiHoYoBBSConstants;
import com.muxiao.Venus.User.UserDropdownAdapter;
import com.muxiao.Venus.common.ui.BottomBarOverlay;
import com.muxiao.Venus.common.ui.LensGlassDrawable;
import com.muxiao.Venus.common.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.widget.ImageView;
import android.widget.LinearLayout;

/**
 * 抽卡链接页（命令台来源列表）：原神/绝区零为「点行即获取」，结果与复制长在行下；
 * 云游戏获取为导航行，打开内置 WebView 访问米游社抽卡记录页面，自动拦截含 authkey 的 URL；
 * VPN 抓包获取为导航行，启动本地 VPN 后在星铁内打开抽卡记录页，旁路解密 KCP 流量捕获 authkey。
 */
@AndroidEntryPoint
public class LinkFragment extends Fragment {

    /** VPN 抓包服务授权回调 */
    private final androidx.activity.result.ActivityResultLauncher<android.content.Intent> vpnLauncher =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == androidx.fragment.app.FragmentActivity.RESULT_OK) {
                            CaptureService.start(requireContext().getApplicationContext());
                            setStatusVpn(getString(R.string.vpn_capture_running_hint), VpnStatus.RUNNING);
                            View root = getView();
                            if (root != null)
                                tools.showCustomSnackbar(root, requireContext().getApplicationContext(),
                                        getString(R.string.vpn_capture_started));
                        } else {
                            View root = getView();
                            if (root != null)
                                tools.showCustomSnackbar(root, requireContext().getApplicationContext(),
                                        getString(R.string.vpn_capture_permission_denied));
                        }
                    });

    /**
     * 电池优化豁免授权回调：无论用户是否豁免，返回后都续启抓包流程
     * （未豁免时仅效果打折——挂机熄屏后可能被省电策略掐网，不影响前台使用）。
     */
    private final androidx.activity.result.ActivityResultLauncher<android.content.Intent> batteryLauncher =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                    result -> startCaptureAfterChecks());

    /**
     * 捕获结果广播接收器（CaptureService/PacketAnalyzer 发出，均带包名限制）：
     * UPDATING = 缺键、云端密钥表更新中（抓包继续，重放机制衔接）；
     * GET_AUTHKEY = 捕获成功，按判定渠道拼抽卡记录 URL 复制并提示；
     * CAPTURE_FAILED = 补拉落定后仍缺键（网络失败或服务端未收录），行内提示原因。
     * 三条路径都同步 status_vpn 行文案——触发时用户多半在游戏里、Snackbar 一闪即逝，
     * 行内持久状态是切回应用后唯一可见的反馈（onResume 也会兜底回显）。
     */
    private final BroadcastReceiver authkeyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            View root = getView();
            if (CaptureService.ACTION_CAPTURE_UPDATING.equals(intent.getAction())) {
                if (root == null) return;
                setStatusVpn(getString(R.string.vpn_capture_updating), VpnStatus.WARN);
                tools.showCustomSnackbar(root, context.getApplicationContext(),
                        getString(R.string.vpn_capture_updating));
                return;
            }
            if (CaptureService.ACTION_CAPTURE_FAILED.equals(intent.getAction())) {
                if (root == null) return;
                setStatusVpn(getString(R.string.vpn_capture_failed_key), VpnStatus.WARN);
                tools.showCustomSnackbar(root, context.getApplicationContext(),
                        getString(R.string.vpn_capture_failed_key));
                return;
            }
            if (!CaptureService.ACTION_GET_AUTHKEY.equals(intent.getAction())) return;
            String authkey = intent.getStringExtra("authkey");
            if (authkey == null || authkey.isEmpty()) return;
            String channel = intent.getStringExtra("channel");
            if (root == null) return;
            lastCapturedChannel = channel;
            lastCapturedUrl = buildHsrUrl(authkey, channel);
            copyToClipboard(root, context.getApplicationContext(), lastCapturedUrl);
            setStatusVpn(getString(R.string.vpn_authkey_captured, channelLabel(channel)),
                    VpnStatus.SUCCESS);
            renderVpnResult();
            tools.showCustomSnackbar(root, context.getApplicationContext(),
                    getString(R.string.vpn_authkey_captured, channelLabel(channel)));
        }
    };

    /* ===== VPN 抓包状态与结果常驻 ===== */

    /** status_vpn 语义状态：着色让用户切回应用后一眼分清运行中/已捕获/异常 */
    private enum VpnStatus {DEFAULT, RUNNING, SUCCESS, WARN}

    /** 首次访问缓存的 status_vpn 默认文字色（DEFAULT 态恢复用） */
    private android.content.res.ColorStateList defaultVpnStatusColor;

    /** 最近一次捕获的渠道与完整 URL：常驻结果区的数据源（进程内记忆，重启抓包不丢） */
    @Nullable
    private String lastCapturedChannel;
    @Nullable
    private String lastCapturedUrl;

    /** 按语义状态更新 status_vpn 文案与文字色（DEFAULT 恢复样式原色） */
    private void setStatusVpn(CharSequence text, VpnStatus state) {
        View root = getView();
        if (root == null) return;
        MaterialTextView tv = root.findViewById(R.id.status_vpn);
        tv.setText(text);
        Context ctx = requireContext();
        if (defaultVpnStatusColor == null)
            defaultVpnStatusColor = tv.getTextColors();
        switch (state) {
            case RUNNING:
                tv.setTextColor(MaterialColors.getColor(ctx,
                        androidx.appcompat.R.attr.colorPrimary, 0xFF415F91));
                break;
            case SUCCESS:
                tv.setTextColor(ContextCompat.getColor(ctx, R.color.status_success));
                break;
            case WARN:
                tv.setTextColor(ContextCompat.getColor(ctx, R.color.status_warning));
                break;
            default:
                if (defaultVpnStatusColor != null)
                    tv.setTextColor(defaultVpnStatusColor);
                break;
        }
    }

    /** VPN 抓包结果区：捕获成功后常驻渲染链接行（切页/切后台回来仍在，可重复复制）。 */
    private void renderVpnResult() {
        View root = getView();
        if (root == null) return;
        LinearLayout container = root.findViewById(R.id.result_vpn);
        container.removeAllViews();
        if (lastCapturedUrl == null) {
            container.setVisibility(View.GONE);
            return;
        }
        container.addView(buildLinkRow(
                lastCapturedChannel == null
                        ? getString(R.string.server_cn)
                        : channelLabel(lastCapturedChannel),
                lastCapturedUrl));
        container.setVisibility(View.VISIBLE);
    }

    /** 按渠道拼星铁抽卡记录 URL：B服与国服同 host（game_biz 区分），国际服换 hoyoverse host + 英文 */
    private String buildHsrUrl(String authkey, String channel) {
        boolean global = CaptureService.CHANNEL_GLOBAL.equals(channel);
        String base = global ? com.muxiao.Venus.common.Constants.Urls.HSR_GACHA_URL_GLOBAL
                : com.muxiao.Venus.common.Constants.Urls.HSR_GACHA_URL_CN;
        String biz = CaptureService.CHANNEL_BILI.equals(channel) ? "hkrpg_bilibili"
                : global ? "hkrpg_global" : "hkrpg_cn";
        return base + android.net.Uri.encode(authkey)
                + "&game_biz=" + biz + "&page=1&size=20&gacha_type=11&end_id=0";
    }

    private String channelLabel(String channel) {
        if (CaptureService.CHANNEL_BILI.equals(channel)) return getString(R.string.capture_channel_bili);
        if (CaptureService.CHANNEL_GLOBAL.equals(channel)) return getString(R.string.server_os);
        return getString(R.string.server_cn);
    }

    private String currentUserId;
    private UserManager userManager;
    private com.google.android.material.textfield.MaterialAutoCompleteTextView userDropdown;
    /** 当前服务器可用用户列表，供无用户空态引导判断（updateDropdown 维护）。 */
    private List<String> currentUsers;
    private View userSection;

    // 按 gameType -> userId -> (uid -> link) 存储（0=原神, 1=绝区零）
    private final Map<Integer, Map<String, Map<Integer, String>>> tabResults = new HashMap<>();

    /** 获取中动效：行尾刷新图标的无限旋转（按 gameType 缓存，onDestroyView 取消）。 */
    private final ObjectAnimator[] refreshSpinners = new ObjectAnimator[2];

    /** chip 上一次渲染的状态：状态未变不重放淡入（onResume 重渲染时避免闪动），视图重建时按 XML 初始态复位。 */
    private final ChipState[] lastChipStates = {ChipState.PENDING, ChipState.PENDING};

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_link, container, false);
        // 视图重建：chip 回到 XML 初始态 PENDING，状态记忆同步复位（重建后首次渲染不重放淡入）
        lastChipStates[0] = ChipState.PENDING;
        lastChipStates[1] = ChipState.PENDING;

        // 液态玻璃透镜接管：VPN 说明圆 / 云游戏导航圆 / 动态复制按钮 / 状态圆（icon_genshin/zzz）
        // / 刷新圆（refresh_genshin/zzz）——有背景图时真实模糊底衬折射，无图回退原表面。
        // 状态圆的状态切换不再整块换背景（会顶掉玻璃）：setChipState 经 setFallbackDrawable
        // 只换透镜的回退底，玻璃保持；刷新圆旋转时圆内壁纸随视图旋转（圆形取景下观感自然）。
        LensGlassDrawable.apply(view.findViewById(R.id.icon_vpn), -1f);
        LensGlassDrawable.apply(view.findViewById(R.id.icon_nav_cloud), -1f);
        LensGlassDrawable.apply(view.findViewById(R.id.icon_genshin), -1f);
        LensGlassDrawable.apply(view.findViewById(R.id.icon_zzz), -1f);
        LensGlassDrawable.apply(view.findViewById(R.id.refresh_genshin), -1f);
        LensGlassDrawable.apply(view.findViewById(R.id.refresh_zzz), -1f);
        // 内容不再容器级避让：滚动容器底部留出底栏覆盖高度，末项可完整滚出玻璃区
        // （抽卡页来源行 + 行内结果区可整体上下滚动，获取结果展开后不再被裁出屏幕）
        BottomBarOverlay.attachClearance(view.findViewById(R.id.link_scroll));
        // 状态 chip 药丸透镜（「玻璃底 + 语义色描边」）：圆角取实际高度一半（真药丸形），
        // 需布局完成后安装；状态填充改描边的逻辑见 setChipState。
        View chipGenshin = view.findViewById(R.id.chip_genshin);
        View chipZzz = view.findViewById(R.id.chip_zzz);
        View[] stateChips = {chipGenshin, chipZzz};
        for (View chip : stateChips) {
            if (chip == null) continue;
            chip.post(() -> {
                if (chip.getHeight() > 0
                        && !(chip.getBackground() instanceof LensGlassDrawable)) {
                    LensGlassDrawable.apply(chip, chip.getHeight() / 2f);
                }
            });
        }

        // 背景图玻璃感收口：标记 glass 的表面行/chip 在有背景图时降透明度（见 tools.applyGlassAlpha）
        tools.applyGlassAlpha(requireContext(), view);

        userDropdown = view.findViewById(R.id.user_dropdown);

        userManager = new UserManager(requireContext());

        updateDropdown();

        // 点击整行（含图标）展开用户下拉；无用户时由 updateUserGuideState 改为跳转用户管理页
        userSection = view.findViewById(R.id.user_section);
        updateUserGuideState();
        userDropdown.setOnItemClickListener((parent, view1, position, id) -> {
            String selectedUser = (String) parent.getItemAtPosition(position);
            userManager.setCurrentUser(selectedUser);
            currentUserId = selectedUser;
            // 切换用户后按新用户重绘两游戏的行内结果与账号行头像；结果区重建时，上一次的行内错误随之清除
            renderGameResult(0);
            renderGameResult(1);
            loadUserAvatar();
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

        // VPN 抓包获取：启动/停止本地 VPN 抓包服务（导航行，点击切换）
        view.findViewById(R.id.src_vpn).setOnClickListener(v -> toggleVpnCapture());

        return view;
    }

    /** VPN 抓包导航行：运行中点击停止（本次未捕获时给出针对性引导）；未运行先过电池优化豁免门控再启动服务。 */
    private void toggleVpnCapture() {
        android.content.Context appContext =
                requireContext().getApplicationContext();
        View root = requireView();
        android.widget.TextView statusVpn = root.findViewById(R.id.status_vpn);
        if (CaptureService.running) {
            // 先读后停：stopCapture 会清 capturedChannel，停止后即无从判断本次是否有结果
            boolean captured = CaptureService.capturedChannel != null;
            CaptureService.stop(appContext);
            statusVpn.setText(R.string.vpn_capture_hint);
            setStatusVpn(getString(R.string.vpn_capture_hint), VpnStatus.DEFAULT);
            // 本次没抓到时给出区别于普通停止的明确提示（缺键场景另有 CAPTURE_FAILED 广播提示）
            tools.showCustomSnackbar(root, appContext, getString(captured
                    ? R.string.vpn_capture_stopped : R.string.vpn_capture_no_result));
        } else if (!BatteryHelper.isIgnoringBatteryOptimizations(appContext)) {
            // 未豁免电池优化：实验证实厂商省电策略会在熄屏/切后台后掐断抓包网络，
            // 导致游戏断连甚至闪退——先引导豁免（不强制，可「仍然启动」）。
            // 走系统设置列表页让用户手动勾选（无需权限、无 Play 政策风险），
            // 对话框文案需告知操作路径：切「所有应用」→ 找到本应用 → 允许
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.dialog_need_battery_optimization))
                    .setMessage(getString(R.string.msg_capture_battery_hint))
                    .setPositiveButton(getString(R.string.battery_request_positive), (d, w) -> {
                        try {
                            batteryLauncher.launch(
                                    BatteryHelper.buildBatteryOptimizationSettingsIntent());
                        } catch (Exception e) {
                            // 机型不支持该设置页：退回应用详情页，返回后手动再点一次
                            BatteryHelper.openVendorBatterySettings(appContext);
                        }
                    })
                    .setNegativeButton(getString(R.string.battery_start_anyway),
                            (d, w) -> startCaptureAfterChecks())
                    .show();
        } else {
            startCaptureAfterChecks();
        }
    }

    /** 电池优化门控通过后的续启流程：VPN 未授权走系统授权页，授权后启动抓包。 */
    private void startCaptureAfterChecks() {
        android.content.Context appContext = requireContext().getApplicationContext();
        android.content.Intent prepare = VpnService.prepare(appContext);
        if (prepare == null) {
            CaptureService.start(appContext);
            setStatusVpn(getString(R.string.vpn_capture_running_hint), VpnStatus.RUNNING);
            View root = getView();
            if (root != null)
                tools.showCustomSnackbar(root, appContext, getString(R.string.vpn_capture_started));
        } else {
            vpnLauncher.launch(prepare);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tools.setupFragmentTransitions(this);
        // 注册捕获结果广播（应用内广播，not exported；成功 + 失败两个 action）
        // 注册捕获结果广播（应用内广播，not exported；更新中 + 失败 + 成功三个 action）
        IntentFilter captureFilter = new IntentFilter(CaptureService.ACTION_GET_AUTHKEY);
        captureFilter.addAction(CaptureService.ACTION_CAPTURE_UPDATING);
        captureFilter.addAction(CaptureService.ACTION_CAPTURE_FAILED);
        ContextCompat.registerReceiver(requireContext().getApplicationContext(), authkeyReceiver,
                captureFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public void onDestroy() {
        requireContext().getApplicationContext().unregisterReceiver(authkeyReceiver);
        super.onDestroy();
    }

    @Override
    public void onDestroyView() {
        // 视图销毁时停掉刷新图标旋转（持有旧 View 的动画不能跨视图重建复用）
        for (int i = 0; i < refreshSpinners.length; i++) {
            if (refreshSpinners[i] != null) {
                refreshSpinners[i].cancel();
                refreshSpinners[i] = null;
            }
        }
        super.onDestroyView();
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
        com.muxiao.Venus.common.AppExecutors.get().execute(() -> {
            Map<Integer, String> result;
            GachaLink gachaLink = new GachaLink(appContext, userId);
            try {
                result = gameType == 0 ? gachaLink.genshin() : gachaLink.zzz();
            } catch (Exception e) {
                if (activity == null) return;
                activity.runOnUiThread(() -> {
                    showInlineError(gameType, tools.describeError(e));
                    setChipState(gameType, ChipState.PENDING);
                });
                return;
            }
            if (activity == null) return;
            final Map<Integer, String> fetched = result;
            activity.runOnUiThread(() -> {
                // computeIfAbsent 需 API 24，这里手动 get + put（调用处在 UI 线程，无并发问题）
                Map<String, Map<Integer, String>> perUser = tabResults.get(gameType);
                if (perUser == null) {
                    perUser = new HashMap<>();
                    tabResults.put(gameType, perUser);
                }
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
            container.addView(buildLinkRow(getString(R.string.gacha_uid_label, e.getKey()), e.getValue()));
        // 结果区出现动效：淡入 + 轻微上滑，与 chip 的"已获取"点亮同步；
        // 仅从隐藏态转可见时播（onResume 同内容重渲染不重放）
        boolean wasHidden = container.getVisibility() != View.VISIBLE;
        container.setVisibility(View.VISIBLE);
        if (wasHidden && !tools.isReducedMotionEnabled(requireContext())) {
            float slide = container.getResources().getDisplayMetrics().density * 8;
            container.setAlpha(0f);
            container.setTranslationY(slide);
            // M3：进入元素用 emphasized-decelerate，时长取 motion token（250ms）
            container.animate().alpha(1f).translationY(0f)
                    .setDuration(getResources().getInteger(R.integer.motion_duration_medium))
                    .setInterpolator(android.view.animation.AnimationUtils.loadInterpolator(
                            requireContext(), R.anim.emphasized_decelerate))
                    .start();
        }
        setChipState(gameType, ChipState.FETCHED);
        refresh.setVisibility(View.VISIBLE);
    }

    /** 构造单条链接行：小字标签（UID 或渠道）+ 截断链接 + 复制按钮。 */
    private View buildLinkRow(String label, String url) {
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
        uidView.setText(label);
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
        LensGlassDrawable.apply(copyBtn,
                ctx.getResources().getDimension(R.dimen.shape_corner_xs));
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

    /** 获取中动效：行尾刷新图标无限旋转（线性插值，速度均匀更像加载指示器）。 */
    private void setRefreshSpinning(int gameType, boolean spinning) {
        View root = getView();
        if (root == null) return;
        ImageView refresh = root.findViewById(gameType == 0 ? R.id.refresh_genshin : R.id.refresh_zzz);
        if (refresh == null) return;
        ObjectAnimator spinner = refreshSpinners[gameType];
        if (spinning) {
            if (spinner == null) {
                spinner = ObjectAnimator.ofFloat(refresh, View.ROTATION, 0f, 360f);
                spinner.setDuration(1000);
                spinner.setInterpolator(new android.view.animation.LinearInterpolator());
                spinner.setRepeatCount(ObjectAnimator.INFINITE);
                refreshSpinners[gameType] = spinner;
            }
            if (!spinner.isRunning()) spinner.start();
        } else if (spinner != null) {
            spinner.cancel();
            refreshSpinners[gameType] = null;
            refresh.setRotation(0f);
        }
    }

    /** 同步某游戏行的状态 chip（文案/底色/文字色）与头像圈（未获取=表面圆灰图标，已获取=主色圆主色图标）。 */
    private void setChipState(int gameType, ChipState state) {
        View root = requireView();
        MaterialTextView chip = root.findViewById(gameType == 0 ? R.id.chip_genshin : R.id.chip_zzz);
        ImageView icon = root.findViewById(gameType == 0 ? R.id.icon_genshin : R.id.icon_zzz);
        MaterialTextView status = root.findViewById(gameType == 0 ? R.id.status_genshin : R.id.status_zzz);
        android.content.Context ctx = requireContext();
        int gray = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF757575);
        int primary = MaterialColors.getColor(ctx, androidx.appcompat.R.attr.colorPrimary, 0xFF415F91);
        int outlineVariant = MaterialColors.getColor(ctx,
                com.google.android.material.R.attr.colorOutlineVariant, 0xFFE0E0E0);
        int success = ContextCompat.getColor(ctx, com.muxiao.Venus.R.color.status_success);
        // chip 液态玻璃化（「玻璃底 + 语义色描边」）：玻璃恒为玻璃，
        // 状态由语义色描边 + 文字色承载，不再整块换填充色；透镜未就绪（布局前）时静默跳过
        LensGlassDrawable chipLens = chip.getBackground() instanceof LensGlassDrawable
                ? (LensGlassDrawable) chip.getBackground() : null;
        switch (state) {
            case FETCHING:
                chip.setVisibility(View.VISIBLE);
                chip.setText(R.string.gacha_fetching);
                chip.setBackgroundTintList(null);
                if (chipLens != null) chipLens.setSemanticStrokeColor(primary);
                chip.setTextColor(primary);
                status.setText(R.string.gacha_fetching);
                status.setTextColor(primary);
                setIconCircleBackground(icon, R.drawable.bg_surface_circle);
                icon.setImageTintList(android.content.res.ColorStateList.valueOf(gray));
                setRefreshSpinning(gameType, true);
                break;
            case FETCHED:
                chip.setVisibility(View.VISIBLE);
                chip.setText(R.string.gacha_fetched);
                chip.setBackgroundTintList(null);
                if (chipLens != null) chipLens.setSemanticStrokeColor(success);
                // 玻璃底上直接用语义绿文字（原 on_success_container 是给实色容器底配的反色）
                chip.setTextColor(success);
                status.setText(R.string.gacha_fetched);
                status.setTextColor(success);
                setIconCircleBackground(icon, R.drawable.bg_icon_circle);
                icon.setImageTintList(android.content.res.ColorStateList.valueOf(primary));
                setRefreshSpinning(gameType, false);
                break;
            default: // PENDING
                chip.setVisibility(View.VISIBLE);
                chip.setText(R.string.gacha_chip_pending);
                chip.setBackgroundTintList(null);
                if (chipLens != null) chipLens.setSemanticStrokeColor(outlineVariant);
                chip.setTextColor(gray);
                status.setText(R.string.gacha_not_fetched);
                status.setTextColor(gray);
                setIconCircleBackground(icon, R.drawable.bg_surface_circle);
                icon.setImageTintList(android.content.res.ColorStateList.valueOf(gray));
                setRefreshSpinning(gameType, false);
                break;
        }
        // chip 状态切换淡入：仅在状态真正变化时重放，onResume 同态重渲染不闪动；
        // M3 短时长 token + emphasized-decelerate，尊重「减少动态效果」
        if (lastChipStates[gameType] != state) {
            lastChipStates[gameType] = state;
            if (tools.isReducedMotionEnabled(requireContext())) {
                chip.setAlpha(1f);
            } else {
                chip.setAlpha(0f);
                chip.animate().alpha(1f)
                        .setDuration(getResources().getInteger(R.integer.motion_duration_short))
                        .setInterpolator(android.view.animation.AnimationUtils.loadInterpolator(
                                requireContext(), R.anim.emphasized_decelerate))
                        .start();
            }
        }
    }

    /**
     * 状态圆换底：玻璃接管后不能整块 setBackgroundResource（会顶掉透镜），
     * 改为只替换透镜的回退底（无背景图时的静态观感），有背景图时玻璃恒定、
     * 状态差异由图标 tint（灰/主色）表达。
     */
    private void setIconCircleBackground(ImageView icon, int backgroundRes) {
        if (icon == null) return;
        if (icon.getBackground() instanceof LensGlassDrawable) {
            ((LensGlassDrawable) icon.getBackground())
                    .setFallbackDrawable(AppCompatResources.getDrawable(requireContext(), backgroundRes));
        } else {
            icon.setBackgroundResource(backgroundRes);
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
        // 错误出现动效：淡入 + 轻微上滑，与结果区出现动效一致（M3 emphasized-decelerate + token 时长）
        if (!tools.isReducedMotionEnabled(requireContext())) {
            float slide = errorView.getResources().getDisplayMetrics().density * 8;
            errorView.setAlpha(0f);
            errorView.setTranslationY(slide);
            errorView.animate().alpha(1f).translationY(0f)
                    .setDuration(getResources().getInteger(R.integer.motion_duration_medium))
                    .setInterpolator(android.view.animation.AnimationUtils.loadInterpolator(
                            requireContext(), R.anim.emphasized_decelerate))
                    .start();
        }
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
        currentUsers = usernames;
        // 下拉展开列表每项带头像：复用账号体系 AvatarStore（与账号行/首页同一套）
        userDropdown.setAdapter(new UserDropdownAdapter(requireContext(), usernames));
        // 行内展示带服务器后缀（如「t [国服]」，与设计稿一致）
        // 注：java.util.function.Function 需 API 24，改用下面的私有方法代替
        // 设置当前用户为默认选中项
        String currentUser = userManager.getCurrentUser();
        if (currentUser != null && !currentUser.isEmpty() && usernames.contains(currentUser)) {
            userDropdown.setText(userLabel(currentUser, isOversea), false);
            currentUserId = currentUser;
        } else if (!usernames.isEmpty()) {
            // 如果没有设置当前用户但有用户存在，默认选择第一个
            String firstUser = usernames.get(0);
            userDropdown.setText(userLabel(firstUser, isOversea), false);
            userManager.setCurrentUser(firstUser);
            currentUserId = firstUser;
        } else {
            // 当前服务器没有用户，清空选择
            userDropdown.setText("", false);
            currentUserId = null;
        }
        // 同步无用户空态引导（账号行提示 + 点击跳转用户管理页）
        updateUserGuideState();
        // 账号行头像随当前用户刷新（复用账号体系自定义头像，见 AvatarStore）
        loadUserAvatar();
    }

    /** 账号行头像：有自定义图则圆形加载，否则恢复默认人像图标（与 HomeFragment 账号行同一套）。 */
    private void loadUserAvatar() {
        View root = getView();
        if (root == null) return;
        ImageView avatar = root.findViewById(R.id.link_user_avatar);
        if (avatar == null) return;
        AvatarStore.loadInto(requireContext(), currentUserId, avatar);
    }

    /** 默认 hint 颜色（首次调用时缓存），空态结束后恢复。 */
    private android.content.res.ColorStateList defaultHintColors;

    /**
     * 无用户空态引导：当前服务器没有用户时，账号行显示「尚未添加用户 · 点击前往添加」，
     * 点击跳转用户管理页（而非弹空下拉）；有用户时恢复正常下拉行为。
     * onCreateView 与 updateDropdown（onResume 刷新）共用。
     */
    private void updateUserGuideState() {
        if (userDropdown == null || userSection == null) return;
        if (defaultHintColors == null)
            defaultHintColors = userDropdown.getHintTextColors();
        if (currentUsers != null && !currentUsers.isEmpty()) {
            userDropdown.setHint(null);
            userDropdown.setHintTextColor(defaultHintColors);
            userSection.setOnClickListener(v -> userDropdown.showDropDown());
            userDropdown.setOnClickListener(v -> userDropdown.showDropDown());
        } else {
            userDropdown.setHint(R.string.home_no_user_hint);
            // 主题默认 hint 色是低透明度变体，压在背景图上几乎看不清——空态提示改用全强度前景色
            userDropdown.setHintTextColor(com.google.android.material.color.MaterialColors
                    .getColor(userDropdown, com.google.android.material.R.attr.colorOnSurface));
            View.OnClickListener jumpToUsers = v -> {
                if (getActivity() instanceof com.muxiao.Venus.MainActivity)
                    ((com.muxiao.Venus.MainActivity) getActivity()).navigateToPage(1);
            };
            userSection.setOnClickListener(jumpToUsers);
            userDropdown.setOnClickListener(jumpToUsers);
        }
    }

    /** 生成带服务器后缀的用户展示名（如「t [国服]」）。java.util.function.Function 需 API 24，故用普通方法代替。 */
    private String userLabel(String username, boolean isOversea) {
        return username + " [" + getString(isOversea ? R.string.server_os : R.string.server_cn) + "]";
    }

    @Override
    public void onResume() {
        super.onResume();
        // 每次恢复 Fragment 时更新下拉框（并保持当前选择）
        updateDropdown();
        // 同步 VPN 抓包行状态（服务可能在其他界面被停止；已捕获时回显最近结果）
        View root = getView();
        if (root != null) {
            if (CaptureService.capturedChannel != null) {
                // 成功反馈发生在游戏后台、Snackbar 早已消失，切回应用时靠这行确认已复制
                setStatusVpn(getString(R.string.vpn_authkey_captured,
                        channelLabel(CaptureService.capturedChannel)), VpnStatus.SUCCESS);
            } else if (CaptureService.running) {
                setStatusVpn(getString(R.string.vpn_capture_running_hint), VpnStatus.RUNNING);
            } else {
                setStatusVpn(getString(R.string.vpn_capture_hint), VpnStatus.DEFAULT);
            }
        }
        // 恢复常驻结果区：捕获过的链接切页/切后台回来仍在，可随时重复复制
        renderVpnResult();
        if (getView() != null) {
            // 恢复后按当前用户重绘行内结果
            renderGameResult(0);
            renderGameResult(1);
        }
    }
}
