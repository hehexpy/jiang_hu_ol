"""局部模板 + 绝对亮度校验；阻止暗色弹窗背景被误认成可点击场景。"""
from pathlib import Path
import cv2
import numpy as np
from recognition import OpenCVRecognizer
from . import config


class GrindRecognizer(OpenCVRecognizer):
    def __init__(self, anchors=None, template_root=None):
        self.anchors = anchors or config.BUTTON_HINTS
        self.template_root = Path(template_root or config.ROOT)
        self.templates = {}
        for key, anchor in self.anchors.items():
            template = cv2.imread(str(self.template_root / anchor.template))
            if template is None:
                raise ValueError('缺少已登记模板：' + anchor.template)
            self.templates[key] = template

    def locate(self, frame, key):
        anchor = self.anchors[key]
        point = self.find_button_near(
            frame, str(self.template_root / anchor.template), anchor.hint,
            pad=anchor.pad, threshold=config.MATCH_THRESHOLD, pad_grow=1,
            scale_base_w=config.REFERENCE_WIDTH)
        if point is None:
            return None
        template = self.templates[key]
        scale = frame.shape[1] / config.REFERENCE_WIDTH
        if scale != 1:
            template = cv2.resize(template, (round(template.shape[1]*scale),
                                             round(template.shape[0]*scale)))
        th, tw = template.shape[:2]
        x, y = point[0] - tw//2, point[1] - th//2
        crop = frame[y:y+th, x:x+tw]
        if crop.shape != template.shape:
            return None
        # CCOEFF 对整体压暗近似不变，必须再用绝对误差排除遮罩。
        error = float(np.abs(crop.astype(np.float32)-template.astype(np.float32)).mean())
        return point if error <= config.MAX_MEAN_ERROR else None

    def is_world(self, frame):
        return all(self.locate(frame, key) is not None for key in ('map', 'tasks', 'enemy'))

    def is_battle(self, frame):
        return self.locate(frame, 'battle') is not None and self.locate(frame, 'tasks') is None

    def enemy_position(self, frame):
        point = self.locate(frame, 'enemy')
        return None if point is None else (point[0]/frame.shape[1], point[1]/frame.shape[0])
