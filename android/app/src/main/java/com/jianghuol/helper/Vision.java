package com.jianghuol.helper;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Vision —— 手机闭环的「眼」。
 *
 * ## Java NCC：先测量，再决定是否需要端侧OpenCV
 *
 * 本项目铁律是 **零第三方依赖**（`app/build.gradle` 里 dependencies 是空的）：
 * 一旦引入 opencv aar，包体 +70MB，ARM 匹配机还得单独编 .so，构建链复杂度翻倍。
 * 而电脑端那套 cv2 匹配真正用到的只有两件事：
 *
 * 1. **TM_CCOEFF_NORMED 模板匹配** —— 找按钮在不在（比如「离队」「邀请组队」）；
 * 2. **HSV 颜色连通域** —— 判断画面状态（左上角有没有绿血条 = 在不在游戏里）。
 *
 * 这两件事几百行 Java 就能实现，功能等价、速度够用：
 * 模板匹配是双线性缩放 + 粗扫与精算的归一化互相关；颜色检测用 flood-fill 连通域。
 * 分辨率无关：模板匹配带缩放搜索，检测阈值全用**归一化判据**（面积占屏幕比例）。
 *
 * 2026-10-20 迁移自 analysis/t046/tpl（20 个）+ clicker 登录模板（6 个）。
 */
public final class Vision {

    private final AssetManager am;
    /** 模板缓存：同一 (名字,宽度) 只解码+缩放一次。find() 会被轮询几百次，不能每次都解码。 */
    private final java.util.HashMap<String, Bitmap> tplCache = new java.util.HashMap<>();

    public Vision(AssetManager am) {
        this.am = am;
    }

    // ---------------------------------------------------------------- 模板匹配

    /** 匹配结果：坐标为**采集帧空间**（540x960）的中心点 + 分数。 */
    public static final class Match {
        public final int cx, cy, w, h;
        public final double score;
        private final int frameWidth, frameHeight;

        Match(int cx, int cy, int w, int h, double score, int frameWidth, int frameHeight) {
            this.cx = cx; this.cy = cy; this.w = w; this.h = h; this.score = score;
            this.frameWidth = frameWidth; this.frameHeight = frameHeight;
        }

        public float fx() { return cx / (float) frameWidth; }       // 归一化 x（采集帧宽 540）
        public float fy() { return cy / (float) frameHeight; }       // 归一化 y（采集帧高 960）
        @Override public String toString() {
            return String.format("(%.0f,%.0f) score=%.3f", (float) cx, (float) cy, score);
        }
    }

    private Bitmap load(String name, int width) { return load(name, width, -1); }

    private Bitmap load(String name, int width, int height) {
        if (width <= 0) width = -1;                  // 原尺寸
        String key = name + "@" + width + "x" + height;
        Bitmap hit = tplCache.get(key);
        if (hit != null) return hit;
        try {
            java.io.InputStream is = am.open("tpl/" + name);
            Bitmap b = BitmapFactory.decodeStream(is);
            is.close();
            if (b == null) return null;
            if (width > 0 && (b.getWidth() != width || (height > 0 && b.getHeight() != height))) {
                int h = height > 0 ? height : Math.max(1, (int) ((float) b.getHeight() * width / b.getWidth()));
                Bitmap s = Bitmap.createScaledBitmap(b, width, h, true);
                if (s != b) b.recycle();
                b = s;
            }
            tplCache.put(key, b);
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 模板采集时的屏幕宽度（全部 t046/clicker 模板都是 1080 宽的设备上截的）。 */
    public static final int CAPTURE_W = 1080;

    // ---- 性能自检（2026-10-21 用户问"这手机跑得动吗"，用真数字回答，不猜）----
    private static long perfCalls = 0, perfNanos = 0, perfRoi = 0;
    private static final long PERF_EVERY = 200;

    /** 每次匹配记一笔；每 PERF_EVERY 次打一条平均耗时（logcat tag JiangHuOL）。 */
    private static void perfNote(long nanos, boolean roi) {
        perfCalls++; perfNanos += nanos; if (roi) perfRoi++;
        if (perfCalls % PERF_EVERY == 0) {
            LogBus.get().i("vision", String.format(
                    "匹配 %d 次，平均 %.1f ms/次（其中 ROI 检索 %d 次）",
                    perfCalls, perfNanos / 1e6 / perfCalls, perfRoi));
        }
    }

    public static String perfSummary() {
        return perfCalls == 0 ? "无匹配记录"
                : String.format("共 %d 次匹配，平均 %.1f ms/次，ROI 检索 %d 次",
                        perfCalls, perfNanos / 1e6 / perfCalls, perfRoi);
    }

    /**
     * 在 screen 里找模板 name（assets/tpl/ 下）。
     *
     * 缩放规则：模板按 **CAPTURE_W → 当前屏宽** 等比缩放，再在此基础上试 3 个尺度。
     * 2026-10-20 23:20 修的 bug：原来写死「屏宽×2%」——模板实际占屏宽 20% 的按钮
     * 被缩成 2%，匹配全废。现在按采集宽度等比换算，改分辨率也不会错。
     */
    public Match find(Bitmap screen, String name, double minScore) {
        return findScaled(screen, name, minScore, screen == null ? 0 : screen.getWidth(), screen == null ? 0 : screen.getHeight());
    }

    /**
     * @param refFrameWidth 搜索图所处的"原始帧"宽度 —— 模板按它（而不是搜索图自身宽度）
     *                      做 CAPTURE_W 等比缩放。全帧搜索时两者相同；ROI 裁剪后
     *                      搜索图变窄，若按它缩放模板会错出一个数量级。
     */
    private Match findScaled(Bitmap screen, String name, double minScore, int refFrameWidth, int refFrameHeight) {
        if (screen == null || refFrameWidth <= 0) return null;
        long t0 = System.nanoTime();
        try {
            Bitmap origin = load(name, -1);
            if (origin == null) return null;
            int baseW = Math.max(8, (int) ((float) origin.getWidth() * refFrameWidth / CAPTURE_W));
            int baseH = Math.max(4, (int) ((float) origin.getHeight() * refFrameHeight / 1920));
            Match best = null;
            for (double scale : new double[]{1.0, 0.9, 1.1}) {
                int tw = Math.max(8, (int) (baseW * scale));
                Bitmap tpl = load(name, tw, Math.max(4, (int) (baseH * scale)));
                if (tpl == null) continue;
                Match m = ncc(screen, tpl);
                if (m != null && (best == null || m.score > best.score)) {
                    best = m;
                    if (m.score >= minScore) return m;   // 够阈值就不必再试其他尺度
                }
            }
            return (best != null && best.score >= minScore) ? best : null;
        } finally {
            perfNote(System.nanoTime() - t0, searchIsRoi);
            PerfStats.add("match:" + name, t0);
        }
    }

    public boolean has(Bitmap screen, String name, double minScore) {
        return find(screen, name, minScore) != null;
    }

    /**
     * 归一化互相关系数（TM_CCOEFF_NORMED 同款定义），粗扫 + 全分辨率精算。
     *
     * ## 为什么必须是两段式（2026-10-21 换掉老的单段隔行采样）
     *
     * 老实现把模板隔行抽点（80 宽抽成 40）去和隔行抽点的屏幕互相关：
     * 两条抽样网格相位对不上，同时抽点会把「点击继续」这类**细笔画文字模板**
     * 打成一片亮斑 —— 真启动页从 0.998 掉到 0.78，比登录屏上的误匹配还低，
     * 整个登录判定失灵（真值不在了，假值反超）。
     * 纯全分辨率搜索又太慢（430k 窗口 × 2240 像素 ≈ 每次数秒）。
     *
     * 所以：① 屏幕与模板**都双线性面积缩到一半**做粗扫（同网格、保形状），
     * 取前 4 个候选峰；② 每个峰回到全分辨率帧上精算一次真分数。
     * 半分辨率亮斑糊不糊无所谓 —— 它只负责"可能在哪"，最终分数永远以全分辨率算。
     */
    private java.util.function.BooleanSupplier cancelled = () -> false;
    void setCancelled(java.util.function.BooleanSupplier check) { cancelled = check; }
    private boolean searchIsRoi;
    private static final class Prepared {
        final int generation, halfWidth, halfHeight;
        final int[] full, half;
        Prepared(Bitmap b, int hw, int hh) {
            generation = b.getGenerationId(); halfWidth = hw; halfHeight = hh;
            full = grey(b, b.getWidth(), b.getHeight());
            Bitmap h = Bitmap.createScaledBitmap(b, hw, hh, true);
            half = grey(h, hw, hh);
            if (h != b) h.recycle();
        }
    }
    // 模板长期复用；当前帧/局部裁图只保留弱引用，不持有历史帧。
    private final java.util.WeakHashMap<Bitmap, Prepared> prepared = new java.util.WeakHashMap<>();
    private Prepared prepare(Bitmap b, int hw, int hh) {
        Prepared p = prepared.get(b);
        if (p == null || p.generation != b.getGenerationId() || p.halfWidth != hw || p.halfHeight != hh) {
            p = new Prepared(b, hw, hh); prepared.put(b, p);
        }
        return p;
    }

    private Match ncc(Bitmap screen, Bitmap tpl) {
        int sw = screen.getWidth(), sh = screen.getHeight();
        int tw = tpl.getWidth(), th = tpl.getHeight();
        if (tw > sw || th > sh || tw < 4 || th < 4) return null;

        // ---- 粗扫（半分辨率，两边同用面积缩放）----
        int dw = (sw + 1) / 2, dh = (sh + 1) / 2;
        int cw = Math.max(4, tw / 2), ch = Math.max(4, th / 2);
        Prepared ps = prepare(screen, dw, dh), pt = prepare(tpl, cw, ch);
        int[] gs = ps.half, gt = pt.half;

        int n = cw * ch;
        long dtSum = 0, dtSq = 0;
        for (int i = 0; i < gt.length; i++) { dtSum += gt[i]; dtSq += (long) gt[i] * gt[i]; }
        double dtMean = (double) dtSum / n, dtVar = (double) dtSq / n - dtMean * dtMean;
        if (dtVar <= 0) return null;   // 纯色模板无法匹配（模板废了）

        int gw = dw - cw + 1, gh = dh - ch + 1;
        float[] sm = new float[gw * gh];
        for (int y = 0; y < gh; y++) {
            if (cancelled.getAsBoolean()) return null;
            for (int x = 0; x < gw; x++) {
                long sSum = 0, sSq = 0, stDot = 0;
                for (int ty = 0; ty < ch; ty++) {
                    int sRow = (y + ty) * dw + x, tRow = ty * cw;
                    for (int tx = 0; tx < cw; tx++) {
                        int s = gs[sRow + tx], t = gt[tRow + tx];
                        sSum += s; sSq += (long) s * s; stDot += (long) s * t;
                    }
                }
                double sMean = (double) sSum / n;
                double sVar = (double) sSq / n - sMean * sMean;
                double num = (double) stDot / n - sMean * dtMean;
                double den = Math.sqrt(Math.max(sVar, 1e-6) * dtVar);
                sm[y * gw + x] = (float) (num / den);
            }
        }

        // ---- 前 4 个候选峰（简单非极大值抑制）----
        int[] px = new int[4], py = new int[4];
        int found = 0;
        for (int k = 0; k < 4; k++) {
            int bx = -1, by = -1;
            float bv = -2f;
            for (int y = 0; y < gh; y++) {
                for (int x = 0; x < gw; x++) {
                    float v = sm[y * gw + x];
                    if (v > bv) { bv = v; bx = x; by = y; }
                }
            }
            if (bx < 0 || bv < -1f) break;
            px[k] = bx; py[k] = by; found++;
            // 抑制周围 8px，避免 4 个候选挤在同一处
            int x0 = Math.max(0, bx - 8), x1 = Math.min(gw, bx + 8);
            int y0 = Math.max(0, by - 8), y1 = Math.min(gh, by + 8);
            for (int y = y0; y < y1; y++)
                for (int x = x0; x < x1; x++) sm[y * gw + x] = -2f;
        }
        if (found == 0) return null;

        // ---- 全分辨率精算 ----
        int[] gf = ps.full;
        int[] gtf = pt.full;
        int tn = tw * th;
        long tSum = 0, tSq = 0;
        for (int i = 0; i < gtf.length; i++) { tSum += gtf[i]; tSq += (long) gtf[i] * gtf[i]; }
        double tMean = (double) tSum / tn, tVar = (double) tSq / tn - tMean * tMean;
        if (tVar <= 0) return null;

        Match best = null;
        for (int k = 0; k < found; k++) {
            if (cancelled.getAsBoolean()) return null;
            // 粗扫峰只有半分辨率精度（±1px 栅格偏差），细笔画模板对 1px 极敏感
            // （实测登录按钮 0.973 会被打到 0.86），所以映射后在 ±3px 邻域里取最优。
            int cx0 = Math.min(px[k] * 2, sw - tw), cy0 = Math.min(py[k] * 2, sh - th);
            for (int dy = -3; dy <= 3; dy++) {
                int y2 = cy0 + dy;
                if (y2 < 0 || y2 + th > sh) continue;
                for (int dx = -3; dx <= 3; dx++) {
                    int x2 = cx0 + dx;
                    if (x2 < 0 || x2 + tw > sw) continue;
                    long sSum = 0, sSq = 0, stDot = 0;
                    for (int ty = 0; ty < th; ty++) {
                        int sRow = (y2 + ty) * sw + x2, tRow = ty * tw;
                        for (int tx = 0; tx < tw; tx++) {
                            int s = gf[sRow + tx], t = gtf[tRow + tx];
                            sSum += s; sSq += (long) s * s; stDot += (long) s * t;
                        }
                    }
                    double sMean = (double) sSum / tn;
                    double sVar = (double) sSq / tn - sMean * sMean;
                    double num = (double) stDot / tn - sMean * tMean;
                    double den = Math.sqrt(Math.max(sVar, 1e-6) * tVar);
                    double score = num / den;
                    if (best == null || score > best.score) {
                        best = new Match(x2 + tw / 2, y2 + th / 2, tw, th, score, sw, sh);
                    }
                }
            }
        }
        return best;
    }

    private static int[] grey(Bitmap b, int w, int h) {
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int[] out = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            // 灰度 = (77R + 151G + 28B) / 256 —— 权重和正好 256，所以 >>8。
            // ⚠️ 2026-10-21 修的致命 bug：原来写 >>16，而最大值只有 255*256=65280，
            // 右移 16 位后每个像素都是 0 —— 整帧全黑，NCC 分母为 0，所有匹配静默失败，
            // 表现就是"永远等不到模板、进不去游戏"。
            out[i] = (((c >> 16) & 0xff) * 77 + ((c >> 8) & 0xff) * 151 + (c & 0xff) * 28) >> 8;
        }
        return out;
    }



    // ---------------------------------------------------------------- ROI 检索（攻防引擎用）

    /** 模板名 → 归一化 ROI 框 {a,b,c,d}，来自 clicker/assault_v1/templates.json（34 项）。 */
    private final java.util.HashMap<String, float[]> roiCache = new java.util.HashMap<>();
    private boolean roiLoaded = false;

    /** 读攻防模板登记表；文件缺失时返回 null（调用方退回全屏搜）。 */
    public float[] roiOf(String name) {
        if (!roiLoaded) {
            roiLoaded = true;
            try {
                java.io.InputStream is = am.open("assault_templates.json");
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(is, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();
                JSONObject root = new JSONObject(sb.toString());
                for (java.util.Iterator<String> it = root.keys(); it.hasNext(); ) {
                    String k = it.next();
                    JSONObject o = root.optJSONObject(k);
                    if (o == null || !o.has("box")) continue;
                    JSONArray b = o.getJSONArray("box");
                    if (b.length() == 4) {
                        roiCache.put(k, new float[]{(float) b.getDouble(0), (float) b.getDouble(1),
                                (float) b.getDouble(2), (float) b.getDouble(3)});
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return roiCache.get(name);
    }

    /**
     * 名称归一化：注册表的 key 是 `tpl_xxx`（不带扩展名），调用方习惯写 `tpl_xxx.png`。
     * 2026-10-21 事故：findInRoi 直接拿 "tpl_leave_team.png" 查表 → 永远查不到 → ROI 判据全静默失效
     * （离队检测/队长ID比对/面板验证/踢人计数都不工作），排查了两小时。
     */
    private float[] roiOfNormalized(String name) {
        float[] b = roiOf(name);
        if (b != null) return b;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? roiOf(name.substring(0, dot)) : null;
    }

    /**
     * 只在模板登记表给的 ROI 里搜（快且准）。
     *
     * 照搬 clicker/assault_v1/recognition.py 的 score()/locate()：ROI 四边各外扩
     * 0.008~0.012 容差。找不到返回 null。
     */
    public Match findInRoi(Bitmap screen, String name, double minScore) {
        return findInRoi(screen, name, minScore, false);
    }

    /** 首先原登记局部框，失败仅扩大一次（绝不退回全屏）。 */
    public Match findNear(Bitmap screen, String name, double minScore) {
        if (roiOfNormalized(name) == null) return find(screen, name, minScore);
        Match m = findInRoi(screen, name, minScore, false);
        return m != null || cancelled.getAsBoolean() ? m : findInRoi(screen, name, minScore, true);
    }

    private Match findInRoi(Bitmap screen, String name, double minScore, boolean expand) {
        float[] b = roiOfNormalized(name);
        return findInRegion(screen, name, minScore, b, expand);
    }

    /** 调用方持有独立模板登记时使用；仍只搜局部，最多扩大一次。 */
    public Match findNearRegion(Bitmap screen, String name, double minScore, float[] box) {
        if (box == null || box.length != 4) return null;
        Match m = findInRegion(screen, name, minScore, box, false);
        return m != null || cancelled.getAsBoolean() ? m : findInRegion(screen, name, minScore, box, true);
    }

    private Match findInRegion(Bitmap screen, String name, double minScore, float[] b, boolean expand) {
        if (screen == null || b == null) return null;
        long t0 = System.nanoTime();
        try {
            int sw = screen.getWidth(), sh = screen.getHeight();
            double px = expand ? 0.035 : 0.012, py = expand ? 0.025 : 0.008;
            int x0 = Math.max(0, (int) ((b[0] - px) * sw));
            int y0 = Math.max(0, (int) ((b[1] - py) * sh));
            int x1 = Math.min(sw, (int) ((b[2] + px) * sw));
            int y1 = Math.min(sh, (int) ((b[3] + py) * sh));
            if (x1 - x0 < 4 || y1 - y0 < 4) return null;
            android.graphics.Bitmap roi = android.graphics.Bitmap.createBitmap(screen, x0, y0, x1 - x0, y1 - y0);
            searchIsRoi = true;
            Match m;
            try { m = findScaled(roi, name, minScore, sw, sh); }
            finally { searchIsRoi = false; }
            roi.recycle();
            return m == null ? null : new Match(m.cx + x0, m.cy + y0, m.w, m.h, m.score, sw, sh);
        } finally {
            // findScaled已统计一次，禁止外层重复计时。
        }
    }

    public boolean hasRoi(Bitmap screen, String name, double minScore) {
        return findInRoi(screen, name, minScore) != null;
    }

    /** ID仅裁局部颜色字形；每次查看好友时调用，不随截屏持续运行。 */
    IdGlyph idGlyph(Bitmap screen, boolean own) {
        float[] box = roiOf(own ? "role_id_glyph" : "friend_id_glyph");
        if (screen == null || box == null) return null;
        int sw = screen.getWidth(), sh = screen.getHeight();
        int x0 = Math.max(0, (int) (box[0] * sw)), y0 = Math.max(0, (int) (box[1] * sh));
        int x1 = Math.min(sw, (int) (box[2] * sw)), y1 = Math.min(sh, (int) (box[3] * sh));
        int w = x1 - x0, h = y1 - y0;
        if (w <= 0 || h <= 0) return null;
        int[] pixels = new int[w * h];
        screen.getPixels(pixels, 0, w, x0, y0, w, h);
        return IdGlyph.extract(pixels, w, h, own, Math.max(1, sw / 540));
    }

    /** 一块「差不多是这个颜色」的连通区域（flood fill 结果）。 */
    public static final class Blob {
        public int x, y, w, h, pixels;

        Blob(int x, int y, int w, int h, int pixels) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.pixels = pixels;
        }
    }

    /**
     * 在 roi（归一化区域：lx,ty,rx,by ∈ [0,1]）内找满足 HSV 范围的最大连通块集合。
     *
     * @param hsvLow  低阈值 {h,s,v}（度%、%）
     * @param hsvHigh 高阈值
     * @param minArea 最小面积（占屏幕比例）
     * @return 按面积降序的区域列表
     */
    public List<Blob> blobs(Bitmap screen, float[] roi, float[] hsvLow, float[] hsvHigh,
                            double minArea, int mergePx) {
        int w = screen.getWidth(), h = screen.getHeight();
        int x0 = Math.max(0, (int) (roi[0] * w)), y0 = Math.max(0, (int) (roi[1] * h));
        int x1 = Math.min(w, (int) (roi[2] * w)), y1 = Math.min(h, (int) (roi[3] * h));
        if (x1 - x0 < 4 || y1 - y0 < 4) return new ArrayList<>();
        int rw = x1 - x0, rh = y1 - y0;
        int[] px = new int[rw * rh];
        screen.getPixels(px, 0, rw, x0, y0, rw, rh);
        int[] hsv = new int[3];
        boolean[] mask = new boolean[rw * rh];
        int minPx = (int) (minArea * w * h);
        List<Blob> out = new ArrayList<>();
        boolean[] seen = new boolean[rw * rh];
        for (int idx = 0; idx < mask.length; idx++) {
            if (seen[idx]) continue;
            int c = px[idx];
            colorToHsv(c, hsv);
            seen[idx] = true;
            if (inRange(hsv, hsvLow, hsvHigh)) {
                // 迭代 flood fill（不用递归，栈安全）
                java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
                stack.push(idx);
                int minx = Integer.MAX_VALUE, miny = Integer.MAX_VALUE, maxx = 0, maxy = 0, cnt = 0;
                seen[idx] = true;
                while (!stack.isEmpty()) {
                    int p = stack.pop();
                    int px_ = p % rw, py_ = p / rw;
                    cnt++;
                    if (px_ < minx) minx = px_;
                    if (px_ > maxx) maxx = px_;
                    if (py_ < miny) miny = py_;
                    if (py_ > maxy) maxy = py_;
                    for (int d = 0; d < 4; d++) {
                        int nx = px_ + (d == 0 ? -1 : d == 1 ? 1 : 0);
                        int ny = py_ + (d == 2 ? -1 : d == 3 ? 1 : 0);
                        if (nx < 0 || ny < 0 || nx >= rw || ny >= rh) continue;
                        int q = ny * rw + nx;
                        if (seen[q]) continue;
                        seen[q] = true;
                        colorToHsv(px[q], hsv);
                        if (inRange(hsv, hsvLow, hsvHigh)) stack.push(q);
                    }
                }
                if (cnt >= minPx && mergePx <= 0) {
                    out.add(new Blob(x0 + minx, y0 + miny, maxx - minx + 1, maxy - miny + 1, cnt));
                }
            }
        }
        java.util.Collections.sort(out, (a, b) -> Integer.compare(b.pixels, a.pixels));
        return out;
    }

    private static boolean inRange(int[] h, float[] lo, float[] hi) {
        return h[0] >= lo[0] && h[0] <= hi[0] && h[1] >= lo[1] && h[1] <= hi[1]
                && h[2] >= lo[2] && h[2] <= hi[2];
    }

    private static void colorToHsv(int c, int[] out) {
        int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        int d = max - min;
        float hue;
        if (d == 0) hue = 0;
        else if (max == r) hue = ((g - b) / (float) d + 6f) % 6f;
        else if (max == g) hue = (b - r) / (float) d + 2f;
        else hue = (r - g) / (float) d + 4f;
        out[0] = (int) (hue * 60);
        out[1] = max == 0 ? 0 : (int) (100f * d / max);
        out[2] = max * 100 / 255;
    }
}
