#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""江湖OL助手 —— App 验收脚本。

做的事情就一条链：**安装 → 启动 → 把三条日志通道全抓一遍 → 截图**，
然后对结果做断言。目的是让「App 能装、能跑、日志能看」这件事
有可复现的命令 + 实际输出，而不是靠印象。

用法：
    python android/tools/app_test.py [serial]

默认 serial 取 emulator-5556。
"""
import os
import re
import subprocess
import sys
import time

PKG = "com.jianghuol.helper"
ACT = "com.jianghuol.helper/.MainActivity"
LOG_PATH = "/sdcard/Android/data/%s/files/logs/app.log" % PKG
LOGCAT_TAG = "JiangHuOL"

HERE = os.path.dirname(os.path.abspath(__file__))
ANDROID_DIR = os.path.dirname(HERE)
APK = os.path.join(ANDROID_DIR, "app", "build", "outputs", "apk", "debug",
                   "app-debug.apk")
SHOT_DIR = os.path.join(ANDROID_DIR, "_shots")


def sh(args, serial=None, timeout=120):
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd += args
    p = subprocess.run(cmd, capture_output=True, timeout=timeout)
    return p.returncode, p.stdout, p.stderr


def dec(b):
    if b is None:
        return ""
    for enc in ("utf-8", "gbk", "latin-1"):
        try:
            return b.decode(enc)
        except Exception:
            continue
    return repr(b)


def step(msg):
    print("\n" + "=" * 66)
    print("  " + msg)
    print("=" * 66)


def main():
    serial = sys.argv[1] if len(sys.argv) > 1 else "emulator-5556"
    fails = []

    step("0. 前置检查")
    rc, out, err = sh(["devices"], None)
    print(dec(out).strip())
    if serial not in dec(out):
        print("!! 设备 %s 不在线，终止" % serial)
        return 1
    if not os.path.exists(APK):
        print("!! APK 不存在: %s" % APK)
        return 1
    size = os.path.getsize(APK)
    print("APK: %s  (%.2f MB)" % (APK, size / 1024.0 / 1024.0))

    step("1. 安装 (adb install -r)")
    rc, out, err = sh(["install", "-r", APK], serial, timeout=180)
    text = dec(out) + dec(err)
    print(text.strip())
    if "Success" not in text:
        fails.append("install 未返回 Success")
    if rc != 0:
        fails.append("install 退出码 %d" % rc)

    step("2. 清理旧日志（让本次输出干净可判定）")
    sh(["shell", "run-as", PKG, "rm", "-f", "files/logs/app.log"], serial)
    rc, out, err = sh(["shell", "rm", "-f", LOG_PATH], serial)
    # 同一个文件两种删法都试（debug 包 run-as 可用；外部目录直接 rm 亦可）
    rc, out, err = sh(["shell", "ls", "-l", LOG_PATH], serial)
    print("删除后 ls: %s" % (dec(out) + dec(err)).strip())

    step("3. 启动 (am start)")
    rc, out, err = sh(["shell", "am", "start", "-n", ACT], serial, timeout=60)
    text = dec(out) + dec(err)
    print(text.strip())
    if "Error" in text or "does not exist" in text:
        fails.append("am start 失败")

    step("4. 等 12s 让自检 + 心跳产日志")
    time.sleep(12)

    step("5. 进程是否还活着（不崩就是活着）")
    rc, out, err = sh(["shell", "pidof", PKG], serial)
    pid = dec(out).strip()
    print("pidof %s -> %r" % (PKG, pid))
    if not pid:
        fails.append("进程不存在（可能已崩溃）")

    step("6. 抓日志通道 A：落盘文件")
    rc, out, err = sh(["shell", "cat", LOG_PATH], serial)
    file_log = dec(out)
    print("文件大小 %d 字节，前 40 行：" % len(file_log))
    print("\n".join(file_log.splitlines()[:40]))
    if len(file_log) < 100:
        fails.append("日志文件内容过少（%d 字节）" % len(file_log))

    step("7. 抓日志通道 B：logcat")
    rc, out, err = sh(["logcat", "-d", "-s", LOGCAT_TAG], serial, timeout=120)
    lc = dec(out)
    lc_lines = [l for l in lc.splitlines() if LOGCAT_TAG in l]
    print("logcat 命中 %d 行，前 15 行：" % len(lc_lines))
    print("\n".join(lc_lines[:15]))
    if len(lc_lines) < 3:
        fails.append("logcat 命中行数过少（%d）" % len(lc_lines))

    step("8. 抓日志通道 C：界面截图")
    os.makedirs(SHOT_DIR, exist_ok=True)
    shot = os.path.join(SHOT_DIR, "app_main.png")
    with open(shot, "wb") as f:
        subprocess.run(["adb", "-s", serial, "exec-out", "screencap", "-p"],
                       stdout=f, timeout=60)
    print("截图: %s (%d 字节)" % (shot, os.path.getsize(shot)))
    if os.path.getsize(shot) < 5000:
        fails.append("截图异常（可能黑屏）")

    step("9. 内容断言")
    checks = [
        ("含启动横幅", "启动" in file_log),
        ("含环境自检", "环境自检" in file_log),
        ("含设备信息", "设备:" in file_log),
        ("含屏幕分辨率", "屏幕:" in file_log),
        ("含 ABI", "ABI:" in file_log),
        ("含日志路径", "日志文件:" in file_log),
        ("含心跳输出", "心跳" in file_log and "tick=" in file_log),
    ]
    for name, ok in checks:
        print("  [%s] %s" % ("OK " if ok else "!! ", name))
        if not ok:
            fails.append("内容断言失败: %s" % name)

    step("结果")
    if fails:
        for f in fails:
            print("  FAIL  %s" % f)
        print("\n=> 验收未通过（%d 项）" % len(fails))
        return 1
    print("  全部通过")
    print("\n日志三通道都可用：")
    print("  A 文件  adb -s %s shell cat %s" % (serial, LOG_PATH))
    print("  B logcat adb -s %s logcat -s %s" % (serial, LOGCAT_TAG))
    print("  C 界面  App 内直接看（截图 %s）" % shot)
    return 0


if __name__ == "__main__":
    sys.exit(main())
