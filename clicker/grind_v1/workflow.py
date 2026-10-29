"""有显式前后判据的任务流程；界面和按钮由独立JSON配置登记。"""
import json
from pathlib import Path
import time
from threading import Event
from steps import Step
from .config import Anchor, PACKAGE
from .engine import Context, GrindStopped
from .recognition import GrindRecognizer


class WorkflowRecognizer(GrindRecognizer):
    def __init__(self, manifest):
        self.path = Path(manifest)
        self.spec = json.loads(self.path.read_text(encoding='utf-8'))
        names = set(self.spec['anchors'])
        ids = set()
        for step in self.spec['steps']:
            if step['id'] in ids or not step.get('pre'):
                raise ValueError('重复节点或缺少前置判据')
            ids.add(step['id'])
            if not step.get('stop_reason') and not step.get('post'):
                raise ValueError('动作必须配置真实后置判据')
            for group in [step['pre'], *step.get('post', [])]:
                if not group or not set(group) <= names:
                    raise ValueError('空判据或未登记模板')
            for group in step.get('post', []):
                if set(group) <= set(step['pre']):
                    raise ValueError('后置判据在动作前已经成立')
            if step.get('tap') and step['tap'] not in names:
                raise ValueError('未登记点击目标')
        anchors = {key: Anchor(value['template'], tuple(value['hint']), tuple(value['pad']))
                   for key, value in self.spec['anchors'].items()}
        super().__init__(anchors, self.path.parent)

    def matches(self, frame, names):
        return all(self.locate(frame, name) is not None for name in names)

    def alternatives(self, frame, groups):
        return any(self.matches(frame, names) for names in groups)


class WorkflowStep(Step):
    def __init__(self, spec):
        self.spec = spec
        self.name = spec['id']

    def detect(self, ctx):
        return (all(ctx.flags.get(key) == value for key, value in self.spec.get('requires_flags', {}).items())
                and ctx.rec.matches(ctx.frame, self.spec['pre'])
                and not any(ctx.rec.locate(ctx.frame, key) is not None for key in self.spec.get('exclude', [])))

    def act(self, ctx):
        ctx.guard()
        ctx.check_foreground()
        frame = ctx.shot()
        if not self.detect(ctx):
            raise GrindStopped(self.name+':SCENE_CHANGED')
        if self.spec.get('tap'):
            point = ctx.rec.locate(frame, self.spec['tap'])
            if point is None:
                raise GrindStopped(self.name+':TARGET_MISSING')
            # 所有模板位置由归一化配置定义；接口边界才转换像素。
            dx, dy = self.spec.get('tap_offset', (0, 0))
            rel = (point[0]/frame.shape[1]+dx, point[1]/frame.shape[0]+dy)
            if not all(0 <= value <= 1 for value in rel):
                raise GrindStopped(self.name+':INVALID_TARGET')
            ctx.emit('action_submitted', step=self.name, position_rel=rel)
            result = ctx.inter.tap(round(rel[0]*frame.shape[1]), round(rel[1]*frame.shape[0]))
            if result.returncode != 0:
                raise GrindStopped(self.name+':TAP_FAILED_NO_RETRY')

    def verify(self, ctx):
        return ctx.wait_for(lambda frame: ctx.rec.alternatives(frame, self.spec['post']),
                            self.spec.get('timeout', 20), self.spec.get('stable', 2))


class WorkflowRunner:
    """可续跑的界面状态图；未知画面停机，不靠旧坐标推进。"""
    def __init__(self, inter, recognizer, output, callback=None, cancel=None, package=PACKAGE):
        self.inter, self.rec, self.output = inter, recognizer, output
        self.callback = callback or (lambda event: None)
        self.cancel = cancel or Event()
        self.package = package
        self.steps = []
        for spec in recognizer.spec['steps']:
            self.register(WorkflowStep(spec))

    def register(self, step):
        self.steps.append(step)

    def run(self, rounds=3, max_seconds=900):
        if type(rounds) is not int or not 1 <= rounds <= 1000:
            raise ValueError('rounds 必须为1..1000整数')
        if not 0 < max_seconds <= 86400:
            raise ValueError('max_seconds 超出范围')
        started = time.monotonic()
        ctx = Context(self.inter, self.rec, self.output, self.cancel,
                      self.callback, self.package, started+max_seconds)
        ctx.flags = {}
        report = {'status': 'STOPPED', 'completed': 0, 'requested': rounds,
                  'actions': [], 'copper_delta': None, 'reason': ''}
        idle_started = time.monotonic()
        try:
            while True:
                ctx.check_foreground()
                ctx.shot()
                found = [step for step in self.steps if step.detect(ctx)]
                if len(found) > 1:
                    raise GrindStopped('AMBIGUOUS_STATE')
                if not found:
                    if time.monotonic()-idle_started > 12:
                        raise GrindStopped('UNKNOWN_STATE')
                    self.cancel.wait(.4)
                    continue
                step = found[0]
                if step.spec.get('stop_reason'):
                    raise GrindStopped(step.spec['stop_reason'])
                step.act(ctx)
                if not step.verify(ctx):
                    raise GrindStopped(step.name+':VERIFY_FAILED_NO_RETRY')
                report['actions'].append(step.name)
                ctx.emit('step_verified', step=step.name)
                ctx.flags.update(step.spec.get('set_flags', {}))
                count_round = (step.spec.get('round_completed') and
                               ctx.flags.get('cycle_battle_observed', False))
                if count_round:
                    report['completed'] += 1
                    ctx.flags['cycle_battle_observed'] = False
                    ctx.emit('round_completed', completed=report['completed'])
                    if report['completed'] >= rounds:
                        report['status'] = 'COMPLETED'
                        break
                if len(report['actions']) >= 500:
                    raise GrindStopped('ACTION_LIMIT')
                idle_started = time.monotonic()
        except GrindStopped as exc:
            report['reason'] = str(exc)
        except KeyboardInterrupt:
            report['reason'] = 'CANCELLED'
        except Exception as exc:
            report['reason'] = 'ERROR:'+type(exc).__name__
        report['elapsed'] = round(time.monotonic()-started, 2)
        ctx.emit('finished', **report)
        return report
