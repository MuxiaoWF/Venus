# netguard —— 用户态 TCP/IP 协议栈（源自 NetGuard / SRGachaLink，GPLv3）
# 由 CaptureService 通过 JNI 调用：jni_init/jni_start/jni_run/jni_stop/jni_clear/jni_done
cmake_minimum_required(VERSION 3.22.1)

project(netguard C)

add_library(netguard SHARED
        netguard/netguard.c
        netguard/session.c
        netguard/ip.c
        netguard/tcp.c
        netguard/udp.c
        netguard/icmp.c
        netguard/tls.c
        netguard/dns.c
        netguard/dhcp.c
        netguard/pcap.c
        netguard/util.c)

include_directories(netguard)

find_library(log-lib log)

target_link_libraries(netguard ${log-lib})

# 16KB 页对齐（Android 15+ 要求）
target_link_options(netguard PRIVATE "-Wl,-z,max-page-size=16384")
