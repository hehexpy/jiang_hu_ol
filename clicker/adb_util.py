"""
adb_util.py — 安卓模拟器/真机交互底层模块 (已针对本环境验证)

封装了在 Windows 沙箱 + adb server 随任务结束而重建的环境下稳定运行的正确模式：
  1) 每次操作前等待设备就绪 (adb server 重建后约需 1-3 秒握手，过早调用会命中 "device offline")
  2) screencap 通过 Python 二进制流写入文件，避免 PowerShell 7 文本重定向破坏 PNG
  3) 点击/滑动/按键 均走 adb shell input

依赖：adb 在 PATH 中；Python 3.8+ (仅用标准库)
"""
import os
import subprocess
import time

SERIAL = os.environ.get("ADB_SERIAL", "emulator-5554")
_ADB = ["adb", "-s", SERIAL]


def set_serial(s: str) -> None:
    """动态切换目标设备序列号（不影响其他模块）。"""
    global SERIAL, _ADB
    SERIAL = s
    _ADB = ["adb", "-s", s]


def _run(args: list[str], timeout: int = 60) -> subprocess.CompletedProcess:
    """执行 adb 命令，返回 CompletedProcess。超时抛异常。"""
    return subprocess.run(_ADB + args, capture_output=True, timeout=timeout)


def wait_device(timeout: float = 60.0) -> bool:
    """循环等待设备进入 device 状态。用于规避 adb server 重建后的 offline 窗口。"""
    start = time.time()
    while time.time() - start < timeout:
        try:
            r = subprocess.run(
                _ADB + ["get-state"], capture_output=True, timeout=10
            )
            if r.stdout.decode(errors="replace").strip() == "device":
                return True
        except Exception:
            pass
        time.sleep(2)
    return False


def tap(x: float, y: float) -> subprocess.CompletedProcess:
    """点击屏幕坐标 (x, y)。"""
    wait_device(25)
    return _run(["shell", "input", "tap", str(int(x)), str(int(y))], timeout=50)


def swipe(x1: float, y1: float, x2: float, y2: float, duration_ms: int = 300) -> subprocess.CompletedProcess:
    """从 (x1,y1) 滑动到 (x2,y2)。"""
    wait_device(25)
    return _run(
        ["shell", "input", "swipe",
         str(int(x1)), str(int(y1)), str(int(x2)), str(int(y2)), str(int(duration_ms))],
        timeout=50,
    )


def keyevent(code: int) -> subprocess.CompletedProcess:
    """发送按键事件 (如 3=HOME, 4=BACK)。"""
    wait_device(25)
    return _run(["shell", "input", "keyevent", str(code)], timeout=50)


def screencap(path: str) -> str:
    """截取当前屏幕并保存到本地 path (二进制安全的 PNG)。返回 path。"""
    wait_device(25)
    with open(path, "wb") as f:
        subprocess.run(
            _ADB + ["exec-out", "screencap", "-p"], stdout=f, check=True, timeout=60
        )
    return path


def foreground_activity() -> str:
    """返回当前前台窗口描述字符串 (用于判断是否在游戏内)。"""
    wait_device(25)
    r = _run(["shell", "dumpsys", "window"], timeout=40)
    for line in r.stdout.decode(errors="replace").splitlines():
        if "mCurrentFocus" in line:
            return line.strip()
    return ""


def screen_size() -> tuple[int, int]:
    """返回 (宽, 高)。"""
    wait_device(25)
    r = _run(["shell", "wm", "size"], timeout=30)
    txt = r.stdout.decode(errors="replace")
    # 形如 "Physical size: 1080x2340"
    for line in txt.splitlines():
        if "Physical size" in line and "x" in line:
            w, h = line.split(":")[-1].strip().split("x")
            return int(w), int(h)
    return (1080, 2340)
