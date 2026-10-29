"""
auto_login.py — 登录流程「门面」（facade）

## 这个文件曾经是什么

699 行的上帝模块：流程 + 状态机 + 弹窗 + uiautomator + 网络 + 模板匹配全塞在一起，
并且依赖模块级全局 `_inter` / `_rec` / `_GAME_BOUNDS`，两条登录路径（线性 `auto_login`
与状态机 `auto_login_sm`）各写一遍、逻辑互相分叉。

## 现在它只做三件事

1. **组装** `Ctx`（依赖显式注入，不再有模块全局影响流程）；
2. **注册步骤 + 跑 Runner**（步骤见 `login_steps.py`，框架见 `steps.py`）；
3. **保留旧接口**做兼容（`menu.py` 只用 `auto_login.auto_login(...)`，脚本可能用到其余的）。

## 各层去哪了

| 原来的东西 | 现在在哪 |
|---|---|
| `_ui_dump` / `_buttons` / `_edit_ui` / `_type_field` | `uia.py` |
| `dismiss_system_dialogs` / `ACCEPT_TEXTS` | `dialogs.py`（并修掉了 `ALLOW` 大小写匹配不上的 bug） |
| `fix_network`（无条件报成功） | `net.py`（真验证 + 检查返回码） |
| 状态机 `auto_login_sm` 的长 if-else 链 | `login_steps.py` 的一个个 `Step` |
| `select_server` / `find_and_enter_server` / `_tap_count_row` | **删除**，由 `login_steps.ServerSelectStep` 统一 |
| 线性 `auto_login` 的重复实现 | 与状态机合并为同一个 `login()` |

## 加新功能怎么做（低耦合的重点）

写一个 `Step` 子类（`detect` / `act` / `verify`），在 `login_steps.register_all()` 里注册一行。
**不要回到这里改流程** —— 这个文件应当长期保持现状。
"""
import os
import sys
import time

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np

import config
import dialogs
import login_steps
import net
import steps as st
import uia
from interaction import Interaction, AdbInteraction
from recognition import Recognizer, OpenCVRecognizer
from steps import Account, Ctx

PKG = "com.good.mmo2"
ACT = "com.good.mmo2.PipActivity"
_BASE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_DEADLINE = 180.0

_inter: Interaction = AdbInteraction()
_rec: Recognizer = OpenCVRecognizer()
_GAME_BOUNDS: tuple | None = None


def configure(inter: Interaction | None = None, rec: Recognizer | None = None):
    """切换交互/识别后端（换注入、换模型时的唯一入口）。"""
    global _inter, _rec
    if inter:
        _inter = inter
    if rec:
        _rec = rec


# ---------------------------------------------------------------- 屏幕/坐标
def detect_game_bounds(img) -> tuple:
    """检测游戏可视区（刨去 letterbox）。纯图像计算，换设备不用改。"""
    import cv2
    h, w = img.shape[:2]
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    rows = np.where(gray.max(axis=1) > 45)[0]
    if len(rows) == 0:
        return (0, 0, w, h)
    y0, y1 = int(rows.min()), int(rows.max())
    cols = np.where(gray.max(axis=0) > 45)[0]
    x0, x1 = int(cols.min()), int(cols.max())
    if (x1 - x0) >= 0.95 * w and (y1 - y0) >= 0.95 * h:
        return (0, 0, w, h)
    return (x0, y0, x1, y1)


def refresh_game_bounds() -> tuple:
    global _GAME_BOUNDS
    _GAME_BOUNDS = detect_game_bounds(shot("_bounds.png"))
    return _GAME_BOUNDS


def tap_rel(fx, fy):
    """相对坐标点击。有游戏可视区就按可视区映射（规避 letterbox），否则按整屏。"""
    global _GAME_BOUNDS
    if _GAME_BOUNDS:
        x0, y0, x1, y1 = _GAME_BOUNDS
        x, y = int(x0 + fx * (x1 - x0)), int(y0 + fy * (y1 - y0))
    else:
        w, h = _inter.screen_size()
        x, y = int(fx * w), int(fy * h)
    _inter.tap(x, y)
    return x, y


def shot(name="_auto.png"):
    return _inter.capture(os.path.join(_BASE, name))


def in_game(img=None):
    return _rec.is_in_game(img if img is not None else shot())


def find_btn(tpl_name, threshold=0.85, min_y_frac=0.0):
    return _rec.find_button(shot(), os.path.join(_BASE, tpl_name), threshold, min_y_frac)


def find_btn_near(tpl_name, hint=None, pad=None, threshold=None):
    reg_hint, reg_pad = config.BUTTON_HINTS.get(tpl_name, (None, config.HINT_PAD_DEFAULT))
    hint = reg_hint if hint is None else hint
    pad = reg_pad if pad is None else pad
    if hint is None:
        return None
    th = config.HINT_THRESHOLD if threshold is None else threshold
    return _rec.find_button_near(shot("_near.png"), os.path.join(_BASE, tpl_name), hint, pad, th)


def _tap_tpl_or(tpl_name, fallback_pos, min_y_frac=0.0, pad=None):
    c = find_btn_near(tpl_name, pad=pad)
    if c is None:
        c = find_btn(tpl_name, threshold=0.85, min_y_frac=min_y_frac)
    if c:
        _inter.tap(*c)
        return True
    tap_rel(*fallback_pos)
    return False


# ---------------------------------------------------------------- 兼容层
# 以下函数保留旧名字，内部转调新模块。**新代码请直接用 uia / dialogs / net。**

def fix_network():
    """兼容旧名。原来无条件打印「已配置」却什么也没做成，现在会实测并如实报告。"""
    rep = net.ensure_online(_inter)
    return rep.ok


def _ui_dump():
    return uia.ui_dump(_inter)


def _buttons(xml):
    return uia.buttons(xml)


def _edit_ui(xml):
    return uia.edit_form(xml)


def _type_field(center, text):
    return uia.type_field(_inter, center, text)


def _hide_keyboard():
    return uia.hide_keyboard(_inter)


def _clear():
    return uia.clear_field(_inter)


ACCEPT_TEXTS = set(dialogs.ACCEPT_TEXTS)
CANCEL_TEXTS = set(dialogs.CANCEL_TEXTS)


def dismiss_system_dialogs(max_tries=8):
    return dialogs.dismiss(_inter, max_tries)


def ensure_credentials(username, password, force=False):
    """确保登录框里是目标账号。走 uiautomator（登录框是原生控件）。"""
    form = uia.edit_form(uia.ui_dump(_inter))
    if form["user"] is None or form["pass"] is None:
        print("  [账号] 未读到原生登录框（可能还没弹出）")
        return False
    cur = (form["user"] or {}).get("text", "")
    if (not force) and cur == username and (form["pass"] or {}).get("text"):
        print(f"  [账号] 已填 {username}，无需重填")
        if form["back"]:
            _inter.tap(*form["back"])
        return True
    print(f"  [账号] 输入 {username}（当前='{cur}'）")
    uia.type_field(_inter, form["user"]["center"], username)
    uia.type_field(_inter, form["pass"]["center"], password)
    uia.hide_keyboard(_inter)
    if form["ok"]:
        _inter.tap(*form["ok"])
    time.sleep(2)
    return True


def _detect_login_state(pkg=None):
    """诊断用：当前界面状态字符串（detect2）。**流程控制不使用它**，见 steps.py 的说明。"""
    from detect2 import detect_state2
    try:
        xml = _ui_dump()
    except Exception:
        xml = ""
    return detect_state2(shot("_sm.png"), xml)


# ---------------------------------------------------------------- 流程
def relaunch(pkg=PKG, act=ACT):
    """冷启动：强停 -> 拉起 -> 关系统弹窗 -> 点「点击继续」。"""
    if not _inter.wait_device(45):
        raise RuntimeError("设备未就绪")
    _inter.shell("am", "force-stop", pkg)
    time.sleep(1)
    _inter.shell("am", "start", "-n", f"{pkg}/{act}")
    print(f"[步骤1] 重启应用 {pkg}……")
    time.sleep(14)
    dialogs.dismiss(_inter, max_tries=6)
    print("[步骤1.5] 已处理系统弹窗")
    b = refresh_game_bounds()
    print(f"[步骤1.6] 游戏区域 = {b}")
    tap_rel(*config.POINTS["continue_splash"])
    print("[步骤2] 点击「点击继续」")
    time.sleep(4)


def build_ctx(username, password, server=None, pkg=PKG, activity=ACT) -> Ctx:
    return Ctx(
        inter=_inter, rec=_rec, cfg=config, base_dir=_BASE,
        account=Account(username=username, password=password, server=server,
                        pkg=pkg, activity=activity),
        bounds=_GAME_BOUNDS,
    )


def login(username: str, password: str, server: str | None = None,
          serial: str | None = None, pkg: str = PKG, activity: str = ACT,
          cold_start: bool = True, check_network: bool = True,
          deadline: float = DEFAULT_DEADLINE, show_steps: bool = True) -> bool:
    """登录一个账号并进入目标区。返回是否真正进到游戏世界。

    与旧版的区别：
      * 线性/状态机两条路径合并成这一条（不再分叉）；
      * **开机先验网络**（旧线性路径从不修网络，实测直接导致「连接失败」）；
      * 结束时打印步骤统计与轨迹，失败点一眼可见。
    """
    global _GAME_BOUNDS
    if serial:
        _inter.set_serial(serial)
    print(f"== 登录 {username} (pkg={pkg} 区={server or '未指定'}) ==")

    # ---- 入口 fail-fast：目标区没登记就立刻停 ----
    # 否则会走进「点切换 -> 兜底乱点」直到超时 —— 2026-10-02 实测：`server=None` 时
    # `ServerSelectStep.detect` 抛的 StepFailure 被 `Runner.pick` 静默吞掉，真正的病因
    # 埋在最后那句"没跑完"里。问题 #9 的修复：配置类错误必须在入口就现形。
    if not server:
        print("❌ 未指定目标区（server=None）—— 无法校验进的区，立刻停止")
        print("   用法：login(user, pwd, server='九州揽月')")
        return False
    slugmap = getattr(config, "SERVER_SLUG", None) or login_steps.SERVER_ROW_SLUG_FALLBACK
    if server not in slugmap:
        print(f"❌ 目标区「{server}」没有登记模板 slug —— 立刻停止")
        print(f"   已登记：{', '.join(slugmap)}")
        print(f"   加新区：往 config.SERVER_SLUG 加一行 + 补 tpl_server_<slug>.png / tpl_srv_<slug>.png")
        return False

    # 面板区名条模板是「确认登录面板上显示的就是目标区」的**唯一手段**（见 T-013 #1/#3）。
    # 缺了它就无法保证进的区 —— 而那正是本次修复的核心，所以入口 fail-fast，
    # 而不是等到 `_confirm_from_panel` 里才失败（那时日志已经很长、病因难找）。
    panel_tpl = (getattr(config, "SERVER_TPL", None) or {}).get(server) \
        or f"tpl_server_{slugmap[server]}.png"
    if not os.path.exists(os.path.join(_BASE, panel_tpl)):
        have = ', '.join(s for s in slugmap
                         if os.path.exists(os.path.join(_BASE, f"tpl_server_{slugmap[s]}.png")))
        print(f"❌ 目标区「{server}」缺少**面板区名条**模板 {panel_tpl} —— 无法确认进的区，立刻停止")
        print(f"   已具备面板条模板的区：{have}")
        print(f"   补法：截一张该区的登录面板图，裁出区名条（实测在相对 (0.500,0.696)）存为 {panel_tpl}")
        return False

    if not _inter.wait_device(45):
        raise RuntimeError("设备未就绪")

    if check_network:
        rep = net.ensure_online(_inter)
        if not rep.ok:
            print("[网络] ⚠️ 仍不通，登录大概率失败：")
            print(rep.text())

    if cold_start:
        relaunch(pkg, activity)
    else:
        _GAME_BOUNDS = refresh_game_bounds()

    st.unregister_all()
    login_steps.register_all()
    ctx = build_ctx(username, password, server, pkg, activity)
    if show_steps:
        print("== 已注册步骤 ==")
        print(st.describe())

    runner = st.Runner(ctx, deadline=deadline)
    ok = False
    try:
        ok = runner.run()
    except st.ConfigError as e:
        print(f"❌ 配置错误，立刻停止：{e}")
    except st.StepFailure as e:
        print(f"❌ 流程中止：{e}")
    finally:
        print(runner.report())

    # ---- 区的确认情况：让「成功」有可区分的含义 ----
    # 这是问题 #1「终点判据里没有区」的落地。`InGameStep` 只看左上角血条，进了**任何**区
    # 都为真（实测把目标区改成「不存在的区」仍返回 True）。所以这里必须如实分级，
    # 不能只说一句"成功"。
    src = ctx.flags.get("server_confirmed")
    if ok and src:
        print(f"✅ 登录成功：已进入游戏世界，区={server}（由「{src}」确认）")
    elif ok:
        print(f"⚠️ 登录成功（已进游戏世界），但**区={server} 未经确认** —— "
              f"脚本无法自证进的哪个区，请人工核对一次")
    return ok


# 兼容旧名：menu.py 用的是 auto_login.auto_login(...)
def auto_login(username: str, password: str, force: bool = False, serial: str | None = None,
               server: str | None = None, pkg: str = PKG, activity: str = ACT) -> bool:
    return login(username, password, server=server, serial=serial, pkg=pkg, activity=activity)


def auto_login_sm(username, password, serial=None, pkg=PKG, activity=ACT, server=None,
                  deadline=DEFAULT_DEADLINE):
    """兼容旧名。状态机已并入 `login()` —— 不存在第二条路径了。"""
    return login(username, password, server=server, serial=serial, pkg=pkg,
                 activity=activity, deadline=deadline)


def main():
    argv = sys.argv
    a = {}
    for k in ("--user", "--pass", "--serial", "--server"):
        if k in argv:
            a[k] = argv[argv.index(k) + 1]
    ok = login(a.get("--user", config.USERNAME), a.get("--pass", config.PASSWORD),
               server=a.get("--server"), serial=a.get("--serial"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
