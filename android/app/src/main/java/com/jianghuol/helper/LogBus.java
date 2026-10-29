package com.jianghuol.helper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 进程内日志总线。
 *
 * <p>设计要点（后续把自动化引擎搬进来时，日志是唯一的排错通道）：
 * <ul>
 *   <li><b>线程安全</b>：自动化引擎以后跑在后台线程，写日志不能加锁排队。</li>
 *   <li><b>环形缓冲</b>：只保留最近 {@link #CAPACITY} 条，长时间挂机不吃内存。</li>
 *   <li><b>多路扇出</b>：界面、落盘、logcat 各自是一个 {@link Sink}，互不影响；
 *       任何一个 Sink 抛异常都不会打断写日志的调用方。</li>
 * </ul>
 */
public final class LogBus {

    public enum Level { V, D, I, W, E }

    /** 日志订阅者。实现方须自行保证快速返回（重活丢到自己的线程）。 */
    public interface Sink {
        void onLog(long ts, Level level, String tag, String msg);
    }

    private static final int CAPACITY = 3000;
    private static final LogBus INSTANCE = new LogBus();

    public static LogBus get() { return INSTANCE; }

    private final Deque<Entry> buffer = new ArrayDeque<>();
    private final List<Sink> sinks = new CopyOnWriteArrayList<>();

    /** 一条日志。 */
    public static final class Entry {
        public final long ts;
        public final Level level;
        public final String tag;
        public final String msg;

        Entry(long ts, Level level, String tag, String msg) {
            this.ts = ts;
            this.level = level;
            this.tag = tag;
            this.msg = msg;
        }

        /** 单行格式：`HH:mm:ss.SSS I/TAG          message`（与 logcat 观感一致）。 */
        public String line() {
            return String.format(Locale.US, "%s %s/%-14s %s", hms(ts), level, tag, msg);
        }
    }

    /**
     * 自己拼时间，不用 {@code SimpleDateFormat} —— 后者非线程安全，
     * 而这里会被多个后台线程同时调用。
     */
    static String hms(long ts) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        return String.format(Locale.US, "%02d:%02d:%02d.%03d",
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE),
                c.get(Calendar.SECOND), c.get(Calendar.MILLISECOND));
    }

    private LogBus() { }

    public void addSink(Sink s) { if (s != null && !sinks.contains(s)) sinks.add(s); }

    public void removeSink(Sink s) { if (s != null) sinks.remove(s); }

    public void log(Level lv, String tag, String msg) {
        final long ts = System.currentTimeMillis();
        final String m = (msg == null) ? "null" : msg;
        synchronized (buffer) {
            buffer.addLast(new Entry(ts, lv, tag, m));
            while (buffer.size() > CAPACITY) buffer.removeFirst();
        }
        for (Sink s : sinks) {
            try {
                s.onLog(ts, lv, tag, m);
            } catch (Throwable ignore) {
                // Sink 自身的问题不能反过来把日志调用方搞崩
            }
        }
    }

    public void v(String tag, String msg) { log(Level.V, tag, msg); }
    public void d(String tag, String msg) { log(Level.D, tag, msg); }
    public void i(String tag, String msg) { log(Level.I, tag, msg); }
    public void w(String tag, String msg) { log(Level.W, tag, msg); }
    public void e(String tag, String msg) { log(Level.E, tag, msg); }

    /** 打异常：把堆栈展开成多行文本，保证落盘与界面都能看全。 */
    public void e(String tag, String msg, Throwable t) {
        StringBuilder sb = new StringBuilder(msg == null ? "" : msg);
        if (t != null) {
            sb.append("\n    ").append(t.getClass().getName()).append(": ").append(t.getMessage());
            for (StackTraceElement el : t.getStackTrace()) {
                sb.append("\n      at ").append(el);
            }
        }
        log(Level.E, tag, sb.toString());
    }

    public List<Entry> snapshot() {
        synchronized (buffer) { return new ArrayList<>(buffer); }
    }

    /** 取最近 {@code max} 行（界面用，避免 TextView 塞太长文本）。 */
    public List<Entry> tail(int max) {
        synchronized (buffer) {
            int n = buffer.size();
            int skip = Math.max(0, n - max);
            List<Entry> out = new ArrayList<>(Math.min(n, max));
            int i = 0;
            for (Entry e : buffer) {
                if (i++ >= skip) out.add(e);
            }
            return out;
        }
    }

    public String dumpText() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : snapshot()) sb.append(e.line()).append('\n');
        return sb.toString();
    }

    public int size() {
        synchronized (buffer) { return buffer.size(); }
    }

    public void clear() {
        synchronized (buffer) { buffer.clear(); }
    }

    // ------------------------------------------------------------------
    // 内置 Sink：转发到 logcat，这样 `adb logcat -s JiangHuOL` 能直接看
    // ------------------------------------------------------------------
    public static final class LogcatSink implements Sink {
        private static final String TAG = "JiangHuOL";
        /** logcat 单条上限约 4KB，超长会截断 —— 按行拆开发，保留完整输出。 */
        private static final int CHUNK = 3500;

        @Override
        public void onLog(long ts, Level level, String tag, String msg) {
            String prefix = tag + ": ";
            String body = prefix + msg;
            if (body.length() <= CHUNK) {
                write(level, body);
                return;
            }
            int start = 0;
            while (start < body.length()) {
                int end = Math.min(body.length(), start + CHUNK);
                write(level, (start == 0 ? "" : "  …") + body.substring(start, end));
                start = end;
            }
        }

        private void write(Level lv, String s) {
            switch (lv) {
                case V: android.util.Log.v(TAG, s); break;
                case D: android.util.Log.d(TAG, s); break;
                case I: android.util.Log.i(TAG, s); break;
                case W: android.util.Log.w(TAG, s); break;
                case E: android.util.Log.e(TAG, s); break;
                default: android.util.Log.i(TAG, s);
            }
        }
    }
}
