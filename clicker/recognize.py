"""
recognize.py — 脚本内的「服务器角色数」识别（OpenCV，不依赖模型/OCR）

原理：
  选服列表每行 = 区名 + 角色数(青色小数字，位于框内右侧约 82% 宽度处)。
  数字用「孔洞数 + 宽高比」区分：
    - 有孔洞(>=1) => 0
    - 无孔洞且窄(宽高比<=0.5) => 1
    - 无孔洞且宽 => 2 或 3 (按“最多三个角色”取高值；脚本内可进一步细分)
  用「连通域」找小而独立的亮青色块，天然过滤大片背景美术/边框。

依赖：opencv-python, numpy
"""
import cv2
import numpy as np


def _hole_count(thresh: np.ndarray) -> int:
    inv = cv2.bitwise_not(thresh)
    n, _, stats, _ = cv2.connectedComponentsWithStats(inv, connectivity=4)
    holes = 0
    for i in range(1, n):
        x, y, bw, bh, area = stats[i]
        if x == 0 or y == 0 or x + bw == thresh.shape[1] or y + bh == thresh.shape[0]:
            continue
        if area < 8:
            continue
        holes += 1
    return holes


def _digit_mask(roi_bgr: np.ndarray) -> np.ndarray:
    """青色数字掩码：青绿色小字，排除天空等大块。"""
    hsv = cv2.cvtColor(roi_bgr, cv2.COLOR_BGR2HSV)
    gray = cv2.cvtColor(roi_bgr, cv2.COLOR_BGR2GRAY)
    # 青绿（数字/区名）主色
    cyan = cv2.inRange(hsv, (78, 70, 110), (100, 255, 255))
    # 高亮白/浅青（更强的数字）
    bright = cv2.inRange(hsv, (0, 0, 190), (180, 90, 255))
    mask = cv2.bitwise_or(cyan, bright)
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))
    return mask


def read_row_counts(img: np.ndarray) -> list[dict]:
    """
    返回 [{'y': 行中心y, 'count': 角色数, 'rank': 权重}...]（按 y 排序）。
    通过统计连通域找小而独立的数字块，过滤大片背景/边框。
    """
    h, w = img.shape[:2]
    x0, x1 = int(0.72 * w), int(0.87 * w)
    y0, y1 = int(0.53 * h), int(0.85 * h)
    roi = img[y0:y1, x0:x1]
    mask = _digit_mask(roi)

    n, labels, stats, centroids = cv2.connectedComponentsWithStats(mask, connectivity=8)
    RW, RH = roi.shape[1], roi.shape[0]
    digits = []
    for i in range(1, n):
        x, y, bw, bh, area = stats[i]
        cy = y + bh // 2
        # 数字应为正方形较小区域；排除过大(背景/边框)与过小(噪点)
        if not (4 * 9 <= bw <= 4 * 60 and 10 <= bh <= 55):
            continue
        if area < 90 or area > 2500:
            continue
        # 排除贴边(通常是边框/美术延伸)
        if x <= 2 or y <= 2 or x + bw >= RW - 2 or y + bh >= RH - 2:
            continue
        component = (labels[y:y + bh, x:x + bw] == i).astype(np.uint8) * 255
        holes = _hole_count(component)
        aspect = bw / max(bh, 1)
        if holes >= 1:
            count = 0
        elif aspect <= 0.5:
            count = 1
        else:
            count = 3            # 2 或 3（宽、无孔洞；按“最多三个角色”视为高值）
        digits.append((y0 + cy, count))

    digits.sort(key=lambda d: d[0])
    return [{"y": cy, "count": c, "rank": c} for cy, c in digits]


def best_row_y(img: np.ndarray) -> int | None:
    rows = read_row_counts(img)
    if not rows:
        return None
    best = max(rows, key=lambda r: (r["count"], -r["y"]))
    if best["count"] <= 0:
        return None
    return best["y"]
