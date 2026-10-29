"""
operations.py — 自动化操作注册表（选定账号后执行的具体操作）

每个操作是一个可调用对象 op(**cfg)，接收可选的 serial 等参数，负责在游戏内循环执行。
内置一个可用的「保持在线」操作；打怪/弹窗/资源条等需先校准战斗界面后再实现。
"""
import os
import time
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import adb_util as adb


def op_heartbeat(serial=None, interval=60, duration=3600, tap_xy=None, **kw):
    """保持在线/防掉线：定时检测是否仍在游戏内，可选轻微点击防超时。"""
    if serial:
        adb.set_serial(serial)
    print(f"[保持在线] 每 {interval}s 检查一次，最多 {duration}s ({tap_xy or '不点击'})")
    start = time.time()
    while time.time() - start < duration:
        if adb.wait_device(20):
            from detect import _is_in_game
            from vision import load
            img = load(adb.screencap(os.path.join(os.path.dirname(__file__), "_hb.png")))
            ok = _is_in_game(img)
            print(f"  [{time.strftime('%H:%M:%S')}] 在游戏内: {ok}")
            if tap_xy:
                adb.tap(*tap_xy)      # 轻微点击防掉线（默认关闭）
        time.sleep(interval)


def op_auto_farm(serial=None, **kw):
    """自动打怪/刷经验 —— 待实现。需先校准：技能/普攻按钮、怪物区、拾取、血蓝条。"""
    raise NotImplementedError("自动打怪/刷经验：待实现（需先校准战斗界面）")


def op_handle_popups(serial=None, **kw):
    """自动处理弹窗/奖励界面 —— 待实现。需基于画面变化检测 + 点击确定/关闭。"""
    raise NotImplementedError("自动处理弹窗：待实现")


def op_resource_trigger(serial=None, **kw):
    """按资源条状态触发（血/蓝低自动吃药/打坐）—— 待实现。需先定位资源条并标定阈值。"""
    raise NotImplementedError("按资源条触发：待实现")


def op_login_all(accounts=None, dry_run=False, stop_on_fail=False, **kw):
    """多号串联：按 `accounts.json` 的顺序，**依次**把每个账号登进它自己的区。

    为什么需要这一条（`HANDOFF.md` §5-10）：`menu.py` 原先一次只登一个号，
    且没有任何批量入口 —— 「3 个号依次登进去」是本项目的**核心需求本身**，
    属于"还没写"，不是"没验证"。

    为什么能直接串起来、不必手工退号：`login()` 默认 `cold_start=True`，
    每个号登录前都会 `force-stop` 再拉起**它自己的包**，所以天然从登录面板开始，
    不会把上一个号的游戏内状态带进下一个号。代价是每个号多约 15s
    （重启 + 关弹窗 + 点「点击继续」）。

    每个账号用自己的 `serial` / `server` / `pkg`（三者都在 `accounts.json` 里）——
    这正是「多开」的含义：不同号可以落在不同模拟器实例上，互不干扰。

    `dry_run=True` 只打印将要执行的动作序列、**不碰设备**，用于离线验证串联逻辑。
    """
    if not accounts:
        print("❌ 没有账号可登（accounts.json 的 team 为空）")
        return False

    print(f"\n==== 多号串联：共 {len(accounts)} 个账号 ====")
    for i, a in enumerate(accounts, 1):
        role = "队长" if a.get("role") == "captain" else "队员"
        print(f"  {i}. [{role}] {a.get('name')} {a.get('username')}"
              f"  区:{a.get('server')}  设备:{a.get('serial')}  包:{a.get('pkg')}")

    # 静态前置校验：缺字段的号不要去点设备（点一半才炸最难查）
    bad = [a.get("name") for a in accounts
           if not a.get("username") or not a.get("server") or not a.get("serial")]
    if bad:
        print(f"❌ 这些账号缺 username/server/serial，无法串联：{bad}")
        return False

    if dry_run:
        print("  （--dry-run：只打印计划，不碰设备）\n")
        return True

    import auto_login      # 延迟导入：本模块被 import 时不拉起重依赖（cv2 等）

    results = []
    total = len(accounts)
    for i, a in enumerate(accounts, 1):
        print(f"\n---- [{i}/{total}] {a.get('name')} ({a.get('username')}) ----")
        err = None
        try:
            ok = auto_login.login(
                a["username"], a["password"],
                server=a.get("server"),
                serial=a.get("serial"),
                pkg=a.get("pkg", "com.good.mmo2"),
            )
        except Exception as e:                      # 一个号崩了不该让整串崩
            ok = False
            err = f"{type(e).__name__}: {e}"
            print(f"  ❌ 异常：{err}")
        results.append((a.get("name"), ok, err))
        if not ok and stop_on_fail:
            print("  ⚠️ --stop-on-fail：中止后续账号")
            break

    print("\n==== 串联结果 ====")
    for name, ok, err in results:
        print(f"  {'✅' if ok else '❌'} {name}" + (f"   {err}" if err else ""))
    n_ok = sum(1 for _, ok, _ in results if ok)
    print(f"  成功 {n_ok} / {len(results)}")
    return n_ok == len(results)


def op_align_map(accounts=None, dry_run=False, stop_on_fail=False, **kw):
    """多号串联：登录全部账号并汇合襄阳城，产出 5 宫格证据图（详见 align_op.py）。"""
    from align_op import op_align_map as _impl
    return _impl(accounts=accounts, dry_run=dry_run, stop_on_fail=stop_on_fail, **kw)


# 菜单中展示的操作顺序。
# `ready=False` = 尚未实现（只剩壳）。调用方**必须**在"登录设备之前"先看这个标记 ——
# 否则会出现「先花 100 秒把号登进去，再告诉用户"该操作尚未实现"」这种浪费
# （2026-10-05 实测踩到）。
OPERATIONS = [
    {"id": "login", "name": "多号串联：依次登录全部账号", "func": op_login_all, "ready": True},
    {"id": "align_map", "name": "对图串联：登录全部并汇合襄阳城", "func": op_align_map, "ready": True},
    {"id": "heartbeat", "name": "保持在线/防掉线", "func": op_heartbeat, "ready": True},
    {"id": "auto_farm", "name": "自动打怪/刷经验", "func": op_auto_farm, "ready": False},
    {"id": "popup", "name": "自动处理弹窗/奖励", "func": op_handle_popups, "ready": False},
    {"id": "resource", "name": "按资源条触发回血", "func": op_resource_trigger, "ready": False},
]


def get_operation(op_id):
    for op in OPERATIONS:
        if op["id"] == op_id:
            return op
    return None
