package com.muxiao.Venus.Link;

/**
 * MT19937-64 伪随机数生成器（复刻自 SRGachaLink）。
 * 星铁会话密钥派生：服务端在 PlayerGetTokenScRsp 下发 secret_seed，
 * 客户端与嗅探端以 seed 播种 MT19937-64 生成 4096 字节 XOR pad 作为会话密钥。
 * ULong 以 long 承载（Java long 运算天然是 mod 2^64，与无符号语义等价）。
 */
public class MT19937_64 {
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
