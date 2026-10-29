"""
align_steps.py — 登录后的「汇合襄阳城」步骤（S3/S4）+ 自纠。v4

v4（2026-10-22 用户实机反馈）：**地图名作主判据**（右上角「襄阳城」，稳定 UI，
离线 1.000/0.025 完美分离），在城 = 零点击返回；"走一步"只发生在客栈内。
v3 的教训：在城里走那一步会点中 NPC（林辕），既误触又费时。

对应 docs/align_logic.md。
"""
import os
import time
import cv2

import config
from steps import Step

_HERE = os.path.dirname(os.path.abspath(__file__))
A = config.ALIGN

_TPL540 = None
_TPLMAP = None


def _tpl540():
    """传送圈 strip 模板（540 尺度）。"""
    global _TPL540
    if _TPL540 is None:
        t = cv2.imread(os.path.join(_HERE, A["strip_tpl"]), 0)
        _TPL540 = cv2.resize(t, None, fx=A["match_scale"], fy=A["match_scale"],
                             interpolation=cv2.INTER_AREA)
    return _TPL540


def _tpl_map():
    """「襄阳城」地图名模板（1080 原生 128x40 → 540 空间 64x20）。"""
    global _TPLMAP
    if _TPLMAP is None:
        t = cv2.imread(os.path.join(_HERE, "tpl_map_city.png"), 0)
        _TPLMAP = cv2.resize(t, (64, 20), interpolation=cv2.INTER_AREA)
    return _TPLMAP


def _strip_score_of(img):
    """strip 模板在底部中央 ROI（540 空间）的最高分。img=None 返回 None。"""
    if img is None:
        return None
    small = cv2.cvtColor(cv2.resize(img, (540, 960)), cv2.COLOR_BGR2GRAY)
    x0r, y0r, x1r, y1r = A["roi"]
    roi = small[int(y0r * 960):int(y1r * 960), int(x0r * 540):int(x1r * 540)]
    t = _tpl540()
    if roi.shape[0] <= t.shape[0] or roi.shape[1] <= t.shape[1]:
        return None
    return float(cv2.matchTemplate(roi, t, cv2.TM_CCOEFF_NORMED).max())


def city_map_score(img):
    """右上角地图名「襄阳城」的匹配分（540 空间固定小 ROI，位置像素级稳定）。"""
    if img is None:
        return None
    small = cv2.cvtColor(cv2.resize(img, (540, 960)), cv2.COLOR_BGR2GRAY)
    roi = small[86:111, 470:539]
    t = _tpl_map()
    if roi.shape[0] < t.shape[0] or roi.shape[1] < t.shape[1]:
        return None
    return float(cv2.matchTemplate(roi, t, cv2.TM_CCOEFF_NORMED).max())


def strip_score(ctx):
    """传送圈 strip 分数 + 原始帧。"""
    img = ctx.shot("_al.png")
    if img is None:
        return -1.0, None
    return _strip_score_of(img), img


class MapCityStep(Step):
    """主判据：右上角地图名 == 「襄阳城」→ 已汇合，零点击。"""
    name = "已在襄阳城(地图名)"
    priority = 4

    def detect(self, ctx):
        img = ctx.shot("_al_city.png")
        if img is None or not ctx.rec.is_in_game(img):
            return False
        s = city_map_score(img)
        self._img = img
        return s is not None and s >= 0.80

    def act(self, ctx):
        path = ctx.flags.get("evidence_path")
        if path:
            cv2.imwrite(path, self._img)
        ctx.log(f"  ✅ 地图名=襄阳城 → 已汇合（零点击）；证据图 {path}")
        ctx.done = True
        ctx.result = True


class InnTeleportStep(Step):
    """在客栈（圈可见 = 角色不在圈上）：直接点蓝圈传送。"""
    name = "客栈传送"
    priority = 5
    terminal = False

    def detect(self, ctx):
        s, img = strip_score(ctx)
        if img is None or not ctx.rec.is_in_game(img):
            return False
        return s >= A["threshold"]

    def act(self, ctx):
        ctx.log("  [客栈] 传送圈可见 → 直接点蓝圈传送")
        ctx.tap_rel(*A["circle_tap"])
        time.sleep(A["after_tap_wait"])

    def verify(self, ctx):
        img = ctx.shot("_al_v.png")
        s = city_map_score(img)
        return s is not None and s >= 0.80      # 成功判据：地图名进城


class InnOccludedStep(Step):
    """不在城、圈又看不见 → 圈被角色挡住：走一步让位（客栈内走路无害）。"""
    name = "让位找圈"
    priority = 6
    terminal = False

    def detect(self, ctx):
        img = ctx.shot("_al_occ.png")
        if img is None or not ctx.rec.is_in_game(img):
            return False
        ms = city_map_score(img)
        if ms is not None and ms >= 0.80:
            return False                         # 其实在城，归 MapCityStep
        s = _strip_score_of(img)
        return s is not None and s < A["threshold"]

    def act(self, ctx):
        ctx.log("  [客栈] 圈被挡住 → 走一步让位")
        ctx.tap_rel(*A["walk_aside"])
        time.sleep(2.5)

    def verify(self, ctx):
        return True


def build_align_runner(ctx):
    """对图阶段的 Runner（独立于登录 Steps）。"""
    from steps import Runner
    return Runner(ctx, steps=[MapCityStep(), InnTeleportStep(), InnOccludedStep()],
                  deadline=A["deadline"])


def heal_once(ctx):
    """自纠一轮：右上半屏找 ✕ 点掉（关不掉就按候选坐标兜底）。返回是否点了东西。"""
    tapped = False
    img = ctx.shot("_al_heal.png")
    if img is None:
        return tapped
    for tpl_name, hint, pad in (
        ("tpl_x.png",          (0.86, 0.10), (0.24, 0.22)),
        ("tpl_dlg_close.png",  (0.850, 0.403), (0.075, 0.050)),
    ):
        c = ctx.find_near(tpl_name, hint=hint, pad=pad, threshold=0.86, img=img)
        if c:
            ctx.log(f"  [自纠] 右上发现 ✕ ({tpl_name}) @{c} → 点掉")
            ctx.tap(*c)
            time.sleep(1.2)
            tapped = True
    return tapped
