"""可复用刷怪核心；不读取账号、不登录、不交任务、不切换地图。"""
from dataclasses import asdict, dataclass, field
from pathlib import Path
from threading import Event
import time
from interaction import Interaction
from recognition import Recognizer
from steps import Step
from . import config


class GrindStopped(RuntimeError):
    pass


@dataclass
class GrindResult:
    requested: int
    completed: int = 0
    status: str = 'STOPPED'
    reason: str = ''
    elapsed: float = 0
    rounds: list = field(default_factory=list)
    # 场景返回只能确认战斗结束，不能冒充铜币到账或胜利结算。
    copper_delta: int | None = None

    def to_dict(self):
        return asdict(self)


class Context:
    def __init__(self, inter, rec, output, cancel, callback, package, deadline):
        self.inter, self.rec = inter, rec
        self.output = Path(output)
        self.output.mkdir(parents=True, exist_ok=True)
        self.cancel, self.callback, self.package = cancel, callback, package
        self.deadline = deadline
        self.frame = None
        self.sequence = 0

    def guard(self):
        if self.cancel.is_set():
            raise GrindStopped('CANCELLED')
        if time.monotonic() >= self.deadline:
            raise GrindStopped('RUN_TIMEOUT')

    def emit(self, event, **values):
        self.callback({'event': event, **values})

    def shot(self):
        self.guard()
        self.sequence += 1
        path = self.output / f'_frame_{self.sequence:05d}.png'
        self.frame = self.inter.capture(str(path))
        if self.frame is None:
            raise GrindStopped('CAPTURE_FAILED')
        return self.frame

    def wait_for(self, predicate, timeout, stable=1):
        end = min(time.monotonic()+timeout, self.deadline)
        consecutive = 0
        while time.monotonic() < end:
            self.guard()
            if predicate(self.shot()):
                consecutive += 1
                if consecutive >= stable:
                    return True
            else:
                consecutive = 0
            self.cancel.wait(config.POLL_SECONDS)
        return False

    def check_foreground(self):
        result = self.inter.shell('dumpsys', 'activity', 'activities')
        if result.returncode != 0:
            raise GrindStopped('FOREGROUND_QUERY_FAILED')
        text = result.stdout.decode('utf-8', errors='replace')
        if not any('mResumedActivity' in line and self.package+'/' in line for line in text.splitlines()):
            raise GrindStopped('WRONG_FOREGROUND')


class AttackMonsterStep(Step):
    name = 'attack_monster'

    def detect(self, ctx):
        return ctx.rec.is_world(ctx.shot())

    def act(self, ctx):
        ctx.guard()
        ctx.check_foreground()
        # 使用点击前的新帧重新核对地图、底栏和目标；不沿用旧定位。
        frame = ctx.shot()
        if not ctx.rec.is_world(frame):
            raise GrindStopped('SCENE_CHANGED_BEFORE_TAP')
        point = ctx.rec.enemy_position(frame)
        if point is None:
            raise GrindStopped('ENEMY_NOT_FOUND')
        x, y = point
        ctx.emit('attack_submitted', position_rel=[x, y])
        result = ctx.inter.tap(round(x*frame.shape[1]), round(y*frame.shape[0]))
        if result.returncode != 0:
            raise GrindStopped('TAP_FAILED_NO_RETRY')

    def verify(self, ctx):
        # 只有明确观察到战斗专属UI才算进入；怪物文字消失不作为判据。
        return ctx.wait_for(ctx.rec.is_battle, config.ENTER_TIMEOUT)


class WaitBattleStep(Step):
    name = 'wait_battle'

    def detect(self, ctx):
        return ctx.rec.is_battle(ctx.frame)

    def act(self, ctx):
        ctx.emit('battle_entered')

    def verify(self, ctx):
        # 回到同地图、底栏恢复、同名怪仍在；连着两帧成立才允许下一轮。
        return ctx.wait_for(ctx.rec.is_world, config.BATTLE_TIMEOUT, config.STABLE_FRAMES)


class GrindRunner:
    """rec 为 Recognizer 的扩展实现，需提供 is_world/is_battle/enemy_position。

    Android 接入时注入无障碍或触控 Interaction 与本地图识别适配器，业务流程不变。
    callback 收到结构化进度；cancel 为 threading.Event，可由UI停止按钮设置。
    """
    def __init__(self, inter: Interaction, rec: Recognizer, output,
                 callback=None, cancel=None, package=config.PACKAGE):
        self.inter, self.rec, self.output = inter, rec, output
        self.callback = callback or (lambda event: None)
        self.cancel = cancel or Event()
        self.package = package
        self.steps = []
        self.register(AttackMonsterStep())
        self.register(WaitBattleStep())

    def register(self, step):
        self.steps.append(step)

    def run(self, rounds=3, max_seconds=600):
        if type(rounds) is not int or not 1 <= rounds <= 1000:
            raise ValueError('rounds 必须为1..1000的整数')
        if not 0 < max_seconds <= 86400:
            raise ValueError('max_seconds 必须在0..86400之间')
        started = time.monotonic()
        report = GrindResult(rounds)
        ctx = Context(self.inter, self.rec, self.output, self.cancel,
                      self.callback, self.package, started+max_seconds)
        try:
            ctx.check_foreground()
            if not ctx.wait_for(ctx.rec.is_world, config.READY_TIMEOUT, config.STABLE_FRAMES):
                raise GrindStopped('NOT_AT_CONFIGURED_MONSTER')
            for number in range(1, rounds+1):
                ctx.guard()
                entry = {'round': number, 'status': 'STARTED'}
                report.rounds.append(entry)
                ctx.emit('round_started', round=number)
                round_started = time.monotonic()
                for step in self.steps:
                    if not step.detect(ctx):
                        raise GrindStopped(step.name+':DETECT_FAILED')
                    step.act(ctx)
                    if not step.verify(ctx):
                        raise GrindStopped(step.name+':VERIFY_TIMEOUT_NO_RETRY')
                entry.update(status='BATTLE_RETURNED', elapsed=round(time.monotonic()-round_started, 2))
                report.completed += 1
                ctx.emit('round_completed', **entry)
            report.status = 'COMPLETED'
        except GrindStopped as exc:
            report.reason = str(exc)
        except KeyboardInterrupt:
            report.reason = 'CANCELLED'
        except Exception as exc:
            # 不输出可能带设备或账号字段的底层异常信息。
            report.reason = 'ERROR:'+type(exc).__name__
        finally:
            if report.rounds and report.rounds[-1]['status'] == 'STARTED':
                report.rounds[-1]['status'] = 'UNVERIFIED_STOPPED'
            report.elapsed = round(time.monotonic()-started, 2)
            ctx.emit('finished', **report.to_dict())
        return report
