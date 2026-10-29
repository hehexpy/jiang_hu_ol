package com.jianghuol.helper;

import android.os.SystemClock;

/**
 * PhoneRunner —— 手机闭环「一键上号 + 组队」的总流程。
 *
 * ## 为什么有这一份（2026-10-20 22:10 定）
 *
 * 电脑版 team_up.py 靠 adb 驱动雷电模拟器，**牛是牛，但离不开电脑**。
 * 手机上要的就是同一套流程、同一个判据，只是「眼睛/手」换了实现：
 *
 * | 电脑版                    | 手机版（本类）                          |
 * |---------------------------|----------------------------------------|
 * | adb screencap             | MediaProjection 帧（CaptureService）    |
 * | cv2 模板匹配/颜色连通域   | Vision 自研 NCC/HSV flood-fill          |
 * | adb input tap             | 无障碍 dispatchGesture                  |
 * | _roles_XXXX.json 门禁     | RoleStore 里的队长/队员配置             |
 *
 * ## 判据来源（三层复用，别重新发明）
 *
 * 1. **REA 静态分析契约**（analysis/t025 → 已复制到 t046）：
 *    `team_session_actions.json` 的 17 条组队/会话契约（preconditions /
 *    success_predicate / timeout_query / retry_policy）+ `action-index.json`。
 *    本类的每个步骤都对应其中一条动作；契约里的不变量（超时=结果未知不能当没发、
 *    身份要刷新、不盲重发）已落进注释和分支。
 * 2. **电脑端实测阈值**：analysis/t046/join_team.py / team_up.py 的判据和模板分数。
 * 3. **本轮真机校准**：2026-10-21 在模拟器上实测修正的模板（见各阈值注释）。
 *
 * 用法：UI 线程造好后丢给工作线程 run()，onLog 回调刷 UI。
 */
public class PhoneRunner {

    public interface Listener {
        void onLog(String line);
        void onDone(String summary, boolean passed);
    }

    private final HelperAccessibilityService acc;
    private final Vision vis;
    private final Listener ui;
    private volatile boolean stop;
    private RotationSession readyRegroup;
    private boolean readyRegroupNeeded;
    boolean readyRegroupNeeded(){return readyRegroupNeeded;}
    void regroupReadyTeam(RotationSession saved){readyRegroup=saved;run();}
    private volatile Thread worker;
    private CaptureService.Snapshot observed;
    private long inputBarrierNs;
    private IdGlyph captainIdGlyph;
    private IdGlyph inspectedFriend, deletedFriend;
    private boolean applyDispatched;
    private boolean rotationOnly;
    private ProfessionRecognizer extraVision;
    private ProfessionRecognizer extras() {
        if (extraVision == null) {
            try { extraVision = new ProfessionRecognizer(acc.getAssets(), vis); }
            catch (java.io.IOException e) { throw new IllegalStateException("现场模板登记不可用", e); }
        }
        return extraVision;
    }

    /** 每轮从当前队长角色面板采集；两张新画面一致才接纳，绝不沿用旧模板。 */
    private boolean captureCaptainId(String pkg) {
        captainIdGlyph = null;
        if (!pkg.equals(acc.currentPackage()) || !inGame()) return false;
        Vision.Match avatar = vis.findNear(frame(300), "tpl_avatar.png", 0.85);
        if (avatar == null) { log("  ✗ 未确认头像，无法采集队长ID"); return false; }
        tapMatch(avatar);
        if (!waitTpl("tpl_role_title.png", 0.88, 3500)) return false;
        IdGlyph first = vis.idGlyph(frame(300), true);
        IdGlyph second = vis.idGlyph(frame(400), true);
        IdGlyph.Score stable = first == null ? null : first.compare(second);
        if (stable == null || !stable.matches()) {
            log("  ✗ 队长ID字形采集不稳定 → 停止，不用旧模板");
            saveEvidence("captain_id_failed");
            return false;
        }
        saveEvidence("captain_id_source");
        Vision.Match close = vis.findNear(frame(300), "tpl_role_close.png", 0.88);
        if (close == null) return false;
        tapMatch(close);
        if (!waitInGameStable(pkg, 600) || has("tpl_role_title.png", 0.88)) return false;
        captainIdGlyph = second;
        log("  ✓ 已采集本轮队长ID字形（" + second.size() + "字符），白/黄字去背景后逐字核对");
        return true;
    }

    /** 判据模板名 → 分数阈值，全部来自 analysis/t046/tpl 实测值。 */
    private static final double T_TAB = 0.88, T_LEAVE = 0.93, T_LEAVEOK = 0.90,
            T_KICK = 0.90, T_TEAMTAB = 0.88, T_INVITE = 0.88, T_INVITEOK = 0.90,
            T_TEAM_INVITE = 0.60, T_AUTOTEAM = 0.92, T_MENU = 0.90, T_SOCIAL = 0.90,
            T_FRIEND = 0.90, T_APPLY = 0.90, T_ADDOK = 0.85, T_ERR = 0.90,
            T_LOGIN = 0.85, T_ENTER = 0.85, T_SRV = 0.90;
    /**
     * 登录面板判据模板。
     *
     * 2026-10-21 00:20 实测校准：老的 tpl_login_game.png 在当前账号的登录屏上只有 0.564
     * （别的机型/账号布局不同），而 tpl_login_panel_btn.png 是 0.999。所以**用后者**。
     */
    private static final String TPL_LOGIN_PANEL = "tpl_login_panel_btn.png";

    public PhoneRunner(HelperAccessibilityService acc, Vision vis, Listener ui) {
        this.acc = acc; this.vis = vis; this.ui = ui;
    }

    public void requestStop() {
        stop = true;
        Thread t = worker;
        if (t != null) t.interrupt();
    }

    private boolean stopped() {
        return stop || Thread.currentThread().isInterrupted();
    }

    /** 绿血条 HSV 范围（照搬 clicker/detect.py：左上角绿色血条=在游戏里）。 */
    private static final float[] GREEN_LOW = {35, 31, 31}, GREEN_HIGH = {85, 100, 100};

    /**
     * 是否已在游戏世界中 —— 左上角 (0~0.4w, 0~0.25h) 有绿色血条/头像。
     *
     * 为什么要先判它：五个号通常都已登录，无脑等登录面板 90 秒会让 ① 步白等 7 分钟
     * （2026-10-20 23:12 实测：5 个号 × 90s）。判据与电脑端 detect.py 完全一致。
     */
    private boolean inGame() {
        return inGame(frame(500));
    }

    private boolean inGame(android.graphics.Bitmap f) {
        if (f == null || extras().enter(f) != null || extras().mapOpen(f)) return false;
        // 判据1：绿色血条（满血状态）
        java.util.List<Vision.Blob> bs = vis.blobs(f, new float[]{0f, 0f, 0.40f, 0.25f},
                GREEN_LOW, GREEN_HIGH, 1.0e-4, 0);
        for (Vision.Blob b : bs) {
            if (b.w >= f.getWidth() * 0.12f) return true;   // 血条有宽度，避免零碎绿点
        }
        // 判据2：右下角「菜单」按钮（2026-10-21 补）：血条变红（低血量）时判据1恒假，
        // 实测公告弹窗盖脸照样被判"没进游戏"白等 90 秒。菜单按钮只在游戏主界面有。
        return vis.findNear(f, "tpl_menu_btn.png", 0.90) != null || extras().worldControls(f);
    }

    private void finish(String summary, boolean passed) {
        boolean confirmed = passed && !stopped();
        if (stopped()) summary = "已停止；结果未确认。" + summary;
        log("[结果] " + (confirmed ? "已确认成功：" : "失败或未确认：") + summary);
        ui.onDone(summary, confirmed);
    }

    private void log(String s) {
        // 双写：LogBus（落盘/logcat）+ 界面（如果有挂着的日志卡）
        LogBus.get().i("phone", s);
        ui.onLog(LogBus.get().hms(System.currentTimeMillis()).substring(0, 8) + "  " + s);
    }

    private void idle(long ms) {
        long perfStart = System.nanoTime();
        long end = SystemClock.elapsedRealtime() + ms;
        try {
            while (!stopped() && SystemClock.elapsedRealtime() < end)
                Thread.sleep(Math.max(1, Math.min(50, end - SystemClock.elapsedRealtime())));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            PerfStats.add("explicit_wait", perfStart);
        }
    }

    /** 取最近一帧；`waitFrames` 等待帧号前进，确保不是上一步的旧画面。 */
    private android.graphics.Bitmap frame(long waitMs) {
        long perfStart = System.nanoTime();
        int before = observed == null ? -1 : observed.number;
        long end = SystemClock.elapsedRealtime() + waitMs;
        while (SystemClock.elapsedRealtime() < end && !stopped()) {
            CaptureService.Snapshot s = CaptureService.snapshot();
            if (s != null && s.number > before && s.acquiredNs > inputBarrierNs
                    && System.nanoTime() - s.acquiredNs < 1_000_000_000L) {
                observed = s;
                PerfStats.add("frame_wait", perfStart); return s.bitmap;
            }
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        PerfStats.add("frame_wait", perfStart);
        return null; // 没有动作后的新帧就是未知，禁止退回latest旧画面。
    }

    /** 同一轮多个模板共用已观察帧；任何输入动作立即使它失效。 */
    private android.graphics.Bitmap observation() {
        if (stopped()) return null;
        if (observed != null && observed.acquiredNs > inputBarrierNs
                && System.nanoTime() - observed.acquiredNs < 500_000_000L) return observed.bitmap;
        return frame(500);
    }

    private void tapPixels(int x, int y) {
        if (stopped()) return;
        acc.tap(x, y);
        inputBarrierNs = System.nanoTime() + 120_000_000L;
        observed = null;
    }

    private void launch(String pkg) {
        if (stopped()) return;
        acc.launch(pkg);
        inputBarrierNs = System.nanoTime() + 120_000_000L;
        observed = null;
    }

    private boolean tapTextExact(String label) {
        if (stopped() || !acc.tapTextExact(label)) return false;
        inputBarrierNs = System.nanoTime() + 120_000_000L; observed = null;
        return true;
    }

    private void back() {
        if (stopped()) return;
        acc.back(); inputBarrierNs = System.nanoTime() + 120_000_000L; observed = null;
    }

    /** 等模板出现（最多 timeout）。轮询间隔 250ms：UI 状态不会毫秒级变，搜再勤也是白烧 CPU。 */
    private boolean waitTpl(String name, double minScore, long timeout) {
        long start = System.nanoTime();
        long end = SystemClock.elapsedRealtime() + timeout;
        try {
            while (SystemClock.elapsedRealtime() < end && !stopped()) {
                if (vis.findNear(frame(Math.min(500, Math.max(1, end - SystemClock.elapsedRealtime()))), name, minScore) != null) return true;
            }
            return false;
        } finally { PerfStats.add("result_wait:" + name, start); }
    }

    private boolean hasOn(android.graphics.Bitmap f, String name, double score) {
        return vis.findNear(f, name, score) != null;
    }

    private boolean has(String name, double score) {
        return vis.findNear(observation(), name, score) != null;
    }

    private void tapTpl(String name, double score, String why) {
        Vision.Match m = vis.findNear(observation(), name, score);
        if (m == null) {
            log("  ✗ 找不到 " + why + "（模板未命中）");
            return;
        }
        // Match.fx/fy 按实际采集帧归一化，直接乘真实屏幕尺寸。
        int sw = acc.getResources().getDisplayMetrics().widthPixels;
        int sh = acc.getResources().getDisplayMetrics().heightPixels;
        int x = (int) (m.fx() * sw), y = (int) (m.fy() * sh);
        log("  点 " + why + " (" + x + "," + y + " 分=" + String.format("%.2f", m.score) + ")");
        tapPixels(x, y);
    }

    // ---------------------------------------------------------------- ① 上号

    /**
     * 拉起客户端并确保进游戏。
     *
     * 为什么改成轮询而不是顺序等（用户 2026-10-21 00:10：
     * 「为什么一定要等待60秒呢？你觉得稳定的情况下是不是越快越好呢？」）：
     * 老写法「先等启动页 18s → 再等登录面板 60s」是**盲等**，已登录的号也要 78 秒。
     * 现在一个循环里同时判三件事，谁先成立谁先走：
     *   ① 左上角绿血条 → 已在游戏（最快 ~5 秒过关）
     *   ② 「点击继续」模板 → 启动页，点掉
     *   ③ 登录面板模板 → 填账号密码
     */
    /** 只关闭明确的掉线弹窗；新帧确认登录页才算恢复到登录入口。 */
    private boolean closeDisconnectToLogin(android.graphics.Bitmap f) {
        Vision.Match close=extras().disconnectClose(f);
        if(close==null)return false;
        log("[掉线] 确认掉线提示及弹窗X → 关闭并等待登录界面");
        saveEvidence("disconnect");tapMatch(close);
        long end=SystemClock.elapsedRealtime()+extras().recoveryLimit("login_timeout_ms");
        while(!stopped()&&SystemClock.elapsedRealtime()<end) {
            f=frame(400);
            if(extras().disconnectClose(f)==null&&vis.findNear(f,TPL_LOGIN_PANEL,.90)!=null) {
                saveEvidence("disconnect_login");log("[掉线] 已确认返回登录游戏界面");return true;
            }
        }
        throw new IllegalStateException(stopped()?"已停止；保留掉线进度":"掉线关闭后登录界面未确认；保留进度");
    }

    private boolean onlineOne(String pkg, String account, String password) {
        log("-- 启动 " + pkg + "（账号 " + account + "）");
        launch(pkg);
        long deadline = SystemClock.elapsedRealtime() + 90_000;
        long lastContinueTap = 0;
        long lastDismiss = 0;
        int reconnects=0;
        while (SystemClock.elapsedRealtime() < deadline && !stopped()) {
            android.graphics.Bitmap connectionFrame=frame(400);
            if(extras().mapOpen(connectionFrame)){
                if(!closeMapToWorld(connectionFrame))return false;
                continue;
            }
            if(extras().disconnectClose(connectionFrame)!=null) {
                RotationSession.require(++reconnects<=extras().recoveryLimit("attempts"),"上号掉线恢复次数已用完");
                closeDisconnectToLogin(connectionFrame);continue;
            }
            if (inGameStable(pkg)) {
                log("  已在游戏世界中 → 跳过登录");
                return true;
            }
            android.graphics.Bitmap loginFrame = observation();
            Vision.Match selectedEnter = extras().enter(loginFrame);
            if (selectedEnter != null && extras().captureSelectionGlyph(loginFrame)!=null) {
                log("  已在选角页 → 只进入当前已选角色，不点全局X");
                tapMatch(selectedEnter);
                return waitInGame(30_000);
            }
            // 先判登录面板再判「点击继续」：continue 模板在登录屏上有 0.782 的误匹配，
            // 若它先命中并 continue，登录面板分支永远轮不到（2026-10-21 实测卡死循环）。
            if (has(TPL_LOGIN_PANEL, 0.90)) {
                log("  检测到登录面板 → 填账号密码");
                return fillLogin(pkg, account, password);
            }
            // 「点击继续」是闪烁提示，且刚拉起时游戏还没接受输入 —— 第一.tap 常常丢掉。
            // 所以不能只点一次：模板还在（说明人还在启动页）就隔 3 秒再点（2026-10-21 实测）。
            // 阈值 0.90：真启动页 0.998，登录屏/游戏内负样本最高 0.782。
            Vision.Match continueBtn = vis.findNear(observation(), "tpl_click_continue.png", 0.90);
            if (continueBtn != null
                    && SystemClock.elapsedRealtime() - lastContinueTap > 3000) {
                log("  启动页 → 点「点击继续」");
                lastContinueTap = SystemClock.elapsedRealtime();
                tapMatch(continueBtn);
                continue;
            }
            // 三个判据都没命中超过 8 秒 → 多半是被「离开游戏」这类 ✕ 对话框压着
            //（对话框压暗背景，登录面板模板也会掉到阈值之下），定期关一次 ✕ 自救。
            if (SystemClock.elapsedRealtime() - lastDismiss > 8000) {
                lastDismiss = SystemClock.elapsedRealtime();
                dismissPopups();
            }
            idle(400);
        }
        // 如实报失败：以前这里返回 true（"可能已在游戏"），是自欺欺人
        log("  ✗ 90 秒内既没进游戏也没见到登录面板");
        return false;
    }

    /**
     * 登录面板填账号密码（2026-10-21 用户要求：登录必须**真输入**账号密码，不吃"已保存账号"老底）。
     *
     * 游戏登录面板是 Canvas 画的（无障碍 edits=0），但点「账号」框会弹**原生**登录对话框
     * （用户名/密码/确定/返回），对话框里的 EditText 无障碍可读可写 —— 手动实测过两次。
     * 输入手法与 clicker/uia.py type_field 同源（那里是 adb 版，这里是 ACTION_SET_TEXT 版）。
     */
    private boolean fillLogin(String pkg, String account, String password) {
        int sw = acc.getResources().getDisplayMetrics().widthPixels;
        int sh = acc.getResources().getDisplayMetrics().heightPixels;
        log("  点账号框 → 等待目标客户端原生登录对话框");
        tapPixels((int) (0.568f * sw), (int) (0.470f * sh));
        long deadline = SystemClock.elapsedRealtime() + 8000;
        boolean retapped = false;
        java.util.List<android.view.accessibility.AccessibilityNodeInfo> edits;
        while (!stopped()) {
            edits = acc.loginFields(pkg);
            if (edits.size() == 2) break;
            if (SystemClock.elapsedRealtime() >= deadline) {
                log("  ✗ 超时未读到目标客户端的两个登录输入框 → 停止，不提交");
                return false;
            }
            // 点按偶尔丢失；只在新帧仍是Canvas登录面板、且完全没有输入框时重开一次。
            if (!retapped && edits.isEmpty() && deadline - SystemClock.elapsedRealtime() < 4000
                    && pkg.equals(acc.currentPackage())
                    && vis.findNear(frame(250), TPL_LOGIN_PANEL, .90) != null) {
                retapped = true;
                tapPixels((int) (0.568f * sw), (int) (0.470f * sh));
            }
            idle(100);
        }
        if (stopped()) return false;
        // ACTION_SET_TEXT成功仅表示动作被接收；每次写入后重新查询节点并精确读回。
        if (!setLoginField(pkg, 0, account) || !setLoginField(pkg, 1, password)) {
            log("  ✗ 登录输入未通过新节点精确读回校验 → 停止，不提交");
            return false;
        }
        edits = acc.loginFields(pkg);
        if (edits.size() != 2 || !fieldEquals(edits.get(0), account)
                || !fieldEquals(edits.get(1), password) || stopped()) {
            log("  ✗ 提交前账号口令读回不一致或停止 → 不提交");
            return false;
        }
        log("  ✓ 账号口令已通过新节点精确读回校验（不输出内容）");
        // 「确定」提交（原生按钮按文本点；点不到用坐标兜底）
        if (!tapTextExact("确定")) return false;
        inputBarrierNs = System.nanoTime() + 120_000_000L; observed = null;
        if (!waitTpl(TPL_LOGIN_PANEL, .90, 4000)) return false;
        // 回到登录面板 → 点「登陆游戏」（按钮文字实测「登陆游戏」；模板兜底 0.90）
        if (tapTextExact("登陆游戏") || tapTextExact("进入游戏")) {
            log("  点了「登陆游戏」");
        } else {
            tapTpl("tpl_login_panel_btn.png", 0.90, "登陆游戏");
        }
        // 先等 12 秒：多数号存的服务器就是目标服，点了直接进
        // （2026-10-21 实测：点完「登陆游戏」先停在**选角色页**——「进入游戏」按钮 tpl_enter_game 0.959）
        long loginEnd = SystemClock.elapsedRealtime() + 32_000;
        while (!stopped() && SystemClock.elapsedRealtime() < loginEnd) {
            android.graphics.Bitmap f = frame(500);
            if (inGame(f)) { log("  ✓ 已进入游戏"); return true; }
            Vision.Match enter = vis.findNear(f, "tpl_enter_game.png", .90);
            if (enter != null) {
                log("  选角色页 → 点「进入游戏」");
                tapMatch(enter);
                return waitInGame(90_000);
            }
        }
        // 还在登录面板 = 服务器不对，切到九州揽月再进
        if (has(TPL_LOGIN_PANEL, 0.90)) {
            log("  点了登录但没进 → 切服务器到九州揽月");
            tapTpl("tpl_server_jiuzhoulanyue.png", 0.90, "选择九州揽月");
            idle(1500);
            tapPixels((int) (0.86f * acc.getResources().getDisplayMetrics().widthPixels),
                    (int) (0.516f * acc.getResources().getDisplayMetrics().heightPixels));  // 「切换」按钮
            idle(1500);
            if (tapTextExact("登陆游戏") || tapTextExact("进入游戏")) {
                idle(1000);
            }
        }
        // 登录后轮询等进游戏（不盲睡固定秒数）
        // 90 秒：实测服务器忙时登录确认要 1 分多钟（wxr3/wxr4 曾被 45 秒掐掉，实际稍后就进了）
        if (waitInGame(90_000)) {
            log("  ✓ 已进入游戏");
            return true;
        }
        log("  ✗ 点了登录但 90 秒内没进游戏");
        return false;
    }

    private static boolean fieldEquals(android.view.accessibility.AccessibilityNodeInfo field, String expected) {
        CharSequence value = field.getText();
        return value != null && expected.contentEquals(value);
    }

    private boolean setLoginField(String pkg, int index, String value) {
        for (int attempt = 0; attempt < 2 && !stopped(); attempt++) {
            java.util.List<android.view.accessibility.AccessibilityNodeInfo> fields = acc.loginFields(pkg);
            if (fields.size() != 2 || !acc.setField(fields.get(index), value)) return false;
            long end = SystemClock.elapsedRealtime() + 1500;
            while (!stopped() && SystemClock.elapsedRealtime() < end) {
                // 不复用performAction之前的AccessibilityNodeInfo快照。
                fields = acc.loginFields(pkg);
                if (fields.size() == 2 && fieldEquals(fields.get(index), value)) return true;
                idle(80);
            }
        }
        return false;
    }

    /** 轮询等进游戏，最多 ms 毫秒；进了立刻返回 true。 */
    /** 进游戏的稳定判据：连续两次（隔 2.5 秒）都认出才算。
     *  ⚠️ launch 瞬间帧还是上一个客户端的画面（包名先切、画面后切），
     *  单次 inGame 会在 0.3 秒内"假进游戏"——2026-10-21 日志实锤（wxr2/wxr5 被跳过登录）。 */
    private boolean inGameStable(String pkg) {
        return inGameStable(pkg, 2500);
    }

    /** gapMs 可调：队长保活已先经 waitForPkg 核对包名，残影风险低，用短间隔。 */
    private boolean inGameStable(String pkg, long gapMs) {
        if (!inGame(observation())) return false;
        // 包名防残影：画面可能还是上一个客户端的（包名先切、渲染后切）
        if (pkg != null && !pkg.equals(acc.currentPackage())) return false;
        idle(gapMs);
        return inGame() && (pkg == null || pkg.equals(acc.currentPackage()));
    }

    /** 轮询等「稳定进游戏」，最多 20 秒（memberApplyJoin/队长保活的拉起确认用）。 */
    private boolean waitInGameStable(String pkg) {
        return waitInGameStable(pkg, 2500);
    }

    private boolean waitInGameStable(String pkg, long gapMs) {
        long end = SystemClock.elapsedRealtime() + 20_000;
        while (SystemClock.elapsedRealtime() < end && !stopped()) {
            if (inGameStable(pkg, gapMs)) return true;
            idle(400);
        }
        return false;
    }

    private boolean waitInGame(long ms) {
        long end = SystemClock.elapsedRealtime() + ms;
        while (SystemClock.elapsedRealtime() < end && !stopped()) {
            if (inGame()) return true;
            idle(400);
        }
        return false;
    }

    // ---------------------------------------------------------------- ② 清场

    /** 右上区域找真实X；每关一次重新截图，覆盖登录公告与意外遮挡。 */
    private boolean dismissPopups() {
        boolean closed = false;
        boolean claimed = false;
        for (int i = 0; i < 8; i++) {
            if (stopped()) break;
            android.graphics.Bitmap f = frame(300);
            if (f == null) return closed;
            // 全局退出X与弹窗X完全同图形。非世界页只处理已登记标题的具体弹窗。
            if (extras().button(f, "exit_title") != null) {
                Vision.Match cancel = extras().button(f, "exit_cancel");
                if (cancel == null) return closed;
                log("  已确认离开游戏提示 → 仅点返回，禁止确定或全局X");
                tapMatch(cancel); closed = true; idle(200); continue;
            }
            // 公告关闭优先于背后好友/周围面板的X；标题+局部按钮双门禁。
            if(extras().button(f,"notice_title")!=null) {
                Vision.Match noticeClose=extras().button(f,"notice_close");
                if(noticeClose==null)return closed;
                log("  游戏公告标题已确认 → 关闭前景公告");
                tapMatch(noticeClose);closed=true;
                long noticeEnd=SystemClock.elapsedRealtime()+1800;
                while(!stopped()&&SystemClock.elapsedRealtime()<noticeEnd) {
                    android.graphics.Bitmap next=frame(300);
                    if(next!=null&&extras().button(next,"notice_title")==null)break;
                }
                continue;
            }
            // 好友/周围大面板也会遮住地图。旧小弹窗模板只截了边框装饰，
            // 全屏搜会在大面板右上角偏左处命中，重复点仍关不掉。
            boolean reward = extras().button(f, "reward_title") != null;
            boolean noSelection = extras().button(f, "reward_none") != null;
            Vision.Match claim = reward ? extras().button(f, "reward_claim") : null;
            Vision.Match rewardClose = reward ? extras().button(f, "reward_close") : null;
            Vision.Match resultClose = noSelection ? extras().button(f, "reward_result_close") : null;
            boolean knownPanel = hasOn(f, "tpl_title_friend.png", 0.90)
                    || vis.findInRoi(f, "tpl_team_tab.png", 0.88) != null;
            if (!reward && !noSelection && !PopupDecision.genericAllowed(inGame(f), knownPanel)) return closed;
            Vision.Match m = vis.findInRoi(f, "tpl_popup_x.png", 0.90);
            // 顶端是登录/选角/角色页共用全局X；只有已知面板才允许在该高度关闭。
            if (m != null && !PopupDecision.candidateAllowed(inGame(f), knownPanel,
                    hasOn(f, "tpl_role_title.png", .90), m.fy())) m = null;
            if (knownPanel) {
                if (m == null) m = vis.findInRoi(f, "tpl_panel_close.png", 0.88);
            }
            PopupDecision.Action action = PopupDecision.choose(noSelection, reward, claim != null,
                    claimed, rewardClose != null, m != null);
            if (action == PopupDecision.Action.NONE) return closed;
            if (action == PopupDecision.Action.CLOSE_RESULT) {
                if (resultClose == null) { saveEvidence("reward_result_unknown"); return closed; }
                log("  奖励未领取：没有选取领取奖励；关闭提示，不自动选物");
                m = resultClose; claimed = true;
            } else if (action == PopupDecision.Action.CLAIM) {
                log("  登陆奖励有领取按钮 → 提交一次，等待新画面");
                tapMatch(claim); claimed = true; closed = true; idle(400); continue;
            } else if (action == PopupDecision.Action.CLOSE_REWARD) {
                log(claimed ? "  本次领取已尝试；闭环关闭奖励页，未据此声明领取成功" : "  无领取按钮 → 关闭奖励页");
                m = rewardClose;
            } else if (action == PopupDecision.Action.WAIT) {
                idle(200); continue;
            }
            int sw = acc.getResources().getDisplayMetrics().widthPixels;
            int sh = acc.getResources().getDisplayMetrics().heightPixels;
            int x = (int) (m.fx() * sw), y = (int) (m.fy() * sh);
            log("  关弹窗 ✕ (" + x + "," + y + " 分=" + String.format("%.2f", m.score) + ")");
            tapPixels(x, y);
            closed = true;
            idle(300);
        }
        return closed;
    }

    /** 地图拖到最左端后找被头像遮挡的标牌两侧；不点击未经匹配的位置。 */
    private boolean worldMapTravel() {
        if (inCityConfirmed()) return true;
        android.graphics.Bitmap f = observation();
        Vision.Match landmark = extras().mapLandmark(f);
        boolean guideSeen = false;
        for (int attempt=0; !extras().mapOpen(f) && !stopped()
                && attempt<extras().mapSetting("open_attempts"); attempt++) {
            // 每次重新确认世界页地图按钮，地图已打开时绝不重复点。
            Vision.Match open = extras().button(f, "map_button");
            if (open == null) { log("  未确认底部地图按钮"); return false; }
            log("  打开世界地图 第"+(attempt+1)+"次");
            tapMatch(open);
            long end = SystemClock.elapsedRealtime() + extras().mapSetting("open_timeout_ms");
            boolean reportedGuide = false;
            while (!stopped() && SystemClock.elapsedRealtime() < end) {
                f = frame(250); landmark = extras().mapLandmark(f);
                if (extras().mapOpen(f)) break;
                if (extras().button(f,"map_guide_blocked") != null) {
                    guideSeen = true;
                    if (!reportedGuide) { log("  出现引导提示 → 等新帧后重新确认地图按钮再试"); reportedGuide = true; }
                }
            }
        }
        if (!extras().mapOpen(f) || stopped()) {
            mapTravelFailure = guideSeen ? "引导提示后地图仍未打开（有界重试已用完）" : "世界地图滚轴与底部X未同时确认";
            log("  "+mapTravelFailure); saveEvidence("map_open_unknown"); return false;
        }
        log("  世界地图已打开 → 向右拖回最左端");
        // 标牌左右结构与塔楼已经联合命中时，目标可直接定位，无需再拖到边界。
        boolean leftEdge = extras().mapCity(f) != null;
        if(leftEdge)log("  襄阳城地标与标牌两侧已确认，跳过地图边界拖动");
        for (int i=0; i<5 && !leftEdge && !stopped(); i++) {
            Float before = landmark == null ? null : landmark.fx();
            // 手指向右拖，使视口回到世界地图最左端。滑动参数在模板登记中。
            float[] drag = extras().mapDrag();
            acc.swipeN(drag[0], drag[1], drag[2], drag[3], 350);
            inputBarrierNs = System.nanoTime() + 400_000_000L; observed = null;
            f = frame(1000); landmark = extras().mapLandmark(f);
            if (!extras().mapOpen(f)) { log("  拖动后世界地图界面未确认"); return false; }
            if(extras().mapCity(f)!=null){leftEdge=true;break;}
            // 中间/右端视口尚无塔楼时继续拖；出现塔楼后再用位置不动确认左端。
            if (before != null && landmark != null && Math.abs(before-landmark.fx()) < .004f) {
                leftEdge = true; break;
            }
        }
        // 手势结束后地图仍可能回弹，短时只观察目标，不继续拖动打断回弹。
        if(!leftEdge){
            log("  目标暂被提示或地图过渡遮挡 → 等待联合模板出现，命中即继续");
            long settleEnd=SystemClock.elapsedRealtime()+extras().mapSetting("timeout_ms");
            while(!stopped()&&SystemClock.elapsedRealtime()<settleEnd){
                f=frame(500);
                if(f==null)continue;
                if(!extras().mapOpen(f))break;
                if(extras().mapCity(f)!=null){leftEdge=true;break;}
            }
        }
        if (!leftEdge || stopped()) {
            log("  有界拖动后未确认地图最左端"); saveEvidence("map_left_edge_unknown"); return false;
        }
        Vision.Match city = null;
        long markerEnd=SystemClock.elapsedRealtime()+3000;
        while(!stopped()&&SystemClock.elapsedRealtime()<markerEnd){
            f=frame(500);
            if(f==null)continue;
            if(!extras().mapOpen(f))break;
            city=extras().mapCity(f);
            if(city!=null)break;
        }
        if (city == null || stopped()) { log("  襄阳城标牌两侧未同时确认；不盲点"); saveEvidence("map_marker_unknown"); return false; }
        log("  地图已到最左端；标牌左右结构同时匹配 → 传送襄阳城");
        tapMatch(city);
        long end = SystemClock.elapsedRealtime() + extras().mapSetting("timeout_ms");
        while (!stopped() && SystemClock.elapsedRealtime() < end) {
            frame(300);
            if (inCityConfirmed()) { log("  已刷新到襄阳城 → 立即继续"); return true; }
        }
        log("  地图传送未确认，停止，不重复点标记"); saveEvidence("map_travel_unknown");
        return false;
    }

    /** 关「自动组队」悬浮窗（有的话）。 */
    private boolean dismissAutoTeam() {
        android.graphics.Bitmap f = frame(600);
        if (f == null) return false;
        Vision.Match button = vis.findInRoi(f, "tpl_autoteam.png", T_AUTOTEAM);
        if (button != null) {
            log("  关「自动组队」悬浮窗");
            tapMatch(button);
            long end = SystemClock.elapsedRealtime() + 2500;
            while (SystemClock.elapsedRealtime() < end && !stopped()) {
                f = frame(300);
                if (f != null && vis.findInRoi(f, "tpl_autoteam.png", T_AUTOTEAM) == null) return true;
            }
            log("  ✗ 自动组队悬浮窗未确认关闭 → 停止好友操作");
            return false;
        }
        return true;
    }

    /** 自己在旧队伍里就退掉（社交面板开着时检查「离队」按钮）。返回是否退了。 */
    /**
     * 在世界画面退掉旧队伍（PC join_team.py leave_team_if_any 同款，2026-10-21 移植）。
     * 右下角「离队」按钮 = 自己在队伍里的唯一可信判据：在队 0.987~1.000 / 不在队 0.667~0.691（阈值 0.85）。
     * ⚠️ 必须在世界画面判（面板盖住右下角会误判"不在队"，PC 2026-10-20 20:58 踩过）。
     * 之前查好友面板的旧版查错了地方——离队按钮根本不在那里，导致整轮申请被静默丢弃。
     */
    private boolean quitOnWorld() {
        android.graphics.Bitmap f = frame(500);
        if (!inGame(f)) return false;
        Vision.Match lv = vis.findNear(f, "tpl_leave_team.png", 0.85);
        if (lv == null) return true;
        log("  世界画面有「离队」→ 自己在旧队伍里，退队");
        int sw = acc.getResources().getDisplayMetrics().widthPixels;
        int sh = acc.getResources().getDisplayMetrics().heightPixels;
        tapPixels((int) (lv.fx() * sw), (int) (lv.fy() * sh));
        // 确认框「确定」（tpl_leave_ok 只在确认框出现时命中 1.000，PC 实测）
        if (waitTpl("tpl_leave_ok.png", 0.90, 4000)) {
            tapTpl("tpl_leave_ok.png", 0.90, "确认退队");
            long end = SystemClock.elapsedRealtime() + 4000;
            while (!stopped() && SystemClock.elapsedRealtime() < end) {
                f = frame(500);
                if (inGame(f) && vis.findNear(f, "tpl_leave_team.png", 0.85) == null
                        && vis.findNear(f, "tpl_leave_ok.png", 0.90) == null) {
                    log("  ✓ 已退出旧队伍（新世界画面离队按钮消失）");
                    return true;
                }
            }
        }
        log("  ⚠️ 点了离队但没等到确认框");
        return false;
    }

    /** 队长仅检查已有队伍；有队才点「解散队伍」，不调用队员退队流程。 */
    private boolean prepareCaptain(String pkg) {
        launch(pkg);
        if (!waitForPkg(pkg, 10_000) || !waitInGameStable(pkg, 600)) return false;
        dismissPopups();
        if (vis.findNear(frame(300), "tpl_leave_team.png", 0.85) == null) {
            log("  队长当前无队伍 → 跳过解散");
            return true;
        }
        if (!openTeamPanel()) return false;
        Vision.Match disband = vis.findNear(frame(300), "tpl_dissolve_team.png", 0.88);
        if (disband == null) {
            log("  ✗ 已有队伍，但没有「解散队伍」按钮 → 停止，不改为退出队伍");
            closeTeamPanel();
            return false;
        }
        tapMatch(disband);
        if (waitTpl("tpl_leave_ok.png", 0.90, 2000)) {
            tapTpl("tpl_leave_ok.png", 0.90, "确认解散队伍");
        }
        if (!closeTeamPanel()) return false;
        long end = SystemClock.elapsedRealtime() + 4000;
        while (SystemClock.elapsedRealtime() < end && !stopped()) {
            dismissPopups();
            if (inGame() && vis.findNear(frame(300), "tpl_leave_team.png", 0.85) == null) {
                log("  ✓ 队长队伍已解散");
                return true;
            }
        }
        log("  ✗ 解散结果未确认");
        return false;
    }

    // ---------------------------------------------------------------- ③ 组队

    /** 队长：清好友→按ID加队员→逐个邀请（team_up.py 第③步）。 */
    private int inviteAll(java.util.List<String> ids, String captainPkg) {
        log("[队长] 清空好友 → 加 " + ids.size() + " 个队员 → 逐个邀请");
        launch(captainPkg);
        idle(3500);
        dismissPopups();
        if (!openFriendPanel()) { log("  ✗ 好友面板打不开，中止邀请"); return 0; }
        purgeFriends();
        // 逐个按 ID 加好友
        for (String id : ids) {
            if (stopped()) break;
            addFriendById(id);
        }
        openFriendPanel();
        // 前 N 行逐个邀请
        int sent = 0;
        for (int i = 0; i < ids.size(); i++) {
            if (stopped()) break;
            int sh = acc.getResources().getDisplayMetrics().heightPixels;
            int sw = acc.getResources().getDisplayMetrics().widthPixels;
            tapPixels(sw / 2, (int) (rowY(i) * sh));       // 点开第 i 行
            idle(700);
            Vision.Match inv = vis.findNear(frame(300), "tpl_invite_btn2.png", 0.80);
            if (inv != null) {
                tapPixels((int) (inv.fx() * sw), (int) (inv.fy() * sh));
                log("  点「邀请组队」（第" + (i + 1) + "行 分=" + String.format("%.2f", inv.score) + "）");
                idle(1600);
                android.graphics.Bitmap f = frame(300);
                if (f != null && hasOn(f, "tpl_invite_ok.png", T_INVITEOK)) {
                    sent++;
                    log("  ✓ 第 " + (i + 1) + " 行邀请已发出");
                } else {
                    log("  ⚠️ 第 " + (i + 1) + " 行没看到邀请成功提示");
                }
                closePopupX();
            } else {
                log("  第 " + (i + 1) + " 行没弹出好友弹窗 → 停止邀请");
                break;
            }
        }
        return sent;
    }

    /**
     * 打开好友面板并确认真的开了（标题模板 0.99）；最多试 3 轮。
     * 面板已打开时直接复用；列表刷新由refreshFriendsTab明确点击好友页签完成。
     */
    private boolean openFriendPanel() {
        if (has("tpl_title_friend.png", 0.90)) return true;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (stopped()) return false;
            if (waitTpl("tpl_menu_btn.png", T_MENU, 3000)) {
                tapTpl("tpl_menu_btn.png", T_MENU, "菜单");
                if (waitTpl("tpl_social_btn.png", T_SOCIAL, 4000)) {
                    tapTpl("tpl_social_btn.png", T_SOCIAL, "社交");
                }
            }
            if (waitTpl("tpl_title_friend.png", 0.90, 2000)) return true;
        }
        return false;
    }

    private void tapMatch(Vision.Match m) {
        if (stopped()) return;
        int sw = acc.getResources().getDisplayMetrics().widthPixels;
        int sh = acc.getResources().getDisplayMetrics().heightPixels;
        tapPixels((int) (m.fx() * sw), (int) (m.fy() * sh));
    }

    /** 只关闭好友操作的中央提示，不点击面板右上角的主关闭按钮。 */
    private boolean closeFriendResult() {
        for (int i = 0; i < 3 && !stopped(); i++) {
            android.graphics.Bitmap f = observation();
            // 与公告闭环共用完整X字形，但只搜中央提示框，排除好友主面板X。
            Vision.Match x = vis.findInRoi(f, "tpl_friend_result_x.png", 0.90);
            if (x == null) {
                return f != null && vis.findNear(f, "tpl_apply_btn.png", 0.88) == null
                        && vis.findNear(f, "tpl_del_friend.png", 0.82) == null;
            }
            tapMatch(x);
            long closeEnd=SystemClock.elapsedRealtime()+1500;
            while(!stopped()&&SystemClock.elapsedRealtime()<closeEnd) {
                android.graphics.Bitmap fresh=frame(300);
                if(fresh!=null&&vis.findInRoi(fresh,"tpl_friend_result_x.png",.90)==null
                        &&vis.findNear(fresh,"tpl_apply_btn.png",.88)==null
                        &&vis.findNear(fresh,"tpl_del_friend.png",.82)==null)return true;
            }
        }
        return false;
    }

    /** 删除/添加之后必须实际点「好友」tab；取到点击之后的新帧才允许读第一行。 */
    private boolean refreshFriendsTab() {
        if (!closeFriendResult() || !openFriendPanel()) return false;
        Vision.Match tab = null;
        long findEnd = SystemClock.elapsedRealtime() + extras().friendTabTimeout();
        // 解散旧队、删除/添加通知会挡住tab；实测超过3.5秒，露出即继续，超出登记时限仍遮挡则失败。
        while (SystemClock.elapsedRealtime() < findEnd && !stopped() && tab == null) {
            tab = vis.findNear(observation(), "tpl_friends_tab.png", 0.85);
            if (tab == null) frame(250);
        }
        if (tab == null) {
            android.graphics.Bitmap failed = observation();
            Vision.Match raw = vis.findInRoi(failed, "tpl_friends_tab.png", 0.0);
            log("  ✗ 找不到左上角「好友」页签，不盲点；原始分=" + raw);
            saveEvidence("friends_tab_not_found");
            return false;
        }
        int before = observed == null ? -1 : observed.number;
        log("  好友刷新定位="+tab+"，动作前帧="+before);
        saveEvidence("friends_tab_before_refresh");
        tapMatch(tab);
        // 点击屏障要求手势结束后的新帧，再核对页签和标题。
        // 奖励飘字可能在点击后才到达并盖住页签；与定位共用有界遮挡时限，清晰的新帧出现即返回。
        long end = SystemClock.elapsedRealtime() + extras().friendTabTimeout();
        int checked=0; Vision.Match lastTitle=null,lastTab=null;
        while (SystemClock.elapsedRealtime() < end && !stopped()) {
            android.graphics.Bitmap f = frame(250);
            if(f!=null){checked++;lastTitle=vis.findNear(f,"tpl_title_friend.png",0.0);lastTab=vis.findInRoi(f,"tpl_friends_tab.png",0.0);}
            if (observed != null && observed.number > before && f != null
                    && hasOn(f, "tpl_title_friend.png", 0.90)
                    && vis.findInRoi(f, "tpl_friends_tab.png", 0.85) != null) {
                log("  已点「好友」页签刷新 → 重新检查第一行");
                return true;
            }
        }
        log("  ✗ 好友页签点击后未取得可确认的新画面；新帧数="+checked+"，标题="+lastTitle+"，页签="+lastTab);
        saveEvidence("friends_tab_refresh_failed");
        return false;
    }

    /** 找绿色 ✕ 关掉弹窗（tpl_close_x 0.98/负 0.6）；找不到不动。 */
    private void closePopupX() {
        Vision.Match m = vis.findNear(frame(300), "tpl_close_x.png", 0.85);
        if (m == null) return;
        int sw = acc.getResources().getDisplayMetrics().widthPixels;
        int sh = acc.getResources().getDisplayMetrics().heightPixels;
        tapPixels((int) (m.fx() * sw), (int) (m.fy() * sh));
        idle(700);
    }

    /** 好友行 y 坐标（归一化），对应 join_team.py 的 ROW_YS 表。 */
    private static float rowY(int i) {
        float[] ys = {0.250f, 0.340f, 0.417f, 0.505f, 0.593f, 0.681f, 0.769f};
        return ys[Math.min(i, ys.length - 1)];
    }

    /**
     * 删好友直到第一行点不开（join_team.py purge_friends 的规律）。
     *
     * 2026-10-21 两个实测修正：
     * ① 弹窗判据换成 tpl_del_friend（0.91）—— 老 tpl_invite_btn 在这个弹窗上只有
     *    0.385，弹窗开着也认不出来，误报"删了 0 个"；
     * ② 每轮用 openFriendPanel() 重开面板（带标题验证），不再盲点菜单/社交。
     */
    /** 队长是否已经在好友列表里（点第 1 行看弹窗标题的 ID 模板）。
     *  2026-10-22 实锤：**删掉队长再加回来、立刻申请 → 无效**；
     *  保持好友直接申请 → toast「已经加入了XX队伍」当场进队。所以已存在就不要重加。 */
    private boolean captainAlreadyFriend() {
        if (!openFriendPanel()) return false;
        int sw = acc.getResources().getDisplayMetrics().widthPixels;
        int sh = acc.getResources().getDisplayMetrics().heightPixels;
        tapPixels(sw / 2, (int) (rowY(0) * sh));
        idle(900);
        android.graphics.Bitmap f = frame(300);
        if (f == null) return false;
        Vision.Match ap = vis.findNear(f, "tpl_apply_btn.png", 0.88);
        if (ap == null) { log("  好友列表为空"); return false; }
        boolean hit = vis.findInRoi(f, "tpl_leader_id.png", 0.85) != null;
        log(hit ? "  ✓ 队长已在好友列表（无需重复添加）" : "  ⚠️ 第 1 行不是队长");
        closePopupX();
        return hit;
    }

    private void purgeFriends() {
        int deleted = 0;
        int startRow = 0;              // 队长所在行跳过（2026-10-21 用户要求：先比 ID，是队长就留着不删）
        for (int round = 0; round < 12; round++) {
            if (stopped()) break;
            if (!openFriendPanel()) { log("  ✗ 好友面板打不开，停止清人"); return; }
            int sw = acc.getResources().getDisplayMetrics().widthPixels;
            int sh = acc.getResources().getDisplayMetrics().heightPixels;
            boolean acted = false;
            for (int row = startRow; row < 2 && !acted; row++) {
                tapPixels(sw / 2, (int) (rowY(row) * sh));
                idle(700);
                // 弹窗判据：删除好友模板（0.91）优先，够不上就退到邀请组队模板（0.87）——
                // 两次实测：弹窗开着但模板分不够 → 误判"删完了"，弹窗留在屏上挡后续操作。
                android.graphics.Bitmap f = frame(300);
                Vision.Match del = f == null ? null : vis.findNear(f, "tpl_del_friend.png", 0.82);
                boolean popupOpen = del != null
                        || (f != null && vis.findNear(f, "tpl_invite_btn2.png", 0.78) != null);
                if (!popupOpen) {
                    if (row == startRow) {          // 当前起点行就空 → 列表清完了
                        log("  清空好友完成：删了 " + deleted + " 个（队长保留）");
                        return;
                    }
                    continue;
                }
                // ★ 先比 ID：弹窗标题里是队长 ID → 保留不删
                if (f != null && vis.findInRoi(f, "tpl_leader_id.png", 0.85) != null) {
                    log("  ★ 第 " + (row + 1) + " 行是队长（ID 匹配）→ 保留不删（删了再加会导致申请无效）");
                    closePopupX();
                    idle(600);
                    startRow = row + 1;
                    acted = true;
                    break;
                }
                if (del != null) {
                    tapPixels((int) (del.fx() * sw), (int) (del.fy() * sh));
                } else {
                    tapPixels((int) (0.694 * sw), (int) (0.502 * sh));   // 「删除好友」兜底坐标
                }
                idle(900);
                // 关「删除成功」提示（914,802）
                tapPixels((int) (0.846 * sw), (int) (0.418 * sh));
                idle(700);
                deleted++;
                log("  已删第 " + deleted + " 个好友");
                acted = true;
            }
            if (!acted && startRow >= 2) {
                log("  清空好友完成：删了 " + deleted + " 个（队长保留）");
                return;
            }
        }
    }

    /** 按 ID 加好友（对话框是原生控件：输入框 / ID·名字 单选 / 确定，无障碍全可读）。 */
    private boolean addFriendById(String id) {
        log("  加好友 " + id);
        if (!openFriendPanel()) { log("  ✗ 好友面板打不开"); return false; }
        if (!closeFriendResult()) return false;
        int sw0 = acc.getResources().getDisplayMetrics().widthPixels;
        int sh0 = acc.getResources().getDisplayMetrics().heightPixels;
        // 只在面板底部局部定位；未命中则停止，不用固定位置盲点。
        Vision.Match addBtn = vis.findNear(frame(300), "tpl_btn_addfriend.png", 0.85);
        if (addBtn != null) {
            tapPixels((int) (addBtn.fx() * sw0), (int) (addBtn.fy() * sh0));
        } else {
            log("  ✗ 未确认「添加好友」按钮 → 停止添加");
            return false;
        }
        long end = SystemClock.elapsedRealtime() + 3000;
        android.view.accessibility.AccessibilityNodeInfo idRadio = null;
        while (!stopped() && SystemClock.elapsedRealtime() < end) {
            idRadio = acc.nodeWithTextExact("ID");
            if (idRadio != null) break;
            idle(80);
        }
        if (idRadio == null || stopped()) return false;
        boolean selected = false;
        for (int attempt = 0; attempt < 2 && !stopped(); attempt++) {
            if (!tapTextExact("ID")) continue;
            end = SystemClock.elapsedRealtime() + 1000;
            while (!stopped() && SystemClock.elapsedRealtime() < end) {
                android.view.accessibility.AccessibilityNodeInfo check = acc.nodeWithTextExact("ID");
                if (check != null && check.isChecked()) { selected = true; break; }
                idle(60);
            }
            if (selected) break;
        }
        if (!selected || stopped()) return false;
        java.util.List<android.view.accessibility.AccessibilityNodeInfo> edits = acc.editFields();
        if (edits.isEmpty()) return false;
        if (stopped() || !acc.setField(edits.get(0), id)) return false;
        if (stopped() || !tapTextExact("确定")) return false;
        inputBarrierNs = System.nanoTime() + 120_000_000L; observed = null;
        end = SystemClock.elapsedRealtime() + 4000;
        while (!stopped() && SystemClock.elapsedRealtime() < end) {
            android.graphics.Bitmap f = frame(500);
            if (hasOn(f, "tpl_add_ok.png", T_ADDOK) || hasOn(f, "tpl_err_exists.png", T_ERR)) {
                log("  ✓ 添加结果已确认 → 刷新后仍须核对队长ID");
                return true;
            }
        }
        log("  ✗ 添加结果未知 → 停止，不当成功");
        return false;
    }

    /**
     * 队员：把队长发来的邀请「同意」掉（前台时邀请才渲染，所以一个个切前台）。
     * 判据照搬 join_team.py accept_team_invite：弹窗出现 → 点「同意」(0.239w,0.623h)
     * → 弹窗消失才算同意。
     */
    private boolean acceptOne(String pkg) {
        launch(pkg);
        idle(3500);
        int sw = acc.getResources().getDisplayMetrics().widthPixels;
        int sh = acc.getResources().getDisplayMetrics().heightPixels;
        long end = SystemClock.elapsedRealtime() + 12_000;
        while (SystemClock.elapsedRealtime() < end && !stopped()) {
            if (hasOn(frame(300), "tpl_team_invite.png", T_TEAM_INVITE)) {
                log("  收到组队邀请 → 点「同意」");
                tapPixels((int) (0.239 * sw), (int) (0.623 * sh));
                idle(1200);
                if (!hasOn(frame(300), "tpl_team_invite.png", T_TEAM_INVITE)) {
                    log("  ✓ 已同意组队邀请");
                    return true;
                }
                log("  ⚠️ 邀请弹窗还在，重试");
            }
            idle(1000);
        }
        log("  ✗ 没等到组队邀请弹窗");
        return false;
    }

    /** 等前台包名真的切到 pkg（最多 ms）。launch 瞬间屏幕可能还停留在上一个客户端的画面。 */
    private boolean waitForPkg(String pkg, long ms) {
        long dl = SystemClock.elapsedRealtime() + ms;
        while (SystemClock.elapsedRealtime() < dl && !stopped()) {
            String cur = acc.currentPackage();
            if (pkg.equals(cur)) return true;
            idle(500);
        }
        String cur = acc.currentPackage();
        log("  ⚠️ 等前台切到 " + pkg + " 超时（当前 " + cur + "）");
        return false;
    }

    /** 队长保活：拉前台 → 15 秒内不在游戏就重登（只保证在线，不改队员端任何逻辑）。 */
    private boolean ensureCaptainOnline(HelperApp.Account captain) {
        log("[队长保活] 确认 " + captain.pkg + " 在线");
        launch(captain.pkg);
        if (!waitForPkg(captain.pkg, 10_000)) return false;
        dismissPopups();
        if (!waitInGameStable(captain.pkg, 600)) {
            if (rotationOnly) { log("  换组期间队长原角色未在线，停止，不重新登录"); return false; }
            if (!onlineOne(captain.pkg, captain.account, captain.password)) return false;
        }
        if (!alignMap() || !inCityConfirmed()) {
            log("[队长保活] ✗ 队长未确认到襄阳城 → 不继续发申请");
            return false;
        }
        return true;
    }

    private String mapTravelFailure = "";
    private long lastMapLog;
    private MapCheck.State lastMapState;

    private double mapScore(android.graphics.Bitmap f, String name) {
        Vision.Match m = vis.findInRoi(f, name, 0.0);
        return m == null ? 0 : m.score;
    }

    /** 同一张新画面：独有后缀 + 建筑场景；不再用共有的“襄阳”两字放行。 */
    private MapCheck.State mapState() {
        android.graphics.Bitmap f = observation();
        if (f == null) return MapCheck.State.UNKNOWN;
        double city = mapScore(f, "tpl_city_suffix.png"), inn = mapScore(f, "tpl_inn_suffix.png");
        double outside = mapScore(f, "tpl_city_scene.png"), inside = mapScore(f, "tpl_inn_scene.png");
        MapCheck.State state = MapCheck.classify(city, inn, outside, inside);
        long now = SystemClock.elapsedRealtime();
        if (state != lastMapState || now - lastMapLog >= 2500) {
            log(String.format(java.util.Locale.ROOT,
                    "  地图联合判据=%s 城后缀/街景=%.3f/%.3f 栈后缀/室内=%.3f/%.3f",
                    state, city, outside, inn, inside));
            lastMapState = state; lastMapLog = now;
        }
        return state;
    }

    /** 完整襄阳城名+世界UI，或原城后缀+街景；任务提示遮挡街景时仍可自证。 */
    private boolean inCityConfirmed() {
        android.graphics.Bitmap f = observation();
        if (extras().cityWorld(f)) return true;
        return mapState() == MapCheck.State.CITY;
    }

    /** Python 驱动接口：地图名是否「襄阳城」（align_flow.py 的主判据）。 */
    public boolean in_city() {
        return inCityConfirmed();
    }

    /** 客栈必须有地图名正证据，不能由“没认到城”推断。 */
    public boolean in_inn() {
        return mapState() == MapCheck.State.INN;
    }

    public boolean align_stopped() {
        return stopped();
    }

    /** 复用既有关闭闭环，清完再由Python重读地图与圈。 */
    public void clear_align_popups() {
        dismissPopups();
    }

    // ================= Chaquopy：Python 版对图的驱动接口 =================
    // 这些方法由 app/src/main/python/align_flow.py 通过 Chaquopy 桥调用。
    // 分工：Java 出"眼和手"（Vision 匹配久经验证），Python 只做状态编排。

    /** 传送圈 strip 在底部中央 ROI 的最高分（Java Vision 局部匹配，与 PC 同判据）。 */
    public double strip_score() {
        Vision.Match m = vis.findNear(observation(), "tpl_goto_xy.png", 0.0);
        return m == null ? 0.0 : m.score;
    }

    /** 相对坐标点击（Python 侧统一入口）。 */
    public void tap_rel(double fx, double fy) {
        if (stopped()) return;
        int sw = acc.getResources().getDisplayMetrics().widthPixels;
        int sh = acc.getResources().getDisplayMetrics().heightPixels;
        tapPixels((int) (fx * sw), (int) (fy * sh));
    }

    public void py_sleep(double sec) {
        idle((long) (sec * 1000));
    }

    public void py_log(String m) {
        log("  " + m);
    }

    private boolean pyAlign() {
        if (!com.chaquo.python.Python.isStarted()) {
            // Android 上必须显式 start，否则 getInstance() 抛
            // "Cannot use GenericPlatform on Android"
            com.chaquo.python.Python.start(new com.chaquo.python.android.AndroidPlatform(acc));
            log("  [PY] Python 运行时已启动");
        }
        return com.chaquo.python.Python.getInstance().getModule("align_flow").callAttr("run_align", this).toBoolean();
    }

    private boolean alignMap() {
        mapTravelFailure = "";
        dismissPopups();
        if (inCityConfirmed()) return true;
        // 用户明确所有其他地图统一走世界地图；不再仅允许从客栈传送圈进城。
        return worldMapTravel();
    }

    private boolean legacyAlignMap() {
        // Python已负责有证据的有限重试；失败不能再盲点相同位置，城里那里是挑战500强。
        try {
            boolean ok = pyAlign();
            // 地图未知可能是延迟出现的公告遮挡；只在实际找到X并关闭后重跑识别。
            if (!ok && dismissPopups() && !stopped()) {
                log("  已关闭意外遮挡 → 重新检查地图");
                ok = pyAlign();
            }
            log("  [PY] Python 对图返回 " + ok);
            return ok;
        } catch (Throwable t) {
            log("  [PY] 异常（" + t + "）→ 停止对图，不追加点击");
            return false;
        }
    }

    /** 打开队长的「周围 → 我的队伍」面板并切 tab 强制刷新（面板是快照）。
     *  返回 false = 面板没打开（周围按钮盲点可能点到别的按钮，比如「活动助手」——
     *  2026-10-21 演示时踩过，用户指出：不该盲点）+ 重试一次。 */
    private Vision.Match teamTab(android.graphics.Bitmap f) {
        Vision.Match m = vis.findNear(f, "tpl_team_tab.png", 0.88);
        return m != null ? m : vis.findNear(f, "tpl_team_inactive.png", 0.94);
    }

    private boolean openTeamPanel() {
        for (int attempt = 0; attempt < 2 && !stopped(); attempt++) {
            android.graphics.Bitmap f = frame(250);
            if (teamTab(f) == null) {
                Vision.Match sb = vis.findNear(f, "tpl_surround_label.png", 0.85);
                if (sb == null) { dismissPopups(); continue; }
                tapMatch(sb);
                long panelEnd = SystemClock.elapsedRealtime() + 3000;
                while (!stopped() && SystemClock.elapsedRealtime() < panelEnd && teamTab(f) == null) f=frame(250);
                if (teamTab(f) == null) { dismissPopups(); continue; }
            }
            // 面板是快照：实际点击玩家，再点我的队伍刷新。每次输入后都等待新帧。
            Vision.Match players = vis.findNear(frame(250), "tpl_players_tab.png", 0.85);
            if (players != null) {
                tapMatch(players);
                if (!waitTpl("tpl_players_active.png", 0.88, 2000)) continue;
            } else if (!has("tpl_players_active.png", 0.88)) {
                log("  玩家页签状态未确认，重取新帧恢复"); continue;
            }
            Vision.Match team = teamTab(frame(250));
            if (team == null) continue;
            tapMatch(team);
            if(waitTpl("tpl_team_tab.png", 0.88, 2500))return true;
        }
        log("  ✗ 「周围 → 我的队伍」面板打不开");
        return false;
    }

    /** 新帧、重新点tab刷新后连续两次读到目标人数，才允许提前结束队长等待。 */
    private boolean awaitRoster(int expected, long timeout) {
        long end = SystemClock.elapsedRealtime() + timeout;
        int stable = 0;
        while (!stopped() && SystemClock.elapsedRealtime() < end) {
            if (!openTeamPanel()) return false;
            int got = countTeamMembers();
            if (got == expected) {
                if (++stable >= 2) {
                    log("  ✓ 队长名册增长至 " + got + " 人 → 提前结束等待");
                    return true;
                }
            } else stable = 0;
        }
        log("  入队尚未确认 → 回队员接收结果，不重发申请");
        return false;
    }

    private boolean waitMemberJoined(String pkg, long timeout) {
        long end = SystemClock.elapsedRealtime() + timeout;
        while (!stopped() && SystemClock.elapsedRealtime() < end) {
            android.graphics.Bitmap f = frame(500);
            if (pkg.equals(acc.currentPackage()) && inGame(f)
                    && vis.findNear(f, "tpl_leave_team.png", 0.85) != null) {
                log("  ✓ 队员新世界画面有在队证据；最终仍以队长名册为准");
                return true;
            }
        }
        log("  队员入队结果未知，等待最终名册");
        return false;
    }

    private boolean closeTeamPanel() {
        for(int attempt=0;attempt<2&&!stopped();attempt++) {
            android.graphics.Bitmap f = frame(400);
            if (f == null) return false;
            if (teamTab(f) == null) return inGame(f);
            Vision.Match x = vis.findNear(f, "tpl_panel_close.png", 0.88);
            if (x == null) { log("  队伍面板关闭按钮未确认");break; }
            log("  关闭队伍面板 第"+(attempt+1)+"次，分="+String.format(java.util.Locale.ROOT,"%.3f",x.score));
            tapMatch(x);
            long end = SystemClock.elapsedRealtime() + 2500;
            while (SystemClock.elapsedRealtime() < end && !stopped()) {
                f = frame(250);
                if (f != null && teamTab(f) == null && inGame(f)) return true;
            }
        }
        saveEvidence("team_panel_close_unconfirmed");
        return false;
    }

    /**
     * 数「踢出队伍」按钮 = 数队员人数（PC join_team.py 同款判据：满编 4 / 两人 2 / 空队 0，
     * 世界/好友画面都是 0）。命中后把该区域涂黑再搜下一个。
     */
    private int countTeamMembers() {
        android.graphics.Bitmap f = observation();
        if (f == null || vis.findInRoi(f, "tpl_team_tab.png", 0.88) == null) return -1;
        android.graphics.Bitmap work = f.copy(android.graphics.Bitmap.Config.ARGB_8888, true);
        android.graphics.Canvas c = new android.graphics.Canvas(work);
        android.graphics.Paint p = new android.graphics.Paint();
        p.setColor(0xFF000000);
        int count = 0;
        for (int i = 0; i < 6; i++) {
            Vision.Match m = vis.findInRoi(work, "tpl_roster_kick.png", 0.88);
            if (m == null) break;
            count++;
            c.drawRect(m.cx - m.w / 2f - 4, m.cy - m.h / 2f - 4,
                    m.cx + m.w / 2f + 4, m.cy + m.h / 2f + 4, p);
        }
        work.recycle();
        return count;
    }

    /**
     * 队员端：地图对齐 → 检查首行 → 删/加后点好友tab刷新 → 核对队长 → 申请。
     *
     * 直接照搬 join_team.py 的 ensure_leader_at_row + apply_join（2026-10-21 用户改定）：
     * 不用队长发邀请，队员端「请求组队」就直接进队 —— 前提是**和队长在同一地图**。
     *
     * 首行ID匹配才申请；每次删/加后刷新，不沿用旧列表。
     */
    private boolean memberApplyJoin(String pkg, String account, String password, String leaderId) {
        return memberApplyJoin(pkg,account,password,leaderId,null);
    }

    private boolean memberApplyJoin(String pkg,String account,String password,String leaderId,Runnable beforeApply) {
        log("-- " + pkg + "：地图对齐 → 好友第1行判定（是队长直接申请 / 否则只删1行再加）→ 申请入队");
        launch(pkg);
        if (!waitForPkg(pkg, 10_000)) return false;
        dismissPopups();
        // 兜底：① 步可能登录失败（服务器慢/客户端黑屏崩溃），这里确认不在游戏就重登一次
        if (!waitInGameStable(pkg, 1200)) {
            if (rotationOnly) { log("  换组期间队员不在已验证角色，停止，不重新登录"); return false; }
            log("  拉起后不在游戏内 → 走一次登录兜底");
            if (!onlineOne(pkg, account, password)) {
                log("  ✗ 登录兜底也没进游戏 → 中止该队员");
                return false;
            }
            dismissPopups();
        }
        if (rotationOnly) {
            if (vis.findNear(observation(),"tpl_leave_team.png",.85)!=null) {
                log("  本队员已有队伍但尚未核对归属 → 停止，不退出或重发申请"); return false;
            }
        } else if (!quitOnWorld()) return false;
        if (!alignMap()) {
            log("  ✗ 地图对不齐（申请入队要求与队长同地图）→ 中止该队员");
            return false;
        }
        // 2026-10-22 强确认闸门（用户定稿：同图是硬前提，不确认后面好友/申请全白费）
        if (!inCityConfirmed()) {
            log("  ✗ 强确认失败：右上角地图名不是「襄阳城」→ 不发申请，中止该队员");
            saveEvidence("not_city_" + pkg);
            return false;
        }
        if (!dismissAutoTeam() || !inCityConfirmed()) {
            log("  ✗ 清理悬浮窗后未确认仍在襄阳城 → 停止好友操作");
            saveEvidence("not_city_after_cleanup_" + pkg);
            return false;
        }
        log("  ✓ 强确认：城后缀与室外场景一致 → 才开始好友/申请操作");
        saveEvidence("before_friends_" + pkg);
        if (!openFriendPanel()) { log("  ✗ 好友面板打不开"); return false; }

        return FriendJoinFlow.run(new FriendJoinFlow.Driver() {
            public boolean stopped() { return PhoneRunner.this.stopped(); }
            public boolean refreshFriends() { return refreshFriendsTab(); }
            public FriendJoinFlow.FirstRow readFirstRow() { return inspectFirstFriend(); }
            public boolean deleteFirst() { return deleteFirstFriend(); }
            public boolean addCaptain() { return addFriendById(leaderId); }
            public boolean applyToCaptain() {
                Vision.Match ap = vis.findNear(observation(), "tpl_apply_btn.png", 0.88);
                if (ap == null) return false;
                if(beforeApply!=null)beforeApply.run();
                boolean ok = applyJoin(ap);
                closeFriendResult();
                return ok;
            }
        }, 12);
    }

    /** 不以“点不开”冒充空列表；先识别行框，两帧都确实无行才判EMPTY。 */
    private FriendJoinFlow.FirstRow inspectFirstFriend() {
        for (int check = 0; check < 2; check++) {
            android.graphics.Bitmap f = check == 0 ? observation() : frame(500);
            if (f == null || !hasOn(f, "tpl_title_friend.png", 0.90)
                    || vis.findInRoi(f, "tpl_friends_tab.png", 0.85) == null) {
                return FriendJoinFlow.FirstRow.UNKNOWN;
            }
            // 空列表也保留首行边框；只能用行内名字和独立操作图标确认实际好友。
            float[] nameRoi = vis.roiOf("friend_first_name");
            if (nameRoi == null) return FriendJoinFlow.FirstRow.UNKNOWN;
            boolean hasName = !vis.blobs(f, nameRoi, new float[]{100, 80, 45},
                    new float[]{140, 100, 100}, 1.0e-5, 0).isEmpty();
            boolean hasActions = vis.findInRoi(f, "tpl_friend_actions.png", 0.88) != null
                    || extras().button(f,"friend_offline_view") != null;
            if (hasName && hasActions) {
                int sw = acc.getResources().getDisplayMetrics().widthPixels;
                int sh = acc.getResources().getDisplayMetrics().heightPixels;
                java.util.List<Vision.Blob> names = vis.blobs(f, nameRoi, new float[]{100, 80, 45},
                        new float[]{140, 100, 100}, 1.0e-5, 0);
                if (names.isEmpty()) return FriendJoinFlow.FirstRow.UNKNOWN;
                Vision.Blob name = names.get(0);
                // 首行图标证明确有好友，实际点击名字色块，避开跟踪/查看图标。
                tapPixels((int) ((name.x + name.w / 2f) / f.getWidth() * sw),
                        (int) ((name.y + name.h / 2f) / f.getHeight() * sh));
                boolean menuOpened = waitTpl("tpl_apply_btn.png", 0.88, 2200);
                if (!menuOpened && !stopped()) {
                    // 打开菜单没有副作用；只在新帧仍明确是首行列表时再点一次，随后仍须核对ID。
                    android.graphics.Bitmap retry = observation();
                    if (hasOn(retry, "tpl_title_friend.png", 0.90)
                            && (vis.findInRoi(retry, "tpl_friend_actions.png", 0.88) != null
                                || extras().button(retry,"friend_offline_view") != null)) {
                        java.util.List<Vision.Blob> retryNames = vis.blobs(retry,
                                nameRoi, new float[]{100, 80, 45},
                                new float[]{140, 100, 100}, 1.0e-5, 0);
                        if (!retryNames.isEmpty()) {
                            Vision.Blob next = retryNames.get(0);
                            log("  首行菜单未出现，列表结构仍在 → 仅重试打开一次");
                            tapPixels((int) ((next.x + next.w / 2f) / retry.getWidth() * sw),
                                    (int) ((next.y + next.h / 2f) / retry.getHeight() * sh));
                            menuOpened = waitTpl("tpl_apply_btn.png", 0.88, 2200);
                        }
                    }
                }
                if (!menuOpened) {
                    log("  ✗ 第一行存在但菜单没打开 → 不当成空列表");
                    saveEvidence("friend_menu_open_failed");
                    return FriendJoinFlow.FirstRow.UNKNOWN;
                }
                f = observation();
                saveEvidence("friend_id_check");
                long glyphCpuStart = android.os.Debug.threadCpuTimeNanos();
                IdGlyph candidate = extras().friendId(f);
                if (candidate != null && captainIdGlyph != null && candidate.size()!=captainIdGlyph.size()) {
                    IdGlyph fresh = extras().friendId(frame(350));
                    IdGlyph.Score stable = candidate.compare(fresh);
                    if (!candidate.complete || fresh == null || !fresh.complete
                            || stable == null || !stable.matches()) {
                        log("  ✗ 长ID边界或连续新帧不完整 → 不删好友"); return FriendJoinFlow.FirstRow.UNKNOWN;
                    }
                    candidate=fresh;
                    log("  ✓ 完整长ID边界与两帧一致；长度不同可确认非队长");
                }
                inspectedFriend = candidate;
                if (deletedFriend != null) {
                    IdGlyph.Score changed = deletedFriend.compare(candidate);
                    if (changed == null || !changed.different()) {
                        log("  ✗ 删除回执后刷新首行仍相同或未知 → 停止，不删除下一人");
                        return FriendJoinFlow.FirstRow.UNKNOWN;
                    }
                    deletedFriend = null;
                    log("  ✓ 删除后刷新首行ID已改变");
                }
                IdGlyph.Score score = captainIdGlyph == null ? null : captainIdGlyph.compare(candidate);
                if (score == null) {
                    log("  ✗ 好友ID字形不完整或长度不符 → 停止，不删好友");
                    return FriendJoinFlow.FirstRow.UNKNOWN;
                }
                log(String.format(java.util.Locale.ROOT,"  ID逐字最低=%.3f，平均=%.3f，CPU耗时=%.2fms",
                        score.min, score.mean, (android.os.Debug.threadCpuTimeNanos() - glyphCpuStart) / 1e6));
                if (score.matches()) {
                    log("  ✓ 第一行就是队长 → 直接申请");
                    return FriendJoinFlow.FirstRow.CAPTAIN;
                }
                if (!score.different()) {
                    log("  ✗ ID匹配处于不确定区间 → 停止，不删好友");
                    return FriendJoinFlow.FirstRow.UNKNOWN;
                }
                log("  第一行不是队长 → 删除后点击好友tab刷新");
                return FriendJoinFlow.FirstRow.OTHER;
            }
            // 有名字或图标但结构不全：不冒充空列表。两者连续两帧均不存在才添加。
            if (hasName || hasActions) {
                return FriendJoinFlow.FirstRow.UNKNOWN;
            }
        }
        log("  好友tab刷新后确认第一行为空 → 按ID加队长");
        deletedFriend = null;
        return FriendJoinFlow.FirstRow.EMPTY;
    }

    private boolean deleteFirstFriend() {
        if (inspectedFriend == null || captainIdGlyph == null) return false;
        IdGlyph.Score identity = captainIdGlyph.compare(inspectedFriend);
        if (identity == null || !identity.different()) return false;
        Vision.Match del = vis.findNear(observation(), "tpl_del_friend.png", 0.82);
        if (del == null) { log("  ✗ 删除按钮未确认，不用固定坐标兜底"); return false; }
        tapMatch(del);
        long end = SystemClock.elapsedRealtime() + 4000;
        boolean confirmed = false;
        int retries = 0;
        long retryAfter = SystemClock.elapsedRealtime() + 1200;
        while (SystemClock.elapsedRealtime() < end && !stopped()) {
            android.view.accessibility.AccessibilityNodeInfo confirm = acc.nodeWithTextExact("确定");
            if (confirm != null && !confirmed && !stopped()) {
                confirmed = tapTextExact("确定");
                inputBarrierNs = System.nanoTime() + 120_000_000L; observed = null;
            }
            android.graphics.Bitmap f = frame(250);
            Vision.Match canvasConfirm = vis.findNear(f, "tpl_leave_ok.png", 0.90);
            if (!confirmed && canvasConfirm != null) {
                tapMatch(canvasConfirm); confirmed = true; continue;
            }
            if (vis.findNear(f, "tpl_delete_ok.png", 0.90) != null) {
                saveEvidence("delete_confirmed");
                deletedFriend = inspectedFriend;
                log("  ✓ 删除成功回执已确认，下一步强制好友tab刷新并核对变化");
                return closeFriendResult();
            }
            if (!confirmed && retries == 0 && SystemClock.elapsedRealtime() >= retryAfter) {
                // 只在原菜单仍在且ID完全相同的明确未执行分支重试一次。
                Vision.Match again = vis.findNear(f, "tpl_del_friend.png", 0.82);
                IdGlyph.Score same = inspectedFriend.compare(extras().friendId(f));
                if (again != null && same != null && same.matches()) {
                    log("  删除点击未生效，确认仍是原好友 → 仅重试一次");
                    tapMatch(again); retries++;
                }
            }
        }
        log("  ✗ 删除结果未确认 → 不继续读旧列表");
        saveEvidence("delete_result_failed");
        return false;
    }

    /** 点弹窗里的「申请入队」并等成功提示（join_team.py apply_join）。 */
    /** 2026-10-22 用户纠正：不要点「查看资料/详细」绕一圈，就是
     *  **点好友名字 → 弹窗 → 点「申请入队」** 一步到位（模板命中即点）。 */
    private boolean applyJoin(Vision.Match ap) {
        if (stopped()) return false;
        int sw = acc.getResources().getDisplayMetrics().widthPixels;
        int sh = acc.getResources().getDisplayMetrics().heightPixels;
        tapPixels((int) (ap.fx() * sw), (int) (ap.fy() * sh));
        applyDispatched = true;
        log("  点「申请入队」(" + (int) (ap.fx() * sw) + "," + (int) (ap.fy() * sh)
                + " 分=" + String.format("%.2f", ap.score) + ")");
        if (waitTpl("tpl_apply_ok.png", 0.85, 5000)) {
            log("  ✓ 申请已发出（有提示）");
            saveEvidence("apply_result");
            return true;
        }
        log("  ⚠️ 没看到申请提示");
        return false;
    }

    /** 把当前这一帧存成 PNG 留证据（唯一可信判据是队长面板）。 */
    private String saveEvidence(String tag) {
        try {
            android.graphics.Bitmap f = observation();
            if (f == null) return null;
            // 私有目录便于调试构建通过run-as取证，避免外部目录权限造成证据不可读。
            java.io.File dir = ContextHolder.get().getFilesDir();
            java.io.File out = new java.io.File(dir, tag + "_" + System.currentTimeMillis() + ".png");
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(out)) {
                f.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, fo);
            }
            log("  证据截图：" + out.getName());
            return out.getName();
        } catch (Throwable t) {
            LogBus.get().e("phone", "存证据失败", t);
            return null;
        }
    }

    // ---------------------------------------------------------------- ④ 主流程

    /** 一键上号 + 组队。team.json 结构见 loadTeam()。 */
    /** 由应用级轮换运行器持有RunGate，复用同一截图/手势与好友闭环。 */
    RotationController.Port rotationPort(RotationSession session, HelperApp.Team team) {
        rotationOnly=true; worker=Thread.currentThread(); vis.setCancelled(this::stopped);
        return new RotationController.Port() {
            boolean captainVerified;
            HelperApp.Account account(String key) {
                for(HelperApp.Account a:team.all())if(RotationRuntime.key(a).equals(key))return a;
                throw new IllegalStateException("本轮账号配置变化");
            }
            void need(boolean ok,String why){RotationSession.require(!stopped()&&ok,why);}
            public boolean stopped(){return PhoneRunner.this.stopped();}
            String activeKey;
            void foreground(String key) {
                activeKey=key;
                String pkg=account(key).pkg;if(!pkg.equals(acc.currentPackage()))launch(pkg);
                need(waitForPkg(pkg,10000),"客户端未到前台");
                recoverConnection(key);
            }
            final java.util.Map<String,Integer> reconnects=new java.util.HashMap<>();
            int connectionGeneration;
            void recoverConnection(String key) {
                android.graphics.Bitmap f=frame(400);
                Vision.Match close=extras().disconnectClose(f);
                if(close==null&&vis.findNear(f,TPL_LOGIN_PANEL,.90)==null)return;
                int attempts=reconnects.containsKey(key)?reconnects.get(key)+1:1;
                reconnects.put(key,attempts);
                need(attempts<=extras().recoveryLimit("attempts"),"本客户端掉线恢复次数已用完；保留进度");
                if(close!=null)need(closeDisconnectToLogin(f),"掉线弹窗消失，等待重新确认");
                f=frame(400);Vision.Match login=vis.findNear(f,TPL_LOGIN_PANEL,.90);
                need(login!=null,"恢复登录按钮未确认");
                long end;
                log("[掉线] 已返回登录游戏界面，使用保留账号恢复到选角；不重填账号");
                tapMatch(login);
                end=SystemClock.elapsedRealtime()+extras().recoveryLimit("selection_timeout_ms");
                SelectionLayout.Observation layout=null;
                while(!stopped()&&SystemClock.elapsedRealtime()<end) {
                    f=frame(400);layout=extras().layout(f);
                    if(layout!=null&&extras().captureSelectionName(f)!=null)break;
                }
                need(layout!=null&&extras().captureSelectionName(f)!=null,"掉线重登后选角未确认；保留进度");
                connectionGeneration++;
                RotationSession.Member member=null;
                for(RotationSession.Member candidate:session.members)if(candidate.key.equals(key))member=candidate;
                if(member!=null) {
                    boolean enterWorld=session.reconnectMayEnter(key,extras().captureSelectionName(f).encode(),extras().rosterNameThreshold());
                    if(!enterWorld) {log("[掉线] 选角已恢复，沿本轮断点继续");return;}
                } else captainVerified=false;
                Vision.Match enter=extras().enter(f);need(enter!=null,"掉线恢复进入按钮未确认");tapMatch(enter);
                end=SystemClock.elapsedRealtime()+extras().recoveryLimit("world_timeout_ms");
                boolean world=false;
                while(!stopped()&&SystemClock.elapsedRealtime()<end) {
                    f=frame(400);
                    if(extras().enter(f)==null&&extras().disconnectClose(f)==null
                            &&vis.findNear(f,"tpl_menu_btn.png",.90)!=null) {world=true;break;}
                }
                need(world,"掉线重登后未进入世界；保留进度");dismissPopups();
                log("[掉线] 世界已恢复，保留本组姓名与申请记录");
                if(member==null)verifyCaptain();
            }
            android.graphics.Bitmap rotationFrame(long waitMs) {
                android.graphics.Bitmap f=frame(waitMs);
                if(extras().disconnectClose(f)!=null) {
                    recoverConnection(activeKey);
                    return frame(waitMs);
                }
                return f;
            }
            RoleNameGlyph name(android.graphics.Bitmap f,float[] r) {
                if(f==null)return null;
                int x=(int)(r[0]*f.getWidth()),y=(int)(r[1]*f.getHeight());
                int w=(int)(r[2]*f.getWidth())-x,h=(int)(r[3]*f.getHeight())-y;
                int[] px=new int[w*h];f.getPixels(px,0,w,x,y,w,h);
                return RoleNameGlyph.extract(px,w,h);
            }
            String token(android.graphics.Bitmap f) {
                float[] r=vis.roiOf("role_id_glyph");
                int x=(int)(r[0]*f.getWidth()),y=(int)(r[1]*f.getHeight());
                android.graphics.Bitmap id=android.graphics.Bitmap.createBitmap(f,x,y,
                        (int)(r[2]*f.getWidth())-x,(int)(r[3]*f.getHeight())-y);
                java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
                id.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);id.recycle();
                return android.util.Base64.encodeToString(out.toByteArray(),android.util.Base64.NO_WRAP);
            }
            IdGlyph decodeId(String token) {
                byte[] bytes=android.util.Base64.decode(token,android.util.Base64.NO_WRAP);
                android.graphics.Bitmap b=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length);
                need(b!=null,"队长身份记录损坏");int w=b.getWidth(),h=b.getHeight();int[] px=new int[w*h];
                b.getPixels(px,0,w,0,0,w,h);b.recycle();
                return IdGlyph.extract(px,w,h,true,Math.max(1,w/250));
            }
            public void verifyCaptain() {
                if(captainVerified)return;
                String key=session.captain;foreground(key);
                if(teamTab(rotationFrame(500))!=null)need(closeTeamPanel(),"队伍面板尚未关闭");
                dismissPopups();
                android.graphics.Bitmap f=null;
                long panelEnd=SystemClock.elapsedRealtime()+8000;
                boolean avatarTapped=false,panelReady=false;
                int avatarAttempts=0;long avatarTappedAt=0;
                while(!stopped()&&SystemClock.elapsedRealtime()<panelEnd) {
                    f=rotationFrame(300);
                    // 登录奖励可能晚于首次清场、甚至晚于点头像才出现；等待面板时也须清障。
                    if(extras().button(f,"reward_title")!=null||extras().button(f,"reward_none")!=null) {
                        log("[换组] 角色核验遇到登录奖励 → 先处理奖励，再重开角色面板");
                        dismissPopups();avatarTapped=false;continue;
                    }
                    if(hasOn(f,"tpl_role_title.png",.88)){panelReady=true;break;}
                    // 头像点击后的展开动画可能暂时既无世界UI，也无完整面板标题；先等刷新。
                    if(avatarTapped&&(avatarAttempts>=2||SystemClock.elapsedRealtime()-avatarTappedAt<1500))continue;
                    need(inGame(f),"当前角色未在线；掉线恢复未确认");
                    Vision.Match avatar=vis.findNear(f,"tpl_avatar.png",.85);
                    if(avatar==null)avatar=extras().button(f,"avatar_frame");
                    if(avatar==null)avatar=extras().button(f,"avatar_shentou");
                    if(avatar==null)avatar=extras().button(f,"avatar_yixian");
                    need(avatar!=null,"角色头像未确认");
                    log("[换组] 打开队长角色面板 第"+(avatarAttempts+1)+"次，目标="
                            +String.format(java.util.Locale.ROOT,"%.3f,%.3f",avatar.fx(),avatar.fy()));
                    tapMatch(avatar);avatarAttempts++;avatarTappedAt=SystemClock.elapsedRealtime();avatarTapped=true;
                }
                if(!panelReady)saveEvidence("rotation_captain_panel_unopened");
                need(panelReady,"角色面板未打开或登录奖励未清除");
                f=rotationFrame(500);need(f!=null,"角色面板新帧未到达");String profession=extras().panel(f);
                RoleNameGlyph n=name(f,new float[]{.20f,.414f,.45f,.446f});
                IdGlyph id=vis.idGlyph(f,true);String idToken=token(f);
                android.graphics.Bitmap fresh=rotationFrame(500);
                IdGlyph.Score stable=id==null?null:id.compare(vis.idGlyph(fresh,true));
                need(!profession.isEmpty()&&profession.equals(extras().panel(fresh))&&n!=null
                        &&n.matches(name(fresh,new float[]{.20f,.414f,.45f,.446f}))
                        &&stable!=null&&stable.matches(),"角色职业/昵称/ID新帧不一致");
                if(key.equals(session.captain)) {
                    // 固定身份包含职业、昵称字形与ID；ID共享也不能单独证明角色。
                    StringBuilder mask=new StringBuilder();for(boolean ink:n.mask)mask.append(ink?'1':'0');
                    String identity=profession+":"+n.aspect+":"+mask;
                    if(session.captainToken.isEmpty()) {
                        need(session.phase==RotationSession.Phase.INITIAL,"缺固定队长原角色记录");
                        session.captainToken=identity+"\n"+idToken;
                    } else {
                        int split=session.captainToken.indexOf('\n');
                        need(split>0&&session.captainToken.substring(0,split).equals(identity),"固定队长原角色发生变化");
                        IdGlyph original=decodeId(session.captainToken.substring(split+1));
                        IdGlyph.Score same=original==null?null:original.compare(id);
                        need(same!=null&&same.matches(),"固定队长ID不符");
                    }
                    captainIdGlyph=id;
                }
                saveEvidence("rotation_role");
                Vision.Match close=vis.findNear(fresh,"tpl_role_close.png",.88);
                need(close!=null,"角色面板关闭按钮未确认");tapMatch(close);
                need(waitInGameStable(account(key).pkg,600)&&!has("tpl_role_title.png",.88),"角色面板未关闭");
                log("[换组] "+new RoleStore(acc).labelOf(account(key).pkg)+" · "+profession);
                captainVerified=true;
            }
            java.util.List<Vision.Match> rosterRows(android.graphics.Bitmap f) {
                need(f!=null&&vis.findNear(f,"tpl_team_tab.png",.88)!=null,"不是队长实际队伍名册");
                android.graphics.Bitmap work=f.copy(android.graphics.Bitmap.Config.ARGB_8888,true);
                java.util.List<Vision.Match> rows=new java.util.ArrayList<>();
                android.graphics.Canvas canvas=new android.graphics.Canvas(work);
                android.graphics.Paint paint=new android.graphics.Paint();paint.setColor(0xff000000);
                try {
                    for(int i=0;i<5;i++) {
                        Vision.Match kick=vis.findInRoi(work,"tpl_roster_kick.png",.90);if(kick==null)break;
                        rows.add(kick);
                        canvas.drawRect(kick.cx-kick.w/2f-4,kick.cy-kick.h/2f-4,kick.cx+kick.w/2f+4,kick.cy+kick.h/2f+4,paint);
                    }
                }finally{work.recycle();}
                rows.sort((left,right)->Integer.compare(left.cy,right.cy));
                for(int i=1;i<rows.size();i++)need(rows.get(i).fy()-rows.get(i-1).fy()>.08f,"名册行结构不完整");
                return rows;
            }
            void verifyRosterRows(RotationSession s,RotationController.Repository repo,
                    android.graphics.Bitmap f,java.util.List<Vision.Match> rows,int limit,boolean refresh) throws Exception {
                for(int i=0;i<limit;i++) {
                    RotationSession.Member m=s.member(s.applicationOrder.get(i));
                    if(m.verified&&!refresh)continue;
                    float cy=rows.get(i).fy()-.045f;
                    need(Math.abs(cy-extras().rosterNameY(i))<.012f,"名册行与登记姓名坐标不符");
                    RoleNameGlyph actual=extras().captureRosterName(f,i);
                    RoleNameGlyph expected=RoleNameGlyph.decode(m.nameGlyph);
                    double score=expected==null?0:expected.score(actual);
                    log("[换组] 按申请顺序核验姓名：第"+(i+1)+"行 分="+String.format(java.util.Locale.ROOT,"%.4f",score));
                    need(score>=extras().rosterNameThreshold(),"名册第"+(i+1)+"行姓名与该次选角不符");
                    s.rosterRowVerified(m.key,i);repo.save(s);
                }
            }
            public boolean exactRoster(RotationSession s,RotationController.Repository repo) throws Exception {
                verifyCaptain();foreground(s.captain);need(openTeamPanel(),"队长名册打不开");
                android.graphics.Bitmap f=rotationFrame(500);java.util.List<Vision.Match> rows=rosterRows(f);
                need(rows.size()==4&&s.applicationOrder.size()==4,"实际名册不足四人或申请顺序缺失");
                saveEvidence("rotation_name_roster_source");
                verifyRosterRows(s,repo,f,rows,4,false);
                saveEvidence("rotation_name_roster_confirmed");
                return s.verifiedCount()==4;
            }
            public boolean reconcileRoster(RotationSession s,RotationController.Repository repo) throws Exception {
                verifyCaptain();foreground(s.captain);need(openTeamPanel(),"同步换组时队长名册打不开");
                android.graphics.Bitmap f=rotationFrame(400);java.util.List<Vision.Match> rows=rosterRows(f);
                need(rows.size()==4,"普通队伍未到四人，不能同步换组");
                java.util.List<String> names=new java.util.ArrayList<>();
                for(int i=0;i<4;i++){
                    need(Math.abs(rows.get(i).fy()-.045f-extras().rosterNameY(i))<.012f,"同步名册行坐标不符");
                    RoleNameGlyph name=extras().captureRosterName(f,i);need(name!=null,"同步名册姓名不完整");names.add(name.encode());
                }
                saveEvidence("rotation_ordinary_sync_source");
                s.reconcileOrdinaryNames(names,extras().rosterNameThreshold(),extras().rosterRecoveryMargin());repo.save(s);
                log("[换组同步] "+(s.paused?s.reason:"第"+s.group+"组真实四名姓名通过，等待手动换下一组"));
                return !s.paused&&(s.phase==RotationSession.Phase.READY||s.phase==RotationSession.Phase.COMPLETE);
            }
            public boolean disbandAndVerify(String key) {
                verifyCaptain();foreground(key);return prepareCaptain(account(key).pkg);
            }
            public boolean disbandStillVerified(String key) {
                // 本次刚核验过固定队长时不重复开头像，解散后的空面板可能稍晚刷新。
                verifyCaptain();foreground(key);
                for(int i=0;i<2;i++)if(teamTab(rotationFrame(400))!=null&&!closeTeamPanel())return false;
                android.graphics.Bitmap first=rotationFrame(400),second=rotationFrame(400);
                return inGame(first)&&inGame(second)&&!hasOn(first,"tpl_leave_team.png",.85)&&!hasOn(second,"tpl_leave_team.png",.85);
            }
            public boolean returnToSelection(String key) {
                foreground(key);if(extras().layout(rotationFrame(500))!=null)return true;
                dismissPopups();need(inGame(),"未在游戏内，不能返回角色列表");
                tapTpl("tpl_menu_btn.png",.90,"换角菜单");
                String[] keys={"system","role_list"};
                for(String button:keys) {
                    long end=SystemClock.elapsedRealtime()+3500;Vision.Match hit=null;
                    while(!stopped()&&SystemClock.elapsedRealtime()<end&&(hit=extras().button(rotationFrame(400),button))==null){}
                    need(hit!=null,"换角菜单按钮未确认");tapMatch(hit);
                }
                long end=SystemClock.elapsedRealtime()+5000;
                while(!stopped()&&SystemClock.elapsedRealtime()<end)if(extras().layout(rotationFrame(400))!=null)return true;
                return false;
            }
            public boolean onSelectionPage(String key){foreground(key);long end=SystemClock.elapsedRealtime()+2000;
                while(!stopped()&&SystemClock.elapsedRealtime()<end)if(extras().layout(rotationFrame(400))!=null)return true;return false;}
            public String selectionLayout(String key) {
                foreground(key);android.graphics.Bitmap f=null;SelectionLayout.Observation o=null;
                RotationSession.Member m=session.member(key);
                long end=SystemClock.elapsedRealtime()+2500;
                while(!stopped()&&SystemClock.elapsedRealtime()<end) {
                    f=rotationFrame(400);o=extras().layout(f);
                    if(o!=null&&o.selected==1&&extras().captureSelectionName(f)!=null) {
                        return o.encode();
                    }
                }
                saveEvidence("rotation_selection_unknown");
                need(false,"选择前中间角色或现场字形未确认（槽位"+(o==null?"未知":o.selected)+"）");return "";
            }
            public RotationController.Selection select(String key,String direction,boolean mayClick) {
                foreground(key);RotationSession.Member m=session.member(key);
                String[] before=m.layoutBefore.split("\\|",-1);int target=direction.equals("中间")?1:direction.equals("左边")?0:2;
                need(before.length==3,"目标槽位布局缺失");
                SelectionLayout.Observation o=extras().layout(rotationFrame(500));
                if(mayClick){
                    long stableEnd=SystemClock.elapsedRealtime()+3000;
                    while(!stopped()&&(o==null||o.selected!=1||!o.encode().equals(m.layoutBefore))
                            &&SystemClock.elapsedRealtime()<stableEnd)o=extras().layout(rotationFrame(250));
                    if(o==null||o.selected!=1||!o.encode().equals(m.layoutBefore))
                        throw new RotationController.SelectionNotDispatched("点击前槽位未稳定，未发出选角点击");
                    tapMatch(o.targets[target]);
                }
                long end=SystemClock.elapsedRealtime()+3000;RoleNameGlyph previous=null;
                while(!stopped()&&SystemClock.elapsedRealtime()<end) {
                    android.graphics.Bitmap f=rotationFrame(400);
                    RoleNameGlyph glyph=extras().captureSelectionName(f);
                    // 已派发后用实际箭头和连续新帧姓名确认；不再复验无关槽位外观。
                    if(extras().selectedSlot(f)==target&&glyph!=null) {
                        if(previous!=null&&previous.matches(glyph)) {
                            saveEvidence("rotation_selected_target");
                            return new RotationController.Selection(true,glyph.encode());
                        }
                        previous=glyph;
                    }else{
                        previous=null;
                    }
                }
                saveEvidence("rotation_selected_unknown");
                return null;
            }
            public boolean enterSelected(String key) {
                foreground(key);android.graphics.Bitmap f=rotationFrame(500);
                Vision.Match enter=extras().enter(f);
                // 选角页背景也会命中宽松世界色彩判据；先排除明确的选角按钮。
                if(enter==null) {
                    need(inGame(f)&&vis.findNear(f,"tpl_menu_btn.png",.90)!=null,"当前已选角色进入按钮或世界菜单不明");
                    dismissPopups();return true;
                }
                tapMatch(enter);long end=SystemClock.elapsedRealtime()+15000;
                int entryGeneration=connectionGeneration;
                while(!stopped()&&SystemClock.elapsedRealtime()<end) {
                    f=rotationFrame(300);
                    if(entryGeneration!=connectionGeneration) {
                        entryGeneration=connectionGeneration;
                        Vision.Match recoveredEnter=extras().enter(f);
                        if(recoveredEnter!=null)tapMatch(recoveredEnter);
                        end=SystemClock.elapsedRealtime()+extras().recoveryLimit("world_timeout_ms");
                        continue;
                    }
                    if(extras().enter(f)==null&&inGame(f)&&vis.findNear(f,"tpl_menu_btn.png",.90)!=null){dismissPopups();return true;}
                }
                need(false,"进入角色超时");return false;
            }
            boolean waitOrderedRosterCount(int expected) {
                long end=SystemClock.elapsedRealtime()+12000;
                while(!stopped()&&SystemClock.elapsedRealtime()<end) {
                    if(openTeamPanel()) {
                        android.graphics.Bitmap fresh=rotationFrame(400);
                        if(fresh!=null&&rosterRows(fresh).size()==expected) {
                            log("[换组] 新名册已到"+expected+"人，立即继续；最终仍核验姓名");
                            return true;
                        }
                    }
                    rotationFrame(300);
                }
                return false;
            }
            public boolean groupMembers(RotationSession s,RotationController.Repository repo) throws Exception {
                for(RotationSession.Member m:s.members){
                    need(m.entered&&!m.nameGlyph.isEmpty(),"四员进入及姓名采集屏障未通过");
                    foreground(m.key);dismissPopups();need(alignMap()&&inCityConfirmed(),"全员同城屏障未通过"+(mapTravelFailure.isEmpty()?"":"："+mapTravelFailure));
                }
                foreground(s.captain);
                if(teamTab(rotationFrame(400))!=null)need(closeTeamPanel(),"队长队伍面板未关闭");
                need(alignMap()&&inCityConfirmed(),"队长同城未确认");
                need(openTeamPanel(),"队长名册打不开");
                int count=rosterRows(rotationFrame(400)).size();
                if(count<s.acceptedApplications) {
                    log("[换组] 保存申请人数="+s.acceptedApplications+"，当前名册="+count+"；刷新核对");
                    android.graphics.Bitmap current=rotationFrame(400);
                    java.util.List<Vision.Match> rows=rosterRows(current);
                    need(rows.size()==count,"恢复名册期间人数变化");
                    verifyRosterRows(s,repo,current,rows,count,true);
                    s.reconcileRosterPrefix(count);repo.save(s);
                    log("[换组] 断线后名册只保留原顺序前"+count+"人，补申请缺席队员，不重复选角");
                }
                need(count>=s.acceptedApplications&&count<=s.applicationOrder.size(),"名册人数与已保存申请顺序不符");
                // 已有申请意图只观察结果，不重发未知请求。
                if(s.applicationOrder.size()>s.acceptedApplications) {
                    need(waitOrderedRosterCount(s.applicationOrder.size()),"上次申请结果未知，禁止重复申请");
                    count=rosterRows(rotationFrame(400)).size();s.applicationAccepted(count);repo.save(s);
                }
                for(RotationSession.Member m:s.members)if(!s.applicationOrder.contains(m.key)) {
                    HelperApp.Account account=account(m.key);
                    need(memberApplyJoin(account.pkg,account.account,account.password,team.captain.charId,()->{
                        s.reserveApplication(m.key);
                        try{repo.save(s);}catch(Exception e){throw new IllegalStateException("申请前记录保存失败",e);}
                    }),"好友/申请未确认");
                    foreground(s.captain);
                    need(waitOrderedRosterCount(s.applicationOrder.size()),"申请后名册人数未到齐，保留申请意图");
                    count=rosterRows(rotationFrame(400)).size();s.applicationAccepted(count);repo.save(s);
                }
                return s.acceptedApplications==4;
            }
            public void status(String stage,int verified,String reason) {
                RotationRuntime.report(stage+" · "+verified+"/4"+(reason.isEmpty()?"":" · "+reason));
            }
        };
    }

    /** 任意客户端上号前恢复遗留地图，必须验证关闭后的真实世界。 */
    private boolean closeMapToWorld(android.graphics.Bitmap current){
        if(!extras().mapOpen(current))return true;
        Vision.Match close=extras().button(current,"map_close");
        if(close==null)return false;
        saveEvidence("resume_map_before_close");tapMatch(close);
        long end=SystemClock.elapsedRealtime()+5000;
        while(!stopped()&&SystemClock.elapsedRealtime()<end){
            android.graphics.Bitmap next=frame(500);
            if(next!=null&&!extras().mapOpen(next)&&inGame(next)){
                log("  已关闭遗留地图并确认世界页");return true;
            }
        }
        saveEvidence("resume_map_close_unknown");return false;
    }

    /** 日程切换账号必须真的回到登录面板再填入，禁止在线画面跳过登录。 */
    boolean loginScheduledMembers(HelperApp.Team team) {
        worker=Thread.currentThread();vis.setCancelled(this::stopped);
        if(!RunGate.acquire(this,this::requestStop))return false;
        try {
            if(!onlineOne(team.captain.pkg,team.captain.account,team.captain.password))return false;
            if(!prepareCaptain(team.captain.pkg))return false;
            int index=0;
            for(HelperApp.Account a:team.members){
                if(stopped())return false;
                log("[日程] 切换队友客户端 "+(++index)+"/4");
                launch(a.pkg);if(!waitForPkg(a.pkg,10000))return false;
                if(!closeMapToWorld(frame(300)))return false;
                if(!waitTpl(TPL_LOGIN_PANEL,.90,1200)){
                    // 沿已有入口把旧选角/世界恢复为世界，再走系统离开游戏。
                    if(!onlineOne(a.pkg,a.account,a.password))return false;
                    dismissPopups();
                    if(!waitTpl("tpl_menu_btn.png",.90,5000))return false;
                    tapTpl("tpl_menu_btn.png",.90,"日程菜单");
                    long end=SystemClock.elapsedRealtime()+5000;Vision.Match system=null;
                    while(!stopped()&&SystemClock.elapsedRealtime()<end){system=extras().button(frame(250),"system");if(system!=null)break;}
                    if(system==null)return false;tapMatch(system);
                    end=SystemClock.elapsedRealtime()+5000;Vision.Match exit=null;
                    while(!stopped()&&SystemClock.elapsedRealtime()<end){exit=extras().button(frame(250),"exit");if(exit!=null)break;}
                    if(exit==null)return false;saveEvidence("schedule_before_logout_"+index);tapMatch(exit);
                    if(!waitTpl(TPL_LOGIN_PANEL,.90,20000))return false;
                }
                saveEvidence("schedule_login_panel_"+index);
                if(stopped()||!fillLogin(a.pkg,a.account,a.password))return false;
                saveEvidence("schedule_new_account_world_"+index);
            }
            return !stopped();
        }catch(Exception e){saveEvidence("schedule_login_unknown");log("[日程] 换账号未确认，停止");return false;}
        finally{worker=null;RunGate.release(this);}
    }
    /** 组队或换角核验后交给刷铜：先收起名册，必须确认队长主界面。 */
    boolean prepareCaptainForFarm(String pkg) throws Exception {
        if (!RunGate.acquire(this, this::requestStop)) return false;
        worker = Thread.currentThread();vis.setCancelled(this::stopped);
        try {
            if(stopped())return false;
            log("[阶段交接] 核对队长前台并关闭队伍面板");
            HelperApp.Team readyTeam=HelperApp.loadTeam();
            RoleRotation readyRepo=new RoleRotation(acc);RotationSession ready=readyRepo.load();
            if(ready!=null&&ready.phase==RotationSession.Phase.READY&&readyTeam.captain.pkg.equals(pkg)){
                // 冷启动/重启后不能把保存的READY当作实时队伍；只进入保留角色，不换左右。
                for(HelperApp.Account a:readyTeam.members)if(!onlineOne(a.pkg,a.account,a.password))return false;
                if(!onlineOne(pkg,readyTeam.captain.account,readyTeam.captain.password))return false;
                dismissPopups();
                if(!openTeamPanel())return false;
                int actual=countTeamMembers();
                if(actual>=0&&actual<4){readyRegroupNeeded=true;log("[阶段交接] 实际名册不足四人，需要按当前角色重新组队");return false;}
                if(!rotationPort(ready,readyTeam).reconcileRoster(ready,readyRepo))return false;
            }
            launch(pkg);
            if(!waitForPkg(pkg,10000)||stopped())return false;
            saveEvidence("farm_handoff_before");
            if(!closeTeamPanel()||!waitInGameStable(pkg,600)||stopped())return false;
            android.graphics.Bitmap f=frame(300);
            if(f==null||!pkg.equals(acc.currentPackage())||teamTab(f)!=null||!inGame(f))return false;
            saveEvidence("farm_handoff_world_confirmed");
            log("[阶段交接] 队伍面板已关闭，队长主界面已确认 → 开始刷铜");
            return true;
        }finally{worker=null;RunGate.release(this);}
    }

    public void run() {
        if (!RunGate.acquire(this, this::requestStop)) { finish("已有流程控制游戏", false); return; }
        PerfStats.begin();
        worker = Thread.currentThread();
        vis.setCancelled(this::stopped);
        long t0 = SystemClock.elapsedRealtime();
        captainIdGlyph = null;
        inspectedFriend = null; deletedFriend = null;
        try {
            HelperApp.Team t = HelperApp.loadTeam();
            RoleRotation rotationRepo=new RoleRotation(acc);
            RotationSession ordinarySession=null;
            if(rotationRepo.enabled()) {
                java.util.List<String> memberKeys=new java.util.ArrayList<>();
                for(HelperApp.Account a:t.members)memberKeys.add(RotationRuntime.key(a));
                if(readyRegroup!=null){
                    RotationSession.require(readyRegroup.phase==RotationSession.Phase.READY&&readyRegroup.sameTeam(RotationRuntime.key(t.captain),memberKeys),"恢复组队记录不符");
                    ordinarySession=readyRegroup;
                    log("[换组] 恢复当前第"+ordinarySession.group+"组；不换角色，结束后核对原角色姓名");
                }else{
                    ordinarySession=new RotationSession(RotationRuntime.key(t.captain),memberKeys);
                    rotationRepo.save(ordinarySession);
                    log("[换组] 本次组队已清除旧轮记录，成功后从当前角色开始：不换 → 左 → 右 → 右循环");
                }
            }
            log("队长：" + t.captain.pkg + "（账号 " + t.captain.account + " / " + t.captain.charId
                    + "）  队员数：" + t.members.size());
            if (t.captain.charId == null || t.captain.charId.isEmpty()) {
                log("✗ App 里没填队长游戏ID（用 collect_ids 采集后回来填），中止");
                finish("缺队长 ID", false);
                return;
            }

            // ① 上号
            log("① 五个号依次上线");
            boolean allOk = true;
            for (HelperApp.Account a : t.all()) {
                if (stopped()) break;
                long ts = SystemClock.elapsedRealtime();
                boolean ok = onlineOne(a.pkg, a.account, a.password);
                log("⏱ " + a.account + " 上线用时 " + (SystemClock.elapsedRealtime() - ts) / 1000 + " 秒"
                        + (ok ? "" : "（失败）"));
                if (!ok) allOk = false;
            }
            if (stopped()) { finish("已停止", false); return; }
            log("⏱ ①上号合计 " + (SystemClock.elapsedRealtime() - t0) / 1000 + " 秒");

            if (!allOk) { finish("有账号未上线，停止组队", false); return; }
            PerfStats.phase("login");
            log("①.5 全员先汇合襄阳城：全部通过后才进入队伍/好友操作");
            for (HelperApp.Account a : t.all()) {
                launch(a.pkg);
                if (!waitForPkg(a.pkg, 10_000)) {
                    saveEvidence("map_client_foreground_unknown");finish("客户端前台未确认，停止全员汇合", false);return;
                }
                // 后台客户端可能被服务器退回选角；只复用现有登录入口进入已选角色。
                if(has("tpl_enter_game.png",.90)){
                    log("[汇合] 客户端回到选角，进入当前已选角色，不切换角色");
                    if(!onlineOne(a.pkg,a.account,a.password)){finish("当前角色恢复未确认，停止全员汇合",false);return;}
                }
                if (!waitInGameStable(a.pkg, 1200)) {
                    saveEvidence("map_client_world_unknown");finish("客户端世界画面未就绪，停止全员汇合", false); return;
                }
                dismissPopups();
                saveEvidence("map_before_" + a.pkg);
                if (!alignMap() || !inCityConfirmed()) {
                    saveEvidence("map_failed_" + a.pkg);
                    finish("未确认所有角色在襄阳城"+(mapTravelFailure.isEmpty()?"":"（"+mapTravelFailure+"）")+"，停止后续组队：" + a.pkg, false);
                    return;
                }
                saveEvidence("map_confirmed_" + a.pkg);
                log("  ✓ 汇合通过 " + a.pkg);
            }
            log("  ✓ 全员汇合通过 → 才允许后续好友/申请");

            PerfStats.phase("maps");
            log("② 队长检查已有队伍：无队跳过，有队解散");
            long t2 = SystemClock.elapsedRealtime();
            if (!prepareCaptain(t.captain.pkg)) {
                finish("队长队伍状态未确认或解散失败，停止组队", false);
                return;
            }
            boolean captainCaptured;
            if (ordinarySession != null) {
                // 新轮必须在 INITIAL 阶段现场建立固定队长身份，再交还手动换组。
                rotationPort(ordinarySession, t).verifyCaptain();
                rotationRepo.save(ordinarySession);
                captainCaptured = captainIdGlyph != null && !ordinarySession.captainToken.isEmpty();
            } else captainCaptured = captureCaptainId(t.captain.pkg);
            if (!captainCaptured) {
                finish("未能采集本轮队长ID，停止组队", false);
                return;
            }
            log("⏱ ②清场合计 " + (SystemClock.elapsedRealtime() - t2) / 1000 + " 秒");

            // ③ 队员逐个检查首行：队长保留；非队长删除后点好友tab刷新，空了才添加。
            PerfStats.phase("captain_prepare");
            log("③ 队员逐个申请入队（队长 " + t.captain.charId + "）");
            int applied = 0;
            JoinProgress progress = new JoinProgress(t.members.size());
            long t3 = SystemClock.elapsedRealtime();
            for (HelperApp.Account a : t.members) {
                if (stopped()) break;
                // 队长保活：后台太久服务器会把队长会话掐掉（10-21 实测队员列表里显示「离线」，
                // 申请发出成功但队长根本收不到）。每个队员动手前都把队长拉前台确认一次在线。
                if (!ensureCaptainOnline(t.captain)) {
                    finish("队长在线/地图状态未确认，停止申请", false);
                    return;
                }
                long ts = SystemClock.elapsedRealtime();
                applyDispatched = false;
                inspectedFriend = null; deletedFriend = null;
                boolean ok = memberApplyJoin(a.pkg, a.account, a.password, t.captain.charId);
                log("⏱ " + a.account + " 申请入队用时 " + (SystemClock.elapsedRealtime() - ts) / 1000 + " 秒");
                PerfStats.phase("member_apply");
                if (ok) applied++;
                if (stopped()) { finish("已停止，入队未确认", false); return; }
                if (!progress.request(applyDispatched, ok)) {
                    finish("队员流程失败且未派发申请，停止，不空等名册：" + a.pkg, false); return;
                }
                // 先让队长处理请求；有实际名册增长就立即继续。
                launch(t.captain.pkg);
                if (!waitForPkg(t.captain.pkg, 10_000) || !waitInGameStable(t.captain.pkg, 600)) {
                    finish("队长前台未确认", false); return;
                }
                boolean joined = awaitRoster(progress.sent(), 3500);
                if (!closeTeamPanel()) { finish("名册面板未确认关闭", false); return; }
                if (!joined) {
                    // 仅恢复队员客户端以接收入队结果；UNKNOWN也绝不重新申请。
                    launch(a.pkg);
                    if (waitForPkg(a.pkg, 10_000)) {
                        dismissPopups();
                        waitMemberJoined(a.pkg, 5000);
                    }
                }
                PerfStats.phase("captain_pulse");
            }
            log("⏱ ③申请阶段合计 " + (SystemClock.elapsedRealtime() - t3) / 1000 + " 秒");

            if (stopped()) { finish("已停止，入队未确认", false); return; }
            log("④ 回队长核对实际名册（申请回执不当入队成功）");
            int expect = t.members.size(), got = -1;
            long rosterDeadline = SystemClock.elapsedRealtime() + 240_000;
            JoinProgress.State result = JoinProgress.State.PENDING;
            int round = 0;
            while (!stopped() && SystemClock.elapsedRealtime() < rosterDeadline) {
                launch(t.captain.pkg);
                if (!waitForPkg(t.captain.pkg, 10_000) || !waitInGameStable(t.captain.pkg, 600)) break;
                dismissPopups();
                got = openTeamPanel() ? countTeamMembers() : -1;
                log("  名册人数：" + (got < 0 ? "读不到" : got + "/" + expect)
                        + "（第 " + (++round) + " 次查）");
                result = progress.observe(got, stopped());
                if (result == JoinProgress.State.CONFIRMED || result == JoinProgress.State.READ_FAILED) break;
                if (!closeTeamPanel()) break;
                // 结果尚未落地，有限恢复客户端，保持原申请不重发。
                for (HelperApp.Account a : t.members) {
                    if (stopped()) break;
                    launch(a.pkg);
                    if (waitForPkg(a.pkg, 6000)) { dismissPopups(); waitMemberJoined(a.pkg, 1500); }
                }
            }
            PerfStats.phase("roster");
            String shot = saveEvidence("leader_after_apply");
            long total = (SystemClock.elapsedRealtime() - t0) / 1000;
            log("⏱ 整轮用时 " + total / 60 + " 分 " + total % 60 + " 秒");
            String rotationResult="";
            if(result==JoinProgress.State.CONFIRMED&&!stopped()&&ordinarySession!=null){
                try {
                    android.graphics.Bitmap rosterFrame=frame(500);
                    RotationSession.require(applied==4&&progress.sent()==4&&hasOn(rosterFrame,"tpl_team_tab.png",.88),"普通组队实际申请或队伍页未确认");
                    java.util.List<String> names=new java.util.ArrayList<>();
                    for(int row=0;row<4;row++){
                        RoleNameGlyph glyph=extras().captureRosterName(rosterFrame,row);
                        RotationSession.require(glyph!=null,"普通队伍第"+(row+1)+"行姓名未采集");names.add(glyph.encode());
                    }
                    if(readyRegroup!=null)ordinarySession.reconcileOrdinaryNames(names,extras().rosterNameThreshold(),extras().rosterRecoveryMargin());
                    else ordinarySession.seedOrdinaryNames(names);
                    rotationRepo.save(ordinarySession);
                    saveEvidence("rotation_fresh_start");
                    rotationResult=readyRegroup!=null?"；第"+ordinarySession.group+"组原角色已恢复，下次"+ordinarySession.nextDirection():"；新轮起点已建立，下次左换";
                    log("[换组] 当前四人建立新起点，不沿用任何旧账号姓名；下次左、再右、再右回起点");
                }catch(Exception e){
                    ordinarySession.pause("普通组队完成，但新轮起点未确认："+e.getMessage());rotationRepo.save(ordinarySession);
                    log("[换组] 起点未确认："+e.getMessage());rotationResult="；普通组队完成，新轮姓名起点未确认";
                }
            }
            finish("申请 " + applied + "/" + t.members.size() + "；队长名册实到 "
                    + (got < 0 ? "读不到" : got + "/" + expect)
                    + "（整轮 " + total + " 秒；" + (shot == null ? "证据截图失败" : "证据 " + shot) + "）"+rotationResult,
                    result == JoinProgress.State.CONFIRMED && !stopped());
        } catch (Throwable e) {
            LogBus.get().e("phone", "运行异常", e);
            finish("运行异常：" + e.getClass().getSimpleName(), false);
        } finally { PerfStats.finish(); worker = null; RunGate.release(this); }
    }
}
