package com.muxiao.Venus.common.ui.compose

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.muxiao.Venus.R
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/** 登录方式：扫码（与 `UserLoginActivity.MODE_QRCODE` 对齐）。 */
const val LOGIN_MODE_QRCODE = 0
/** 登录方式：账号密码（与 `UserLoginActivity.MODE_PASSWORD` 对齐）。 */
const val LOGIN_MODE_PASSWORD = 1
/** 登录方式：浏览器 Cookie 导入（与 `UserLoginActivity.MODE_COOKIE` 对齐，仅国服）。 */
const val LOGIN_MODE_COOKIE = 2
/** 登录方式：短信验证码（与 `UserLoginActivity.MODE_SMS` 对齐，仅国服）。 */
const val LOGIN_MODE_SMS = 3

/**
 * 用户登录页：工具栏 → 页头 → 登录方式切换（国际服隐藏）→ 二维码卡 → 账号密码卡（含可选头像）→ 状态日志卡。
 * 所有输入值都上提到宿主 [UserLoginScreenView]，Activity 经 getter 读取——不要让输入变成 Compose 内部私有状态，
 * 否则与 Java 侧业务逻辑会脱节。状态日志用 `verticalScroll`，追加后自动滚到底。
 */
@Composable
fun UserLoginScreen(
    loginMode: Int,
    oversea: Boolean,
    reloginMode: Boolean,
    reloginUsername: String,
    inputsEnabled: Boolean,
    username: String,
    account: String,
    password: String,
    cookie: String,
    phone: String,
    smsCode: String,
    codeCountdown: Int,
    qrVisible: Boolean,
    qrBitmap: Bitmap?,
    log: String,
    /** 登录页选好但尚未落盘的头像（登录成功才写入该账号）；null = 用默认头像。 */
    avatarUri: Uri?,
    /** 重新登录时该账号已有的自定义头像（AvatarStore 按 storage id 定址）；无新选头像时回显，null 或无头像文件则显示默认图标。 */
    existingAvatarUser: String? = null,
    onBackClick: () -> Unit,
    onPickAvatarClick: () -> Unit,
    onModeSelect: (Int) -> Unit,
    onUsernameChange: (String) -> Unit,
    onAccountChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onCookieChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onSmsCodeChange: (String) -> Unit,
    onSendCodeClick: () -> Unit,
    onLoginClick: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textStyles = LocalVenusTextStyles.current
    val isPasswordMode = loginMode == LOGIN_MODE_PASSWORD
    val isCookieMode = loginMode == LOGIN_MODE_COOKIE
    val isSmsMode = loginMode == LOGIN_MODE_SMS

    // 验证码重发倒计时（发码成功后由宿主下发秒数）；放函数顶层而非短信分支内，切 Tab 再回来不会清零
    var codeRemain by remember { mutableIntStateOf(0) }
    LaunchedEffect(codeCountdown) {
        codeRemain = codeCountdown
        while (codeRemain > 0) {
            delay(1_000.milliseconds)
            codeRemain--
        }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // 工具栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(
                    start = dimensionResource(R.dimen.space_2),
                    end = dimensionResource(R.dimen.gutter_screen),
                    top = dimensionResource(R.dimen.space_2),
                    bottom = dimensionResource(R.dimen.space_2),
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(
                icon = VenusIcons.ChevronDown,
                contentDescription = stringResource(R.string.close_webpage),
                onClick = onBackClick,
                // chevron 默认朝下，顺时针 90° 后朝左（与全项目返回箭头同一处理）
                iconModifier = Modifier.rotate(90f),
            )
            Spacer(Modifier.width(dimensionResource(R.dimen.space_1)))
            Text(
                text = stringResource(R.string.user_login_title),
                style = textStyles.toolbarTitle,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // 与其余页面同一栅格：左右 gutter_screen(16dp)，底部让开导航栏
                .navigationBarsPadding()
                .padding(
                    start = dimensionResource(R.dimen.gutter_screen),
                    end = dimensionResource(R.dimen.gutter_screen),
                    top = dimensionResource(R.dimen.space_2),
                    bottom = dimensionResource(R.dimen.space_4),
                ),
        ) {
            LoginHeader()

            // 登录方式切换：账号密码/短信/Cookie 仅国服可用，国际服整卡隐藏
            if (!oversea) {
                // 窄屏只放短名（扫码 / 密码 / 短信 / Cookie），完整名称留给提交按钮；本页非玻璃页故 glass = false
                val loginOptions = listOf(
                    stringResource(R.string.login_tab_qrcode_short) to LOGIN_MODE_QRCODE,
                    stringResource(R.string.login_tab_password_short) to LOGIN_MODE_PASSWORD,
                    stringResource(R.string.login_tab_sms_short) to LOGIN_MODE_SMS,
                    stringResource(R.string.login_tab_cookie_short) to LOGIN_MODE_COOKIE,
                )
                VenusSegmented(
                    options = loginOptions.map { it.first },
                    selectedIndex = loginOptions.indexOfFirst { it.second == loginMode }.coerceAtLeast(0),
                    onSelect = { index -> onModeSelect(loginOptions[index].second) },
                    glass = false,
                )
                Spacer(Modifier.height(dimensionResource(R.dimen.space_3)))
            }

            // 二维码卡（切到账号密码时收起，避免残留旧 ticket）
            if (qrVisible) {
                VenusCard(modifier = Modifier.padding(bottom = dimensionResource(R.dimen.space_2))) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(dimensionResource(R.dimen.space_5)),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(dimensionResource(R.dimen.qr_code_size))
                                .clip(RoundedCornerShape(dimensionResource(R.dimen.shape_corner_sm)))
                                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (qrBitmap != null) {
                                Image(
                                    bitmap = qrBitmap.asImageBitmap(),
                                    contentDescription = stringResource(R.string.login_qr_code),
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        Spacer(Modifier.height(dimensionResource(R.dimen.space_3)))
                        Text(
                            text = stringResource(R.string.login_qr_hint),
                            style = textStyles.rowSubtitle,
                        )
                    }
                }
            }

            // 账号密码卡
            VenusCard(modifier = Modifier.padding(bottom = dimensionResource(R.dimen.space_3))) {
                Column(modifier = Modifier.padding(dimensionResource(R.dimen.space_4))) {
                    // 头像：选填，登录成功后写到这个账号名下（重登录时也能顺手换）
                    LoginAvatarRow(
                        avatarUri = avatarUri,
                        // 登录进行中禁止换头像：此时提交的任务已经带走凭证，再改会与落盘时序打架
                        enabled = inputsEnabled,
                        onPick = onPickAvatarClick,
                        // 重新登录：未新选头像时回显该账号已有头像
                        existingAvatarUser = existingAvatarUser,
                    )
                    // 重登录模式：用户名固定展示在按钮上，输入框隐藏
                    if (!reloginMode) {
                        VenusTextField(
                            value = username,
                            onValueChange = onUsernameChange,
                            label = stringResource(R.string.username_hint),
                            enabled = inputsEnabled,
                            onSubmit = onSubmit,
                        )
                    }
                    if (isPasswordMode) {
                        Spacer(Modifier.height(dimensionResource(R.dimen.space_3)))
                        VenusTextField(
                            value = account,
                            onValueChange = onAccountChange,
                            label = stringResource(R.string.login_account_hint),
                            enabled = inputsEnabled,
                            keyboardType = KeyboardType.Email,
                            onSubmit = onSubmit,
                        )
                        Spacer(Modifier.height(dimensionResource(R.dimen.space_3)))
                        VenusPasswordField(
                            value = password,
                            onValueChange = onPasswordChange,
                            label = stringResource(R.string.login_password_hint),
                            enabled = inputsEnabled,
                            onSubmit = onSubmit,
                        )
                        Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
                        Text(
                            text = stringResource(R.string.login_password_notice),
                            style = textStyles.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                    }
                    if (isCookieMode) {
                        Spacer(Modifier.height(dimensionResource(R.dimen.space_3)))
                        // Cookie 整段可能很长（十几个键值对），给多行输入框而不是单行
                        VenusTextField(
                            value = cookie,
                            onValueChange = onCookieChange,
                            label = stringResource(R.string.login_cookie_hint),
                            enabled = inputsEnabled,
                            imeAction = ImeAction.Done,
                            minLines = 3,
                            maxLines = 6,
                            onSubmit = onSubmit,
                        )
                        Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
                        Text(
                            text = stringResource(R.string.login_cookie_desc),
                            style = textStyles.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                    }
                    if (isSmsMode) {
                        Spacer(Modifier.height(dimensionResource(R.dimen.space_3)))
                        VenusTextField(
                            value = phone,
                            onValueChange = onPhoneChange,
                            label = stringResource(R.string.login_sms_phone_hint),
                            enabled = inputsEnabled,
                            keyboardType = KeyboardType.Phone,
                            onSubmit = onSubmit,
                        )
                        Spacer(Modifier.height(dimensionResource(R.dimen.space_3)))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.weight(1f)) {
                                VenusTextField(
                                    value = smsCode,
                                    onValueChange = onSmsCodeChange,
                                    label = stringResource(R.string.login_sms_code_hint),
                                    enabled = inputsEnabled,
                                    keyboardType = KeyboardType.Number,
                                    onSubmit = onSubmit,
                                )
                            }
                            Spacer(Modifier.width(dimensionResource(R.dimen.space_2)))
                            // 倒计时用服务端下发的秒数；归零前禁用，避免短时间内反复请求短信
                            TextButton(
                                onClick = onSendCodeClick,
                                enabled = inputsEnabled && codeRemain == 0,
                            ) {
                                Text(
                                    text = if (codeRemain > 0)
                                        stringResource(R.string.login_sms_resend, codeRemain)
                                    else
                                        stringResource(R.string.login_sms_send),
                                    style = textStyles.labelMedium,
                                    maxLines = 1,
                                )
                            }
                        }
                        Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
                        Text(
                            text = stringResource(R.string.login_sms_desc),
                            style = textStyles.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                    }
                    Spacer(Modifier.height(dimensionResource(R.dimen.space_4)))
                    Button(
                        onClick = onLoginClick,
                        enabled = inputsEnabled,
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = when {
                                reloginMode -> stringResource(R.string.login_relogin_button, reloginUsername)
                                isPasswordMode -> stringResource(R.string.login_tab_password)
                                isCookieMode -> stringResource(R.string.login_tab_cookie)
                                isSmsMode -> stringResource(R.string.login_tab_sms)
                                else -> stringResource(R.string.login)
                            },
                            style = textStyles.labelMedium,
                        )
                    }
                }
            }

            // 状态日志卡
            VenusCard {
                Column(modifier = Modifier.padding(dimensionResource(R.dimen.space_4))) {
                    Text(
                        text = stringResource(R.string.login_log),
                        style = textStyles.title,
                        modifier = Modifier.padding(bottom = dimensionResource(R.dimen.space_2)),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(dimensionResource(R.dimen.login_log_height)),
                    ) {
                        val logScroll = rememberScrollState()
                        // 追加日志后自动滚到底（原实现为 ScrollView.fullScroll(FOCUS_DOWN)）
                        LaunchedEffect(log) {
                            logScroll.scrollTo(Int.MAX_VALUE)
                        }
                        Text(
                            text = log,
                            style = textStyles.body.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(logScroll),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 页头：一行欢迎语 + 安全说明。刻意压到约 60dp——原来的 48dp 徽标 + 大标题 + 副标题 + 上下留白约 150dp，
 * 在 640dp 高的屏上占近四分之一却只是装饰。
 */
@Composable
private fun LoginHeader() {
    val textStyles = LocalVenusTextStyles.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = dimensionResource(R.dimen.space_1),
                bottom = dimensionResource(R.dimen.space_3),
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row {
            Text(
                text = stringResource(R.string.user_login_welcome),
                style = textStyles.title,
                // 样式只带字号不带颜色，必须显式给：否则继承 LocalContentColor 的纯黑，深色模式下与背景同色
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.alignByBaseline(),
            )
            Spacer(Modifier.width(dimensionResource(R.dimen.space_2)))
            Text(
                text = stringResource(R.string.user_login_subtitle),
                style = textStyles.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.alignByBaseline(),
            )
        }
        Spacer(Modifier.height(dimensionResource(R.dimen.space_1)))
        // 安全承诺：登录全程在本机完成，避免用户误以为应用会远程代登录
        SettingHint(
            text = stringResource(R.string.login_local_notice),
            maxLines = 3,
            textAlign = TextAlign.Center,
        )
    }
}

/** 登录页头像行：与用户管理页共用同一 AvatarStore 与头像外框，区别只在「先选、登录成功才落盘」——失败不留孤儿头像文件。 */
@Composable
private fun LoginAvatarRow(
    avatarUri: Uri?,
    enabled: Boolean,
    onPick: () -> Unit,
    existingAvatarUser: String? = null,
) {
    val textStyles = LocalVenusTextStyles.current
    val avatarSize = dimensionResource(R.dimen.icon_badge_lg)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = dimensionResource(R.dimen.space_3))
            .clickable(enabled = enabled, onClick = onPick),
        verticalAlignment = Alignment.CenterVertically,

    ) {
        Box(
            modifier = Modifier
                .size(avatarSize)
                .clip(CircleShape)
        ) {
            if (avatarUri != null) {
                AvatarPreview(model = avatarUri, size = avatarSize)
            } else {
                // 重新登录：回显该账号已有头像；storage id 在 AvatarStore 无头像文件时 VenusAvatar 内部自动回落默认图标
                VenusAvatar(username = existingAvatarUser, size = avatarSize)
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = dimensionResource(R.dimen.space_3)),
        ) {
            Text(text = stringResource(R.string.login_avatar_label), style = textStyles.drawerLabel)
            Text(
                text = stringResource(
                    when {
                        avatarUri != null -> R.string.login_avatar_picked
                        // 重新登录且回显已有头像：提示保留原头像、可点击更换
                        existingAvatarUser != null -> R.string.login_avatar_existing
                        else -> R.string.login_avatar_hint
                    }
                ),
                style = textStyles.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** [UserLoginScreen] 的 View 互操作宿主。 */
class UserLoginScreenView(context: Context) : AbstractComposeView(context) {

    private val modeState = mutableIntStateOf(LOGIN_MODE_QRCODE)
    private val overseaState = mutableStateOf(false)
    private val reloginModeState = mutableStateOf(false)
    private val reloginUsernameState = mutableStateOf("")
    private val inputsEnabledState = mutableStateOf(true)
    private val usernameState = mutableStateOf("")
    private val accountState = mutableStateOf("")
    private val passwordState = mutableStateOf("")
    private val cookieState = mutableStateOf("")
    private val phoneState = mutableStateOf("")
    private val smsCodeState = mutableStateOf("")
    private val codeCountdownState = mutableIntStateOf(0)
    private val qrVisibleState = mutableStateOf(false)
    private val qrBitmapState = mutableStateOf<Bitmap?>(null)
    private val logState = mutableStateOf("")
    /** 登录页选好的头像（尚未落盘）；登录成功后由 Activity 写入该账号。 */
    private val avatarUriState = mutableStateOf<Uri?>(null)
    /** 重新登录时该账号已有的自定义头像（AvatarStore 按 storage id 定址），用于无新选头像时回显。 */
    private val existingAvatarUserState = mutableStateOf<String?>(null)

    private var onBackClickListener: Runnable? = null
    private var onModeSelectListener: IndexConsumer? = null
    private var onLoginClickListener: Runnable? = null
    private var onSubmitListener: Runnable? = null
    private var onSendCodeClickListener: Runnable? = null
    private var onPickAvatarClickListener: Runnable? = null
    private var onClearAvatarClickListener: Runnable? = null

    /** 当前登录方式（0 扫码 / 1 账号密码）。 */
    var loginMode: Int
        get() = modeState.intValue
        set(value) {
            modeState.intValue = value
        }

    /** 国际服：隐藏登录方式切换卡。 */
    var oversea: Boolean
        get() = overseaState.value
        set(value) {
            overseaState.value = value
        }

    /** 输入区是否可用（登录进行中禁用）。 */
    var inputsEnabled: Boolean
        get() = inputsEnabledState.value
        set(value) {
            inputsEnabledState.value = value
        }

    /** 重登录模式：隐藏用户名输入框，按钮文案带上用户名。 */
    fun setRelogin(username: String) {
        reloginModeState.value = true
        reloginUsernameState.value = username
        usernameState.value = username
    }

    fun getUsername(): String = usernameState.value

    fun getAccount(): String = accountState.value

    fun getPassword(): String = passwordState.value

    /** 浏览器粘贴的整段 Cookie（Cookie 导入模式使用）。 */
    fun getCookie(): String = cookieState.value

    /** 短信登录：手机号（不含 +86，区号固定由业务侧补）。 */
    fun getPhone(): String = phoneState.value

    /** 短信登录：用户输入的验证码。 */
    fun getSmsCode(): String = smsCodeState.value

    /** 下发成功后由宿主设置重发倒计时秒数，UI 侧自行递减。 */
    fun setCodeCountdown(seconds: Int) {
        codeCountdownState.intValue = seconds.coerceAtLeast(0)
    }

    /** 清空验证码输入（登录成功后避免一次性验证码长期驻留界面）。 */
    fun clearSmsCode() {
        smsCodeState.value = ""
    }

    fun setQrVisible(visible: Boolean) {
        qrVisibleState.value = visible
    }

    fun setQrBitmap(bitmap: Bitmap?) {
        qrBitmapState.value = bitmap
    }

    /** 相册选好后的头像预览；真正的落盘由 Activity 在登录成功后执行。 */
    fun setPendingAvatar(uri: Uri?) {
        avatarUriState.value = uri
    }

    /** 重新登录：传入该账号的 storage id，登录页回显其在 AvatarStore 中已有的头像。 */
    fun setExistingAvatarUser(storageId: String?) {
        existingAvatarUserState.value = storageId
    }

    /** 清空密码框（登录成功后避免明文长期驻留界面）。 */
    fun clearPassword() {
        passwordState.value = ""
    }

    fun clearLog() {
        logState.value = ""
    }

    fun appendLog(text: String) {
        logState.value += text
    }

    @Composable
    override fun Content() {
        VenusTheme {
            VenusPageRoot {
                UserLoginScreen(
                    loginMode = modeState.intValue,
                    oversea = overseaState.value,
                    reloginMode = reloginModeState.value,
                    reloginUsername = reloginUsernameState.value,
                    inputsEnabled = inputsEnabledState.value,
                    username = usernameState.value,
                    account = accountState.value,
                    password = passwordState.value,
                    cookie = cookieState.value,
                    phone = phoneState.value,
                    smsCode = smsCodeState.value,
                    codeCountdown = codeCountdownState.intValue,
                    qrVisible = qrVisibleState.value,
                    qrBitmap = qrBitmapState.value,
                    log = logState.value,
                    avatarUri = avatarUriState.value,
                    existingAvatarUser = existingAvatarUserState.value,
                    onBackClick = { onBackClickListener?.run() },
                    onPickAvatarClick = { onPickAvatarClickListener?.run() },
                    onModeSelect = { mode ->
                        // 受控组件：先落自身状态再回调，Java 侧据此切换业务模式
                        modeState.intValue = mode
                        if (mode != LOGIN_MODE_QRCODE) qrVisibleState.value = false
                        onModeSelectListener?.accept(mode)
                    },
                    onUsernameChange = { usernameState.value = it },
                    onAccountChange = { accountState.value = it },
                    onPasswordChange = { passwordState.value = it },
                    onCookieChange = { cookieState.value = it },
                    onPhoneChange = { phoneState.value = it },
                    onSmsCodeChange = { smsCodeState.value = it },
                    onSendCodeClick = { onSendCodeClickListener?.run() },
                    onLoginClick = { onLoginClickListener?.run() },
                    onSubmit = { onSubmitListener?.run() },
                )
            }
        }
    }

    fun setOnBackClickListener(listener: Runnable?) {
        onBackClickListener = listener
    }

    fun setOnModeSelectListener(listener: IndexConsumer?) {
        onModeSelectListener = listener
    }

    fun setOnLoginClickListener(listener: Runnable?) {
        onLoginClickListener = listener
    }

    fun setOnSubmitListener(listener: Runnable?) {
        onSubmitListener = listener
    }

    /** 短信登录的「获取验证码」按钮回调（与登录提交分开派发）。 */
    fun setOnSendCodeClickListener(listener: Runnable?) {
        onSendCodeClickListener = listener
    }

    fun setOnPickAvatarClickListener(listener: Runnable?) {
        onPickAvatarClickListener = listener
    }

    fun setOnClearAvatarClickListener(listener: Runnable?) {
        onClearAvatarClickListener = listener
    }
}

@Preview(name = "用户登录", showBackground = true)
@Composable
private fun UserLoginScreenPreview() {
    VenusTheme {
        UserLoginScreen(
            loginMode = LOGIN_MODE_PASSWORD,
            oversea = false,
            reloginMode = false,
            reloginUsername = "",
            inputsEnabled = true,
            username = "muxiao",
            account = "user@example.com",
            password = "secret",
            cookie = "ltuid_v2=306118684; ltoken_v2=xxx",
            phone = "13800138000",
            smsCode = "123456",
            codeCountdown = 0,
            qrVisible = false,
            qrBitmap = null,
            log = stringResource(R.string.waiting),
            avatarUri = null,
            onBackClick = {},
            onPickAvatarClick = {},
            onModeSelect = {},
            onUsernameChange = {},
            onAccountChange = {},
            onPasswordChange = {},
            onCookieChange = {},
            onPhoneChange = {},
            onSmsCodeChange = {},
            onSendCodeClick = {},
            onLoginClick = {},
            onSubmit = {},
        )
    }
}
