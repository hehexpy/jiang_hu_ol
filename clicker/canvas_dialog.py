"""
canvas_dialog.py — Canvas 弹窗的识别与关闭（uiautomator 读不到的那一类）

## 为什么单独一个模块

游戏主体是 Canvas 渲染，`uiautomator dump` 在弹窗上只能拿到 6 个空节点
（实测 2049 字节 XML、零 text）。所以 `dialogs.py`（走文字层的那套）**看不见**它们。
2026-10-02 13:26 实测：模拟器闲置后游戏弹「掉线了，确认返回主界面！」，
流程静默卡死 —— 这正是「无人值守自跑」的直接阻塞项。

## 识别怎么做（以及为什么不能只看模板得分）

游戏所有 Canvas 弹窗共用**同一个关闭按钮控件**（绿 X + 右上角框饰）。
但 **登录面板右上角那个 ✕ 是同一款图形**（同 46x36、面积 782/782），
实测模板得分 0.873，与真弹窗 0.952 **区间重叠** —— 只看阈值必然误点，
而点中面板的 ✕ 会把登录面板弄没，属于高危误动作。

因此判据是**两条同时成立**：

  1. `tpl_dlg_close.png` 在锚点附近命中，得分 ≥ `TH_CLOSE`；
  2. 命中点周围**底色足够暗**（`V` 均值 < `BG_V_MAX`）。

第 2 条来自实测：弹窗底色是暗青（V=53~59），面板 ✕ 背后是蓝天照（V=93~106），
24 个样本（4 正 / 20 负）零反例。底色是**弹窗自身的画法**，不随背后的游戏画面变，
所以比「大模板」稳 —— 大模板会把弹窗背后的场景一起编码进去，反而失配。

## 加新弹窗怎么办

绝大多数弹窗都用这套关闭按钮，**不用加代码**。只有当某类弹窗需要「关之前先做点别的」
（例如「领奖」而不是「关闭」）时，才在本模块加一个 `Step` 子类并 `register()`。
"""
import cv2

from steps import Ctx, Step

# ---- 模板（2026-10-02 emulator-5556 / 1080x2340 实测）----
# 锚点与搜索窗口登记在 `config.BUTTON_HINTS["tpl_dlg_close.png"]`（AGENTS.md §4 的规范位置），
# 这里只放**判据阈值** —— 坐标属于设备标定，阈值属于识别逻辑，分开存。
TPL_CLOSE = "tpl_dlg_close.png"        # 100x96，中心正对 X；命中点即 X 中心
TH_CLOSE = 0.85                        # 模板阈值：正样本 0.952~1.000，负样本 ≤0.873

BG_V_MAX = 80.0                        # 底色亮度上限：正样本 53~59，负样本 93~106
BG_HALF = (50, 48)                     # 取底色统计的半宽/半高，与大模板尺寸一致

# 判定「已换了一个弹窗」的最小位移（像素）。叠弹窗时位置会差一截。
MOVED_PX = 60


def _bg_v(img, cx: int, cy: int, half=BG_HALF) -> float:
    """命中点周围、**排除 X 本体**后的底色亮度均值。取不到返回 255（=判为不暗）。

    排除规则：X 是高亮低饱和（V>170 且 S<140）；剩下的是弹窗底色。
    """
    h, w = img.shape[:2]
    hw, hh = half
    x0, y0 = max(0, cx - hw), max(0, cy - hh)
    sub = img[y0:min(h, cy + hh), x0:min(w, cx + hw)]
    if sub.size == 0:
        return 255.0
    hsv = cv2.cvtColor(sub, cv2.COLOR_BGR2HSV)
    s, v = hsv[..., 1], hsv[..., 2]
    mask = ~((v > 170) & (s < 140))
    if int(mask.sum()) < 50:
        return 255.0
    return float(v[mask].mean())


def find_close(ctx: Ctx, img=None):
    """找「弹窗关闭按钮」的绝对坐标。找不到 / 底色校验不过返回 None。

    先按 `config.BUTTON_HINTS` 里的锚点做局部匹配（快、且不受画面别处相似图案干扰），
    没命中再退全图 —— 全图那步要靠底色校验挡住登录面板的 ✕，见模块 docstring。
    """
    img = ctx.shot("_dlg.png") if img is None else img
    c = ctx.find_near(TPL_CLOSE, threshold=TH_CLOSE, img=img)
    if c is None:
        c = ctx.find(TPL_CLOSE, threshold=TH_CLOSE, img=img)
    if c is None:
        return None
    v = _bg_v(img, *c)
    if v >= BG_V_MAX:
        ctx.log(f"    [弹窗] 模板命中 {c} 但底色 V={v:.0f} 不够暗（阈值 {BG_V_MAX:.0f}）"
                f" -> 判为非弹窗，不点")
        return None
    return (int(c[0]), int(c[1]))    # 转 Python int：matchTemplate 给的是 numpy 标量，
                                     # 直接进日志会变成 @(np.int64(918), ...)，难读也难断言


class DialogCloseStep(Step):
    """有弹窗就点掉它的关闭按钮。

    优先级 **5**（比「已进游戏」还前）：弹窗会盖在任意界面之上，
    必须最先处理；否则下面的 Step 全都在「隔着弹窗」做判断。

    顺带的好处：进游戏后那些「登陆奖励 / 游戏公告」弹窗会被一并清掉，
    不会挡着后续操作。代价是**同时也放弃了点「领取奖励」** ——
    要做自动领奖得单独写一个 Step（见模块 docstring）。
    """
    name = "关弹窗"
    priority = 5
    # **非终止步骤**：它优先级最高（弹窗盖在任意界面之上，必须最先处理），
    # 但如果遇到关不掉的弹窗（例如"服务器维护中"），连败 3 次会把整个流程一起中止 ——
    # 哪怕游戏其实已经进去了。标 False 后 Runner 只禁用本步、继续跑。
    terminal = False

    def detect(self, ctx: Ctx) -> bool:
        self._c = find_close(ctx, ctx.shot("_s_dlg.png"))
        return self._c is not None

    def act(self, ctx: Ctx):
        ctx.log(f"  [弹窗] 点关闭 @{self._c}")
        ctx.tap(*self._c)

    def verify(self, ctx: Ctx) -> bool:
        """判据：刚才那个关闭按钮**不在原处**了。

        不写「再没有弹窗」是因为弹窗可能叠着来（关掉一个又来一个），
        那属于「本步做对了、还有下一个」，不该记成失败。
        """
        import time
        time.sleep(1.2)
        c = find_close(ctx, ctx.shot("_s_dlg_v.png"))
        if c is None:
            return True
        return abs(c[0] - self._c[0]) > MOVED_PX or abs(c[1] - self._c[1]) > MOVED_PX


def register_all():
    """注册本模块的全部步骤。"""
    from steps import register
    for s in (DialogCloseStep(),):
        register(s)
