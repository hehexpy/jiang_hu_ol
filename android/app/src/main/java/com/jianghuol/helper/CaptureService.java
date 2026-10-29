package com.jianghuol.helper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.SystemClock;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

/**
 * 截屏服务：手机闭环的「眼睛」。
 *
 * MediaProjection 是 Android 上**不需要 root** 的截屏通道：用户点一次授权，
 * 之后就持续给帧。VirtualDisplay + ImageReader(RGBA_8888) 拿到的 Image
 * 直接转 Bitmap，交给 Vision 做模板匹配。
 *
 * 生命周期：以前台服务形态存在（否则进程容易被回收）；由 MainActivity
 * 拿到授权 Intent 后 startService 启动。`latest()` 永远是最近一帧的拷贝。
 */
public class CaptureService extends Service {

    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_DATA = "data";
    private static final String CHANNEL = "capture";
    private static volatile Bitmap latest;
    private static volatile int frames = 0;
    private static volatile long lastFrameMs = 0;
    static final class Snapshot {
        final Bitmap bitmap;
        final int number;
        final long acquiredNs;
        Snapshot(Bitmap b, int n, long ns) { bitmap = b; number = n; acquiredNs = ns; }
    }
    private static volatile Snapshot snapshot;
    static Snapshot snapshot() { return snapshot; }
    private long captureCount, captureNanos;
    /** 节流间隔（毫秒）。10fps：识别够用，且不会把设备单核吃满。 */
    public static volatile long frameIntervalMs = 100;

    private MediaProjection projection;
    private ImageReader reader;
    private Handler handler;
    private HandlerThread captureThread;

    /** 最近一帧（拷贝，调用方随便改）。 */
    public static Bitmap latest() {
        return latest;
    }

    public static int frames() {
        return frames;
    }

    public static boolean running() {
        return latest != null;
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, CaptureService.class));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        // ⚠️ Android 8 起后台服务会被系统杀；MediaProjection 又必须活在服务里，
        // 所以**两个分支都要 startForeground**（只有 Android 10+ 才需要带 type 参数）。
        // 2026-10-20 23:04 踩过：原来只在 SDK>=Q 分支里调，Android 9 上助手一切后台
        // 约 8 秒服务就被停、截屏跟着被回收（"released because media projection stopped"）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notify_(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, notify_());
        }
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(code, data);
        if (projection == null) {
            LogBus.get().e("capture", "授权被拒绝：屏幕捕获不可用");
            stopSelf();
            return START_NOT_STICKY;
        }
        // API 28 硬性要求：必须先注册 Callback，否则系统认为使用方"没人要"，
        // 会在 Activity 销毁时回收 token（表现为无异常、突然没帧 + bg anr 死掉）
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                LogBus.get().i("capture", "截屏被系统停止（授权被回收）");
                latest = null;
                snapshot = null;
            }
        }, handler);
        handler = new Handler(getMainLooper());
        // 取帧放到独立后台线程：每帧都要 copyPixelsFromBuffer + 缩放（~10ms），
        // 放主线程会把 UI 线程拖到 60% 占用，切后台时直接 ANR（2026-10-20 23:04 实测 bg anr）
        captureThread = new HandlerThread("jh-capture");
        captureThread.start();
        Handler capHandler = new Handler(captureThread.getLooper());
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        // 宽度减半：视觉匹配用不到全分辨率，省一半 CPU 和时间
        int rw = Math.max(160, w / 2), rh = Math.max(160, h / 2);
        reader = ImageReader.newInstance(rw, rh, PixelFormat.RGBA_8888, 3);
        projection.createVirtualDisplay("jh-capture", rw, rh,
                getResources().getDisplayMetrics().densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, capHandler);
        reader.setOnImageAvailableListener(r -> {
            // 节流到 ~10fps：UI 匹配 5-10fps 完全够用，而 60fps 全速 copyPixelsFromBuffer
            // 会常驻 60% 单核 —— 真机上发热降频会把游戏本身拖卡（2026-10-20 23:50 评估）
            long now = SystemClock.elapsedRealtime();
            if (now - lastFrameMs < frameIntervalMs) {
                try (Image skip = r.acquireLatestImage()) { /* 直接丢弃，不转 Bitmap */ }
                return;
            }
            lastFrameMs = now;
            try (Image img = r.acquireLatestImage()) {
                if (img == null) return;
                long captureStart = System.nanoTime();
                long imageTimeNs = img.getTimestamp();
                if (imageTimeNs <= 0) return; // 无采集时间就无法证明它在输入之后，不发布旧队列帧。
                if (img.getPlanes().length == 0) return;
                Image.Plane p = img.getPlanes()[0];
                int rowStride = p.getRowStride(), pixStride = p.getPixelStride();
                int rowPadding = rowStride - pixStride * rw;
                int bw = rw + rowPadding / pixStride;
                java.nio.ByteBuffer buf = p.getBuffer();
                Bitmap bmp = Bitmap.createBitmap(bw, rh, Bitmap.Config.ARGB_8888);
                bmp.copyPixelsFromBuffer(buf);
                Bitmap out = Bitmap.createBitmap(bmp, 0, 0, rw, rh);
                if (bmp != out) bmp.recycle();
                latest = out;
                frames++;
                snapshot = new Snapshot(out, frames, imageTimeNs);
                captureCount++; captureNanos += System.nanoTime() - captureStart;
                if (captureCount % 100 == 0) LogBus.get().i("perf", String.format(java.util.Locale.ROOT,
                        "PERF capture_convert calls=%d mean_ms=%.2f", captureCount, captureNanos / 1e6 / captureCount));
            } catch (Throwable t) {
                LogBus.get().e("capture", "取帧失败 " + t);
            }
        }, capHandler);
        LogBus.get().i("capture", "屏幕捕获已启动 " + rw + "x" + rh);
        return START_STICKY;
    }

    private Notification notify_() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "屏幕捕获", NotificationManager.IMPORTANCE_LOW));
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setContentTitle("江湖助手").setContentText("正在识别画面…").setSmallIcon(android.R.drawable.ic_menu_view).build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (projection != null) {
            try {
                projection.stop();
            } catch (Throwable ignored) {
            }
        }
        if (captureThread != null) {
            captureThread.quitSafely();
            captureThread = null;
        }
        latest = null;
        snapshot = null;
        LogBus.get().i("capture", "屏幕捕获已停止");
    }
}
