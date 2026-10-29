package com.jianghuol.helper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 无障碍服务：手机闭环的「手」——点击/滑动/返回、读界面文字、往输入框填字。
 *
 * 为什么用它而不是 adb：**脱离电脑**就必须摆脱 adb shell；Android 上不用 root
 * 也能操作其他 App 的标准通道就是 AccessibilityService 的 dispatchGesture()。
 * 2026-10-20 起，手机版「一键上号」的所有点击都从这里发出去。
 */
public class HelperAccessibilityService extends AccessibilityService {

    private static volatile HelperAccessibilityService self;
    private StatusOverlay statusOverlay;

    public static void showScriptStatus() {
        HelperAccessibilityService s=self;
        if(s!=null && s.statusOverlay!=null) s.statusOverlay.show();
    }

    public static HelperAccessibilityService get() {
        return self;
    }

    /** 服务是否已开启（设置里手动打开；开启后才能手势）。 */
    public static boolean ready() {
        return self != null;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        self = this;
        if(statusOverlay!=null) statusOverlay.hide();
        statusOverlay=new StatusOverlay(this);
        if(ScriptStatus.snapshot().active()) statusOverlay.show();
        LogBus.get().i("helper", "无障碍服务已连接（手势可用）");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if(statusOverlay!=null) { statusOverlay.hide(); statusOverlay=null; }
        self = null;
        LogBus.get().i("helper", "无障碍服务已断开");
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        if(statusOverlay!=null) { statusOverlay.hide(); statusOverlay=null; }
        if(self==this) self=null;
        super.onDestroy();
    }

    // ---------------- 手势 ----------------

    private static GestureDescription gesture(Path path, long durationMs) {
        return new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs))
                .build();
    }

    /** 点一个归一化坐标（0~1，按屏幕尺寸换算，设备无关）。 */
    public void tapN(float nx, float ny) {
        HelperAccessibilityService s = self;
        if (s == null) return;
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        tap((int) (nx * w), (int) (ny * h));
    }

    public void tap(int x, int y) {
        HelperAccessibilityService s = self;
        if (s == null) return;
        Path p = new Path();
        p.moveTo(x, y);
        s.dispatchGesture(gesture(p, 60), null, null);
    }

    public void swipeN(float x1, float y1, float x2, float y2, long ms) {
        HelperAccessibilityService s = self;
        if (s == null) return;
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        Path p = new Path();
        p.moveTo(x1 * w, y1 * h);
        p.lineTo(x2 * w, y2 * h);
        s.dispatchGesture(gesture(p, ms), null, null);
    }

    /** 返回键。 */
    public void back() {
        HelperAccessibilityService s = self;
        if (s == null) return;
        s.performGlobalAction(GLOBAL_ACTION_BACK);
    }

    /** 回桌面（把游戏切到后台）。 */
    public void home() {
        HelperAccessibilityService s = self;
        if (s == null) return;
        s.performGlobalAction(GLOBAL_ACTION_HOME);
    }

    /** 拉起一个 App。 */
    public void launch(String pkg) {
        HelperAccessibilityService s = self;
        if (s == null) return;
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return;
        i = reusableLaunchIntent(i);
        s.startActivity(i);
    }

    /** 与桌面再次点图标一致：复用已有任务，不创建第二个游戏Canvas入口。 */
    static Intent reusableLaunchIntent(Intent launch) {
        Intent result = new Intent(launch);
        // getLaunchIntentForPackage多了pkg字段；显式Component已限定目标，去掉pkg使其与桌面入口一致。
        result.setPackage(null);
        result.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return result;
    }

    /** 强停一个 App（等价 adb am force-stop）。 */
    public void forceStop(String pkg) {
        HelperAccessibilityService s = self;
        if (s == null) return;
        s.performGlobalAction(GLOBAL_ACTION_HOME);
        try {
            java.lang.Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "am force-stop " + pkg});
            p.waitFor();
        } catch (Exception ignored) {
            // 没 root 时做不到强停，改用「后台+清理」手势由调用方兜底
        }
    }

    // ---------------- 读界面 ----------------

    /** 当前前台的根节点（每 200ms 最多取一次，避免拖慢主线程）。 */
    public AccessibilityNodeInfo root() {
        HelperAccessibilityService s = self;
        return s == null ? null : s.getRootInActiveWindow();
    }

    /** 当前前台应用包名；读不到返回 null（2026-10-21 加：区分「画面还是上一个客户端」的残影）。 */
    public String currentPackage() {
        AccessibilityNodeInfo r = root();
        CharSequence p = r == null ? null : r.getPackageName();
        return p == null ? null : p.toString();
    }

    public List<AccessibilityNodeInfo> all() {
        AccessibilityNodeInfo r = root();
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        if (r != null) collect(r, out);
        return out;
    }

    private void collect(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out) {
        if (n == null) return;
        out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c != null) collect(c, out);
        }
    }

    /** 按文字找可点节点（精确包含），返回中心点。 */
    public int[] centerOfText(String text) {
        for (AccessibilityNodeInfo n : all()) {
            CharSequence cs = n.getText();
            if (cs != null && cs.toString().contains(text)) {
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                if (r.width() > 0 && r.height() > 0) {
                    return new int[]{r.centerX(), r.centerY()};
                }
            }
        }
        return null;
    }

    /** 点文字（原生死记点不到 Canvas 界面时才用；优先用坐标模板）。 */
    public boolean tapText(String text) {
        return tapTextExact(text) || tapTextExact(text.trim());
    }

    /** 点**精确**文字（加好友对话框的「ID」单选要精确，避免点到「名字」）。 */
    public boolean tapTextExact(String text) {
        for (AccessibilityNodeInfo n : all()) {
            CharSequence cs = n.getText();
            if (cs == null) continue;
            String s = cs.toString().trim();
            if (s.equals(text)) {
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                if (r.width() > 0 && r.height() > 0) {
                    tap(r.centerX(), r.centerY());
                    return true;
                }
            }
        }
        return false;
    }

    /** 按**精确**文字找节点（不点击；读 RadioButton 的 checked 状态等用）。 */
    public AccessibilityNodeInfo nodeWithTextExact(String text) {
        for (AccessibilityNodeInfo n : all()) {
            CharSequence cs = n.getText();
            if (cs != null && cs.toString().trim().equals(text)) return n;
        }
        return null;
    }

    /** 所有可输入框（登录页填账号密码用）。 */
    public List<AccessibilityNodeInfo> editFields() {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        for (AccessibilityNodeInfo n : all()) {
            if (n.isEditable() && n.isEnabled()) out.add(n);
        }
        return out;
    }

    /** 登录只接纳当前目标客户端的两个可见输入框，按屏幕上下顺序返回新节点。 */
    public List<AccessibilityNodeInfo> loginFields(String pkg) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        AccessibilityNodeInfo r = root();
        // 登录窗口与输入内容都可能仍在无障碍缓存中，不能只刷新根节点。
        if (r == null || !r.refresh()
                || !pkg.contentEquals(r.getPackageName() == null ? "" : r.getPackageName())) return out;
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collect(r, nodes);
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isEditable() || !n.refresh()) continue;
            Rect bounds = new Rect();
            n.getBoundsInScreen(bounds);
            if (n.isEnabled() && n.isVisibleToUser() && n.isEditable()
                    && bounds.width() > 0 && bounds.height() > 0) out.add(n);
        }
        out.sort((a, b) -> {
            Rect ra = new Rect(), rb = new Rect();
            a.getBoundsInScreen(ra); b.getBoundsInScreen(rb);
            return Integer.compare(ra.top, rb.top);
        });
        return out;
    }

    /** 往输入框填字。 */
    public boolean setField(AccessibilityNodeInfo n, String value) {
        HelperAccessibilityService s = self;
        if (s == null || n == null) return false;
        try {
            android.os.Bundle args = new android.os.Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        } catch (Exception e) {
            return false;
        }
    }

    /** 同步等一下（引擎里代替 Thread.sleep，让读代码的人知道在等什么）。 */
    public static void idle(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 等一个手势/动作完成（用 CountDownLatch 等待回调）。 */
    public static boolean awaitGesture(HelperAccessibilityService s, GestureDescription g) {
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = {true};
        s.dispatchGesture(g, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                latch.countDown();
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                ok[0] = false;
                latch.countDown();
            }
        }, null);
        try {
            return latch.await(6, TimeUnit.SECONDS) && ok[0];
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
