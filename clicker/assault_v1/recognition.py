"""识别接口扩展：只在模板登记的局部区域内匹配。"""
from abc import abstractmethod
from recognition import OpenCVRecognizer, Recognizer
import cv2
from . import config


class AssaultRecognition(Recognizer):
    """可替换的城战识别接口；业务流程不依赖OpenCV实现。"""

    @abstractmethod
    def locate(self, frame, name, offset_y=0., threshold=config.THRESHOLD): ...

    @abstractmethod
    def has(self, frame, name, offset_y=0.): ...

    @abstractmethod
    def state(self, frame): ...

    @abstractmethod
    def inventory_page(self, frame): ...


class AssaultRecognizer(OpenCVRecognizer, AssaultRecognition):
    def score(self, frame, name):
        """诊断分数：与运行定位使用相同的局部窗口。"""
        a, b, c, d = config.TEMPLATES[name][1]
        h, w = frame.shape[:2]
        roi = frame[max(0, int((b-.008)*h)):min(h, int((d+.008)*h)),
                    max(0, int((a-.012)*w)):min(w, int((c+.012)*w))]
        tpl = self._load_tpl_scaled(str(config.template_path(name)), w)
        if tpl is None or roi.shape[0] < tpl.shape[0] or roi.shape[1] < tpl.shape[1]:
            return -1.
        return float(cv2.minMaxLoc(cv2.matchTemplate(roi, tpl, cv2.TM_CCOEFF_NORMED))[1])

    def locate(self, frame, name, offset_y=0., threshold=config.THRESHOLD):
        a, b, c, d = config.TEMPLATES[name][1]
        hint = ((a+c)/2, (b+d)/2 + offset_y)
        pad = ((c-a)/2 + .012, (d-b)/2 + .008)
        point = self.find_button_near(frame, str(config.template_path(name)),
                                      hint, pad=pad, threshold=threshold, pad_grow=1)
        if point is None:
            return None
        h, w = frame.shape[:2]
        return point[0]/w, point[1]/h

    def has(self, frame, name, offset_y=0.):
        return self.locate(frame, name, offset_y) is not None

    def state(self, frame):
        if self.has(frame, 'error_title'):
            if self.has(frame, 'closed'):
                return 'CLOSED'
            return 'ARMY_LIMIT' if self.has(frame, 'army_limit') else 'ERROR'
        if self.has(frame, 'confirm_title'):
            if self.has(frame, 'recruit_confirm'):
                return 'RECRUIT_CONFIRM'
            if self.has(frame, 'attack_confirm'):
                return 'ATTACK_CONFIRM'
            return 'UNKNOWN_CONFIRM'
        if self.has(frame, 'header'):
            if self.has(frame, 'recruit_list'):
                return 'TROOPS'
            if self.has(frame, 'my_troops') and self.has(frame, 'recruit_name'):
                return 'RECRUIT'
            if self.has(frame, 'defend') and self.has(frame, 'city'):
                return 'OCCUPY'
            if self.has(frame, 'overview'):
                return 'OVERVIEW'
            return 'PANEL_UNKNOWN'
        if self.has(frame, 'start'):
            return 'ENTRY'
        if self.has(frame, 'npc'):
            return 'WORLD'
        return 'UNKNOWN'

    def inventory_page(self, frame):
        if self.state(frame) != 'TROOPS':
            raise ValueError('不是我的士兵页面')
        page = 1 if self.has(frame, 'page_1') else 2 if self.has(frame, 'page_2') else None
        if page is None:
            raise ValueError('页码未标定')
        total = sum(self.has(frame, 'rest', row * config.ROW_SPACING) for row in range(3))
        red = sum(self.has(frame, 'owned_red', (row-1) * config.ROW_SPACING) for row in range(3))
        if total == 0 or red > total:
            raise ValueError('部队行判据不一致')
        return page, total, red
