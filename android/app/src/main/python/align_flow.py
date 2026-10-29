# -*- coding: utf-8 -*-
"""汇合襄阳城：每次传送前确认客栈和圈，进城即停止点击。

驱动提供 in_city / in_inn / strip_score / tap_rel / py_sleep / py_log，
以及 clear_align_popups / align_stopped。未知地图交还调用方，不猜测角色位置。
"""

THRESHOLD = 0.80
WALK_ASIDE = (0.28, 0.735)
CIRCLE_TAP = (0.521, 0.747)     # 蓝色圆环中心，实测（3 帧一致）
MAX_TELEPORT = 3


def run_align(drv):
    """True仅表示认到襄阳城；最多3次有前置证据的传送。"""
    walked = False
    for attempt in range(MAX_TELEPORT):
        if drv.align_stopped():
            return False
        drv.clear_align_popups()  # 保留原有找✕关闭闭环，然后重新看画面。
        if drv.in_city():
            drv.py_log("PY 地图名=襄阳城 → 停止传送点击 ✓")
            return True
        if not drv.in_inn():
            drv.py_log("PY 未确认襄阳客栈 → 不移动、不点传送位置")
            return False

        score = drv.strip_score()
        if score < THRESHOLD:
            if walked or drv.align_stopped():
                drv.py_log("PY 客栈传送圈仍不可见 → 停止，交回闭环处理")
                return False
            # 只有地图名正向确认客栈，才保留原来的让位动作；每轮最多一次。
            drv.py_log("PY 已确认客栈、圈被挡住 → 让位一次后重新识别")
            drv.tap_rel(WALK_ASIDE[0], WALK_ASIDE[1])
            walked = True
            drv.py_sleep(2.5)
            drv.clear_align_popups()

        # 使用新画面再次确认；上一轮已传送或弹窗遮挡时，不能沿用旧坐标继续点。
        if drv.align_stopped():
            return False
        if drv.in_city():
            return True
        if not drv.in_inn() or drv.strip_score() < THRESHOLD:
            drv.py_log("PY 点击前未同时确认客栈和传送圈 → 停止")
            return False
        if drv.align_stopped():
            return False
        drv.py_log("PY 点蓝圈传送（第 %d 次）" % (attempt + 1))
        drv.tap_rel(CIRCLE_TAP[0], CIRCLE_TAP[1])
        # 最多复核18次，间隔0.5秒（取帧/匹配耗时另计）；进城就结束，不再点同位置。
        for _ in range(18):
            drv.py_sleep(0.5)
            if drv.align_stopped():
                return False
            if drv.in_city():
                drv.py_log("PY 传送成功（地图名=襄阳城）✓")
                return True

    drv.py_log("PY %d 次传送未确认进城 → 失败，不追加兜底点击" % MAX_TELEPORT)
    return False
