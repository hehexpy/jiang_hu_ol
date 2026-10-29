package com.jianghuol.helper;

import java.util.Locale;

/**
 * 心跳线程。
 *
 * <p>存在的意义不只是「证明还活着」，而是**证明后台线程写日志这条链路是通的**——
 * 以后的自动化引擎就是一个长期驻留的后台线程，它和界面之间的唯一连接就是日志。
 * 这里先把它跑起来，等于提前验证了那条链路。
 */
public final class Heartbeat {

    private static final long PERIOD_MS = 2000L;

    private volatile boolean running = false;
    private Thread thread;
    private long startedAt;
    private int tick;

    public void start() {
        if (running) return;
        running = true;
        startedAt = System.currentTimeMillis();
        LogBus.get().i("心跳", "心跳线程启动（每 " + (PERIOD_MS / 1000) + "s 一条）");
        thread = new Thread(this::loop, "heartbeat");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        if (!running) return;
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) t.interrupt();
        LogBus.get().i("心跳", "心跳线程停止（共 " + tick + " 次，"
                + fmt(elapsed()) + "）");
    }

    public boolean isRunning() { return running; }

    public long elapsed() {
        return startedAt == 0 ? 0 : System.currentTimeMillis() - startedAt;
    }

    private void loop() {
        while (running) {
            try {
                Thread.sleep(PERIOD_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (!running) break;
            tick++;
            Runtime rt = Runtime.getRuntime();
            long usedMb = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
            long maxMb = rt.maxMemory() / 1024 / 1024;
            LogBus.get().i("心跳", String.format(Locale.US,
                    "运行 %s | tick=%d | 日志 %d 条 | 堆 %dMB/%dMB",
                    fmt(elapsed()), tick, LogBus.get().size(), usedMb, maxMb));
        }
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
