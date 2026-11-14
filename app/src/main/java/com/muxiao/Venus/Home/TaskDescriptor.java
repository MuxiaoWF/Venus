package com.muxiao.Venus.Home;

/**
 * 一条任务：稳定 id（持久化/匹配键，与语言无关，见 {@link TaskIds}）+ 当前语言显示名。
 *
 * <p>刻意不可变：列表构建后由 UI/小组件只读消费，避免「渲染中名称被改写」导致的状态键错位。
 */
public final class TaskDescriptor {

    public final String id;
    public final String name;

    public TaskDescriptor(String id, String name) {
        this.id = id;
        this.name = name;
    }

    @Override
    public String toString() {
        return id + '(' + name + ')';
    }
}
