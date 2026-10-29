"""
dialogs.py — 系统/游戏弹窗处理（按文字，不按坐标）

单独成层的原因：弹窗是「横切关注点」——登录、选区、组队、重复操作每一步都可能撞上它，
把它塞进流程代码里会导致每个流程各自实现一遍。这里只暴露一个 `dismiss()`。

**2026-10-02 实测修掉的真 bug**：原实现用 `t in ACCEPT_TEXTS` 做**精确匹配**，
而安卓权限弹窗给的是全大写 `ALLOW`，集合里写的是 `Allow` —— 于是
`dismiss_system_dialogs()` 在权限弹窗前完全失效，游戏被 3 个权限弹窗挡住、
后续每一步都点空。现在统一走 `norm()`（去空白 + 转大写）比较，不再依赖大小写。
"""
import re
import time

import uia

# 点击优先序：先「取消」类的吗？不是 —— 见下。
# 规则：**先接受、后取消**。
#   * 权限弹窗 {DENY, ALLOW} 必须点 ALLOW，否则游戏会弹「未授予权限，无法正常游戏」；
#   * 退出确认 {取消, 确定} 里「确定」= 真退出，所以「取消」类要优先于会退出的确认类。
# 两者冲突，用「白名单 + 黑名单」解决：会退出的文字进 EXIT_TEXTS，永远不点。
ACCEPT_TEXTS = (
    "OK", "确定", "确认", "继续", "同意", "允许", "好的", "下载", "安装",
    "ALLOW", "YES", "AGREE", "GOTIT", "知道了", "开始使用", "去开启", "重试",
)
CANCEL_TEXTS = ("取消", "CANCEL", "稍后", "以后再说", "LATER", "NOTNOW", "暂不")
# 点到这些会退出游戏/丢失进度，任何时候都不点
EXIT_TEXTS = ("退出游戏", "退出程序", "EXIT", "QUIT")


def norm(s: str) -> str:
    """归一化：去掉所有空白并转大写。`ALLOW` / `Allow` / `GOT IT` / `GOTIT` 都归一。"""
    return re.sub(r"\s+", "", s or "").upper()


_ACC = {norm(x) for x in ACCEPT_TEXTS}
_CAN = {norm(x) for x in CANCEL_TEXTS}
_EXIT = {norm(x) for x in EXIT_TEXTS}


def classify(text: str) -> str:
    """把按钮文字分成 accept / cancel / exit / other。"""
    n = norm(text)
    if not n:
        return "other"
    if n in _EXIT:
        return "exit"
    if n in _ACC:
        return "accept"
    if n in _CAN:
        return "cancel"
    return "other"


def dismiss(inter, max_tries: int = 6, log=print) -> int:
    """关掉当前所有系统/游戏弹窗，返回实际关掉的数量。

    只点击 uiautomator 能读到的**原生**弹窗；游戏 Canvas 弹窗读不到文字，
    这一步会直接返回（由业务侧的模板步骤负责，例如 tpl_x / tpl_confirm）。
    """
    closed = 0
    for _ in range(max_tries):
        btns = uia.buttons(uia.ui_dump(inter))
        if not btns:
            return closed
        pick = None
        for cls in ("accept", "cancel"):        # 先接受后取消
            for t, c in btns:
                if classify(t) == cls:
                    pick = (t, c)
                    break
            if pick:
                break
        if pick is None:
            return closed
        log(f"  [弹窗] 点「{pick[0]}」@{pick[1]}")
        inter.tap(*pick[1])
        closed += 1
        time.sleep(1.5)
    return closed
