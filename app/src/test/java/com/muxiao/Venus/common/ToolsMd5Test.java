package com.muxiao.Venus.common;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 纯算法单测：覆盖 tools.md5Hex / bytesToHex。
 * 不依赖 Android 上下文（tools 类的静态字段仅为懒加载 OkHttpClient，加载安全）。
 * 运行：./gradlew testDebugUnitTest --tests "com.muxiao.Venus.common.ToolsMd5Test"
 */
public class ToolsMd5Test {

    @Test
    public void md5Hex_empty() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", tools.md5Hex(""));
    }

    @Test
    public void md5Hex_abc() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", tools.md5Hex("abc"));
    }

    @Test
    public void md5Hex_messageDigest() {
        assertEquals("f96b697d7cb7938d525a2f31aaf161d0", tools.md5Hex("message digest"));
    }

    @Test
    public void md5Hex_alphabet() {
        assertEquals("c3fcd3d76192e4007dfb496cca67e13b", tools.md5Hex("abcdefghijklmnopqrstuvwxyz"));
    }

    @Test
    public void bytesToHex_roundTrip() {
        byte[] data = {0x00, 0x0a, (byte) 0xff, (byte) 0x80};
        assertEquals("000aff80", tools.bytesToHex(data));
    }
}
