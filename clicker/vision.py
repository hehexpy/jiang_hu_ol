"""
vision.py — 设备无关的屏幕视觉识别模块 (OpenCV)

设计目标：适配不同分辨率的手机/模拟器，不写死绝对像素。
  - 所有坐标既可用【绝对像素】，也可用【相对比例 (0..1)】表示。
  - 检测优先基于【颜色/模板】，天然不依赖某一固定分辨率。
  - 屏幕尺寸在运行时通过 adb 读取，自动适配。

依赖：opencv-python, numpy (已安装)
"""
import cv2
import numpy as np


def load(path: str) -> np.ndarray:
    """读取图片为 BGR ndarray。"""
    img = cv2.imread(path)
    if img is None:
        raise FileNotFoundError(f"cannot load image: {path}")
    return img


def size(img: np.ndarray) -> tuple[int, int]:
    """返回 (宽, 高)。"""
    h, w = img.shape[:2]
    return w, h


def rel_to_abs(frac_x: float, frac_y: float, w: int, h: int) -> tuple[int, int]:
    """相对比例 (0..1) 转绝对像素坐标。"""
    return int(frac_x * w), int(frac_y * h)


def abs_to_rel(x: int, y: int, w: int, h: int) -> tuple[float, float]:
    """绝对像素坐标转相对比例 (0..1)。"""
    return x / w, y / h


def to_hsv(img: np.ndarray) -> np.ndarray:
    return cv2.cvtColor(img, cv2.COLOR_BGR2HSV)


def find_color_blobs(
    img: np.ndarray,
    hsv_lower: tuple,
    hsv_upper: tuple,
    min_area: float = 500,
    max_area: float = 1e6,
    min_w: int = 10,
    min_h: int = 10,
    close_ksize: int = 9,
) -> list[dict]:
    """
    在整张图中找出符合 HSV 颜色范围的连通区域，返回每个区域的中心。
    返回 [{'rect':(x,y,w,h), 'center_abs':(cx,cy), 'center_rel':(fx,fy), 'area':a}]，按面积降序。
    用于定位按钮/指示条/红点等，分辨率无关。
    """
    hsv = to_hsv(img)
    mask = cv2.inRange(hsv, np.array(hsv_lower, np.uint8), np.array(hsv_upper, np.uint8))
    # 形态学处理，让同一控件连成完整块
    k = np.ones((close_ksize, close_ksize), np.uint8)
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, k)
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))

    W, H = size(img)
    cnts, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    out = []
    for c in cnts:
        x, y, bw, bh = cv2.boundingRect(c)
        area = bw * bh
        if area < min_area or area > max_area:
            continue
        if bw < min_w or bh < min_h:
            continue
        cx, cy = x + bw // 2, y + bh // 2
        out.append({
            "rect": (x, y, bw, bh),
            "center_abs": (cx, cy),
            "center_rel": (cx / W, cy / H),
            "area": area,
        })
    out.sort(key=lambda d: -d["area"])
    return out


def template_match(
    img: np.ndarray,
    template: np.ndarray,
    threshold: float = 0.8,
    max_matches: int = 5,
) -> list[dict]:
    """模板匹配：在 img 中找 template 出现的位置。返回中心点列表（绝对 + 相对）。"""
    W, H = size(img)
    res = cv2.matchTemplate(img, template, cv2.TM_CCOEFF_NORMED)
    ys, xs = np.where(res >= threshold)
    matches = []
    for y, x in zip(ys, xs):
        cx, cy = x + template.shape[1] // 2, y + template.shape[0] // 2
        matches.append({
            "center_abs": (cx, cy),
            "center_rel": (cx / W, cy / H),
            "score": float(res[y, x]),
        })
    # 去重（相近点保留得分最高的）
    matches.sort(key=lambda m: -m["score"])
    kept = []
    for m in matches:
        if all(abs(m["center_abs"][0] - k["center_abs"][0]) > 40 or
               abs(m["center_abs"][1] - k["center_abs"][1]) > 40 for k in kept):
            kept.append(m)
        if len(kept) >= max_matches:
            break
    return kept


def region(img: np.ndarray, x: int, y: int, w: int, h: int) -> np.ndarray:
    """裁剪区域。"""
    return img[y:y + h, x:x + w]


def region_avg_color(img: np.ndarray, x: int, y: int, w: int, h: int) -> tuple:
    """区域内平均 BGR 颜色。"""
    r = region(img, max(0, x), max(0, y), w, h)
    if r.size == 0:
        return (0, 0, 0)
    b, g, rr = r.mean(axis=(0, 1))
    return (int(b), int(g), int(rr))


def is_similar(frame_a: np.ndarray, frame_b: np.ndarray, threshold: float = 10.0) -> bool:
    """判断两帧是否基本一致（用于检测画面变化/弹窗）。"""
    if frame_a.shape != frame_b.shape:
        return False
    diff = cv2.absdiff(frame_a, frame_b).mean()
    return diff < threshold


def region_rel_to_px(w: int, h: int, fx0: float, fy0: float, fx1: float, fy1: float) -> tuple:
    """相对区域 (0..1) 转像素区域 (x0, y0, x1, y1)，自动 clamp 进画面。"""
    x0 = max(0, min(int(round(fx0 * w)), w - 1))
    y0 = max(0, min(int(round(fy0 * h)), h - 1))
    x1 = max(x0 + 1, min(int(round(fx1 * w)), w))
    y1 = max(y0 + 1, min(int(round(fy1 * h)), h))
    return x0, y0, x1, y1


def match_template_in_region(
    img: np.ndarray,
    template: np.ndarray,
    region_px: tuple,
    threshold: float = 0.8,
    max_matches: int = 3,
) -> list[dict]:
    """局部模板匹配：只在 region_px=(x0,y0,x1,y1) 内搜索，返回【整图绝对坐标】。

    与全图 `template_match` 的区别：
      1. ROI 越小越快（matchTemplate 复杂度与画面面积成正比）；
      2. 天然避开画面其他区域的相似图案干扰，误匹配更少；
      3. 返回值已补上 ROI 偏移，调用方无需关心裁剪。
    ROI 比模板还小时直接返回空列表（matchTemplate 会报错）。
    """
    h, w = img.shape[:2]
    x0, y0, x1, y1 = region_px
    th, tw = template.shape[:2]
    if (y1 - y0) < th or (x1 - x0) < tw:
        return []
    roi = img[y0:y1, x0:x1]
    res = cv2.matchTemplate(roi, template, cv2.TM_CCOEFF_NORMED)
    ys, xs = np.where(res >= threshold)
    matches = []
    for yy, xx in zip(ys, xs):
        cx, cy = x0 + int(xx) + tw // 2, y0 + int(yy) + th // 2
        matches.append({
            "center_abs": (cx, cy),
            "center_rel": (cx / w, cy / h),
            "score": float(res[yy, xx]),
        })
    matches.sort(key=lambda m: -m["score"])
    kept = []
    for m in matches:
        if all(abs(m["center_abs"][0] - k["center_abs"][0]) > 30 or
               abs(m["center_abs"][1] - k["center_abs"][1]) > 30 for k in kept):
            kept.append(m)
        if len(kept) >= max_matches:
            break
    return kept
