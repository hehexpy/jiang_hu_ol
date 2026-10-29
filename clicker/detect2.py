"""
detect2.py — 可靠的界面状态判定（基于按钮模板 + uiautomator，而非颜色启发式）

每个界面用"能唯一标识它的按钮/控件"来判定：
  - LOGIN_DIALOG : uiautomator 出现 用户名/密码 EditText
  - MAIN_MENU    : 模板匹配「登陆游戏」主菜单按钮
  - SERVER_LIST  : 模板匹配「进入」按钮(服务器弹窗)
  - CHAR_SELECT  : 模板匹配「进入游戏」按钮(角色选择)
  - CHAR_CREATE  : 模板匹配「创建人物」按钮
  - LOGIN_PANEL  : uiautomator 出现「账号/服务器」或模板匹配「切换」
  - IN_GAME      : 模板匹配「菜单」(游戏内右下角工具栏菜单)
判定顺序：优先可唯一识别的(靠模板/uiautomator)，最后才回退到颜色启发式。
"""
import os
import cv2
import numpy as np

_base = os.path.dirname(os.path.abspath(__file__))
_tpl_cache = {}


def _tpl(name):
    if name not in _tpl_cache:
        p = os.path.join(_base, name)
        _tpl_cache[name] = cv2.imread(p) if os.path.exists(p) else None
    return _tpl_cache[name]


def _match(img, tpl, threshold=0.85):
    if tpl is None:
        return None
    W, H = img.shape[1], img.shape[0]
    t = tpl
    scale = W / 1080.0
    if abs(scale - 1.0) > 0.06:
        t = cv2.resize(t, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
    res = cv2.matchTemplate(img, t, cv2.TM_CCOEFF_NORMED)
    _, mx, _, mloc = cv2.minMaxLoc(res)
    if mx >= threshold:
        cx = mloc[0] + t.shape[1] // 2
        cy = mloc[1] + t.shape[0] // 2
        return (cx, cy, mx)
    return None


def _edit_texts(xml):
    """返回 (用户名EditText中心, 密码EditText中心) 或 (None,None)。"""
    import re
    edits = []
    for m in re.finditer(r'class="android\.widget\.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        x1, y1, x2, y2 = map(int, m.groups())
        edits.append(((x1 + x2) // 2, (y1 + y2) // 2))
    return (edits[0] if len(edits) > 0 else None, edits[1] if len(edits) > 1 else None)


def detect_state2(img, ui_xml=""):
    """返回状态字符串。优先模板/uiautomator，回退颜色启发式。"""
    # 1) 视觉按钮模板(优先，避免uiautomator过期ReadText误报)
    m_menu = _match(img, _tpl("tpl_mainmenu.png"))
    if m_menu:
        return "MAIN_MENU"
    m_create = _match(img, _tpl("tpl_create_char.png"))
    if m_create and m_create[1] > 0.6 * img.shape[0]:
        return "CHAR_CREATE"
    m_enter = _match(img, _tpl("tpl_enter.png"))
    if m_enter:
        return "SERVER_LIST"
    m_entergame = _match(img, _tpl("tpl_enter_game.png"))
    if m_entergame:
        return "CHAR_SELECT"
    # IN_GAME 收紧：需「左上绿血条」+"底部菜单图标"同时成立
    from detect import _is_in_game
    if _is_in_game(img):
        m_menu_in = _match(img, _tpl("tpl_menu.png"))
        if m_menu_in and m_menu_in[1] > 0.70 * img.shape[0]:
            return "IN_GAME"
    # 2) uiautomator 有 用户名/密码 EditText -> 简单登录框或登录面板
    u, p = _edit_texts(ui_xml)
    if u and p:
        if _match(img, _tpl("tpl_switch.png"), 0.85):
            return "LOGIN_PANEL"
        return "LOGIN_DIALOG"
    # 3) 回退：颜色启发式
    from detect import detect_state
    return detect_state(img)
