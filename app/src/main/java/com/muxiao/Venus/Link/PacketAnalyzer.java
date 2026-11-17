package com.muxiao.Venus.Link;

import static com.muxiao.Venus.Link.CaptureService.ACTION_GET_AUTHKEY;

import android.content.Context;
import android.content.Intent;
import android.util.Base64;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

/**
 * 抓包分析器（复刻自 SRGachaLink）：从 VPN 旁路镜像的 IP 包过滤星铁 KCP 流量（UDP:23301），解密 proto 包，
 * 提取含 authkey 的抽卡记录 URL 并广播（ACTION_GET_AUTHKEY）。
 * 密钥两阶段：1) 初始密钥——包首 4 字节 XOR magic head 得 key ID，查 HsrKeyStore（assets/hsr.json 内置表，
 * 云端更新见 HsrKeyStore 注释），缺键时缓存待解包并触发即时补拉，拿到密钥后按到达顺序重放；
 * 2) 会话密钥——解出 PlayerGetTokenScRsp 后取 seed，MT19937-64 生成 4096 字节新 XOR pad。
 * 3) 会话切换——现 key 解不开、而该包的初始密钥能解通时，回到 1) 重新等 seed（新会话会重下 seed，旧 pad 必然失效）。
 * 判定只在「解不开」时做，不按 conv/端口变化主动清空：游戏可能同时有多条 23301 连接，主动清空会反复丢状态。
 * <p>
 * 异步架构：转发线程只调 {@link #offerIpPacket}（无锁快速过滤 + 有界入队），解密/字符串构造等重负载全在独立分析线程，
 * 解析耗时不再阻塞转发路径，也不形成上下行争抢锁的网络抖动（此类抖动可被游戏内网络质量检测感知）；
 * 单消费者模型：启动后所有密钥状态仅由分析线程读写，无需加锁。
 */
public class PacketAnalyzer {
    private static final String TAG = "PacketAnalyzer";
    /** 星铁游戏服务器 KCP 端口 */
    private static final int GAME_PORT = 23301;
    /** authkey 的定位标记与长度下限——**不能按固定长度切**。服务端只会回 retcode -100 authkey error。 */
    private static final byte[] AUTHKEY_TAG = "authkey=".getBytes(StandardCharsets.US_ASCII);
    /** 抽卡报文特征（auth_appid 字段值）：字节级常量，避免热路径上反复 getBytes 分配 */
    private static final byte[] GACHA_APPID_TAG = "webview_gacha".getBytes(StandardCharsets.US_ASCII);
    /** key 长度下限（正常是 500+ 的 base64）：仅用于排除明文里偶然出现的同名短参数，不参与切分 */
    private static final int AUTHKEY_MIN_LEN = 512;

    private final Context context;
    // 100KB 载荷缓冲：不做跨 KCP 分片重组，authkey 消息实测不分片
    private final byte[] buffer = new byte[1024 * 100];
    private byte[] key;
    private boolean isInitKey = true;
    /** 「更新中」提示只上报一次：缺版本时每个 KCP 包都会走到该分支，重复广播会刷爆 UI */
    private boolean updatingNotified = false;

    /** 待分析包（payload 已是独立数组；含渠道判定的客户端源端口） */
    private static final class Pending {
        final byte[] payload;
        final int clientPort;

        Pending(byte[] payload, int clientPort) {
            this.payload = payload;
            this.clientPort = clientPort;
        }
    }

    /**
     * 待分析队列上限：只吸收分析耗速与转发耗速的瞬时差，超限丢最旧（丢包只影响捕获不影响转发）；256 × MTU 包 ≈ 亚 MB 级上界。
     */
    private static final int QUEUE_LIMIT = 256;
    private final LinkedBlockingDeque<Pending> queue = new LinkedBlockingDeque<>(QUEUE_LIMIT);

    /**
     * 缺键缓存上限（个）：仅保近期包供拿到键后重放，超限丢最旧——缺键期间每个 KCP 包都会入缓存，无界会 OOM。
     */
    private static final int PENDING_LIMIT = 256;
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();

    /**
     * 缺键补拉是否已落定：由 HsrKeyStore 的 onSettled 在后台线程置位，仅作事件标志、不承载密钥数据——
     * pending/key 是分析线程私有的（单消费者模型，见类注释），跨线程读写会破坏该约定。分析线程在空闲轮询里消费它。
     */
    private volatile boolean keyFetchSettled = false;
    /** 缺键失败是否已上报（分析线程私有）：空闲轮询/每包重试都会走到判定点，只广播一次 */
    private boolean failureNotified = false;
    /** 明文含 webview_gacha 却找不到 authkey= 标记（消息结构变化）：只留痕一次，避免每包刷日志 */
    private boolean authkeyTagMissingNotified = false;

    private volatile boolean running = false;
    private Thread worker;
    private long droppedCount = 0;
    /** 计数（仅分析线程读写）：每 1024 个 KCP 包打一条，用于区分「没包进来 / 解不开 / 取不到 key」 */
    private long statPackets, statDecrypted, statGacha, statCaptured;
    /** 最近一次取 key 命中的层级（tag=URL 形态、run=裸字段兜底）：仅分析线程读写，用于日志定位 */
    private String lastExtractTier = "?";

    public PacketAnalyzer(Context context) {
        this.context = context.getApplicationContext();
    }

    /** 启动独立分析线程（每轮抓包调用一次） */
    public synchronized void start() {
        if (running) return;
        running = true;
        keyFetchSettled = false;
        failureNotified = false;
        authkeyTagMissingNotified = false;
        statPackets = statDecrypted = statGacha = statCaptured = 0;
        worker = new Thread(this::analyzeLoop, "CaptureAnalyzer");
        worker.start();
    }

    /** 停止分析线程并清空队列（stopCapture 时调用） */
    public synchronized void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        queue.clear();
    }

    // 数据面入口（转发线程调用，必须无锁、低开销）

    /**
     * 快速过滤旁路镜像的 IP 包：仅识别 IPv4/UDP 且端口为 23301 的游戏 KCP 包，命中才拷贝入队交由分析线程。
     * 其余包在此直接返回，零分配零锁。
     */
    public void offerIpPacket(byte[] data, int length) {
        if (!running || length < 28 || (data[0] >> 4) != 4)
            return;
        int ihl = (data[0] & 0x0F) * 4;
        if ((data[9] & 0xFF) != 17 || length < ihl + 8) // 17 = UDP
            return;
        int srcPort = ((data[ihl] & 0xFF) << 8) | (data[ihl + 1] & 0xFF);
        int dstPort = ((data[ihl + 2] & 0xFF) << 8) | (data[ihl + 3] & 0xFF);
        if (srcPort != GAME_PORT && dstPort != GAME_PORT)
            return;
        // 客户端侧临时端口：服务端端口恒为 23301，另一端即客户端端口（渠道判定依据）
        int clientPort = dstPort == GAME_PORT ? srcPort : dstPort;
        byte[] packet = new byte[length];
        System.arraycopy(data, 0, packet, 0, length);
        // 队列满：丢最旧保最新（重放与捕获均只需近期包）
        while (!queue.offerLast(new Pending(packet, clientPort))) {
            queue.pollFirst();
            if (++droppedCount % 1024 == 1)
                Log.w(TAG, "analyze queue full, dropped total=" + droppedCount);
        }
    }

    // 分析线程（单消费者）

    private void analyzeLoop() {
        while (running) {
            Pending p;
            try {
                p = queue.pollFirst(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                continue; // running 置 false 后自然退出
            }
            if (p == null) {
                // 空闲自愈：补拉落定后游戏侧可能已无新 KCP 包（authkey 往往就是连接里最后一批
                // 数据），不主动查一次表就再也接不上；补拉被节流/单飞跳过（HsrKeyStore 不回调）
                // 的情况也由这里兜住
                recoverOnIdle();
                continue;
            }
            try {
                analyzePacket(p.payload, p.clientPort);
            } catch (Throwable t) {
                // 单包解析异常只丢弃该包，绝不外溢（此前此类异常曾杀死转发线程形成隧道黑洞）
                Log.w(TAG, "analyze packet failed", t);
            }
        }
    }

    /** 解析单个已过滤的 IP 包（仅分析线程调用；IPv4/UDP/端口过滤已在入队前完成） */
    private void analyzePacket(byte[] data, int clientPort) {
        int ihl = (data[0] & 0x0F) * 4;
        handleUdpPacket(data, ihl + 8, data.length - ihl - 8, clientPort);
    }

    /** UDP 载荷内逐个解析 KCP 包（一个 UDP 包可含多个连续 KCP 包） */
    private void handleUdpPacket(byte[] data, int offset, int size, int clientPort) {
        int pos = offset;
        while (true) {
            if (size - (pos - offset) < 28)
                break;
            KcpPacket kcp;
            try {
                kcp = new KcpPacket(slice(data, pos, size - (pos - offset)));
            } catch (Exception e) {
                break; // 解析越界/异常：帧边界已坏，放弃本 UDP 包剩余部分
            }
            int lenTotal = 28 + kcp.len;
            if (size - (pos - offset) < lenTotal)
                break;
            pos += lenTotal;
            if (kcp.cmd != 81) // 非 KCP 数据包（ACK/窗口探测等），忽略
                continue;
            handleKcpPacket(kcp, clientPort);
        }
    }

    private void handleKcpPacket(KcpPacket packet, int clientPort) {
        if (packet.frg != 0) // 分片包无法独立解密，丢弃（见 buffer 注释）
            return;
        if ((++statPackets & 1023) == 0)
            Log.i(TAG, "stats: packets=" + statPackets + " decrypted=" + statDecrypted
                    + " gacha=" + statGacha + " captured=" + statCaptured
                    + " key=" + (key == null ? "none" : (isInitKey ? "init" : "session")));
        // 首包：从加密 magic head 反解 key ID，查初始密钥表
        if (key == null) {
            if (packet.payload.length < 4)
                return;
            long version = extractVersion(packet.payload);
            if (!trySetInitKey(version)) {
                // 缺键（当前游戏版本既不在 assets 表、云端也还没覆盖到）：先缓存待解包再触发即时补拉。
                // seed 包在连接后毫秒级到达、补拉来不及，若直接丢弃则本会话永远解不开；
                // 缓存到拿到密钥后按到达顺序重放即可衔接（补拉 10min 节流 + 单飞闸在
                // HsrKeyStore 内部，重复 miss 不会刷请求）。此处必须 return——
                // 密钥为空时继续解密只会得到垃圾数据。
                if (pending.size() >= PENDING_LIMIT)
                    pending.pollFirst();
                pending.addLast(new Pending(packet.payload, clientPort));
                notifyCaptureUpdating();
                HsrKeyStore.checkForUpdateAsync(context, true, this::onKeyFetchSettled);
                notifyKeyMissingIfSettled(version);
                Log.e(TAG, "no match key for version " + version + ", pending=" + pending.size());
                return;
            }
            Log.i(TAG, "get init key: " + version);
            drainPending();
        }
        if (processKcpPayload(packet.payload, clientPort))
            return;
        // 解不开才考虑「换会话了」：新会话（重连/切网/进程被杀重进）的握手包是用初始密钥加密的，
        // 旧 pad 必然解不开。不能反过来——一看到 conv/端口变化就清空 key 会把可用状态反复丢掉：
        // 游戏可能同时存在多条 UDP 23301 连接（登录阶段尤其多），逐包重置会让分析线程在登录突发时空转、
        // 刷日志，间接拖慢同进程的转发线程。
        switchToInitKeyIfDecrypts(packet.payload, clientPort);
    }

    /**
     * 会话密钥解不开时的一次性回退：按本包的 key ID 取初始密钥，若能解通（magic head/tail 双校验），
     * 说明这是新会话的握手包——切回初始态继续等 secret_seed；否则什么都不做（宁可少抓一次，也不误判重置）。
     */
    private void switchToInitKeyIfDecrypts(byte[] payload, int clientPort) {
        if (isInitKey || payload.length < 16)
            return;
        long version = extractVersion(payload);
        String keyB64 = HsrKeyStore.getKey(context, version + "");
        if (keyB64 == null || keyB64.isEmpty())
            return;
        byte[] initKey = Base64.decode(keyB64, Base64.DEFAULT);
        if (initKey.length == 0)
            return;
        byte[] plain = xor(slice(payload, 0, payload.length), initKey);
        ProtoPacket probe = new ProtoPacket(plain);
        if (probe.mh != ProtoPacket.MAGIC_HEAD || probe.mt != ProtoPacket.MAGIC_TAIL)
            return;
        Log.i(TAG, "session switched: handshake for " + version + " decoded by init key, back to init state");
        key = initKey;
        isInitKey = true;
        pending.clear(); // 旧会话缓存的待解包对新会话毫无意义
        processKcpPayload(payload, clientPort);
    }

    /**
     * 查表并装载初始密钥；表中没有该版本时返回 false（调用方按缺键处理）。正常收包与补拉落定后的衔接共用，
     * 两处都必须「查不到就返回 false」，否则 Base64.decode(null) 抛 NPE 被 analyzeLoop 吞掉，表现为抓包静默无结果。
     */
    private boolean trySetInitKey(long version) {
        String keyB64 = HsrKeyStore.getKey(context, version + "");
        // getKey 对表里没有的版本返回 null（不是空串）：写成 requireNonNull 会抛 NPE，
        // 被 analyzeLoop 吞掉后「缓存待解包 + 触发补拉」这条路径永远不会执行，只剩每包一条堆栈日志。
        if (keyB64 == null || keyB64.isEmpty())
            return false;
        byte[] decoded = Base64.decode(keyB64, Base64.DEFAULT);
        if (decoded.length == 0) // 损坏条目（空或非法 base64）：长度为 0 的 key 会在 xor 里除零
            return false;
        key = decoded;
        return true;
    }

    /** KCP 首包加密 magic head 反解 key ID（缺键判定与补拉落定核对共用） */
    private static long extractVersion(byte[] payload) {
        int mhEnc = ((payload[0] & 0xFF) << 24) | ((payload[1] & 0xFF) << 16)
                | ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
        return (mhEnc ^ ProtoPacket.MAGIC_HEAD) & 0xFFFFFFFFL;
    }

    /** 按到达顺序重放缺键期间缓存的包；重放途中解出 seed 切到会话密钥后，余下包自然衔接 */
    private void drainPending() {
        Pending p;
        while ((p = pending.pollFirst()) != null)
            processKcpPayload(p.payload, p.clientPort);
    }

    /**
     * 用当前 key 解一个 KCP 载荷，返回 false 表示魔数校验不过（key 不匹配或不是 proto 帧）。
     * 解出 header 为空的首个应答（PlayerGetTokenScRsp）时切到会话密钥。
     */
    private boolean processKcpPayload(byte[] payload, int clientPort) {
        System.arraycopy(payload, 0, buffer, 0, payload.length);
        // XOR 解密并校验魔数
        byte[] plain = xor(slice(buffer, 0, payload.length), key);
        ProtoPacket protoPacket = new ProtoPacket(plain);
        if (protoPacket.mh != ProtoPacket.MAGIC_HEAD || protoPacket.mt != ProtoPacket.MAGIC_TAIL)
            return false;
        statDecrypted++;
        handleProtoPacket(protoPacket, clientPort);
        // 会话密钥更新：header 为空的首个 token 应答携带 seed
        if (isInitKey && protoPacket.header.length == 0) {
            byte[][] pair = parsePlayerGetTokenScRsp(protoPacket.proto);
            long seed = decodeVarint(pair[1]);
            key = createXorPad(seed);
            isInitKey = false;
            // 留痕：seed 由启发式 varint 解析得来，若与后面的「session switched」成对出现，
            // 说明这次 seed 猜错了（旧 pad 解不开，又被初始密钥接回）
            Log.i(TAG, "session key derived, seed=" + seed);
        }
        return true;
    }

    private void handleProtoPacket(ProtoPacket packet, int clientPort) {
        byte[] proto = packet.proto;
        // 抽卡报文特征：含 auth_appid 的 "webview_gacha"，或（auth_appid 变名时）直接含 authkey=。
        // 全程在字节层匹配与取值，不做整包 UTF-8 解码后按字符索引——protobuf 里的非 ASCII 字节解码后
        // 会变成替换字符，字符偏移与字节偏移不再等价，取值必然错位。
        if (indexOfAscii(proto, GACHA_APPID_TAG, 0) < 0 && indexOfAscii(proto, AUTHKEY_TAG, 0) < 0)
            return;
        statGacha++;
        String authkey = extractAuthkey(proto);
        if (authkey == null) {
            // 只有「够长却取不到 key」才值得留痕：长度不足 512 的报文（例如只带 auth_appid 字段的
            // 配置包，实测 protoLen=19）本来就装不下 key，不算异常，避免刷假告警
            if (proto.length >= AUTHKEY_MIN_LEN && !authkeyTagMissingNotified) {
                authkeyTagMissingNotified = true;
                Log.w(TAG, "gacha packet without authkey: protoLen=" + proto.length
                        + ", longestB64=" + longestAuthkeyRun(proto)[1]
                        + ", tail=" + describeTail(proto, 160));
            }
            return;
        }
        statCaptured++;
        // 渠道判定只查 ASCII 子串：ISO_8859_1 保证 1 字节 = 1 字符，避免多字节解码带来的偏移问题
        String s = new String(proto, StandardCharsets.ISO_8859_1);
        String channel = detectChannel(s, clientPort);
        // 写入静态状态：触发时用户多半在游戏里，Snackbar 早已消失，切回应用靠行内回显确认
        CaptureService.capturedChannel = channel;
        Intent intent = new Intent(ACTION_GET_AUTHKEY);
        intent.setPackage(context.getPackageName());
        intent.putExtra("authkey", authkey);
        intent.putExtra("channel", channel);
        context.sendBroadcast(intent);
        Log.i(TAG, "get authkey via " + lastExtractTier + ", channel=" + channel + ", len=" + authkey.length());
    }

    /**
     * 从 proto 明文里取 authkey，两级策略：
     * <ol>
     * <li>先找 {@code authkey=} 标记（URL 形态下最精确：标记之后到分隔符为止）；</li>
     * <li>找不到标记时退化为「最长的连续 base64 段」——这条报文里的 key 是**独立字符串字段**，
     * 前后都是 protobuf 的 tag/长度前缀（如长度 683 的 varint {@code 0xAB 0x05}，非 base64 字符），
     * 512 字节随机二进制恰好全落在 base64 字符集内的概率可忽略，因此不会误命中。</li>
     * </ol>
     * 都不能按固定长度切：旧实现取「最后 684 字符」，尾部把 auth_appid 字段（{@code 0x0D + "webview_gacha"}）
     * 一起卷进 key，且顶掉 key 头部若干字符，服务端一律回 authkey error。
     */
    private String extractAuthkey(byte[] proto) {
        String byTag = extractByTag(proto);
        if (byTag != null) {
            lastExtractTier = "tag";
            return byTag;
        }
        lastExtractTier = "run";
        int[] run = longestAuthkeyRun(proto);
        int len = run[1];
        if (len < AUTHKEY_MIN_LEN)
            return null;
        // base64 的 '=' 只出现在结尾：段内出现 '=' 时，其后的字节不属于 key
        // （下一字段的长度前缀若恰好落在 base64 字符集内，最长段会多吞一个字节，导致 key 变长而报 authkey error）
        for (int i = run[0] + len - 1; i >= run[0]; i--) {
            if (proto[i] == '=') {
                len = i - run[0] + 1;
                break;
            }
        }
        return new String(proto, run[0], len, StandardCharsets.US_ASCII);
    }

    /** 按 {@code authkey=} 标记取值，遇非 key 字符即止；取最长的一段命中（同时出现多次时以最长为准） */
    private static String extractByTag(byte[] proto) {
        int bestStart = -1;
        int bestLen = 0;
        int from = 0;
        int at;
        while ((at = indexOfAscii(proto, AUTHKEY_TAG, from)) >= 0) {
            int start = at + AUTHKEY_TAG.length;
            int end = start;
            while (end < proto.length && isAuthkeyByte(proto[end]))
                end++;
            if (end - start > bestLen) {
                bestLen = end - start;
                bestStart = start;
            }
            from = start; // 允许重叠扫描：下一处标记的查找从值起点继续
        }
        return bestLen >= AUTHKEY_MIN_LEN
                ? new String(proto, bestStart, bestLen, StandardCharsets.US_ASCII) : null;
    }

    /** 明文里最长的连续 base64 段：返回 {起点, 长度}，无命中返回 {-1, 0} */
    private static int[] longestAuthkeyRun(byte[] proto) {
        int bestStart = -1;
        int bestLen = 0;
        int start = -1;
        for (int i = 0; i <= proto.length; i++) {
            if (i < proto.length && isAuthkeyByte(proto[i])) {
                if (start < 0) start = i;
            } else if (start >= 0) {
                if (i - start > bestLen) {
                    bestLen = i - start;
                    bestStart = start;
                }
                start = -1;
            }
        }
        return new int[]{bestStart, bestLen};
    }

    /**
     * 明文尾部可读化，供「抓不到」时定位报文结构：长 base64 段折叠为 {@code <b64:n>}（不泄露 key 本体），
     * 非可打印字节转义为 {@code \xNN}（这样 protobuf 的长度前缀/字段 tag 一眼可辨）。
     */
    private static String describeTail(byte[] proto, int max) {
        StringBuilder sb = new StringBuilder();
        int b64 = 0;
        for (int i = Math.max(0, proto.length - max); i < proto.length; i++) {
            byte b = proto[i];
            if (isAuthkeyByte(b)) {
                b64++;
                continue;
            }
            if (b64 > 0) {
                sb.append("<b64:").append(b64).append('>');
                b64 = 0;
            }
            if (b >= 0x20 && b < 0x7F) sb.append((char) b);
            else sb.append("\\x").append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
        }
        if (b64 > 0) sb.append("<b64:").append(b64).append('>');
        return sb.toString();
    }

    /** authkey 合法字符集（标准 base64 与 base64url 的并集）；遇到 {@code &}、{@code %}、空白或二进制字节即视为 key 结束 */
    private static boolean isAuthkeyByte(byte b) {
        return (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') || (b >= '0' && b <= '9')
                || b == '+' || b == '/' || b == '=' || b == '-' || b == '_';
    }

    /** ASCII 子串查找（字节层 contains，无需 UTF-8 解码）；带起始偏移，供 authkey 多次出现时逐个扫描 */
    private static int indexOfAscii(byte[] data, byte[] needle, int from) {
        byte first = needle[0];
        outer:
        for (int i = Math.max(0, from); i <= data.length - needle.length; i++) {
            if (data[i] != first)
                continue;
            for (int j = 1; j < needle.length; j++)
                if (data[i + j] != needle[j])
                    continue outer;
            return i;
        }
        return -1;
    }

    /**
     * 渠道判定（层层兜底）：1) gacha URL 明文含 game_biz/hkrpg_xx 直接判定；2) 会话归属——netguard 已把新 UDP 会话的
     * 客户端源端口解析到渠道包名（CaptureService 记录）；3) 设备上恰好只装一个渠道包时归属该包；4) 最终默认国服。
     */
    private String detectChannel(String s, int clientPort) {
        if (s.contains("hkrpg_bilibili"))
            return CaptureService.CHANNEL_BILI;
        if (s.contains("hkrpg_global"))
            return CaptureService.CHANNEL_GLOBAL;
        if (s.contains("hkrpg_cn"))
            return CaptureService.CHANNEL_CN;
        String pkg = CaptureService.packageForPort(clientPort);
        if (pkg == null)
            pkg = CaptureService.soleInstalledApp(context);
        return packageToChannel(pkg);
    }

    /**
     * 缺键时广播「更新中」（每分析器实例仅一次），让 UI 有可见的等待反馈；抓包不中断，拿到键重放衔接后由成功广播覆盖。
     */
    private void notifyCaptureUpdating() {
        if (updatingNotified) return;
        updatingNotified = true;
        Intent intent = new Intent(CaptureService.ACTION_CAPTURE_UPDATING);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
        Log.i(TAG, "key table updating");
    }

    /**
     * 缺键强制补拉的落定回调（后台线程，来自 HsrKeyStore.checkForUpdateAsync 的 onSettled）：本线程只置事件标志，
     * 不触碰 pending/key（单消费者约定），衔接由分析线程的空闲轮询完成。
     */
    private void onKeyFetchSettled() {
        keyFetchSettled = true;
    }

    /**
     * 空闲自愈（仅分析线程调用，队列空转 1s 触发）：补拉落定后重新查表——命中则装载并按到达顺序重放
     * （重放途中解出 seed 即切会话密钥，本会话就能接着解）；仍未收录则广播失败事件收尾 UI「更新中」。
     */
    private void recoverOnIdle() {
        if (key != null || pending.isEmpty()) return;
        long version = extractVersion(Objects.requireNonNull(pending.peekFirst()).payload);
        if (trySetInitKey(version)) {
            Log.i(TAG, "get init key on idle: " + version + ", replay pending=" + pending.size());
            drainPending();
            return;
        }
        notifyKeyMissingIfSettled(version); // 补拉未落定则先等，下一个空闲轮询再试
    }

    /**
     * 补拉落定后该版本仍不在密钥表（网络失败或服务端未收录）：广播一次失败事件（reason=key_missing），
     * 让 UI 把「更新中」收尾成明确失败。查表而不是分析器的 key 字段——拉取成功到下个包到达之间 key 尚未刷新，
     * 查表才能避免误报；调用点有两个（每次缺键收包、空闲轮询），故用标志去重。
     */
    private void notifyKeyMissingIfSettled(long version) {
        if (!keyFetchSettled || failureNotified) return;
        failureNotified = true;
        Intent intent = new Intent(CaptureService.ACTION_CAPTURE_FAILED);
        intent.setPackage(context.getPackageName());
        intent.putExtra("reason", "key_missing");
        context.sendBroadcast(intent);
        Log.e(TAG, "key still missing after update: " + version);
    }

    private static String packageToChannel(String pkg) {
        if (pkg == null)
            return CaptureService.CHANNEL_CN;
        switch (pkg) {
            case "com.miHoYo.hkrpg.bilibili":
                return CaptureService.CHANNEL_BILI;
            case "com.HoYoverse.hkrpgoversea":
                return CaptureService.CHANNEL_GLOBAL;
            default:
                return CaptureService.CHANNEL_CN;
        }
    }

    /**
     * 启发式解析 PlayerGetTokenScRsp 的 uid / secret_seed 两个 varint 字段：proto 中字段 tag 字节高位为 1（负数），
     * 遍历按「负数字节进入字段、正数字节结束字段」的状态机切出两段 varint，再按出现顺序短者在前返回。
     */
    private byte[][] parsePlayerGetTokenScRsp(byte[] d) {
        byte[] a = new byte[10];
        int aLen = 0;
        byte[] b = new byte[10];
        int bLen = 0;
        int state = 0;
        for (byte item : d) {
            if (item < 0) {                  // 字段 tag：进入字段
                if (state % 2 == 0) state++; // out -> in
                if (state / 2 == 0) a[aLen++] = item;
                else b[bLen++] = item;
            } else {                         // 字段值：离开字段
                if (state % 2 == 1) {
                    if (state / 2 == 0) a[aLen++] = item;
                    else b[bLen++] = item;
                    state++;                 // in -> out
                }
            }
        }
        byte[] first = new byte[aLen];
        System.arraycopy(a, 0, first, 0, aLen);
        byte[] second = new byte[bLen];
        System.arraycopy(b, 0, second, 0, bLen);
        return aLen < bLen ? new byte[][]{first, second} : new byte[][]{second, first};
    }

    /** 以 seed 播种 MT19937-64，生成 4096 字节大端 XOR pad（与客户端密钥协商逻辑一致） */
    public static byte[] createXorPad(long seed) {
        MT19937_64 mt = new MT19937_64();
        mt.seed(seed);
        ByteBuffer pad = ByteBuffer.allocate(4096).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < 4096; i += 8)
            pad.putLong(mt.generate());
        return pad.array();
    }

    public static byte[] xor(byte[] data, byte[] key) {
        for (int i = 0; i < data.length; i++)
            data[i] ^= key[i % key.length];
        return data;
    }

    /** 解析 proto varint（长整型承载，最多 10 字节） */
    public static long decodeVarint(byte[] data) {
        long result = 0;
        int shift = 0;
        for (byte b : data) {
            result |= ((long) b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        return result;
    }

    private static byte[] slice(byte[] src, int offset, int length) {
        byte[] out = new byte[length];
        System.arraycopy(src, offset, out, 0, length);
        return out;
    }

    // 协议数据结构（仅本分析器使用，收敛为嵌套类）

    /**
     * KCP 协议包头（复刻自 SRGachaLink）：24 字节标准头 + 4 字节 len（米哈游变体共 28 字节，小端）。
     * conv/conv2/wnd/ts/sn/una 本分析器未消费，仅按小端读出以推进游标（协议头偏移固定）。
     */
    static final class KcpPacket {
        public final int cmd;
        public final int frg;
        public final int len;
        public final byte[] payload;

        KcpPacket(byte[] data) {
            ByteBuffer buffer = ByteBuffer.wrap(data, 0, 28).order(ByteOrder.LITTLE_ENDIAN);
            buffer.getInt();  // conv（未消费，占位推进）
            buffer.getInt();  // conv2（未消费，占位推进）
            cmd = buffer.get();
            frg = buffer.get();
            buffer.getShort(); // wnd（未消费，占位推进）
            buffer.getInt();  // ts（未消费，占位推进）
            buffer.getInt();  // sn（未消费，占位推进）
            buffer.getInt();  // una（未消费，占位推进）
            len = buffer.getInt();
            payload = new byte[len];
            System.arraycopy(data, 28, payload, 0, len);
        }
    }

    /**
     * 米哈游 KCP 载荷的 proto 包结构（复刻自 SRGachaLink）：
     * [magic head 0x9D74C714][cmdId u16][headerLen u16][protoLen u32][header][proto][magic tail 0xD7A152C8]，
     * 魔数校验失败时 headerLen/protoLen 置 0，由调用方丢弃。
     */
    static final class ProtoPacket {
        public static final int MAGIC_HEAD = 0x9D74C714;
        public static final int MAGIC_TAIL = 0xD7A152C8;

        public final int mh;        // magic head（原始位型，负数属正常）
        public final int mt;        // magic tail
        public final int headerLen;
        public final int protoLen;
        public final byte[] header;
        public final byte[] proto;

        ProtoPacket(byte[] data) {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            mh = buffer.getInt();
            buffer.getShort(); // cmdId（本分析器未消费，仅占位推进游标）
            int hl = buffer.getShort();
            int pl = buffer.getInt();
            buffer.position(data.length - 4);
            mt = buffer.getInt();
            if (mh != MAGIC_HEAD || mt != MAGIC_TAIL) {
                hl = 0;
                pl = 0;
            }
            headerLen = hl;
            protoLen = pl;

            int start = 12;
            int end1 = start + headerLen;
            header = new byte[headerLen];
            System.arraycopy(data, start, header, 0, Math.min(headerLen, data.length - start));
            proto = new byte[Math.max(0, Math.min(protoLen, data.length - end1))];
            System.arraycopy(data, end1, proto, 0, proto.length);
        }
    }

    /**
     * MT19937-64 伪随机数生成器（复刻自 SRGachaLink）。星铁会话密钥派生：服务端在 PlayerGetTokenScRsp 下发 secret_seed，
     * 客户端与嗅探端以 seed 播种 MT19937-64 生成 4096 字节 XOR pad。ULong 以 long 承载（Java long 天然是 mod 2^64）。
     */
    static final class MT19937_64 {
        private static final int N = 312;
        private static final int M = 156;
        private static final long MATRIX_A = 0xB5026F5AA96619E9L;
        private static final long UPPER_MASK = 0xFFFFFFFF80000000L;
        private static final long LOWER_MASK = 0x7FFFFFFFL;

        private final long[] array = new long[N];
        private int index = N + 1;

        public void seed(long seed) {
            array[0] = seed;
            for (int i = 1; i < N; i++) {
                long prev = array[i - 1];
                long temp = (prev ^ (prev >>> 62)) * 6364136223846793005L;
                array[i] = temp + i;
            }
            index = N;
        }

        public long generate() {
            return int63();
        }

        private long int63() {
            long[] mag01 = {0L, MATRIX_A};
            if (index >= N) {
                if (index == N + 1)
                    seed(5489L); // 默认种子
                for (int i = 0; i < N - M; i++) {
                    long x = (array[i] & UPPER_MASK) | (array[i + 1] & LOWER_MASK);
                    array[i] = array[i + M] ^ (x >>> 1) ^ mag01[(int) (x & 1)];
                }
                for (int i = N - M; i < N - 1; i++) {
                    long x = (array[i] & UPPER_MASK) | (array[i + 1] & LOWER_MASK);
                    array[i] = array[i + (M - N)] ^ (x >>> 1) ^ mag01[(int) (x & 1)];
                }
                long x = (array[N - 1] & UPPER_MASK) | (array[0] & LOWER_MASK);
                array[N - 1] = array[M - 1] ^ (x >>> 1) ^ mag01[(int) (x & 1)];
                index = 0;
            }
            long y = array[index++];
            y ^= (y >>> 29) & 0x5555555555555555L;
            y ^= (y << 17) & 0x71D67FFFEDA60000L;
            y ^= (y << 37) & 0xFFF7EEE000000000L;
            y ^= y >>> 43;
            return y;
        }
    }
}
