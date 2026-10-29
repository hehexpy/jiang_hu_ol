"""默认配置标定于安卓9模拟器；其他地图应建立独立配置与同源模板。"""
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'com.good.mmo2'
REFERENCE_WIDTH = 1080
POLL_SECONDS = .3
ENTER_TIMEOUT = 15.0
BATTLE_TIMEOUT = 90.0
READY_TIMEOUT = 8.0
MATCH_THRESHOLD = .92
MAX_MEAN_ERROR = 13.0
STABLE_FRAMES = 2


@dataclass(frozen=True)
class Anchor:
    template: str
    hint: tuple[float, float]
    pad: tuple[float, float]


# 模板均在此登记；无全图搜索或固定坐标点击后备路径。
BUTTON_HINTS = {
    'enemy': Anchor('tpl_grind_enemy.png', (.268, .526), (.13, .025)),
    'map': Anchor('tpl_grind_world_map.png', (.931, .086), (.065, .025)),
    'tasks': Anchor('tpl_grind_world_tasks.png', (.067, .914), (.073, .036)),
    'battle': Anchor('tpl_grind_battle_money.png', (.803, .021), (.047, .023)),
}
