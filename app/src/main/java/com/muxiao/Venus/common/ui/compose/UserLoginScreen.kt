package com.muxiao.Venus.common.ui.compose

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.muxiao.Venus.R
import kotlinx.coroutines.delay

/** 登录方式：扫码（与 `UserLoginActivity.MODE_QRCODE` 对齐）。 */
const val LOGIN_MODE_QRCODE = 0
/** 登录方式：账号密码（与 `UserLoginActivity.MODE_PASSWORD` 对齐）。 */
const val LOGIN_MODE_PASSWORD = 1
/** 登录方式：浏览器 Cookie 导入（与 `UserLoginActivity.MODE_COOKIE` 对齐，仅国服）。 */
const val LOGIN_MODE_COOKIE = 2
/** 登录方式：短信验证码（与 `UserLoginActivity.MODE_SMS` 对齐，仅国服）。 */
const val LOGIN_MODE_SMS = 3

/**
 * 国服用户登录页（原 res/layout/activity_user_login.xml）。
 *
 * 结构：工具栏 → 品牌欢迎区 → 登录方式切换（国际服隐藏）→ 二维码卡 → 账号密码卡 → 状态日志卡。
 *
 * 与原 XML 的对应关系：
 * - 两枚 TabLayout 页签 → [SingleChoiceSegmentedButtonRow] + [SegmentedButton] 单选分段
 * - 三个 `TextInputLayout(OutlinedBox)` → [OutlinedTextField] + `shape_corner_sm` 圆角
 * - 密码框的 `endIconMode=password_toggle` → trailingIcon 文案按钮（显示/隐藏）
 * - 状态日志 `ScrollView`（固定 `login_log_height`）→ `verticalScroll` + 追加后自动滚到底
 *
 * 所有输入值都**上提到宿主**（[UserLoginScreenView]），Activity 通过 getter 读取，
 * 避免 Compose 内部私有状态与 Java 侧业务逻辑脱节。
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
    onBackClick: () -> Unit,
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

    // 验证码重发倒计时：宿主在发码成功后下发秒数，这里每秒递减。
    // 状态放在函数顶层而非短信分支内，切到其他 Tab 再切回来不会把倒计时清零。
    var codeRemain by remember { mutableIntStateOf(0) }
    LaunchedEffect(codeCountdown) {
        codeRemain = codeCountdown
        while (codeRemain > 0) {
            delay(1_000)
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
            IconButton(onClick = onBackClick) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron),
                    contentDescription = stringResource(R.string.close_webpage),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.rotate(90f),
                )
            }
            Spacer(Modifier.width(dimensionResource(R.dimen.space_1)))
            Text(
                text = stringResource(R.string.user_login_title),
                style = textStyles.toolbarTitle,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = dimensionResource(R.dimen.space_5),
                    end = dimensionResource(R.dimen.space_5),
                    top = dimensionResource(R.dimen.space_2),
                    bottom = dimensionResource(R.dimen.space_4),
                ),
        ) {
            BrandHeader()

            // 登录方式切换：账号密码仅国服可用，国际服整卡隐藏
            if (!oversea) {
                    val loginOptions = listOf(
                        stringResource(R.string.login_tab_qrcode) to LOGIN_MODE_QRCODE,
                        stringResource(R.string.login_tab_password) to LOGIN_MODE_PASSWORD,
                        // 短信验证码走 passport 账号 SDK，同样只对国服有效
                        stringResource(R.string.login_tab_sms) to LOGIN_MODE_SMS,
                        // Cookie 导入同样只对国服有效（国际服走 HoYoLAB 授权页）
                        stringResource(R.string.login_tab_cookie) to LOGIN_MODE_COOKIE,
                    )
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier
                            .fillMaxWidth()
                    ) {
                        loginOptions.forEachIndexed { index, (text, mode) ->
                            SegmentedButton(
                                selected = loginMode == mode,
                                onClick = { onModeSelect(mode) },
                                // 按位置给出形状：首尾圆角、中间段直角，相邻段共用边框
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = loginOptions.size,
                                ),
                                label = {
                                    Text(
                                        text = text,
                                        style = textStyles.labelMedium,
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                            )
                        }
                    }
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
                        OutlinedTextField(
                            value = cookie,
                            onValueChange = onCookieChange,
                            label = { Text(stringResource(R.string.login_cookie_hint)) },
                            enabled = inputsEnabled,
                            minLines = 3,
                            maxLines = 6,
                            shape = RoundedCornerShape(dimensionResource(R.dimen.shape_corner_sm)),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                            modifier = Modifier.fillMaxWidth(),
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

/** 品牌欢迎区：圆底徽标 + 欢迎语 + 副标题。 */
@Composable
private fun BrandHeader() {
    val textStyles = LocalVenusTextStyles.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = dimensionResource(R.dimen.space_5),
                bottom = dimensionResource(R.dimen.space_4),
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(dimensionResource(R.dimen.icon_badge_lg))
                .clip(CircleShape)
                .background(colorResource(R.color.primary_alpha_10))
                .padding(dimensionResource(R.dimen.space_3)),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_brand),
                contentDescription = null,
                // 矢量自带 icon_foreground 填充色，不应用 Icon 默认 tint，保留原 Image 行为
                tint = Color.Unspecified,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(dimensionResource(R.dimen.space_3)))
        Text(
            text = stringResource(R.string.user_login_welcome),
            style = textStyles.titleLarge,
        )
        Spacer(Modifier.height(dimensionResource(R.dimen.space_1)))
        Text(
            text = stringResource(R.string.user_login_subtitle),
            style = textStyles.rowSubtitle,
        )
    }
}

/** 单行输入框，等价于 `Widget.Venus.TextInput`（OutlinedBox + shape_corner_sm）。 */
@Composable
private fun VenusTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    keyboardType: KeyboardType = KeyboardType.Text,
    onSubmit: () -> Unit = {},
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        shape = RoundedCornerShape(dimensionResource(R.dimen.shape_corner_sm)),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }, onGo = { onSubmit() }),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * 密码输入框：保留原 `endIconMode=password_toggle` 的能力。
 *
 * Compose 没有内置的眼睛图标（material-icons-extended 未引入），
 * 用「显示 / 隐藏」文字按钮达成同样的可见性切换，且读屏可读。
 */
@Composable
private fun VenusPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    onSubmit: () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        shape = RoundedCornerShape(dimensionResource(R.dimen.shape_corner_sm)),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }) {
                Text(
                    text = stringResource(if (visible) R.string.password_hide else R.string.password_show),
                    style = LocalVenusTextStyles.current.labelMedium,
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
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

    private var onBackClickListener: Runnable? = null
    private var onModeSelectListener: IndexConsumer? = null
    private var onLoginClickListener: Runnable? = null
    private var onSubmitListener: Runnable? = null
    private var onSendCodeClickListener: Runnable? = null

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
                    onBackClick = { onBackClickListener?.run() },
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
            onBackClick = {},
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
