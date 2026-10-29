package com.jianghuol.helper;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import java.io.File;
import java.util.Locale;

/**
 * 环境自检。
 *
 * <p>这里报的不只是「设备是什么」，更是**后续自动化引擎的前置条件**：
 * 分辨率（坐标归一化的基准）、ABI（决定能不能内嵌 Python/OpenCV）、
 * 无障碍服务是否已开启、有没有 root。提前把状态摊开，
 * 免得后面排查「为什么点不中」时才发现是环境不对。
 */
public final class SelfCheck {

    private SelfCheck() { }

    /** 跑一遍自检并写入日志；返回给界面顶部显示的短摘要。 */
    @SuppressWarnings("deprecation")
    public static String run(Context ctx, LogFile logFile) {
        LogBus L = LogBus.get();
        L.i("自检", "==================== 环境自检 ====================");

        // ---- 设备 ----
        String device = safe(Build.MANUFACTURER) + " " + safe(Build.MODEL);
        L.i("自检", "设备: " + device
                + "  (brand=" + safe(Build.BRAND) + " device=" + safe(Build.DEVICE)
                + " hw=" + safe(Build.HARDWARE) + ")");
        L.i("自检", "系统: Android " + safe(Build.VERSION.RELEASE)
                + " / API " + Build.VERSION.SDK_INT
                + " / 指纹 " + safe(Build.FINGERPRINT));

        // ---- ABI：决定能否直接跑 Python + OpenCV 的原生库 ----
        StringBuilder abis = new StringBuilder();
        for (String a : Build.SUPPORTED_ABIS) {
            if (abis.length() > 0) abis.append(", ");
            abis.append(a);
        }
        L.i("自检", "ABI: " + abis + "   (64位=" + (Build.SUPPORTED_64_BIT_ABIS.length > 0) + ")");

        // ---- 屏幕：坐标归一化的基准，必须记准 ----
        int w = 0, h = 0;
        float density = 0f;
        int dpi = 0;
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            Display d = wm.getDefaultDisplay();
            DisplayMetrics dm = new DisplayMetrics();
            d.getRealMetrics(dm);          // 真实屏幕（含导航栏），不是 App 窗口
            w = dm.widthPixels;
            h = dm.heightPixels;
            density = dm.density;
            dpi = dm.densityDpi;
        } catch (Throwable t) {
            L.e("自检", "读取屏幕参数失败", t);
        }
        L.i("自检", "屏幕: " + w + "x" + h + "  density=" + density + "  dpi=" + dpi
                + "  (归一化坐标即以此为准)");

        // ---- 进程与内存 ----
        Runtime rt = Runtime.getRuntime();
        long maxMb = rt.maxMemory() / 1024 / 1024;
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
        L.i("自检", "进程: pid=" + android.os.Process.myPid()
                + "  CPU=" + rt.availableProcessors() + " 核"
                + "  堆 " + usedMb + "MB/" + maxMb + "MB");

        // ---- 应用 ----
        String verName = "?";
        long verCode = -1;
        try {
            android.content.pm.PackageInfo pi =
                    ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            verName = pi.versionName;
            verCode = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Throwable ignore) { }
        L.i("自检", "应用: " + ctx.getPackageName() + "  v" + verName + " (" + verCode + ")"
                + "  " + (isDebuggable(ctx) ? "debug" : "release"));

        // ---- 日志通道（本次交付的核心）----
        L.i("自检", "日志文件: " + logFile.path());
        L.i("自检", "日志可写: " + writable(logFile.file().getParentFile())
                + "  (adb 读取: adb shell cat " + logFile.path() + ")");

        // ---- root 探测（只探测不依赖，技术红线要求方案必须无 root 成立）----
        String su = findSu();
        if (su == null) {
            L.i("自检", "root: 未检测到 su（符合预期 —— 方案不依赖 root）");
        } else {
            L.w("自检", "root: 检测到 " + su + "（但方案不应使用它）");
        }

        // ---- 无障碍服务：后续「点击」的可选通道之一 ----
        String a11y = "读取失败";
        try {
            String s = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (s == null || s.trim().isEmpty()) {
                a11y = "未开启任何无障碍服务";
            } else {
                a11y = s;
            }
        } catch (Throwable t) {
            a11y = "读取异常: " + t;
        }
        L.i("自检", "无障碍: " + a11y);
        L.i("自检", "================================================");

        // 顶部摘要
        return String.format(Locale.US,
                "设备  %s\nABI   %s\n屏幕  %dx%d  density=%.2f  dpi=%d\n进程  pid=%d  %d核  堆 %dMB/%dMB\n日志  %s",
                device, abis, w, h, density, dpi,
                android.os.Process.myPid(), rt.availableProcessors(), usedMb, maxMb,
                logFile.path());
    }

    private static String safe(String s) { return s == null ? "?" : s; }

    private static boolean writable(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        File probe = new File(dir, ".writeprobe");
        try {
            if (probe.exists() && !probe.delete()) return false;
            boolean created = probe.createNewFile();
            boolean deleted = probe.delete();
            return created && deleted;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String findSu() {
        String[] paths = {
                "/system/bin/su", "/system/xbin/su", "/sbin/su",
                "/su/bin/su", "/magisk/.core/bin/su", "/system/sbin/su"
        };
        for (String p : paths) {
            try {
                if (new File(p).exists()) return p;
            } catch (Throwable ignore) { }
        }
        return null;
    }

    private static boolean isDebuggable(Context ctx) {
        return (ctx.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }
}
