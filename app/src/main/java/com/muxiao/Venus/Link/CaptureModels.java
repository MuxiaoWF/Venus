package com.muxiao.Venus.Link;

/*
    This file is part of NetGuard.

    NetGuard is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    NetGuard is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with NetGuard.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2015-2025 by Marcel Bokhorst (M66B)
*/

import androidx.annotation.NonNull;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * VPN 抓包链路的 JNI 数据模型（源自 NetGuard，GPLv3）。
 * 四个数据类由 C 层（netguard.c）经 FindClass/NewObject 构造并按字段名读写，
 * 类名/包名变更必须同步 netguard.c 中的 FindClass 字符串与 JNI 签名
 * （现为 com/muxiao/Venus/Link/CaptureModels$X），并由 proguard-rules.pro keep。
 */
public final class CaptureModels {
    private CaptureModels() {
    }

    /** 单个 IP 包的解析结果（C 层 jni_run 旁路回调 logPacket / isAddressAllowed） */
    public static final class Packet {
        public long time;
        public int version;
        public int protocol;
        public String flags;
        public String saddr;
        public int sport;
        public String daddr;
        public int dport;
        public String data;
        public int uid;
        public boolean allowed;

        @NonNull
        @Override
        public String toString() {
            return "uid=" + uid + " v" + version + " p" + protocol + " " + daddr + "/" + dport;
        }
    }

    /** 地址放行判定结果：raddr 非空时表示需要重定向的地址 */
    public static final class Allowed {
        public final String raddr;
        public final int rport;

        public Allowed() {
            this.raddr = null;
            this.rport = 0;
        }

        public Allowed(String raddr, int rport) {
            this.raddr = raddr;
            this.rport = rport;
        }
    }

    /** DNS 解析记录（C 层 dns.c 回调 dnsResolved） */
    public static final class ResourceRecord {
        public long Time;
        public String QName;
        public String AName;
        public String Resource;
        public int TTL;
        public int uid;

        private static final DateFormat formatter = SimpleDateFormat.getDateTimeInstance();

        @NonNull
        @Override
        public String toString() {
            return formatter.format(new Date(Time).getTime()) +
                    " Q " + QName +
                    " A " + AName +
                    " R " + Resource +
                    " TTL " + TTL +
                    " uid " + uid +
                    " " + formatter.format(new Date(Time + TTL * 1000L).getTime());
        }
    }

    /** 流量用量统计（C 层按会话回调 accountUsage） */
    public static final class Usage {
        public long Time;
        public int Version;
        public int Protocol;
        public String DAddr;
        public int DPort;
        public int Uid;
        public long Sent;
        public long Received;

        private static final DateFormat formatter = SimpleDateFormat.getDateTimeInstance();

        @NonNull
        @Override
        public String toString() {
            return formatter.format(new Date(Time).getTime()) +
                    " v" + Version + " p" + Protocol +
                    " " + DAddr + "/" + DPort +
                    " uid " + Uid +
                    " out " + Sent + " in " + Received;
        }
    }
}
