package com.muxiao.Venus.Link;

import static com.muxiao.Venus.Link.CaptureService.ACTION_GET_AUTHKEY;

import android.content.Context;
import android.content.Intent;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * 抓包分析器（复刻自 SRGachaLink）：
 * 从 VPN 旁路镜像的 IP 包中过滤星铁 KCP 流量（UDP:23301），解密 proto 包，
 * 提取含 authkey 的抽卡记录 URL 并广播（ACTION_GET_AUTHKEY）。
 * 密钥两阶段：
 * 1. 初始密钥：包首 4 字节 XOR magic head 得 key ID，查 assets/hsr.json（社区逆向的各版本初始密钥表）；
 * 2. 会话密钥：解出 PlayerGetTokenScRsp 后取 seed，MT19937-64 生成 4096 字节新 XOR pad。
 * 注：初始密钥表随游戏版本更新可能失效，本版为本地静态表（暂无云端更新机制）。
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
    private JSONObject keyStore;

    public PacketAnalyzer(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * 处理旁路镜像的原始 IP 包（双向均传入）。
     * 由 CaptureService 的两条转发线程并发调用，需串行化保护密钥状态。
     */
    public synchronized void handleIpPacket(byte[] data, int length) {
        // 仅处理 IPv4/UDP（VPN 只声明了 IPv4 地址与路由，TUN 内不会出现 IPv6 包）
        if (length < 28 || (data[0] >> 4) != 4)
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
        handleUdpPacket(data, ihl + 8, length - ihl - 8, clientPort);
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
        System.arraycopy(packet.payload, 0, buffer, 0, packet.payload.length);
        int len = packet.payload.length;
        if (packet.frg != 0) // 分片包无法独立解密，丢弃（见 buffer 注释）
            return;
        // 首包：从加密 magic head 反解 key ID，查初始密钥表
        if (key == null) {
            int mhEnc = ((buffer[0] & 0xFF) << 24) | ((buffer[1] & 0xFF) << 16)
                    | ((buffer[2] & 0xFF) << 8) | (buffer[3] & 0xFF);
            long version = (mhEnc ^ ProtoPacket.MAGIC_HEAD) & 0xFFFFFFFFL;
            try {
                String keyB64 = loadInitKey(version + "");
                key = Base64.decode(keyB64, Base64.DEFAULT);
                Log.i(TAG, "get init key: " + version);
            } catch (Exception e) {
                Log.e(TAG, "no match key for version " + version);
                return;
            }
        }
        // XOR 解密并校验魔数
        byte[] plain = xor(slice(buffer, 0, len), key);
        ProtoPacket protoPacket = new ProtoPacket(plain);
        if (protoPacket.mh != ProtoPacket.MAGIC_HEAD || protoPacket.mt != ProtoPacket.MAGIC_TAIL)
            return;
        handleProtoPacket(protoPacket, clientPort);
        // 会话密钥更新：header 为空的首个 token 应答携带 seed
        if (isInitKey && protoPacket.header.length == 0) {
            byte[][] pair = parsePlayerGetTokenScRsp(protoPacket.proto);
            long uid = decodeVarint(pair[0]);
            long seed = decodeVarint(pair[1]);
            Log.i(TAG, "uid = " + uid + ", seed = " + seed);
            key = createXorPad(seed);
            isInitKey = false;
        }
    }

    private void handleProtoPacket(ProtoPacket packet, int clientPort) {
        String s = new String(packet.proto, StandardCharsets.UTF_8);
        // 抽卡 URL 明文特征：含 webview_gacha、非 https、长度足以容纳 684 字符 authkey
        if (s.contains("webview_gacha") && !s.contains("https") && s.length() > AUTHKEY_LEN) {
            String authkey = s.substring(s.length() - AUTHKEY_LEN);
            String channel = detectChannel(s, clientPort);
            Intent intent = new Intent(ACTION_GET_AUTHKEY);
            intent.setPackage(context.getPackageName());
            intent.putExtra("authkey", authkey);
            intent.putExtra("channel", channel);
            context.sendBroadcast(intent);
            Log.i(TAG, "get authkey, channel=" + channel);
        }
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

    /** 从 assets/hsr.json（社区逆向的版本 → 初始密钥表）按 key ID 查初始密钥 */
    private String loadInitKey(String version) throws Exception {
        if (keyStore == null) {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(context.getAssets().open("hsr.json"), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null)
                    sb.append(line);
            }
            keyStore = new JSONObject(sb.toString());
        }
        return keyStore.getString(version);
    }

    private static byte[] slice(byte[] src, int offset, int length) {
        byte[] out = new byte[length];
        System.arraycopy(src, offset, out, 0, length);
        return out;
    }
}
