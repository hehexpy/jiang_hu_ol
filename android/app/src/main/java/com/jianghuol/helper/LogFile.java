package com.jianghuol.helper;

import android.content.Context;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * 把日志落盘。
 *
 * <p><b>为什么用 App 私有外部目录</b>：{@code /sdcard/Android/data/<pkg>/files/logs/}
 * 在 Android 4.4+ 上**不需要任何存储权限**，而且 adb 可以直接读——
 * 这是「App 跑在手机上、人在电脑前看日志」最省事的通道：
 * <pre>
 *   adb shell cat  /sdcard/Android/data/com.jianghuol.helper/files/logs/app.log
 *   adb pull       /sdcard/Android/data/com.jianghuol.helper/files/logs/app.log
 * </pre>
 *
 * <p>外部目录不可用时回落到内部私有目录（adb 需 {@code run-as} 才能读，debug 包可以）。
 */
public final class LogFile implements LogBus.Sink {

    private static final String DIR = "logs";
    private static final String FILE = "app.log";

    private final File logFile;
    private BufferedWriter writer;
    private volatile boolean failed;

    public LogFile(Context ctx) {
        File base = null;
        try {
            base = ctx.getExternalFilesDir(null);   // /sdcard/Android/data/<pkg>/files
        } catch (Throwable ignore) {
            // 外部存储未挂载等情况
        }
        if (base == null) base = ctx.getFilesDir(); // 回落内部
        File dir = new File(base, DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            LogBus.get().w("LogFile", "无法创建日志目录: " + dir.getAbsolutePath());
        }
        this.logFile = new File(dir, FILE);
        open();
    }

    private void open() {
        try {
            // append 模式：App 重启后日志接续，不丢上一次崩溃前的输出
            writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(logFile, true), StandardCharsets.UTF_8));
        } catch (IOException e) {
            failed = true;
            LogBus.get().e("LogFile", "打开日志文件失败: " + logFile.getAbsolutePath(), e);
        }
    }

    public String path() {
        return logFile.getAbsolutePath();
    }

    public File file() {
        return logFile;
    }

    @Override
    public void onLog(long ts, LogBus.Level level, String tag, String msg) {
        if (failed || writer == null) return;
        try {
            synchronized (this) {
                writer.write(LogBus.hms(ts));
                writer.write(' ');
                writer.write(level.name());
                writer.write('/');
                writer.write(tag);
                writer.write(' ');
                writer.write(msg);
                writer.write('\n');
                // 立刻 flush：让 adb 随时拉到的都是完整内容（日志量小，代价可接受）
                writer.flush();
            }
        } catch (IOException e) {
            failed = true;
            LogBus.get().e("LogFile", "写日志失败，后续不再落盘", e);
        }
    }

    /** 把任意文本追加写到单独文件（导出用）。 */
    public File writeScript(String name, String text) throws IOException {
        File f = new File(logFile.getParentFile(), name);
        try (FileOutputStream fos = new FileOutputStream(f, false)) {
            fos.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return f;
    }

    public synchronized void close() {
        if (writer != null) {
            try { writer.flush(); writer.close(); } catch (IOException ignore) { }
            writer = null;
        }
    }
}
