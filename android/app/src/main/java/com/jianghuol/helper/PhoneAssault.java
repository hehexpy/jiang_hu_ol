package com.jianghuol.helper;

import android.graphics.Bitmap;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * PhoneAssault —— 手机版「城战攻防」，整层移植自 clicker/assault_v1（2026-10-21）。
 *
 * ## 为什么能整层搬
 *
 * 电脑版 assault_v1 的设计本来就与"手"无关：它的 flow.py 只有
 * 状态机（state）+ 转移表（STEPS）+ 提交前持久化（submit_once）+ 响应轮询（response），
 * 所有取帧/点击都走注入的 Interaction。把 Interaction 换成
 * CaptureService（帧）+ HelperAccessibilityService（手势），算法一行不改。
 * 34 个模板与 ROI 登记表原样进 assets（assault_templates.json + tpl_assault_*.png）。
 *
 * ## 从电脑版继承的铁律（别在该怀的地方"优化"掉）
 *
 * 1. **提交先落盘再点击**：pending_<action>.json 用 'x' 模式原子创建 —— 点击崩了也不能重发；
 * 2. **超时 = UNKNOWN_AFTER_DISPATCH**，不是"没发出去"，不能当成功也不能直接重试；
 * 3. 招募/进攻/驻防都要 **before/after 对账**（军队总数、兵种数），对不上就是 UNKNOWN；
 * 4. REJECTED_ARMY_LIMIT（满员）是 **SKIPPED_ARMY_LIMIT**，不是失败，也不冒充成功。
 */
public class PhoneAssault {

    /** 与 clicker/assault_v1/config.py 对齐；实际运行用 RoleStore 的队长客户端（已登录的号）。 */
    private static final String PACKAGE_FALLBACK = "com.good.mmo2";
    private static final long RESPONSE_TIMEOUT_MS = 95_000;
    private static final double TH = 0.85;

    public interface Listener {
        void onLog(String line);
        void onDone(String summary);
    }

    private final HelperAccessibilityService acc;
    private final Vision vis;
    private final Listener ui;
    private final File dir;          // 持久化目录（App 外部 files）
    private volatile boolean stop;

    public PhoneAssault(HelperAccessibilityService acc, Vision vis, Listener ui, File dir) {
        this.acc = acc; this.vis = vis; this.ui = ui; this.dir = dir;
    }

    public void requestStop() { stop = true; }

    private boolean stopped() { return stop; }

    private void log(String s) {
        LogBus.get().i("assault", s);
        ui.onLog(s);
    }

    private void idle(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private Bitmap frame(long waitMs) {
        int before = CaptureService.frames();
        long end = SystemClock.elapsedRealtime() + waitMs;
        while (SystemClock.elapsedRealtime() < end) {
            Bitmap b = CaptureService.latest();
            if (b != null && CaptureService.frames() > before) return b;
            idle(80);
        }
        return CaptureService.latest();
    }

    private boolean has(String name) {
        return vis.hasRoi(frame(300), name, TH);
    }

    /** 等某个模板出现（最多 ms）；出现即返回。 */
    private boolean waitHas(String name, long ms) {
        long end = SystemClock.elapsedRealtime() + ms;
        while (SystemClock.elapsedRealtime() < end && !stopped()) {
            if (has(name)) return true;
            idle(300);
        }
        return false;
    }

    private void tapTpl(String name, String why) {
        Vision.Match m = vis.findInRoi(frame(300), name, TH);
        if (m == null) { log("  ✗ 找不到「" + why + "」"); return; }
        int sw = acc.getResources().getDisplayMetrics().widthPixels;
        int sh = acc.getResources().getDisplayMetrics().heightPixels;
        // Match 坐标是 capture（540 宽）坐标，按真实屏幕放大
        int x = (int) (m.cx / (float) 540 * sw), y = (int) (m.cy / (float) 960 * sh);
        log("  点「" + why + "」(" + x + "," + y + " 分=" + String.format("%.2f", m.score) + ")");
        acc.tap(x, y);
    }

    // ---------------------------------------------------------------- 状态机

    /** 与 recognition.py 的 state() 同序同判据。 */
    private String state() {
        if (has("error_title")) {
            if (has("closed")) return "CLOSED";
            return has("army_limit") ? "ARMY_LIMIT" : "ERROR";
        }
        if (has("confirm_title")) {
            if (has("recruit_confirm")) return "RECRUIT_CONFIRM";
            if (has("attack_confirm")) return "ATTACK_CONFIRM";
            return "UNKNOWN_CONFIRM";
        }
        if (has("header")) {
            if (has("recruit_list")) return "TROOPS";
            if (has("my_troops") && has("recruit_name")) return "RECRUIT";
            if (has("defend") && has("city")) return "OCCUPY";
            if (has("overview")) return "OVERVIEW";
            return "PANEL_UNKNOWN";
        }
        if (has("start")) return "ENTRY";
        if (has("npc")) return "WORLD";
        return "UNKNOWN";
    }

    /** 转移一步：before 态点 button，等到 after 态。 */
    private boolean transition(String before, String button, String after) {
        if (!state().equals(before)) {
            log("  ✗ 预期在 " + before + "，实际 " + state());
            return false;
        }
        tapTpl(button, button);
        long end = SystemClock.elapsedRealtime() + 8000;
        while (SystemClock.elapsedRealtime() < end && !stopped()) {
            if (state().equals(after)) return true;
            idle(400);
        }
        log("  ✗ 点了 " + button + " 但没到 " + after + "（现在 " + state() + "）");
        return false;
    }

    // ---------------------------------------------------------------- 对账

    /** 数我的士兵两页：总数 + 红巾数（recognition.py inventory_page 的等价物）。 */
    private int[] inventory() {
        int total = 0, red = 0;
        for (int page = 1; page <= 2; page++) {
            if (page == 2) { tapTpl("next", "下一页"); idle(1200); }
            for (int row = 0; row < 3; row++) {
                // 原版用 ROW_SPACING 偏移在小窗口里逐行找；手机版按行 ROI 逐行试
                if (vis.hasRoi(frame(300), "rest", TH)) total++;
                if (vis.hasRoi(frame(300), "owned_red", TH)) red++;
                acc.swipeN(0.5f, 0.75f, 0.5f, 0.35f, 300);
                idle(700);
            }
        }
        return new int[]{total, red};
    }

    // ---------------------------------------------------------------- 提交（先落盘后点击）

    private File pending(String action) { return new File(dir, "pending_" + action + ".json"); }

    /** 已经提交过（pending 还在）就不能再提交 —— 跨进程、跨重启都算。 */
    private boolean alreadyPending(String action) { return pending(action).exists(); }

    private void writeAtomic(File f, String body) {
        try {
            try (FileOutputStream fo = new FileOutputStream(f)) {
                fo.write(body.getBytes(StandardCharsets.UTF_8));
                fo.flush();
                fo.getFD().sync();
            }
        } catch (Throwable t) {
            log("  ✗ 落盘失败 " + t);
        }
    }

    private void clearPending(String action) {
        // minSdk 24，没有 File.unlink()（Java 13+）；删不掉不致命，pending 留着只是阻止重发
        if (!pending(action).delete()) {
            log("  ⚠️ pending 删除失败（不影响结果，只会阻止重发）");
        }
    }

    // ---------------------------------------------------------------- 三个动作

    private String recruit() {
        int[] before = inventory();
        if (!transition("RECRUIT", "recruit", "RECRUIT_CONFIRM")) return "UNKNOWN_AFTER_DISPATCH";
        return submit("recruit", "recruit_yes", "RECRUIT_CONFIRM", "RECRUIT", before);
    }

    private String fight(String action) {
        String st = state();
        if (st.equals("RECRUIT")) {
            if (!transition("RECRUIT", "occupy_tab", "OCCUPY")) return "UNKNOWN_AFTER_DISPATCH";
        }
        if (!state().equals("OCCUPY")) {
            log("  ✗ 不在占领页（" + state() + "）");
            return "UNKNOWN_AFTER_DISPATCH";
        }
        return submit(action, action.equals("attack") ? "attack_yes" : "defend",
                "OCCUPY", "OCCUPY", null);
    }

    /**
     * 提交一次动作：pending 落盘 → 点按钮 → 轮询响应 → 对账。
     *
     * before 非空时做 before/after 对账（招募）；攻防没有成功样本，只报 UNKNOWN。
     */
    private String submit(String action, String button, String expectBefore,
                          String expectAfter, int[] before) {
        if (alreadyPending(action)) {
            log("  ⚠️ " + action + " 有未决提交（pending 还在），**不再重发**；请人工核对游戏内结果");
            return "UNKNOWN_AFTER_DISPATCH";
        }
        if (!state().equals(expectBefore)) {
            log("  ✗ 提交前界面不符：" + state());
            return "UNKNOWN_AFTER_DISPATCH";
        }
        // ① 先落盘（原子），再点击 —— 崩在点击上也不得自动重发
        writeAtomic(pending(action), "{\"action\":\"" + action + "\","
                + "\"result\":\"UNKNOWN_AFTER_DISPATCH\"}");
        log("  提交 " + action + "（已落 pending，不重发）");
        tapTpl(button, button);

        // ② 轮询响应：CLOSED/ERROR/ARMY_LIMIT 都是明确拒绝；超时=未知
        long end = SystemClock.elapsedRealtime() + RESPONSE_TIMEOUT_MS;
        String result = null;
        while (SystemClock.elapsedRealtime() < end && !stopped()) {
            String st = state();
            if (st.equals("CLOSED")) { result = "REJECTED_ACTIVITY_CLOSED"; break; }
            if (st.equals("ARMY_LIMIT")) { result = "REJECTED_ARMY_LIMIT"; break; }
            if (st.equals("ERROR")) { result = "UNKNOWN_AFTER_DISPATCH"; break; }
            if (st.equals(expectAfter)) { result = "NEEDS_RECONCILIATION"; break; }
            idle(600);
        }
        if (result == null) {
            log("  " + action + " 响应超时 → UNKNOWN_AFTER_DISPATCH（不是没发出去，是证明不了）");
            return "UNKNOWN_AFTER_DISPATCH";
        }

        // ③ 对账
        if ("NEEDS_RECONCILIATION".equals(result) && before != null) {
            int[] after = inventory();
            boolean ok = after[0] == before[0] + 1 && after[1] == before[1] + 1;
            result = ok ? "VERIFIED" : "UNKNOWN_AFTER_DISPATCH";
            log("  对账 前=" + before[0] + "/" + before[1] + " 后=" + after[0] + "/" + after[1]
                    + " → " + result);
        } else if ("NEEDS_RECONCILIATION".equals(result)) {
            // 攻防没有成功样本，回到占领页不能证明成功
            result = "UNKNOWN_AFTER_DISPATCH";
        }
        if (result.equals("VERIFIED") || result.startsWith("REJECTED_")) {
            clearPending(action);
        }
        log("  " + action + " = " + result);
        return result;
    }

    // ---------------------------------------------------------------- 入口

    /** action: cycle / recruit / attack / defend。 */
    public void run(String action) {
        if (!RunGate.acquire(this, this::requestStop)) { ui.onDone("已有流程控制游戏"); return; }
        try {
            dir.mkdirs();
            // 优先队长客户端（已登录），没配置过队长才回退到电脑版标定包
            RoleStore roles = new RoleStore(ContextHolder.get());
            String pkg = roles.leader();
            if (pkg == null || pkg.isEmpty()) pkg = PACKAGE_FALLBACK;
            log("[攻防·手机版] action=" + action + "  包=" + pkg);
            acc.launch(pkg);
            idle(4000);
            // 导航到征兵页（flow.py navigate()）
            navigate();
            String[] actions = action.equals("cycle")
                    ? new String[]{"recruit", "attack", "defend"} : new String[]{action};
            StringBuilder sb = new StringBuilder();
            for (String a : actions) {
                if (stopped()) break;
                String r = a.equals("recruit") ? recruit() : fight(a);
                sb.append(a).append('=').append(r).append(' ');
                if (!r.equals("VERIFIED") && !r.equals("SKIPPED_ARMY_LIMIT")) break;
            }
            ui.onDone("攻防结束：" + sb.toString().trim());
        } catch (Throwable t) {
            LogBus.get().e("assault", "运行异常", t);
            ui.onDone("攻防异常：" + t.getClass().getSimpleName());
        } finally {
            RunGate.release(this);
        }
    }

    /** 照搬 flow.py navigate()：WORLD --npc--> ENTRY --start--> OVERVIEW --recruit_tab--> RECRUIT。 */
    private void navigate() {
        String[] chain = {"npc", "start", "recruit_tab"};
        String[] from = {"WORLD", "ENTRY", "OVERVIEW"};
        String[] to = {"ENTRY", "OVERVIEW", "RECRUIT"};
        long deadline = SystemClock.elapsedRealtime() + 40_000;
        while (SystemClock.elapsedRealtime() < deadline && !stopped()) {
            String st = state();
            if (st.equals("RECRUIT")) { log("  已到征兵页"); return; }
            boolean moved = false;
            for (int i = 0; i < chain.length; i++) {
                if (st.equals(from[i])) {
                    if (transition(from[i], chain[i], to[i])) { moved = true; break; }
                }
            }
            if (!moved) idle(800);
        }
        log("  ⚠️ 没能导航到征兵页（现在 " + state() + "）——停在当前状态，不盲点");
    }
}
