"""招兵、进攻、驻防编排。设备操作只经过 Interaction，识别只经过 Recognizer。"""
from dataclasses import dataclass
import math
from pathlib import Path
import re
import time


PACKAGES = (
    "com.good.mmo6", "com.good.mmo2", "com.good.mmo4", "com.good.mmo3",
    "com.good.mmo9", "com.good.mmo15", "com.good.qw777", "com.good.usd6666",
    "com.good.usd7777", "com.good.usd8888", "com.good.u9999", "com.good.mmo11",
    "com.good.mmo12", "com.good.mmo13", "com.good.qw555",
)
TROOP_PAGES = (("红卫", "明王", "巨盾"), ("方阵", "轻骑", "侦查"),
               ("狼骑", "重骑", "投石"), ("弩车", "长弓"))


class CampaignError(RuntimeError):
    """前置检查或动作结果未达成；禁止继续后续动作。"""


@dataclass(frozen=True)
class Visit:
    cycle: int
    package: str
    phase: str


def build_plan(packages=PACKAGES, cycles=1):
    """先整组进攻，再整组驻防；惰性迭代，支持长周期。"""
    if isinstance(cycles, bool) or not isinstance(cycles, int) or cycles < 1:
        raise CampaignError("[配置] cycles 必须是正整数")
    packages = tuple(packages)
    if not packages or len(set(packages)) != len(packages):
        raise CampaignError("[配置] 包列表为空或重复")
    if any(not re.fullmatch(r"[A-Za-z]\w*(?:\.[A-Za-z]\w*)+", p) for p in packages):
        raise CampaignError("[配置] 包名格式错误")
    for cycle in range(1, cycles + 1):
        for phase in ("attack", "defend"):
            for package in packages:
                yield Visit(cycle, package, phase)


def action_keys(phase):
    """重复点击改为一次点击+结果核验；不在未知画面盲点第二下。"""
    keys = ["occupy", "recruit_open"]
    for page, troops in enumerate(TROOP_PAGES, 1):
        for troop in troops:
            keys.extend((f"recruit_{troop}", f"confirm_{troop}"))
        if page < len(TROOP_PAGES):
            keys.append(f"recruit_page_{page + 1}")
    keys.append("recruit_close")
    if phase == "attack":
        keys.extend(("attack_open", "attack_target", "attack_submit"))
    elif phase == "defend":
        keys.extend(("defend_open", "defend_submit"))
    else:
        raise CampaignError("[配置] 未知阶段")
    return keys


def _checked(result, label):
    if result.returncode:
        raise CampaignError(f"[设备] {label}失败（退出码 {result.returncode}）")
    return result.stdout.decode("utf-8", "replace")


def installed_packages(inter):
    return {line.removeprefix("package:").strip() for line in
            _checked(inter.shell("pm", "list", "packages"), "查询包").splitlines()
            if line.startswith("package:")}


def validate_profile(profile, base_dir):
    """执行前整体验证，缺最后一步模板也不允许先执行前面步骤。"""
    actions = profile.get("actions", {})
    required = set(action_keys("attack") + action_keys("defend"))
    for key in sorted(required):
        spec = actions.get(key)
        if not isinstance(spec, dict):
            raise CampaignError(f"[标定] 缺少动作 {key}")
        for field in ("button", "before", "after"):
            marker = spec.get(field)
            if not isinstance(marker, dict):
                raise CampaignError(f"[标定] {key}.{field} 未标定")
            path = Path(base_dir) / marker.get("template", "")
            if not path.is_file():
                raise CampaignError(f"[标定] {key}.{field} 模板文件不存在")
            for name in ("hint", "pad"):
                value = marker.get(name)
                if not isinstance(value, (list, tuple)) or len(value) != 2 or any(
                    isinstance(v, bool) or not isinstance(v, (int, float))
                    or not math.isfinite(v) or not 0 < v < 1 for v in value
                ):
                    raise CampaignError(f"[标定] {key}.{field}.{name} 必须为归一化二元组")
            threshold = marker.get("threshold")
            if not isinstance(threshold, (int, float)) or not 0 < threshold < 1:
                raise CampaignError(f"[标定] {key}.{field}.threshold 无效")
        if spec["before"] == spec["after"]:
            raise CampaignError(f"[标定] {key} 的前后判据相同")
    for key in ("launch_timeout", "verify_timeout", "poll_interval", "between_apps"):
        value = profile.get(key)
        if isinstance(value, bool) or not isinstance(value, (float, int)) or not math.isfinite(value) or value <= 0:
            raise CampaignError(f"[配置] {key} 必须为正秒数")


class CampaignRunner:
    def __init__(self, inter, rec, profile, base_dir, artifact_dir,
                 sleep=time.sleep, clock=time.monotonic, log=print):
        self.inter, self.rec, self.profile = inter, rec, profile
        self.base_dir, self.artifact_dir = Path(base_dir), Path(artifact_dir)
        self.sleep, self.clock, self.log = sleep, clock, log

    def capture(self):
        self.artifact_dir.mkdir(parents=True, exist_ok=True)
        img = self.inter.capture(str(self.artifact_dir / "_campaign.png"))
        if img is None:
            raise CampaignError("[设备] 截图失败")
        return img

    def match(self, img, marker):
        return self.rec.find_button_near(
            img, str(self.base_dir / marker["template"]), marker["hint"],
            pad=marker["pad"], threshold=marker["threshold"], pad_grow=1.0)

    def foreground(self, package):
        text = _checked(self.inter.shell("dumpsys", "activity", "activities"), "查询前台")
        return any("mResumedActivity" in line and re.search(
            r"\bu\d+\s+" + re.escape(package) + r"/", line) for line in text.splitlines())

    def launch(self, package):
        result = _checked(self.inter.shell("cmd", "package", "resolve-activity", "--brief", package), "解析启动页")
        component = next((line.strip() for line in reversed(result.splitlines())
                          if re.fullmatch(re.escape(package) + r"/[\w.$]+", line.strip())), None)
        if not component:
            raise CampaignError(f"[启动] 无法解析 {package} 的启动页")
        _checked(self.inter.shell("am", "start", "-W", "-n", component), "启动应用")
        end = self.clock() + self.profile["launch_timeout"]
        while self.clock() < end:
            if self.foreground(package):
                return
            self.sleep(self.profile["poll_interval"])
        raise CampaignError(f"[启动] {package} 未成为前台")

    def act(self, package, key):
        if not self.foreground(package):
            raise CampaignError("[设备] 前台包不符，停止点击")
        spec = self.profile["actions"][key]
        img = self.capture()
        if self.match(img, spec["before"]) is None:
            raise CampaignError(f"[判据] {key} 前置界面不符")
        # 结果在动作前就存在，不能证明此次操作成功。
        if self.match(img, spec["after"]) is not None:
            raise CampaignError(f"[判据] {key} 结果在操作前已存在")
        point = self.match(img, spec["button"])
        if point is None:
            raise CampaignError(f"[定位] {key} 按钮未命中")
        result = self.inter.tap(*point)
        if result is not None and hasattr(result, "returncode"):
            _checked(result, "点击")
        end = self.clock() + self.profile["verify_timeout"]
        while self.clock() < end:
            if not self.foreground(package):
                raise CampaignError("[设备] 验证时前台包变化")
            if self.match(self.capture(), spec["after"]) is not None:
                self.log(f"[验证] {key} 成功")
                return
            self.sleep(self.profile["poll_interval"])
        raise CampaignError(f"[验证] {key} 未观察到结果，停止；不重复提交")

    def run(self, packages=PACKAGES, cycles=1):
        packages = tuple(packages)
        plan = build_plan(packages, cycles)
        first = next(plan)  # 参数校验先于设备操作
        validate_profile(self.profile, self.base_dir)
        if not self.inter.wait_device():
            raise CampaignError("[设备] 设备不在线")
        missing = set(packages) - installed_packages(self.inter)
        if missing:
            raise CampaignError("[设备] 缺少游戏包：" + ", ".join(sorted(missing)))
        from itertools import chain
        completed = 0
        for visit in chain((first,), plan):
            self.log(f"[流程] 第 {visit.cycle} 轮 {visit.package} {visit.phase}")
            self.launch(visit.package)
            for key in action_keys(visit.phase):
                self.act(visit.package, key)
            completed += 1
            self.sleep(self.profile["between_apps"])
        return completed
