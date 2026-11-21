package com.muxiao.Venus.Home;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 前台人机验证队列：多账号按挂起先后排队，一次只挂一个极验控件。
 *
 * <p>只排队，不取挑战、不落库。按账号去重，成功/失败/关框/异常后由调用方 {@link #remove(String)} 出队。
 */
public final class CaptchaQueue {

    /** 等待验证的账号，按挂起先后排序。 */
    private final LinkedHashSet<String> waiting = new LinkedHashSet<>();

    /** 入队；已存在返回 false（不改变原位置）。 */
    public synchronized boolean offer(String userId) {
        if (userId == null || userId.isEmpty()) return false;
        return waiting.add(userId);
    }

    /** 队首账号；空队列返回 null。 */
    public synchronized String peek() {
        return waiting.isEmpty() ? null : waiting.iterator().next();
    }

    /** 移除某账号（验证有结论后调用）；不存在时无副作用。 */
    public synchronized void remove(String userId) {
        if (userId != null) waiting.remove(userId);
    }

    public synchronized boolean contains(String userId) {
        return userId != null && waiting.contains(userId);
    }

    public synchronized int size() {
        return waiting.size();
    }

    public synchronized boolean isEmpty() {
        return waiting.isEmpty();
    }

    /** 快照，仅用于日志。 */
    public synchronized List<String> snapshot() {
        return new ArrayList<>(waiting);
    }

    /** 清空（Activity 销毁时调用）。 */
    public synchronized void clear() {
        waiting.clear();
    }
}
