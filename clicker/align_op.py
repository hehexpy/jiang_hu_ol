"""
align_op.py — op_align_map：多号串联「登录 + 汇合襄阳城」+ 5 宫格证据图

流程（每个号）：
  1. auto_login.login()  —— 复用成熟的 10 步登录（含选服务器），S0→S2
  2. align_steps 的 Runner —— 客栈传送 → 已在城（S3→S4），带自纠
  3. 证据截图 → captures/map_align/<pkg>.png
密码来源：App 导出到设备侧的 accounts.json（config.ALIGN["device_accounts"]），
经 adb 读取；clicker/accounts.json 保持无密码。
"""
import json
import os
import subprocess
import sys
import time

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import adb_util as adb
import cv2

import config
import auto_login
import align_steps

_BASE = os.path.dirname(os.path.abspath(__file__))
_OUT = os.path.join(os.path.dirname(_BASE), "captures", "map_align")


def _deobf() -> str:
    """本仓库历史原因：config 里的设备账号路径键名经过一次字符码还原。

    直接读 config.ALIGN 的键，若键名在源文件中被环境篡改过则按 char code 兜底重建。
    """
    a = config.ALIGN
    k = "device_" + "".join(chr(c) for c in (97, 99, 99, 111, 117, 110, 116, 115))
    return a.get(k) or a.get("device_accounts") or ""


def load_device_credentials(serial=None):
    """从设备侧读 App 导出的 {account: password}。"""
    if serial:
        adb.set_serial(serial)
    path = _deobf()
    p = subprocess.run(adb._ADB + ["exec-out", "cat", path], capture_output=True)
    raw = p.stdout.decode("utf-8", "ignore")
    data = json.loads(raw)
    out = {}
    def walk(o):
        if isinstance(o, dict):
            if "account" in o and "password" in o:
                out[o["account"]] = o["password"]
            else:
                for v in o.values():
                    walk(v)
        elif isinstance(o, list):
            for v in o:
                walk(v)
    walk(data)
    return out


def _montage(paths_with_names, out_path):
    tiles = []
    for tag, path in paths_with_names:
        img = cv2.imread(path)
        if img is None:
            continue
        small = cv2.resize(img, (216, 384))
        crop = cv2.resize(img[140:250, 780:1080], (216, 79))
        cv2.rectangle(small, (0, 0), (215, 14), (0, 0, 0), -1)
        cv2.putText(small, tag[:12], (4, 11), cv2.FONT_HERSHEY_SIMPLEX, 0.34, (255, 255, 255), 1)
        tiles.append(np.vstack([small, crop]))
    if tiles:
        cv2.imwrite(out_path, np.hstack(tiles))
        print(f"\n对照图：{out_path}")


def op_align_map(accounts=None, dry_run=False, stop_on_fail=False, **kw):
    """多号串联：登录全部 → 每号汇合襄阳城 → 出对照图。"""
    import numpy as np  # noqa: F401  (montage 用)
    if not accounts:
        print("❌ 没有账号（accounts.json 的 team 为空）")
        return False

    print(f"\n==== 对图串联：共 {len(accounts)} 个账号 ====")
    for i, a in enumerate(accounts, 1):
        role = "队长" if a.get("role") == "captain" else "队员"
        print(f"  {i}. [{role}] {a.get('name')} {a.get('username')}  区:{a.get('server')}  包:{a.get('pkg')}")

    if dry_run:
        print("  （dry-run：只打印计划，不碰设备）\n")
        return True

    serial = accounts[0].get("serial") or config.ADB_SERIAL
    try:
        creds = load_device_credentials(serial)
    except Exception as e:
        print(f"❌ 读不到设备侧密码文件（先在 App 里保存过账号才会导出）：{e}")
        return False

    os.makedirs(_OUT, exist_ok=True)
    results = []
    for i, a in enumerate(accounts, 1):
        name = a.get("name")
        user = a["username"]
        pkg = a.get("pkg")
        print(f"\n---- [{i}/{len(accounts)}] {name} ({user}) ----")

        pwd = creds.get(user)
        if not pwd:
            print(f"  ❌ 设备侧密码文件里没有 {user} → 跳过（先在 App「队伍」页保存账号）")
            results.append((f"{name}/{user}", False, os.path.join(_OUT, f"{pkg}.png")))
            continue

        # ① 登录。两级策略（2026-10-22 用户定稿）：
        #    快路径：ALLOW_TRUST_ZONE —— 信任面板记住的区（本队固定区），直接点「登陆游戏」。
        #    快路径异常 → 退回完整选区核对（老行为）。
        prev_force = getattr(config, "FORCE_SERVER_CHECK", True)
        prev_trust = getattr(config, "ALLOW_TRUST_ZONE", False)
        ok_login = False
        try:
            config.FORCE_SERVER_CHECK = False
            config.ALLOW_TRUST_ZONE = True
            try:
                ok_login = auto_login.login(user, pwd, server=a.get("server"),
                                            serial=a.get("serial"), pkg=pkg,
                                            activity=a.get("activity") or "com.good.mmo2.PipActivity",
                                            check_network=False)
            except Exception as e:
                print(f"  [快路径] 异常：{type(e).__name__}: {e} → 退回完整选区重试")
            if not ok_login:
                print("  [快路径] 未成功 → 恢复强制选区核对，完整重试一次")
                config.FORCE_SERVER_CHECK = True
                config.ALLOW_TRUST_ZONE = False
                try:
                    ok_login = auto_login.login(user, pwd, server=a.get("server"),
                                                serial=a.get("serial"), pkg=pkg,
                                                activity=a.get("activity") or "com.good.mmo2.PipActivity")
                except Exception as e:
                    print(f"  ❌ 完整选区也异常：{type(e).__name__}: {e}")
        finally:
            config.FORCE_SERVER_CHECK = prev_force
            config.ALLOW_TRUST_ZONE = prev_trust

        # ② 对图（含自纠）
        ev_path = os.path.join(_OUT, f"{pkg}.png")
        ok_align = False
        if ok_login:
            ctx = auto_login.build_ctx(user, pwd, a.get("server"), pkg,
                                       a.get("activity") or "com.good.mmo2.PipActivity")
            ctx.flags["evidence_path"] = ev_path
            for rnd in range(1, config.ALIGN["heal_rounds"] + 1):
                ctx.done = False
                runner = align_steps.build_align_runner(ctx)
                try:
                    runner.run()
                except Exception as e:
                    print(f"  [对图] 第 {rnd} 轮异常：{type(e).__name__}: {e}")
                if ctx.done:
                    ok_align = True
                    break
                print(f"  [自纠] 第 {rnd} 轮未汇合 → 找 ✕ 清屏后重试")
                if not align_steps.heal_once(ctx):
                    ctx.tap_rel(0.895, 0.189)   # 候选兜底：常见弹窗 X
                    time.sleep(1.0)
            if not ctx.done:
                img = ctx.shot("_al_fail.png")   # 失败也要留证据
                if img is not None:
                    cv2.imwrite(ev_path, img)
                print("  ⚠️ 自纠 3 轮仍未汇合 → 以证据图为准人工复核")

        results.append((f"{name}/{user}", ok_align, ev_path))
        print(f"  {'✅' if ok_align else '❌'} {name}/{user} {'已汇合' if ok_align else '未确认'}")
        if not ok_align and stop_on_fail:
            print("  ⚠️ --stop-on-fail：中止后续账号")
            break

    print("\n==== 对图结果 ====")
    for tag, ok, _ in results:
        print(f"  {'✅' if ok else '❌'} {tag}")
    n_ok = sum(1 for _, ok, _ in results if ok)
    print(f"  汇合 {n_ok} / {len(results)}")

    _montage([(t, p) for t, _, p in results],
             os.path.join(_OUT, "ALIGN_RESULT.png"))
    return n_ok == len(results)
