package com.muxiao.Venus.Home;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 「该任务需要人机验证」的挂起信号。
 *
 * <p>取代旧模型里「在任务线程上阻塞等人」的写法：任务命中 1034 后直接把本异常抛到
 * {@link TaskExecutor}，由执行器把任务置为待验证并结束本轮。用户在前台完成验证后，结果经
 * {@link CaptchaStore} 落库，下一轮续跑时自动带进请求头。
 *
 * <p>异常只携带「发起验证所需的最小上下文」——触发任务的 id 与当时的请求头（含 Cookie，供 API1/API2
 * 绑定同一会话）。真正的 gt/challenge 由轮末统一取一次，避免多个任务并发各发一次 API1 拿到互相
 * 不匹配的 challenge。
 */
public class CaptchaRequiredException extends RuntimeException {

    /** 触发验证的任务 id（与 {@link TaskIds} 同源）。 */
    private final String taskId;

    /** 命中 1034 时的请求头快照（含 Cookie / DS 等），构造后不可变。 */
    private final Map<String, String> headers;

    public CaptchaRequiredException(String taskId, Map<String, String> headers) {
        super("captcha required: " + taskId);
        this.taskId = taskId;
        this.headers = headers == null
                ? Collections.emptyMap()
                : Map.copyOf(headers);
    }

    public String getTaskId() {
        return taskId;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }
}
