"""
interaction.py — 「与设备/游戏交互」的抽象接口 + 默认 adb 实现

目的：把"如何看到画面、如何点击"抽象成 Interaction 接口。
  默认 AdbInteraction 通过 adb 截屏/输入点击（无需 root、稳定）。
  以后想换【注入】交互，只需实现另一个 Interaction（如 InjectionInteraction），
  控制逻辑（auto_login 等）零改动即可切换。

接口方法（全部面向"更高层控制逻辑"，不暴露底层实现）：
  set_serial / wait_device / capture / tap / swipe / keyevent / screen_size / shell / exec_out
"""
import os
import subprocess
import time
from abc import ABC, abstractmethod
import cv2

import adb_util as adb


class Interaction(ABC):
    """设备交互抽象接口。控制逻辑只依赖此接口。"""

    @abstractmethod
    def set_serial(self, serial: str): ...

    @abstractmethod
    def wait_device(self, timeout: float = 45.0) -> bool: ...

    @abstractmethod
    def capture(self, path: str):
        """截取当前屏幕并保存到 path，返回图像(numpy BGR)。"""

    @abstractmethod
    def tap(self, x, y): ...

    @abstractmethod
    def swipe(self, x1, y1, x2, y2, duration_ms: int = 300): ...

    @abstractmethod
    def keyevent(self, code: int): ...

    @abstractmethod
    def screen_size(self): ...

    @abstractmethod
    def shell(self, *args):
        """执行 `adb shell <args>`，返回 CompletedProcess。"""

    @abstractmethod
    def exec_out(self, *args):
        """执行 `adb exec-out <args>`，返回 CompletedProcess。"""


class AdbInteraction(Interaction):
    """基于 adb 的交互实现（无需 root，适配模拟器/USB 调试手机）。"""

    def __init__(self, serial: str | None = None):
        if serial:
            adb.set_serial(serial)

    def set_serial(self, serial: str):
        adb.set_serial(serial)

    def wait_device(self, timeout: float = 45.0) -> bool:
        return adb.wait_device(timeout)

    def capture(self, path: str):
        adb.screencap(path)
        return cv2.imread(path)

    def tap(self, x, y):
        return adb.tap(x, y)

    def swipe(self, x1, y1, x2, y2, duration_ms: int = 300):
        return adb.swipe(x1, y1, x2, y2, duration_ms)

    def keyevent(self, code: int):
        return adb.keyevent(code)

    def screen_size(self):
        return adb.screen_size()

    def shell(self, *args):
        """执行 `adb shell <args>`，返回 CompletedProcess。

        注意：`shell` 子命令不可省 —— 漏掉会得到 `adb.exe: unknown command am`
        这类报错，且调用方若没检查 returncode 就会静默失效。
        """
        return subprocess.run(adb._ADB + ["shell"] + list(args), capture_output=True)

    def exec_out(self, *args):
        return subprocess.run(adb._ADB + ["exec-out"] + list(args), capture_output=True)
