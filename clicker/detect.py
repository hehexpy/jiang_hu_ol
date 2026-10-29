"""
detect.py — 识别当前画面属于登录流程的哪个阶段（设备无关，基于视觉特征）

状态：MAIN_MENU / LOGIN_DIALOG / SERVER_LIST / CHAR_CREATE / IN_GAME / UNKNOWN
"""
import cv2
import numpy as np
from vision import size, find_color_blobs

# 主菜单三个青色按钮（登陆界面）
T_BUTTON = ((85, 80, 90), (110, 255, 255))

# 游戏内：左上角绿色血条
GREEN_HP = ((35, 80, 80), (85, 255, 255))


def _is_in_game(img) -> bool:
    """在屏幕左上角(0~0.4w, 0~0.25h)检测绿色血条/头像，命中即视为游戏内。"""
    h, w = img.shape[:2]
    roi = img[0:int(0.25 * h), 0:int(0.40 * w)]
    blobs = find_color_blobs(roi, *GREEN_HP, min_area=200, close_ksize=15)
    if blobs:
        bx, by, bw, bh = blobs[0]["rect"]
        # 血条应有一定宽度
        if bw >= 0.25 * int(0.40 * w) * 0.5:
            return True
    return False


def _is_main_menu(img) -> bool:
    """中下带 (y rel 0.55..0.82) 检测到 >=2 个青色按钮块。"""
    h, w = img.shape[:2]
    roi = img[int(0.55 * h):int(0.83 * h), int(0.30 * w):int(0.72 * w)]
    blobs = find_color_blobs(roi, *T_BUTTON, min_area=8000, close_ksize=11)
    return len(blobs) >= 2


def _is_login_dialog(img) -> bool:
    """登录对话框：上部大面积为近白/浅色，且有灰色按钮 (V高 S低)。"""
    h, w = img.shape[:2]
    # 画面中部: 白色对话框背景占比高
    roi = img[int(0.02 * h):int(0.6 * h), int(0.05 * w):int(0.98 * w)]
    hsv = cv2.cvtColor(roi, cv2.COLOR_BGR2HSV)
    white = cv2.inRange(hsv, (0, 0, 200), (180, 40, 255))  # 高V低S = 白/浅灰
    ratio = (white > 0).mean()
    # 灰色按钮（确定/返回）在中带
    gray = cv2.inRange(hsv, (0, 0, 150), (180, 45, 215))
    gray_ratio = (gray > 0).mean()
    # 对话框应覆盖中部较大部分
    return ratio > 0.30 and gray_ratio > 0.02


def _is_server_list(img) -> bool:
    """选服列表：中部有多条横向青色文字块（分布于 y rel 0.5..0.85）。"""
    h, w = img.shape[:2]
    roi = img[int(0.45 * h):int(0.90 * h), int(0.05 * w):int(0.98 * w)]
    blobs = find_color_blobs(roi, *T_BUTTON, min_area=1000, close_ksize=9)
    rows = set()
    for b in blobs:
        x, y, bw, bh = b["rect"]
        # 排除过宽的长条(背景)，保留文字行
        if bw < 0.9 * int(0.95 * w):
            rows.add(y // 60)
    return len(rows) >= 3


def _is_char_create(img) -> bool:
    """创建人物界面：中部有多个圆形头像(人物立绘)，下方青色『创建人物』按钮。"""
    h, w = img.shape[:2]
    # 下半部检测青色按钮块（创建人物）
    roi = img[int(0.72 * h):int(0.90 * h), int(0.30 * w):int(0.72 * w)]
    blobs = find_color_blobs(roi, *T_BUTTON, min_area=5000, close_ksize=11)
    if blobs:
        return True
    return False


def detect_state(img) -> str:
    """返回画面所属阶段。"""
    if _is_in_game(img):
        return "IN_GAME"
    if _is_main_menu(img):
        return "MAIN_MENU"
    if _is_login_dialog(img):
        return "LOGIN_DIALOG"
    if _is_server_list(img):
        return "SERVER_LIST"
    if _is_char_create(img):
        return "CHAR_CREATE"
    return "UNKNOWN"
