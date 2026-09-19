package com.muxiao.Venus.Link;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * KCP 协议包头（复刻自 SRGachaLink）：24 字节标准头 + 4 字节 len（米哈游变体共 28 字节，小端）。
 * conv/conv2 双会话号、cmd（81=数据包）、frg 分片标记、len 为载荷长度。
 */
public class KcpPacket {
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

    public KcpPacket(byte[] data) {
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
