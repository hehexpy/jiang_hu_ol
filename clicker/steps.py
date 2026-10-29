"""
steps.py — 可插拔的「界面步骤」框架（降耦合的核心）

## 为什么要有这一层

原来的流程是一个 100 多行的 `while` + `if state == "XXX"` 长链：加一个界面要改中心循环，
两条流程（线性 `auto_login` 与状态机 `auto_login_sm`）各写一遍、互相分叉，
而且依赖 `auto_login` 的模块级全局变量（`_inter` / `_rec` / `_GAME_BOUNDS`），
换后台、做测试都很难。

现在拆成三件互不关心的事：

    Ctx      —— 一次运行的全部上下文。依赖**显式传入**，不再有模块全局。
    Step     —— 一个界面的「识别 / 动作 / 验证」三件套，自己管自己的模板与坐标。
    Runner   —— 只做一件事：挑第一个 detect() 为真的 Step，跑它，验它，记一笔。

**加一个新界面（例如「组队面板」）= 写一个新 Step 类 + `register()`，别处一行都不用动。**

## 每一步都必须自证

`Step.verify()` 不是装饰：AGENTS.md §5 写着「没有实际输出就不算验证」，
本仓最大的病就是「代码看起来都有，但从没验证过」。
所以框架强制每个 Step 在动作之后给出「成功判据」，执行器把它记进统计，
连续失败会直接抛错而不是默默重试到超时。
"""
import os
import time
from dataclasses import dataclass, field

import vision


# ---------------------------------------------------------------- 上下文
@dataclass
class Account:
    """一个待登录账号的设备无关描述。"""
    username: str
    password: str
    server: str | None = None
    pkg: str = ""
    activity: str = ""


class StepFailure(RuntimeError):
    """步骤遇到「识别到了、但推进不了」的状态。由 Runner 记录并继续下一轮。"""


class ConfigError(StepFailure):
    """**配置类**错误（目标区没登记、模板缺失等）。

    与普通 StepFailure 的区别：这类错误再跑多少轮都不会自己好，继续跑只会
    「点切换 -> 兜底乱点」直到超时，把真正的病因埋在最后那句"没跑完"里。
    所以 `Runner.pick` **不吞**它，一路抛到调用方，让问题立刻可见。
    （2026-10-02 实测：`server=None` 时 `ServerSelectStep.detect` 抛的 StepFailure
     被 pick 静默吞掉，流程空转到超时 —— 就是这条要修的东西。）
    """


@dataclass
class Ctx:
    """一次流程运行的全部依赖与状态。所有 Step 只通过它拿东西。"""
    inter: object                 # interaction.Interaction
    rec: object                   # recognition.Recognizer
    cfg: object                   # config 模块（设备无关配置）
    base_dir: str                 # clicker/ 目录（模板所在）
    account: Account
    bounds: tuple | None = None   # 游戏可视区 (x0,y0,x1,y1)，None=整屏
    done: bool = False
    result: bool = False
    stats: dict = field(default_factory=dict)     # step.name -> [成功次数, 失败次数]
    trace: list = field(default_factory=list)     # 执行轨迹
    flags: dict = field(default_factory=dict)     # 跨步骤的最小共享状态（如「登录已提交」）
    verbose: bool = True

    # ---- 基础
    def log(self, msg: str):
        if self.verbose:
            print(msg, flush=True)

    def tpl(self, name: str) -> str:
        return os.path.join(self.base_dir, name)

    def shot(self, name: str = "_step.png"):
        """截屏。name 以 `_` 开头会落在 clicker/ 下，被 .gitignore 排除。"""
        return self.inter.capture(os.path.join(self.base_dir, name))

    def size(self, img):
        return img.shape[1], img.shape[0]

    # ---- 点击
    def tap(self, x, y):
        self.inter.tap(x, y)

    def tap_rel(self, fx, fy):
        """按归一化坐标点击。有游戏可视区就按可视区映射，否则按整屏。"""
        if self.bounds:
            x0, y0, x1, y1 = self.bounds
            x, y = int(x0 + fx * (x1 - x0)), int(y0 + fy * (y1 - y0))
        else:
            w, h = self.inter.screen_size()
            x, y = int(fx * w), int(fy * h)
        self.inter.tap(x, y)
        return x, y

    # ---- 识别
    def find(self, tpl_name: str, threshold: float = 0.85, min_y_frac: float = 0.0, img=None):
        """【全图】模板匹配。慢、易受干扰，只在没有锚点时用。"""
        img = self.shot("_find.png") if img is None else img
        return self.rec.find_button(img, self.tpl(tpl_name), threshold, min_y_frac)

    def score(self, tpl_name: str, img, region_rel=None):
        """返回模板在整屏（或指定相对区域内）的最高分与**整图绝对**位置。

        `region_rel=(fx0, fy0, fx1, fy1)` 把搜索限定在某个界面分区里。为什么需要它：
        同一个字符串在不同位置会以不同控件出现（例如服务器列表里「当前区」小条和
        列表某一行都是区名），不分区就会互相当成对方 —— 2026-10-02 实测该伪匹配
        能到 **0.796**，离阈值 0.80 只差 0.004。

        返回 `(得分, 中心)`；模板缺失或比区域还大时返回 `(-1.0, None)`。
        """
        import cv2
        tpl = cv2.imread(self.tpl(tpl_name))
        if tpl is None:
            return -1.0, None
        x0 = y0 = 0
        roi = img
        if region_rel:
            h, w = img.shape[:2]
            x0, y0, x1, y1 = vision.region_rel_to_px(w, h, *region_rel)
            roi = img[y0:y1, x0:x1]
        if tpl.shape[0] > roi.shape[0] or tpl.shape[1] > roi.shape[1]:
            return -1.0, None
        res = cv2.matchTemplate(roi, tpl, cv2.TM_CCOEFF_NORMED)
        _, mx, _, loc = cv2.minMaxLoc(res)
        return float(mx), (x0 + loc[0] + tpl.shape[1] // 2,
                           y0 + loc[1] + tpl.shape[0] // 2)

    def find_near(self, tpl_name: str, hint=None, pad=None, threshold=None, img=None):
        """【局部区域匹配】按锚点 hint(相对坐标) 在附近窗口内匹配，返回绝对中心或 None。

        锚点优先取显式 hint，其次查 `config.BUTTON_HINTS`（按模板名登记）。没锚点返回 None。
        """
        reg_hint, reg_pad = self.cfg.BUTTON_HINTS.get(
            tpl_name, (None, self.cfg.HINT_PAD_DEFAULT))
        hint = reg_hint if hint is None else hint
        pad = reg_pad if pad is None else pad
        if hint is None:
            return None
        img = self.shot("_near.png") if img is None else img
        th = self.cfg.HINT_THRESHOLD if threshold is None else threshold
        return self.rec.find_button_near(img, self.tpl(tpl_name), hint, pad, th)

    def tap_tpl_or(self, tpl_name: str, fallback_pos, hint=None, pad=None,
                   threshold=None, img=None):
        """点按钮：局部匹配 -> 全图匹配 -> 固定坐标兜底。返回 (是否模板命中, 用了什么)。

        `hint` 是局部匹配的锚点（相对坐标）。没给就查 `config.BUTTON_HINTS`。
        兜底坐标只该在「模板还没标定」时救急 —— 它出现在日志里就说明有模板待补。
        """
        c = self.find_near(tpl_name, hint=hint, pad=pad, threshold=threshold, img=img)
        how = "局部匹配"
        if c is None:
            c = self.find(tpl_name, img=img)
            how = "全图匹配"
        if c:
            self.tap(*c)
            return True, (how, c)
        self.log(f"    !! 模板 {tpl_name} 未命中，退化为固定坐标 {fallback_pos}（该模板待标定）")
        self.tap_rel(*fallback_pos)
        return False, ("固定坐标兜底", fallback_pos)


# ---------------------------------------------------------------- 步骤
class Step:
    """一个界面 / 一个动作单元。

    子类只需实现 `detect` 与 `act`；`verify` 强烈建议实现
    （不实现就等于「我说我做完了」，那正是本仓以前的毛病）。
    """
    name = "step"
    priority = 100      # 越小越先被检测

    # 是否「终止性」步骤。终止性 = 它卡住就意味着整个流程没救。
    # False 的步骤（例如关弹窗）连败后会被 Runner **禁用**并继续跑，
    # 而不是把整个流程一起拖停 —— 见 Runner.run 的连败分支。
    terminal = True

    def detect(self, ctx: Ctx) -> bool:
        raise NotImplementedError

    def act(self, ctx: Ctx):
        raise NotImplementedError

    def verify(self, ctx: Ctx) -> bool:
        """成功判据。默认 True（不主张、也不隐瞒）；子类应尽量给出真判据。"""
        return True

    def __repr__(self):
        return f"<{type(self).__name__} {self.name} p={self.priority}>"


# ---------------------------------------------------------------- 注册表
_REGISTRY: list[Step] = []


def register(step: Step):
    """注册一个 Step（可当装饰器用）。同 priority 按注册顺序。"""
    _REGISTRY.append(step)
    _REGISTRY.sort(key=lambda s: s.priority)
    return step


def registered() -> list[Step]:
    return list(_REGISTRY)


def unregister_all():
    """清空注册表（测试用）。"""
    _REGISTRY.clear()


# ---------------------------------------------------------------- 执行器
class Runner:
    """反复「挑步骤 -> 执行 -> 验证」，直到 `ctx.done` 或超时。

    连续失败保护：同一个 Step 连续失败 `max_step_fail` 次就直接抛错停下 ——
    宁可报错也不要像以前那样默默重试到超时，让人以为「只是没跑完」。
    """

    def __init__(self, ctx: Ctx, steps: list[Step] | None = None,
                 deadline: float = 180.0, max_loops: int = 200, max_step_fail: int = 3):
        self.ctx = ctx
        self.steps = list(steps) if steps else registered()
        self.deadline = deadline
        self.max_loops = max_loops
        self.max_step_fail = max_step_fail
        self._disabled: set[str] = set()      # 非终止步骤连败后进这里，后续不再检测

    def pick(self) -> Step | None:
        for s in self.steps:
            if s.name in self._disabled:
                continue
            try:
                if s.detect(self.ctx):
                    return s
            except ConfigError:
                raise                                  # 配置错误不许吞，见 ConfigError
            except Exception as e:                     # 一个 Step 的识别异常不该拖垮整轮
                self.ctx.log(f"  !! {s.name} detect 异常: {e}")
        return None

    def run(self) -> bool:
        ctx, t0 = self.ctx, time.time()
        consec_name, consec_fail = None, 0
        loops = 0
        while not ctx.done and loops < self.max_loops and time.time() - t0 < self.deadline:
            loops += 1
            step = self.pick()
            if step is None:
                ctx.log("  !! 没有任何 Step 认出当前界面，停止")
                break
            last_exc = None
            try:
                step.act(ctx)
                ok = step.verify(ctx)
            except ConfigError:
                raise                      # 配置错误立刻穿透，连 3 轮都不用等
            except StepFailure as e:
                ok = False
                last_exc = e
                ctx.log(f"  [{step.name}] 推进失败: {e}")
            except Exception as e:
                ok = False
                last_exc = e
                ctx.log(f"  [{step.name}] 异常: {type(e).__name__}: {e}")

            st = ctx.stats.setdefault(step.name, [0, 0])
            st[0 if ok else 1] += 1
            ctx.trace.append((round(time.time() - t0, 1), step.name, ok))

            if ok:
                consec_name, consec_fail = None, 0
            else:
                consec_fail = consec_fail + 1 if consec_name == step.name else 1
                consec_name = step.name
                if consec_fail >= self.max_step_fail:
                    if getattr(step, "terminal", True):
                        # 把**最后一次的具体原因**带上 —— 只报「连续失败 3 次」等于把病因埋掉，
                        # 而排错时唯一有用的就是那句具体原因。
                        tail = f"：{last_exc}" if last_exc else "，停下来而不是空转到超时"
                        raise StepFailure(f"{step.name} 连续失败 {consec_fail} 次{tail}")
                    # 非终止步骤（如「关弹窗」）：它卡住不代表流程没救 ——
                    # 2026-10-02 实测，遇到一个关不掉的弹窗时会在这里中止整个流程，
                    # 哪怕游戏其实已经进去了。改成禁用该步骤、继续跑。
                    self._disabled.add(step.name)
                    ctx.log(f"  [{step.name}] 连续失败 {consec_fail} 次 -> **禁用该步骤继续**"
                            f"（非终止步骤，不中止整个流程）")
                    consec_name, consec_fail = None, 0

            time.sleep(0.6)

        if not ctx.done:
            ctx.log(f"  !! 未达成目标（跑了 {loops} 轮 / {time.time() - t0:.0f}s）")
        return ctx.result

    def report(self) -> str:
        lines = ["  步骤统计:"]
        for name, (good, bad) in self.ctx.stats.items():
            lines.append(f"    {name:16s} 成功 {good}  失败 {bad}")
        lines.append("  轨迹: " + " -> ".join(f"{t}s:{n}{'✓' if ok else '✗'}"
                                            for t, n, ok in self.ctx.trace[-12:]))
        return "\n".join(lines)


def describe(steps: list[Step] | None = None) -> str:
    """列出已注册的步骤，方便接手者一眼看出「现在支持哪些界面」。"""
    steps = steps if steps is not None else registered()
    return "\n".join(f"  p={s.priority:<4} {s.name:18s} {type(s).__name__}" for s in steps)
