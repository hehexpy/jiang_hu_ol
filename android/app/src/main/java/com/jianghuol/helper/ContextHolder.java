package com.jianghuol.helper;

import android.content.Context;

import java.util.HashMap;
import java.util.Map;

/** 进程级的 Context 持有者（Service 与 Runner 都不该持有 Activity）。 */
final class ContextHolder {
    private ContextHolder() {
    }

    private static volatile Context ctx;

    static void set(Context c) {
        ctx = c == null ? null : c.getApplicationContext();
    }

    static Context get() {
        Context c = ctx;
        if (c == null) throw new IllegalStateException("ContextHolder 未初始化");
        return c;
    }
}
