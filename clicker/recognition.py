"""
recognition.py — 「画面识别」抽象接口 + 默认 OpenCV 实现

目的：把"如何识别画面/按钮/文字"抽象成 Recognizer 接口。
  默认 OpenCVRecognizer 用模板匹配/阈值（无需模型、无需 OCR）。
  以后想换【模型识别】(CNN/OCR)，只需实现另一个 Recognizer（如 MLRecognizer），
  控制逻辑（auto_login 等）零改动即可切换。

接口方法：
  find_button(img, tpl_path, ...)  按模板找按钮中心(绝对坐标或 None)
  match_template(img, tpl, ...)    底层模板匹配
  is_in_game / is_main_menu        画面状态判定
  read_server_counts(img)          读服务器角色数(可被模型文字识别替换)
"""
import os
from abc import ABC, abstractmethod
import cv2

import vision
from vision import load, template_match
from detect import _is_in_game, _is_main_menu
import recognize as _count

DEFAULT_SCALE_BASE_W = 1080

# 模板缓存：键 = (绝对路径, 缩放比, mtime)，值 = ndarray。
# 模板文件不变就不重复读盘/解码；模板被替换后 mtime 变化，缓存自动失效。
_TPL_CACHE: dict = {}
_TPL_CACHE_MAX = 64


class Recognizer(ABC):
    @abstractmethod
    def match_template(self, img, tpl, threshold=0.85, max_matches=1): ...

    @abstractmethod
    def is_in_game(self, img) -> bool: ...

    @abstractmethod
    def is_main_menu(self, img) -> bool: ...

    @abstractmethod
    def read_server_counts(self, img): ...

    def _load_tpl_scaled(self, tpl_path, target_w, scale_base_w=DEFAULT_SCALE_BASE_W):
        """按目标宽度缩放加载模板（带缓存）。文件缺失返回 None，不抛异常。"""
        try:
            mtime = os.path.getmtime(tpl_path)
        except OSError:
            return None
        scale = target_w / float(scale_base_w)
        if abs(scale - 1.0) <= 0.06:
            scale = 1.0
        key = (os.path.abspath(tpl_path), round(scale, 3), mtime)
        cached = _TPL_CACHE.get(key)
        if cached is not None:
            return cached
        tpl = load(tpl_path)
        if scale != 1.0:
            tpl = cv2.resize(tpl, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
        if len(_TPL_CACHE) >= _TPL_CACHE_MAX:
            _TPL_CACHE.clear()
        _TPL_CACHE[key] = tpl
        return tpl

    def find_button(self, img, tpl_path, threshold=0.85, min_y_frac=0.0,
                    scale_base_w=DEFAULT_SCALE_BASE_W):
        """【全图】匹配：在整个 img 中找模板，返回中心绝对坐标或 None。

        只在不知道按钮大概位置时才用 —— 全图 matchTemplate 慢，
        且容易被画面别处的相似图案干扰。知道大概位置请用 `find_button_near`。
        """
        tpl = self._load_tpl_scaled(tpl_path, img.shape[1], scale_base_w)
        if tpl is None:
            return None
        m = self.match_template(img, tpl, threshold=threshold, max_matches=1)
        if m and m[0]["center_abs"][1] >= min_y_frac * img.shape[0]:
            return m[0]["center_abs"]
        return None

    def find_button_near(self, img, tpl_path, hint, pad=(0.22, 0.06), threshold=0.80,
                         pad_grow=1.8, scale_base_w=DEFAULT_SCALE_BASE_W):
        """【局部区域匹配】以 `hint` 为锚点，只在附近窗口内找模板。返回中心绝对坐标或 None。

        推荐用这个定位按钮，好处三条：
          1. **快** —— matchTemplate 只跑一小块 ROI，耗时与画面总面积无关；
          2. **稳** —— 不写死点击坐标，最终位置由模板决定，分辨率/布局微调都能容错；
          3. **准** —— 搜索窗口之外的相似图案不会被误匹配。

        参数
          hint     : `(fx, fy)` 相对坐标 —— 按钮「大概在哪」。锚点越准，pad 可越小、越快。
          pad      : `(半宽, 半高)` 相对比例，搜索窗口 = 中心点 ± pad。
          pad_grow : 首次未命中时把窗口放大该倍数再试一次（兼容布局小幅偏移）。
        """
        tpl = self._load_tpl_scaled(tpl_path, img.shape[1], scale_base_w)
        if tpl is None:
            return None
        h, w = img.shape[:2]
        for k in (1.0, pad_grow):
            pw, ph = pad[0] * k, pad[1] * k
            region = vision.region_rel_to_px(
                w, h, hint[0] - pw, hint[1] - ph, hint[0] + pw, hint[1] + ph
            )
            ms = vision.match_template_in_region(img, tpl, region, threshold, 1)
            if ms:
                return ms[0]["center_abs"]
        return None


class OpenCVRecognizer(Recognizer):
    def match_template(self, img, tpl, threshold=0.85, max_matches=1):
        return template_match(img, tpl, threshold=threshold, max_matches=max_matches)

    def is_in_game(self, img) -> bool:
        return _is_in_game(img)

    def is_main_menu(self, img) -> bool:
        return _is_main_menu(img)

    def read_server_counts(self, img):
        return _count.read_row_counts(img)
