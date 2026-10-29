"""
login_steps.py — 登录流程的具体界面步骤

每个类 = 一个界面。加界面就加类、注册，不改中心逻辑（见 steps.py 的说明）。

优先级越小越先检测。判断依据尽量用「本界面独有的模板」，
而不是中心化的状态字符串 —— 这样各个 Step 之间互不依赖（低耦合），
把某个 Step 删掉，其余照常工作。
"""
import os
import time

import config

import dialogs
import net
import steps
from steps import Ctx, Step, StepFailure, ConfigError
import uia

# ---------------------------------------------------------------- 模板
TPL_MAINMENU = "tpl_mainmenu.png"          # 主菜单独有（登录面板上只有 ~0.5）
TPL_LOGIN_GAME = "tpl_login_game.png"      # 主菜单/面板都有「登陆游戏」按钮
TPL_SWITCH = "tpl_switch.png"              # 登录面板独有的「切换」小按钮
TPL_CREATE_CHAR = "tpl_create_char.png"
TPL_ENTER_GAME = "tpl_enter_game.png"
TPL_ROW_FIRST = "tpl_srv_pingzong.png"     # 服务器列表**首行**：列表一打开就能看到

TH_STRONG = 0.85
TH_MED = 0.80
TH_ROW = 0.80

# 「面板栏里是不是目标区」的阈值（`tpl_server_*.png`）。
# 实测：是目标区 0.922~0.943；不是 0.513；列表盖住面板时 0.50~0.58。取中间值，两侧余量都够。
TH_PANEL_SERVER = 0.85

TPL_LOGIN_GAME_PANEL = "tpl_login_panel_btn.png"   # 登录面板上的「登陆游戏」（与主菜单那个不同源）

# 按钮锚点（相对坐标）——局部匹配的「大概位置」。
# **规范上应登记进 `config.BUTTON_HINTS`**（AGENTS.md §4）。当前 config.py 不可改
# （读取审批未通过），故先在各 Step 里显式传入，待 config 可写后迁移过去。
# 来源：2026-10-02 emulator-5556（1080x2340）实测。
HINT_SWITCH_PANEL = (0.942, 0.691)          # 面板「切换」小按钮(1017,1616)
HINT_LOGIN_BTN_MAINMENU = (0.513, 0.720)    # 主菜单「登陆游戏」(554,1685)
HINT_LOGIN_BTN_PANEL = (0.500, 0.628)       # 面板「登陆游戏」(540,1465)
HINT_ENTER_GAME = (0.500, 0.585)            # 角色选择「进入游戏」(540,1368)
PAD_BTN = (0.10, 0.040)
PAD_SMALL = (0.06, 0.035)

# ---------------------------------------------------------------- 提交状态机
# 2026-10-02 实测：每 12s 重复点一次「登陆游戏」，连接永远完不成，看起来像"点了没反应"。
# 原来的做法是「60s 计时宽限」—— 问题是 60s 后**无上限重试**，一直点。
# 现在改成**状态机**：
#   提交 -> 最长等 SUBMIT_TIMEOUT 秒（期间绝不重复点击、也不乱点屏幕）
#        -> 超时才允许再提交
#        -> 累计超过 MAX_SUBMIT 次即**明确失败**（抛错），不空转到 deadline
# 回到原生登录框时清零 —— 那才是「上一轮确实没成」的真信号。
DEFAULT_SUBMIT_TIMEOUT = 90.0
DEFAULT_MAX_SUBMIT = 2

# 区名 -> 模板 slug 的**本地兜底**。权威来源是 `config.SERVER_SLUG`
# （AGENTS.md §4：模板映射属设备无关配置）。留一份兜底是为了 config 未更新时仍能跑。
SERVER_ROW_SLUG_FALLBACK = {
    "九州揽月": "jiuzhoulanyue",
    "萍踪侠影": "pingzong",
    "空灵缥缈": "kongling",
    "燕语莺啼": "yanyu",
    "瑭霄浩渺": "xuanxiao",
    "仙岛沧海": "xiandao",
}

# 面板区名条的匹配区域（相对）。权威来源 `config.PANEL_SERVER_ROI`。
PANEL_SERVER_ROI_FALLBACK = (0.25, 0.655, 0.80, 0.740)


def slug_map(ctx: Ctx) -> dict:
    """区名 -> 模板 slug。

    **唯一真相源是 `config.SERVER_SLUG`** —— 这是问题 #8 的修复：原先
    `config.SERVER_TPL`（面板条）与 `login_steps.SERVER_ROW_SLUG`（列表行）
    两处各写一份，加一个新区要改两个文件、还容易改漏。
    """
    m = getattr(ctx.cfg, "SERVER_SLUG", None)
    return m if m else SERVER_ROW_SLUG_FALLBACK


def _slug_or_none(ctx: Ctx) -> str | None:
    srv = ctx.account.server
    if not srv:
        return None
    return slug_map(ctx).get(srv)


def _slug(ctx: Ctx) -> str:
    """取 slug。取不到抛 **ConfigError**（不是 StepFailure）——
    配置类错误必须立刻暴露，否则会被 `Runner.pick` 吞掉、空转到超时（问题 #9）。"""
    srv = ctx.account.server
    if not srv:
        raise ConfigError("未指定目标区（account.server 为空）")
    slug = _slug_or_none(ctx)
    if not slug:
        raise ConfigError(f"目标区「{srv}」没有登记模板 slug，请补进 config.SERVER_SLUG")
    return slug


def server_row_template(ctx: Ctx) -> str:
    """**列表行**模板（服务器列表弹窗里的一行）。

    坑：`config.SERVER_TPL[区名]` 存的是**登录面板**上那个区名小条（`tpl_server_*.png`），
    不是列表行（`tpl_srv_*.png`）。两个文件名只差三个字母，2026-10-02 实测正好因此
    用错模板、滚 6 帧都匹配不上（HANDOFF §6.1 也记着这对陷阱）。
    所以这里**一律按 slug 推列表行模板**，不取 SERVER_TPL。
    """
    return f"tpl_srv_{_slug(ctx)}.png"


def server_panel_template(ctx: Ctx) -> str | None:
    """**登录面板**区名小条模板（用于判断面板当前记着的区是不是目标区）。"""
    srv = ctx.account.server
    if not srv:
        return None
    reg = getattr(ctx.cfg, "SERVER_TPL", None) or {}
    if srv in reg:
        return reg[srv]
    slug = _slug_or_none(ctx)
    return f"tpl_server_{slug}.png" if slug else None


def panel_server_roi(ctx: Ctx):
    return getattr(ctx.cfg, "PANEL_SERVER_ROI", None) or PANEL_SERVER_ROI_FALLBACK


def submit_timeout(ctx: Ctx) -> float:
    return float(getattr(ctx.cfg, "SUBMIT_TIMEOUT", DEFAULT_SUBMIT_TIMEOUT))


def max_submit(ctx: Ctx) -> int:
    return int(getattr(ctx.cfg, "MAX_SUBMIT", DEFAULT_MAX_SUBMIT))


def waiting_for_server(ctx: Ctx) -> bool:
    """是否处于「已提交、等服务器响应」的等待期。窗口内不许重复点击。"""
    t = ctx.flags.get("submitted_at")
    return bool(t) and (time.time() - t) < submit_timeout(ctx)


def mark_submitted(ctx: Ctx):
    ctx.flags["submitted_at"] = time.time()
    ctx.flags["submit_count"] = ctx.flags.get("submit_count", 0) + 1


def clear_submitted(ctx: Ctx, why: str = ""):
    """清除提交状态。**只有「确实失败了」的真信号才该调它**（例如又回到原生登录框）。"""
    if ctx.flags.get("submitted_at"):
        ctx.log(f"  [登录] 清除提交状态{('（' + why + '）') if why else ''}")
    ctx.flags.pop("submitted_at", None)


def needs_server_check(ctx: Ctx) -> bool:
    """是否还需要去服务器列表核对一次目标区（问题 #2 的修复）。

    - 已确认过 -> False
    - `config.FORCE_SERVER_CHECK=True` 且本轮**还没核对过** -> True
      不信任「游戏记住了上次选区」：2026-10-02 端到端连跑 8 次，面板始终记得九州揽月，
      于是 `ServerSelectStep` 的滚动链路**从没在真机执行过** —— 那段最复杂的选区代码
      长期没有真机证据。强制核对让选区成为常态路径。
    - 否则 -> False，交给 `LoginPanelStep._confirm_from_panel()` 用面板区名条判。
    """
    if ctx.flags.get("server_confirmed") or ctx.flags.get("server_checked"):
        return False
    return bool(getattr(ctx.cfg, "FORCE_SERVER_CHECK", True))



def front_activity(ctx: Ctx) -> str:
    r = ctx.inter.shell("dumpsys", "activity", "activities")
    for line in r.stdout.decode("utf-8", "replace").splitlines():
        if "mResumedActivity" in line:
            return line.strip()
    return ""


# ---------------------------------------------------------------- 1 已进游戏
def ingame_server_template(ctx: Ctx) -> str | None:
    """游戏内「区名」模板 —— **自证所处的区的唯一强证据**。没标定则返回 None。

    现状（2026-10-02 查实）：**没有**这个模板，因为找不到素材 ——
    `_sim/in_game.png` 的 HUD 区根本没有区名控件；`screenshots/in_game/game_screen.png`
    实为闪屏（素材放错目录）。所以流程会**如实报告「区未经确认」**，而不是假装验过了。
    要启用：截一张干净的游戏内截图，量出区名控件，存 `tpl_ingame_<slug>.png` 即可，
    本函数的探测是自动的，不用改代码。
    """
    slug = _slug_or_none(ctx)
    if not slug:
        return None
    name = f"tpl_ingame_{slug}.png"
    return name if os.path.exists(os.path.join(ctx.base_dir, name)) else None


def verify_ingame_server(ctx: Ctx, img=None):
    """进游戏后能否自证所处的区。

    返回 True（是目标区）/ False（不是）/ **None（没标定模板 = 没验，不等于通过）**。
    """
    tpl = ingame_server_template(ctx)
    if not tpl:
        return None
    img = ctx.shot("_s_in_srv.png") if img is None else img
    s, c = ctx.score(tpl, img)
    ctx.log(f"  [区校验] 游戏内区名 {tpl} 得分={s:.3f} @{c}（阈值 {TH_PANEL_SERVER}）")
    return s >= TH_PANEL_SERVER


class InGameStep(Step):
    """已进入游戏世界。

    **本步的判据（`is_in_game`）与「区」无关** —— 它只看左上角绿血条，所以进了
    **任何**区都会为真。2026-10-02 实测：把目标区改成「不存在的区」，本步照样返回 True。
    这就是问题 #1「终点判据里没有区」：进错区也报成功。

    修法不是硬造一个假判据（游戏内暂时没有区名素材），而是**如实分级报告**：
    `act` 里把区的确认来源写进日志与 `ctx.flags`，让「成功」这件事有可区分的含义。
    """
    name = "已进游戏"
    priority = 10

    def detect(self, ctx):
        img = ctx.shot("_s_in.png")
        if not ctx.rec.is_in_game(img):
            return False
        self._srv = verify_ingame_server(ctx, img)   # True / False / None
        return True

    def act(self, ctx):
        srv = ctx.account.server or "未指定"
        src = ctx.flags.get("server_confirmed")
        g = getattr(self, "_srv", None)
        if g is True:
            ctx.flags["server_confirmed"] = "game"
            ctx.log(f"  ✅ 已进入游戏世界，且**游戏内区名核对通过**（目标区={srv}）")
        elif g is False:
            ctx.flags["server_mismatch"] = True
            ctx.log(f"  ❌ 已进入游戏世界，但**游戏内区名不是目标区「{srv}」** —— 区选错了！")
        elif src:
            label = {"game": "游戏内区名", "panel": "登录面板区名条",
                     "row": "服务器列表目标行"}.get(src, src)
            ctx.log(f"  ✅ 已进入游戏世界（区={srv}，由「{label}」确认；游戏内无区名模板可再核对）")
        else:
            ctx.flags["server_unverified"] = True
            ctx.log(f"  ⚠️ 已进入游戏世界，但**无法自证所处的区**（目标={srv}）——"
                    f" 进游戏后没有区名模板可比对（见 TASKS.md T-013 #1）")
        ctx.done = True
        ctx.result = True


# ---------------------------------------------------------------- 2 游戏不在前台
class HomeStep(Step):
    name = "拉起游戏"
    priority = 20

    def detect(self, ctx):
        front = front_activity(ctx)
        return bool(ctx.account.pkg) and ctx.account.pkg not in front

    def act(self, ctx):
        ctx.log(f"  -> 游戏不在前台，拉起 {ctx.account.pkg}")
        ctx.inter.shell("am", "start", "-n", f"{ctx.account.pkg}/{ctx.account.activity}")
        time.sleep(10)

    def verify(self, ctx):
        return ctx.account.pkg in front_activity(ctx)


# ---------------------------------------------------------------- 3 原生登录框
class LoginDialogStep(Step):
    """原生登录框（EditText + 确定/返回）。uiautomator 可读，所以走文字不走向量。"""
    name = "填登录框"
    priority = 30

    def detect(self, ctx):
        form = uia.edit_form(uia.ui_dump(ctx.inter))
        ctx._form = form                       # 供 act 复用，省一次 dump
        return form["user"] is not None and form["pass"] is not None

    def act(self, ctx):
        form = getattr(ctx, "_form", None) or uia.edit_form(uia.ui_dump(ctx.inter))
        # 又见登录框 = **上一轮提交确实没成**（而不是"计时到了"）—— 这是清除提交状态的真信号。
        clear_submitted(ctx, "又见原生登录框 = 上轮提交没成")
        cur = (form["user"] or {}).get("text", "")
        if cur == ctx.account.username and (form["pass"] or {}).get("text"):
            ctx.log(f"  [账号] 已是 {ctx.account.username}，无需重填")
        else:
            ctx.log(f"  [账号] 输入 {ctx.account.username}（当前='{cur}'）")
            uia.type_field(ctx.inter, form["user"]["center"], ctx.account.username)
            uia.type_field(ctx.inter, form["pass"]["center"], ctx.account.password)
            uia.hide_keyboard(ctx.inter)
        if form["ok"]:
            ctx.tap(*form["ok"])
            ctx.log(f"  [账号] 点「确定」@{form['ok']}")
        time.sleep(2)

    def verify(self, ctx):
        """判据：登录框已消失（不再有 EditText）。"""
        form = uia.edit_form(uia.ui_dump(ctx.inter))
        return form["user"] is None


# ---------------------------------------------------------------- 4 服务器列表（选区）
class ServerSelectStep(Step):
    """服务器列表：滚动 + 模板验证，选中目标区。

    这是 2026-10-02 查实的那条断链的修复：
      * 旧实现 `select_server()` 用 `tpl_enter.png`（实测最高 0.70，**够不到 0.85**）
        判断「弹窗已打开」，于是 4 个候选全部「未确认」，永远走不到真正选区；
      * 旧的状态机分支是「选角色数最大的行」，**完全不认目标区**；
      * 唯一实现「滚动 + 模板验证」的 `find_and_enter_server()` 是死代码，从没被调用。

    现在：用**首行模板**判断列表是否打开（实测 0.93），滚动时逐帧匹配目标区模板
    （实测「九州揽月」需上滑一次，命中 0.93），点中的坐标由模板决定，不写死。
    """
    name = "选目标区"
    priority = 40

    # 滑动起止点（相对高度）。**必须落在列表内容区内**：
    # 2026-10-02 对照实测 —— 起点 0.80(=1872px) 已贴到列表边框，手势被忽略、列表纹丝不动
    # （目标区 0.546 始终不出现）；起点 0.727(=1700px) 则一次上滑即可见目标区（0.863~0.925）。
    LIST_TOP = 0.535
    LIST_BOTTOM = 0.727
    MAX_SCROLL = 6

    # 列表框体的实际范围（相对高度），用于**把行模板匹配限制在列表里**。
    # 2026-10-02 量化：列表上下边框在 y≈1205 / 1846；顶部「当前区」小条在 y≈1130。
    # 两者都是「区名」字符串，不分区时小条能把行模板顶到 **0.796**（阈值 0.80 —— 只差 0.004！），
    # 一旦越线就会把面板上的小条当成列表行去点。限进本区后该伪匹配降到 **0.564**。
    # 注：`LIST_TOP/BOTTOM` 是**手势**范围，比框体窄（怕手势落在边框上被忽略），
    # 不能拿来当匹配区域 —— 实测最下面一行中心在 y=1770，已在 LIST_BOTTOM 之外。
    LIST_ROI = (0.0, 0.505, 1.0, 0.780)

    def detect(self, ctx):
        """列表已打开 ⟺ 列表区里能看到**任意一个已登记的区行**。

        为什么不能只认首行：滚动之后首行会被滑出视野（实测 `_s_scroll.png` 里
        首行模板只剩 **0.479**）。若上一次 `act` 抛了 StepFailure（目标区根本不在列表里），
        列表就停在滚动后的位置 —— 只认首行会漏判，于是流程落到别的 Step 上乱点。
        先查首行（刚打开时的常见情形，一次匹配就够），再查目标行，最后兜其余区。
        """
        img = ctx.shot("_s_list.png")
        slug = _slug_or_none(ctx)      # 用安全版：detect 里抛 ConfigError 会打断整轮检测
        order = [TPL_ROW_FIRST]
        if slug:
            order.append(f"tpl_srv_{slug}.png")
        order += [f"tpl_srv_{s}.png" for s in slug_map(ctx).values()]
        best = 0.0
        for tpl in order:
            s, _ = ctx.score(tpl, img, self.LIST_ROI)
            if s > best:
                best = s
            if best >= TH_ROW:
                break
        ctx._list_score = best
        return best >= TH_ROW

    def act(self, ctx):
        target = server_row_template(ctx)
        hit = self._scroll_to(ctx, target)
        if not hit:
            raise StepFailure(f"{self.MAX_SCROLL} 次滚动内没找到目标区行（{target}）")
        ctx.log(f"  [选区] 目标行命中 @{hit} -> 点击选中")
        ctx.tap(*hit)
        time.sleep(2.5)

    def verify(self, ctx):
        """判据：**列表已收起**（= 点击被游戏接受了）。

        三段否定，都是实测换来的：

        1. 不能用「首行模板消失」—— 滚动之后**首行本来就被滑出视野**（实测 0.479），
           那样无论点没点中 verify 都返回真，等于没验证；
        2. 不能用「回到面板且面板条==目标区」—— 2026-10-02 **真机实测**：点中目标行后
           游戏**直接进入该区**（跳到「角色选择」），根本回不到面板，该判据只拿到
           **0.421** 而误判失败（轨迹 `26.8s:选目标区✗`，虽然区其实选对了）；
        3. 用「列表区里**不再有任何已登记区行**」：列表收起后必然成立，且滚动后
           目标行/其他行仍会在视野里，所以不会因为滚动而恒真。

        至于「点的是不是目标行」，由 `act` 里 `_scroll_to` 的模板匹配分保证
        （真机实测命中 **0.958**）；那是「选对了」的直接证据，本步据此把确认级别
        记为 `row`，交给 `InGameStep.act` 如实分级报告。
        """
        img = ctx.shot("_s_list_v.png")
        rows = [TPL_ROW_FIRST] + [f"tpl_srv_{s}.png" for s in slug_map(ctx).values()]
        best, which = 0.0, None
        for t in rows:
            s, _ = ctx.score(t, img, self.LIST_ROI)
            if s > best:
                best, which = s, t
        if best >= TH_ROW:
            ctx.log(f"  [选区] 列表仍在（{which}={best:.3f}），未选中")
            return False

        # 列表已收起。但**光凭这一点不够** —— 点击落空、或列表被别的操作关掉时它同样成立。
        # 所以还要能看出"选区动作生效了"：回到目标区的面板，或者已经进入了某个区。
        ptpl = server_panel_template(ctx)
        ps = -1.0
        if ptpl:
            ps, _ = ctx.score(ptpl, img, panel_server_roi(ctx))
        eg, _ = ctx.score(TPL_ENTER_GAME, img)
        cc, _ = ctx.score(TPL_CREATE_CHAR, img)
        entered = max(eg, cc)

        if ps >= TH_PANEL_SERVER:
            ctx.flags["server_confirmed"] = "panel"
            ctx.flags["server_checked"] = True
            ctx.log(f"  [选区] 列表已收起（行分 {best:.3f}）；回到面板且区名条={ps:.3f} -> 级别「panel」")
            return True
        if entered >= 0.90:
            # 真机走的就是这条：点中目标行后游戏**直接进入该区**（跳到角色选择/创建人物），
            # 回不到面板。「列表里匹配到目标区行并点了它」本身就是一次确认（实测行分 0.958），
            # 而点击位置完全由模板匹配决定（不是固定坐标），所以不存在"点错行"。
            ctx.flags.setdefault("server_confirmed", "row")
            ctx.flags["server_checked"] = True
            ctx.log(f"  [选区] 列表已收起（行分 {best:.3f}）；已进入区（进入游戏/创建人物={entered:.3f}）"
                    f" -> 级别「{ctx.flags['server_confirmed']}」")
            return True
        ctx.log(f"  [选区] 列表已收起，但**既没回到目标区面板（{ps:.3f}）也没进入任何区（{entered:.3f}）**"
                f" -> 无法确认选区生效")
        return False

    def _scroll_to(self, ctx, target_tpl):
        """逐帧匹配目标区模板；没有就上滑一屏再试。返回命中中心或 None。

        匹配**限定在列表区内**（见 `LIST_ROI`）：列表外的同名文本（顶部「当前区」小条）
        分值可到 0.796，混进来会点错地方。
        """
        for i in range(self.MAX_SCROLL):
            img = ctx.shot("_s_scroll.png")
            s, c = ctx.score(target_tpl, img, self.LIST_ROI)
            ctx.log(f"    [选区] 第{i}帧 {target_tpl} = {s:.3f} @{c}")
            if s >= TH_ROW:
                return c
            w, h = ctx.size(img)
            x = w // 2
            ctx.inter.swipe(x, int(self.LIST_BOTTOM * h), x, int(self.LIST_TOP * h), 400)
            time.sleep(1.6)
        return None


# ---------------------------------------------------------------- 4.5 打开服务器列表
class OpenServerListStep(Step):
    """在登录面板上、目标区**尚未确认**时，点「切换」打开服务器列表。

    为什么要单独一步（原来这段并进 `LoginPanelStep.act`）：**`verify` 才可能有真判据**。
    合在一起时「点切换」与「点登陆游戏」两个分支共用一个 verify，而"面板还在不在"
    对前者恒为真 —— 那正是问题 #4（恒真的空验证）的由来。

    优先级 45（早于 `LoginPanelStep` 的 50）：「区没确认」这件事必须先解决。
    """
    name = "打开区列表"
    priority = 45

    def detect(self, ctx):
        if not needs_server_check(ctx):
            return False
        if not server_panel_template(ctx):
            return False     # 目标区没登记模板 -> 由 login() 入口拦下（ConfigError）
        img = ctx.shot("_s_open.png")
        sw, _ = ctx.score(TPL_SWITCH, img)
        mm, _ = ctx.score(TPL_MAINMENU, img)
        ctx._open_sw, ctx._open_mm = sw, mm
        return sw >= 0.72 and mm < TH_STRONG

    def act(self, ctx):
        ctx.log(f"  [选区] 目标区尚未确认（FORCE_SERVER_CHECK="
                f"{getattr(ctx.cfg, 'FORCE_SERVER_CHECK', True)}），点「切换」打开列表核对")
        ok, how = ctx.tap_tpl_or(TPL_SWITCH, fallback_pos=HINT_SWITCH_PANEL,
                                 hint=HINT_SWITCH_PANEL, pad=PAD_SMALL, threshold=0.70)
        ctx.log(f"  [选区] 切换按钮 -> {how}")
        time.sleep(5)

    def verify(self, ctx):
        """判据：**列表确实打开了**（列表区内能看到任一已登记区行）。

        实测（2026-10-02 / 1080x2340）：列表打开时首行模板 `tpl_srv_pingzong.png`
        在列表区内 0.93+；未打开时列表区里没有这个字符串。
        """
        img = ctx.shot("_s_open_v.png")
        best, which = 0.0, None
        for tpl in [TPL_ROW_FIRST] + [f"tpl_srv_{s}.png" for s in slug_map(ctx).values()]:
            s, _ = ctx.score(tpl, img, ServerSelectStep.LIST_ROI)
            if s > best:
                best, which = s, tpl
            if best >= TH_ROW:
                break
        ctx.log(f"  [选区] 列表核验：最高 {best:.3f}（{which}，阈值 {TH_ROW}）")
        return best >= TH_ROW


# ---------------------------------------------------------------- 5 登录面板
class LoginPanelStep(Step):
    """登录面板（有大区列表入口「切换」，服务器栏可能显示 `--`）。

    说明：面板上也有「登陆游戏」按钮，所以不能靠它区分面板与主菜单；
    用 `tpl_switch`（面板独有）来认。

    **只有目标区已确认之后**本步才提交。「未确认时去打开列表」拆给了
    `OpenServerListStep`(45) —— 这样两步各有真判据，不再是恒真的空验证（问题 #4）。
    """
    name = "登录面板"
    priority = 50

    def detect(self, ctx):
        img = ctx.shot("_s_panel.png")
        sw, _ = ctx.score(TPL_SWITCH, img)
        mm, _ = ctx.score(TPL_MAINMENU, img)
        ctx._panel_switch, ctx._panel_mainmenu = sw, mm
        return sw >= 0.72 and mm < TH_STRONG

    def act(self, ctx):
        # 0) 已提交且未超时 -> 安静等（**状态机，不是计时器**：超时才允许下一次提交）
        if waiting_for_server(ctx):
            left = submit_timeout(ctx) - (time.time() - ctx.flags["submitted_at"])
            ctx.log(f"  [登录] 已提交，等待服务器响应（剩 {left:.0f}s，期间不重复点击）")
            time.sleep(6)
            return

        # 1) 提交次数到顶 -> 明确失败，不空转到 deadline
        n = ctx.flags.get("submit_count", 0)
        if n >= max_submit(ctx):
            raise StepFailure(
                f"已提交 {n} 次（上限 {max_submit(ctx)}）仍未进入游戏 —— 停止重试，"
                f"请查账号/密码/网络/区是否正确")

        # 2) 确保账号已填
        form = uia.edit_form(uia.ui_dump(ctx.inter))
        if form["user"] is not None:
            self._fill(ctx, form)

        # 3) 区必须已确认。未确认时 `OpenServerListStep`(45) 本应先被挑中；
        #    走到这里说明它没成功（例如 FORCE_SERVER_CHECK=False），就地用面板条复核。
        if not ctx.flags.get("server_confirmed"):
            self._confirm_from_panel(ctx)
        if not ctx.flags.get("server_confirmed"):
            ctx.log("  [选区] 区仍未确认 -> 本步不提交（先把区确认了）")
            time.sleep(2)
            return

        # 4) 提交登录
        ctx.log(f"  [选区] 区已确认（来源 {ctx.flags['server_confirmed']}），点「登陆游戏」")
        ok, how = ctx.tap_tpl_or(TPL_LOGIN_GAME_PANEL, fallback_pos=HINT_LOGIN_BTN_PANEL,
                                 hint=HINT_LOGIN_BTN_PANEL, pad=PAD_BTN)
        ctx.log(f"  [登录] 登陆游戏 -> {how}")
        mark_submitted(ctx)
        time.sleep(8)

    def verify(self, ctx):
        """判据：**离开登录面板**，或处于「已提交、等服务器响应」的等待期。

        原先这里是 `return True` —— 问题 #4：恒真的空验证，点空了也记「成功」。
        真判据两部分：
          * 等待期内 -> 成功（本步此刻的动作就是"等"，没有"做错"这回事）；
          * `tpl_switch` 掉下阈值 -> 已离开面板 -> 成功。
        实测（2026-10-02 / 1080x2340）：`tpl_switch` 在登录面板 **0.874~0.991**；
        角色选择 0.444、游戏内 0.407、列表打开 0.613~0.657 —— 阈值 0.72 两侧余量充足。
        """
        if waiting_for_server(ctx):
            return True
        img = ctx.shot("_s_panel_v.png")
        sw, _ = ctx.score(TPL_SWITCH, img)
        if sw < 0.72:
            ctx.log(f"  [面板] 已离开登录面板（切换按钮={sw:.3f}）")
            return True
        ctx.log(f"  [面板] 仍在登录面板（切换按钮={sw:.3f}）且不在等待期 -> 本步未推进")
        return False

    def _fill(self, ctx, form):
        cur = (form["user"] or {}).get("text", "")
        if cur == ctx.account.username and (form["pass"] or {}).get("text"):
            ctx.log(f"  [账号] 已是 {ctx.account.username}")
            return
        ctx.log(f"  [账号] 输入 {ctx.account.username}（当前='{cur}'）")
        uia.type_field(ctx.inter, form["user"]["center"], ctx.account.username)
        uia.type_field(ctx.inter, form["pass"]["center"], ctx.account.password)
        uia.hide_keyboard(ctx.inter)

    def _confirm_from_panel(self, ctx):
        """用**面板区名条**确认目标区。

        这是问题 #3 的修复：原为**全图**匹配（`ctx.score(panel_tpl, img)` 没传区域），
        画面任何位置出现同款区名文本（服务器列表里也有区名行）都会被判 True。
        现在限定进 `config.PANEL_SERVER_ROI` —— 区名条固定在相对 (0.500, 0.696)。

        2026-10-22 新增 ALLOW_TRUST_ZONE：服务器栏是半透明条叠在**动态背景**上，
        文字像素逐帧变化，模板匹配天生不稳（同号两次实测 1.000 / 0.758）。
        对图流程（本队固定一个区）开启它 = 信任面板记住的区、跳过逐帧核对，
        直接提交 —— 由证据图与告警日志兜底，见 op_align_map。
        """
        if getattr(config, "ALLOW_TRUST_ZONE", False):
            ctx.flags["server_confirmed"] = "trusted"
            ctx.log("  [选区] ALIGN 模式：信任面板记住的区（固定区队），跳过逐帧核对")
            return True
        panel_tpl = server_panel_template(ctx)
        if not panel_tpl:
            ctx.log("  [选区] 目标区没有面板区名模板")
            return False
        tries = ctx.flags.get("_panel_tries", 0)
        if tries >= 2:
            raise StepFailure(
                f"面板区名条连续 {tries} 次未命中（{panel_tpl}）—— 停止重试。"
                f"可能是区没选对，或模板与当前环境失配（换环境要重截模板）")
        ctx.flags["_panel_tries"] = tries + 1
        roi = panel_server_roi(ctx)
        img = ctx.shot("_s_panel2.png")
        s, c = ctx.score(panel_tpl, img, roi)
        ctx.log(f"  [选区] 面板区名条 {panel_tpl} 得分={s:.3f} @{c}（区域 {roi}，阈值 {TH_PANEL_SERVER}）")
        if s >= TH_PANEL_SERVER:
            ctx.flags["server_confirmed"] = "panel"
            return True
        return False


# ---------------------------------------------------------------- 6 主菜单
class MainMenuStep(Step):
    name = "主菜单"
    priority = 60

    def detect(self, ctx):
        img = ctx.shot("_s_menu.png")
        s, _ = ctx.score(TPL_MAINMENU, img)
        ctx._menu_score = s
        return s >= TH_STRONG

    def act(self, ctx):
        ok, how = ctx.tap_tpl_or(TPL_LOGIN_GAME, fallback_pos=(0.5, 0.763),
                                 hint=HINT_LOGIN_BTN_MAINMENU, pad=PAD_BTN)
        ctx.log(f"  [主菜单] 点「登陆游戏」-> {how}")
        time.sleep(5)

    def verify(self, ctx):
        """判据：离开主菜单（主菜单模板不再高分）。"""
        img = ctx.shot("_s_menu_v.png")
        s, _ = ctx.score(TPL_MAINMENU, img)
        return s < TH_STRONG


# ---------------------------------------------------------------- 7 角色选择
class EnterGameStep(Step):
    """角色选择界面：显示已有角色 + 「进入游戏」按钮，点它进世界。

    2026-10-02 实测补的一步。**当时误以为账号缺角色**（TASKS T-010），
    真相是游戏当时用了错的区（自动选了萍踪侠影），所以到了「创建人物」；
    正确选到九州揽月后，`REDACTED_LOCAL_CREDENTIAL` 是有角色的（武圣/威海/70级），走的是本界面。
    """
    name = "进入游戏"
    priority = 65

    def detect(self, ctx):
        img = ctx.shot("_s_eg.png")
        s, _ = ctx.score(TPL_ENTER_GAME, img)
        ctx._eg_score = s
        return s >= 0.90

    def act(self, ctx):
        ok, how = ctx.tap_tpl_or(TPL_ENTER_GAME, fallback_pos=HINT_ENTER_GAME,
                                 hint=HINT_ENTER_GAME, pad=PAD_BTN)
        ctx.log(f"  [角色] 点「进入游戏」-> {how}")
        time.sleep(10)

    def verify(self, ctx):
        img = ctx.shot("_s_eg_v.png")
        s, _ = ctx.score(TPL_ENTER_GAME, img)
        return s < 0.90


# ---------------------------------------------------------------- 8 创建人物
class CharCreateStep(Step):
    name = "创建人物"
    priority = 70

    def detect(self, ctx):
        img = ctx.shot("_s_cc.png")
        s, c = ctx.score(TPL_CREATE_CHAR, img)
        ctx._cc = (s, c)
        return s >= 0.90

    def act(self, ctx):
        if not getattr(ctx.cfg, "ALLOW_CREATE", False):
            raise StepFailure(
                "账号在该区没有角色，停在「创建人物」；config.ALLOW_CREATE=False 故不自动建号"
                "（建号是不可逆的游戏内操作，需用户裁决 —— 见 TASKS.md T-010）")
        s, c = ctx._cc
        ctx.log(f"  [建角] 点「创建人物」@{c}")
        ctx.tap(*c)
        time.sleep(8)

    def verify(self, ctx):
        img = ctx.shot("_s_cc_v.png")
        s, _ = ctx.score(TPL_CREATE_CHAR, img)
        return s < 0.90


# ---------------------------------------------------------------- 9 兜底
class SplashStep(Step):
    """没被任何界面认领时的兜底：关弹窗（+ 必要时点「点击继续」）。

    故意放在最后一位（detect 恒为真）—— 「认不出的界面」也有确定行为，
    而不是让 Runner 直接崩掉；找不到对应 Step 的界面应当在这里留下痕迹，
    再回头补一个 Step（或补模板）。

    **修复 #6**：原来是无条件按固定坐标点屏幕 —— 认不出的界面会一直乱点，
    点到 deadline 才停，而超时的日志只会说一句"没跑完"，把真正的病因埋掉。
    现在：
      * 固定坐标点击**限次**（`MAX_TAPS`），到顶后空转 `MAX_IDLE` 轮就**明确失败**；
      * 等待期内只关弹窗、不点屏幕（实测乱点会把正在进行的连接打断）。

    > 注：本想改成「优先模板匹配」（新建 `tpl_continue.png`），实测**放弃** ——
    > 真机截图裁的模板在模拟器截图上只有 **0.457**（跨环境失配，本仓老坑），
    > 且 `_sim/` 里没有模拟器环境的闪屏素材。故只做「限次」。
    """
    name = "兜底推进"
    priority = 999
    MAX_TAPS = 2        # 固定坐标点击上限
    MAX_IDLE = 4        # 到顶后还认不出，空转几轮就明确失败

    def detect(self, ctx):
        return True

    def act(self, ctx):
        # 关弹窗失败**不能**让兜底步骤自己崩掉 —— 它是最后一道保险，必须永远不抛。
        # （离线回归实测：uia dump 异常时会抛 AttributeError，导致兜底连败 3 次后
        #   整个流程被中止 —— 恰好把"兜底"变成了"最先崩的那一步"。）
        try:
            dialogs.dismiss(ctx.inter, max_tries=2, log=ctx.log)
        except Exception as e:
            ctx.log(f"  [兜底] 关弹窗失败（忽略）: {type(e).__name__}: {e}")
        if waiting_for_server(ctx):
            ctx.log("  [兜底] 登录已提交，等待服务器响应（不点屏幕）")
            time.sleep(5)
            return
        n = ctx.flags.get("splash_taps", 0)
        if n >= self.MAX_TAPS:
            idle = ctx.flags.get("splash_idle", 0) + 1
            ctx.flags["splash_idle"] = idle
            if idle >= self.MAX_IDLE:
                raise StepFailure(
                    f"认不出的界面已持续 {idle} 轮（固定坐标已点满 {n} 次）—— **明确失败**，"
                    f"不空转到 deadline。请为这个界面补一个 Step 或模板")
            ctx.log(f"  [兜底] ⚠️ 认不出的界面（第 {idle}/{self.MAX_IDLE} 轮），"
                    f"已点满 {n} 次 -> 停止乱点")
            time.sleep(3)
            return
        ctx.flags["splash_taps"] = n + 1
        ctx.log(f"  [兜底] ⚠️ 认不出当前界面，按「点击继续」位置点一次"
                f"（{n + 1}/{self.MAX_TAPS}，该坐标待标定）")
        ctx.tap_rel(*ctx.cfg.POINTS["continue_splash"])
        time.sleep(4)


def register_all():
    """注册全部登录步骤。**加新界面就在这里加一行**（或在自己的模块里 register）。"""
    for s in (InGameStep(), HomeStep(), LoginDialogStep(), ServerSelectStep(),
              OpenServerListStep(), LoginPanelStep(), MainMenuStep(), EnterGameStep(),
              CharCreateStep(), SplashStep()):
        steps.register(s)
    # 通用界面（Canvas 弹窗）不属于登录流程，放在自己的模块里。
    # 在这里委托一次是为了让 `auto_login.login()` **一行都不用改** ——
    # 那个文件的门面职责不该被新界面的注册逻辑污染（见它的模块 docstring）。
    import canvas_dialog
    canvas_dialog.register_all()
