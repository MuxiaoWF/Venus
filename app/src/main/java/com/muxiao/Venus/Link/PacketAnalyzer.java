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
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

/**
 * 抓包分析器（复刻自 SRGachaLink）：
 * 从 VPN 旁路镜像的 IP 包中过滤星铁 KCP 流量（UDP:23301），解密 proto 包，
 * 提取含 authkey 的抽卡记录 URL 并广播（ACTION_GET_AUTHKEY）。
 * 密钥两阶段：
 * 1. 初始密钥：包首 4 字节 XOR magic head 得 key ID，查 HsrKeyStore（assets/hsr.json 内置表，
 *    云端更新见 HsrKeyStore 注释）；缺键时缓存待解包并触发即时补拉，拿到密钥后按到达顺序重放；
 * 2. 会话密钥：解出 PlayerGetTokenScRsp 后取 seed，MT19937-64 生成 4096 字节新 XOR pad。
 *
 * <p>异步架构：转发线程只调用 {@link #offerIpPacket}（无锁快速过滤 + 有界入队），
 * 解密/字符串构造等重负载全部在独立分析线程执行——解析耗时不再阻塞转发路径、
 * 也不再形成上下行争抢锁的网络抖动（此类抖动可被游戏内网络质量检测感知）。
 * 单消费者模型：启动后所有密钥状态仅由分析线程读写，无需加锁。
 */
public class PacketAnalyzer {
    private static final String TAG = "PacketAnalyzer";
    /** 星铁游戏服务器 KCP 端口 */
    private static final int GAME_PORT = 23301;
    /** 抽卡 URL 中 authkey 固定长度 */
    private static final int AUTHKEY_LEN = 684;

    private final Context context;
    // 100KB 载荷缓冲（原实现为定长 buffer，丢弃跨 KCP 分片的重组逻辑，authkey 消息实测不分片）
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
     * 待分析队列上限：仅吸收分析耗速与转发耗速的瞬时差，超限丢最旧
     * （分析尽力而为，丢包只影响捕获不影响转发）。256 × MTU 包 ≈ 亚 MB 级内存上界。
     */
    private static final int QUEUE_LIMIT = 256;
    private final LinkedBlockingDeque<Pending> queue = new LinkedBlockingDeque<>(QUEUE_LIMIT);

    /**
     * 缺键缓存上限（个）：仅保近期包供拿到键后重放衔接，超限丢最旧（与 QUEUE_LIMIT
     * 同策略）——缺键期间每个 KCP 包都会入缓存，无界会 OOM。
     */
    private static final int PENDING_LIMIT = 256;
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();

    private volatile boolean running = false;
    private Thread worker;
    private long droppedCount = 0;

    public PacketAnalyzer(Context context) {
        this.context = context.getApplicationContext();
    }

    /* ===== 生命周期 ===== */

    /** 启动独立分析线程（每轮抓包调用一次） */
    public synchronized void start() {
        if (running) return;
        running = true;
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

    /* ===== 数据面入口（转发线程调用，必须无锁、低开销） ===== */

    /**
     * 快速过滤旁路镜像的 IP 包：仅识别 IPv4/UDP 且端口为 23301 的游戏 KCP 包，
     * 命中才拷贝入队交由分析线程处理。其余包在此直接返回，零分配零锁。
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

    /* ===== 分析线程（单消费者） ===== */

    private void analyzeLoop() {
        while (running) {
            Pending p;
            try {
                p = queue.pollFirst(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                continue; // running 置 false 后自然退出
            }
            if (p == null)
                continue;
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
        // 首包：从加密 magic head 反解 key ID，查初始密钥表
        if (key == null) {
            if (packet.payload.length < 4)
                return;
            long version = extractVersion(packet.payload);
            String keyB64 = HsrKeyStore.getKey(context, version + "");
            key = Base64.decode(keyB64, Base64.DEFAULT);
            Log.i(TAG, "get init key: " + version);
            drainPending();
        }
        processKcpPayload(packet.payload, clientPort);
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

    private void processKcpPayload(byte[] payload, int clientPort) {
        System.arraycopy(payload, 0, buffer, 0, payload.length);
        // XOR 解密并校验魔数
        byte[] plain = xor(slice(buffer, 0, payload.length), key);
        ProtoPacket protoPacket = new ProtoPacket(plain);
        if (protoPacket.mh != ProtoPacket.MAGIC_HEAD || protoPacket.mt != ProtoPacket.MAGIC_TAIL)
            return;
        handleProtoPacket(protoPacket, clientPort);
        // 会话密钥更新：header 为空的首个 token 应答携带 seed
        if (isInitKey && protoPacket.header.length == 0) {
            byte[][] pair = parsePlayerGetTokenScRsp(protoPacket.proto);
            long seed = decodeVarint(pair[1]);
            key = createXorPad(seed);
            isInitKey = false;
        }
    }

    private void handleProtoPacket(ProtoPacket packet, int clientPort) {
        byte[] proto = packet.proto;
        // 抽卡 URL 明文特征：含 webview_gacha、非 https、长度足以容纳 684 字符 authkey。
        // 直接在字节层做 ASCII 特征匹配，仅命中候选时才整包解码为 String，
        // 避免对每个 proto 包都做 UTF-8→UTF-16 转换造成 GC 压力
        if (proto.length <= AUTHKEY_LEN
                || indexOfAscii(proto, "webview_gacha") < 0
                || indexOfAscii(proto, "https") >= 0)
            return;
        String s = new String(proto, StandardCharsets.UTF_8);
        String authkey = s.substring(s.length() - AUTHKEY_LEN);
        String channel = detectChannel(s, clientPort);
        // 写入静态状态：触发时用户多半在游戏里，Snackbar 早已消失，切回应用靠行内回显确认
        CaptureService.capturedChannel = channel;
        Intent intent = new Intent(ACTION_GET_AUTHKEY);
        intent.setPackage(context.getPackageName());
        intent.putExtra("authkey", authkey);
        intent.putExtra("channel", channel);
        context.sendBroadcast(intent);
        Log.i(TAG, "get authkey, channel=" + channel);
    }

    /** ASCII 子串查找（字节层 contains，无需 UTF-8 解码） */
    private static int indexOfAscii(byte[] data, String needle) {
        byte first = (byte) needle.charAt(0);
        outer:
        for (int i = 0; i <= data.length - needle.length(); i++) {
            if (data[i] != first)
                continue;
            for (int j = 1; j < needle.length(); j++)
                if (data[i + j] != (byte) needle.charAt(j))
                    continue outer;
            return i;
        }
        return -1;
    }

    /**
     * 渠道判定（层层兜底）：
     * 1. 明文自报：gacha URL 明文若含 game_biz/hkrpg_xx 字样，直接判定；
     * 2. 会话归属：netguard 已把新 UDP 会话的客户端源端口解析到渠道包名（CaptureService 记录）；
     * 3. 安装判定：设备上恰好只装一个渠道包时归属该包；
     * 4. 最终默认国服。
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
     * 缺键时广播「更新中」（每分析器实例仅一次），让 UI 给出可见的等待反馈而不是静默；
     * 抓包不中断，拿到键重放衔接后由成功广播覆盖此状态。
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
     * 缺键强制补拉落定回调（后台线程，HsrKeyStore.checkForUpdateAsync 的 onSettled）：
     * 拉取失败或服务端仍未收录该版本时广播失败事件（reason=key_missing），
     * LinkFragment 行内提示收尾「更新中」状态。键已就绪（本次拉取成功）则静默——
     * 分析线程处理下一个包时查表命中并重放缓存，成功广播自然覆盖此状态。
     * 注意重放不在本线程执行：processKcpPayload 的复用 buffer 仅限分析线程使用。
     */
    private void onKeyFetchSettled() {
        if (key != null) return;
        Pending first = pending.peekFirst();
        if (first == null) return;
        long version = extractVersion(first.payload);
        HsrKeyStore.getKey(context, version + "");
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
     * 启发式解析 PlayerGetTokenScRsp 的 uid / secret_seed 两个 varint 字段：
     * proto 编码中字段 tag 字节高位为 1（负数），遍历按「负数字节进入字段、正数字节结束字段」
     * 的状态机切出两段 varint，再按出现顺序短者在前返回（uid 在前、seed 在后）。
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

    /* ===== 密钥派生 ===== */

    /** 以 seed 播种 MT19937-64，生成 4096 字节大端 XOR pad（与客户端密钥协商逻辑一致） */
    public static byte[] createXorPad(long seed) {
        MT19937_64 mt = new MT19937_64();
        mt.seed(seed);
        ByteBuffer pad = ByteBuffer.allocate(4096).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < 4096; i += 8)
            pad.putLong(mt.generate());
        return pad.array();
    }

    /** 循环 XOR */
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

    /* ===== 协议数据结构（仅本分析器使用，收敛为嵌套类） ===== */

    /**
     * KCP 协议包头（复刻自 SRGachaLink）：24 字节标准头 + 4 字节 len（米哈游变体共 28 字节，小端）。
     * conv/conv2 双会话号、cmd（81=数据包）、frg 分片标记、len 为载荷长度。
     */
    static final class KcpPacket {
        public final int conv;
        public final int conv2;
        public final int cmd;
        public final int frg;
        public final int wnd;
        public final int ts;
        public final int sn;
        public final int una;
        public final int len;
        public final byte[] payload;

        KcpPacket(byte[] data) {
            ByteBuffer buffer = ByteBuffer.wrap(data, 0, 28).order(ByteOrder.LITTLE_ENDIAN);
            conv = buffer.getInt();
            conv2 = buffer.getInt();
            cmd = buffer.get();
            frg = buffer.get();
            wnd = buffer.getShort();
            ts = buffer.getInt();
            sn = buffer.getInt();
            una = buffer.getInt();
            len = buffer.getInt();
            payload = new byte[len];
            System.arraycopy(data, 28, payload, 0, len);
        }
    }

    /**
     * 米哈游 KCP 载荷的 proto 包结构（复刻自 SRGachaLink）：
     * [magic head 0x9D74C714][cmdId u16][headerLen u16][protoLen u32][header][proto][magic tail 0xD7A152C8]
     * 魔数校验失败时 headerLen/protoLen 置 0，由调用方丢弃。
     */
    static final class ProtoPacket {
        public static final int MAGIC_HEAD = 0x9D74C714;
        public static final int MAGIC_TAIL = 0xD7A152C8;

        public final int mh;        // magic head（原始位型，负数属正常）
        public final int mt;        // magic tail
        public final int cmdId;
        public final int headerLen;
        public final int protoLen;
        public final byte[] header;
        public final byte[] proto;

        ProtoPacket(byte[] data) {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            mh = buffer.getInt();
            cmdId = buffer.getShort();
            int hl = buffer.getShort();
            int pl = buffer.getInt();
            buffer.position(data.length - 4);
            mt = buffer.getInt();
            if (mh != MAGIC_HEAD || mt != MAGIC_TAIL) {
                // 魔数不匹配：视为无效包，交由调用方通过 header/proto 长度丢弃
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
     * MT19937-64 伪随机数生成器（复刻自 SRGachaLink）。
     * 星铁会话密钥派生：服务端在 PlayerGetTokenScRsp 下发 secret_seed，
     * 客户端与嗅探端以 seed 播种 MT19937-64 生成 4096 字节 XOR pad 作为会话密钥。
     * ULong 以 long 承载（Java long 运算天然是 mod 2^64，与无符号语义等价）。
     */
    static final class MT19937_64 {
        private static final int N = 312;
        private static final int M = 156;
        private static final long MATRIX_A = 0xB5026F5AA96619E9L;
        private static final long UPPER_MASK = 0xFFFFFFFF80000000L;
        private static final long LOWER_MASK = 0x7FFFFFFFL;

        private final long[] array = new long[N];
        // 初始化为 N+1，表示未初始化
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
