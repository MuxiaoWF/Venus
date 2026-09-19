package com.muxiao.Venus.Link;

import java.nio.ByteBuffer;

/**
 * 米哈游 KCP 载荷的 proto 包结构（复刻自 SRGachaLink）：
 * [magic head 0x9D74C714][cmdId u16][headerLen u16][protoLen u32][header][proto][magic tail 0xD7A152C8]
 * 魔数校验失败时 headerLen/protoLen 置 0，由调用方丢弃。
 */
public class ProtoPacket {
    public static final int MAGIC_HEAD = 0x9D74C714;
    public static final int MAGIC_TAIL = 0xD7A152C8;

    public final int mh;        // magic head（原始位型，负数属正常）
    public final int mt;        // magic tail
    public final int cmdId;
    public final int headerLen;
    public final int protoLen;
    public final byte[] header;
    public final byte[] proto;

    public ProtoPacket(byte[] data) {
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
