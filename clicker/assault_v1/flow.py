"""有限Step流程。消费提交不重试；持久化日志先于点击。"""
from dataclasses import dataclass
import json
import time
from steps import Step
from interaction import Interaction
from .recognition import AssaultRecognition
from . import config


class StopRun(RuntimeError):
    pass


@dataclass
class Context:
    inter: Interaction
    rec: AssaultRecognition
    output: object
    package: str = config.PACKAGE
    sequence: int = 0
    dispatch_dir: object = None

    def __post_init__(self):
        self.output.mkdir(parents=True, exist_ok=True)
        self.dispatch_dir = self.dispatch_dir or self.output
        self.dispatch_dir.mkdir(parents=True, exist_ok=True)
        self.events = []

    def event(self, **values):
        record = {'time': time.time(), **values}
        self.events.append(record)
        with (self.output / 'events.jsonl').open('a', encoding='utf-8') as f:
            f.write(json.dumps(record, ensure_ascii=False) + '\n')

    def shot(self, label='frame'):
        self.sequence += 1
        frame = self.inter.capture(str(self.output / f'_{self.sequence:03}_{label}.png'))
        if frame is None:
            raise StopRun('截屏失败')
        return frame

    def check_package(self):
        r = self.inter.shell('dumpsys', 'activity', 'activities')
        if r.returncode:
            raise StopRun('无法核对前台客户端')
        text = r.stdout.decode('utf-8', errors='replace')
        active = [line for line in text.splitlines() if 'mResumedActivity' in line]
        if not any(self.package + '/' in line for line in active):
            raise StopRun('前台不是指定游戏，停止')

    def tap(self, name, frame=None):
        self.check_package()
        frame = self.shot('before_' + name) if frame is None else frame
        position = self.rec.locate(frame, name)
        if position is None:
            raise StopRun('局部模板不匹配：' + name)
        x, y = position
        h, w = frame.shape[:2]
        result = self.inter.tap(round(x*w), round(y*h))
        if result.returncode:
            raise StopRun('点击失败：' + name)
        time.sleep(config.SETTLE)


class Transition(Step):
    def __init__(self, before, button, after):
        self.before, self.button, self.after = before, button, after
        self.name = before + '->' + after

    def detect(self, ctx):
        return ctx.rec.state(ctx.shot()) == self.before

    def act(self, ctx):
        ctx.tap(self.button)

    def verify(self, ctx):
        return ctx.rec.state(ctx.shot(self.after)) == self.after


STEPS = []


def register(step):
    STEPS.append(step)


register(Transition('WORLD', 'npc', 'ENTRY'))
register(Transition('ENTRY', 'start', 'OVERVIEW'))
register(Transition('OVERVIEW', 'recruit_tab', 'RECRUIT'))


def transition(ctx, before, button, after):
    step = Transition(before, button, after)
    if not step.detect(ctx):
        raise StopRun('预期界面：' + before)
    step.act(ctx)
    if not step.verify(ctx):
        raise StopRun('界面结果未核实：' + step.name)
    ctx.event(step=step.name, result='VERIFIED')


def navigate(ctx):
    for step in STEPS:
        state = ctx.rec.state(ctx.shot('navigate'))
        if state == 'RECRUIT':
            return
        if step.before != state:
            continue
        step.act(ctx)
        if not step.verify(ctx):
            raise StopRun('导航结果未核实：' + step.name)
        ctx.event(step=step.name, result='VERIFIED')
    if ctx.rec.state(ctx.shot()) != 'RECRUIT':
        raise StopRun('无法到达征兵界面；需要重新登录')


def inventory(ctx):
    transition(ctx, 'RECRUIT', 'my_troops', 'TROOPS')
    frame = ctx.shot('troops_p1')
    page1, total1, red1 = ctx.rec.inventory_page(frame)
    if page1 != 1:
        raise StopRun('我的士兵未从第一页开始')
    ctx.tap('next', frame)
    page2, total2, red2 = ctx.rec.inventory_page(ctx.shot('troops_p2'))
    if page2 != 2:
        raise StopRun('我的士兵翻页未确认')
    transition(ctx, 'TROOPS', 'recruit_list', 'RECRUIT')
    snapshot = {'total': total1+total2, 'red': red1+red2}
    ctx.event(inventory=snapshot)
    return snapshot


def submit_once(ctx, action, button, expected):
    """每个日志目录中的该动作最多一次；进程崩溃后也不能再次发出。"""
    journal = ctx.output / ('dispatch_' + action + '.json')
    pending = ctx.dispatch_dir / ('pending_' + action + '.json')
    frame = ctx.shot('dispatch_' + action)
    if ctx.rec.state(frame) != expected:
        raise StopRun('提交前界面不符')
    if ctx.rec.locate(frame, button) is None:
        raise StopRun('提交按钮未定位')
    ctx.check_package()
    # x模式原子创建；发出前落盘，即使点击抛异常也不得自动重试。
    with pending.open('x', encoding='utf-8') as f:
        json.dump({'action': action, 'result': 'UNKNOWN_AFTER_DISPATCH'}, f)
        f.flush()
        import os
        os.fsync(f.fileno())
    with journal.open('x', encoding='utf-8') as f:
        json.dump({'action': action, 'result': 'UNKNOWN_AFTER_DISPATCH'}, f)
    ctx.event(action=action, result='DISPATCH_INTENT')
    ctx.tap(button, frame)


def finish_dispatch(ctx, action, result):
    ctx.event(action=action, result=result)
    journal = ctx.output / ('dispatch_' + action + '.json')
    journal.write_text(json.dumps({'action': action, 'result': result}), encoding='utf-8')
    if result == 'VERIFIED' or result.startswith(('REJECTED_', 'SKIPPED_')):
        # 只删除本任务运行时锁；完整提交证据保留在独立运行日志中。
        (ctx.dispatch_dir / ('pending_' + action + '.json')).unlink(missing_ok=True)


def response(ctx, action, expected):
    until = time.monotonic() + config.RESPONSE_TIMEOUT
    while True:
        state = ctx.rec.state(ctx.shot(action + '_response'))
        if state in ('CLOSED', 'ERROR', 'ARMY_LIMIT'):
            result = {'CLOSED': 'REJECTED_ACTIVITY_CLOSED', 'ARMY_LIMIT': 'REJECTED_ARMY_LIMIT',
                      'ERROR': 'UNKNOWN_AFTER_DISPATCH'}[state]
            ctx.event(action=action, result=result)
            return result
        if state == expected:
            return 'NEEDS_RECONCILIATION'
        if time.monotonic() >= until:
            ctx.event(action=action, result='UNKNOWN_AFTER_DISPATCH')
            return 'UNKNOWN_AFTER_DISPATCH'
        time.sleep(config.POLL_INTERVAL)


def recruit(ctx):
    before = inventory(ctx)
    frame = ctx.shot('recruit_preconditions')
    if not all(ctx.rec.has(frame, name) for name in ('funds', 'available', 'recruit_name')):
        raise StopRun('资金来源/兵种/可招募状态未核实')
    transition(ctx, 'RECRUIT', 'recruit', 'RECRUIT_CONFIRM')
    submit_once(ctx, 'recruit', 'recruit_yes', 'RECRUIT_CONFIRM')
    result = response(ctx, 'recruit', 'RECRUIT')
    if result == 'REJECTED_ARMY_LIMIT':
        transition(ctx, 'ARMY_LIMIT', 'close_error', 'RECRUIT')
        after = inventory(ctx)
        result = ('SKIPPED_ARMY_LIMIT' if before == after and after['total'] > 0
                  else 'UNKNOWN_AFTER_DISPATCH')
        ctx.event(action='recruit_inventory_reconciliation', before=before, after=after)
    if result == 'NEEDS_RECONCILIATION':
        after = inventory(ctx)
        result = ('VERIFIED' if after['total'] == before['total']+1 and
                  after['red'] == before['red']+1 else 'UNKNOWN_AFTER_DISPATCH')
        ctx.event(action='recruit', result=result, before=before, after=after)
    finish_dispatch(ctx, 'recruit', result)
    return result


def fight(ctx, action):
    state = ctx.rec.state(ctx.shot())
    if state == 'RECRUIT':
        transition(ctx, 'RECRUIT', 'occupy_tab', 'OCCUPY')
    frame = ctx.shot('occupy_preconditions')
    if ctx.rec.state(frame) != 'OCCUPY' or not ctx.rec.has(frame, 'city'):
        raise StopRun('城池/占领界面未核实')
    if action == 'attack':
        # 长国名会横向滚动，列表裁图不能稳定核对全名。
        # 页码+局部进攻按钮只用于打开确认；真正提交必须匹配完整国名确认框。
        if not ctx.rec.has(frame, 'page_1'):
            raise StopRun('国家列表页码不符')
        transition(ctx, 'OCCUPY', 'attack', 'ATTACK_CONFIRM')
        submit_once(ctx, action, 'attack_yes', 'ATTACK_CONFIRM')
    else:
        submit_once(ctx, action, 'defend', 'OCCUPY')
    result = response(ctx, action, 'OCCUPY')
    if result == 'NEEDS_RECONCILIATION':
        # 成功样本未采集，回到占领页不能证明出征/驻防成功。
        result = 'UNKNOWN_AFTER_DISPATCH'
        ctx.event(action=action, result=result)
    finish_dispatch(ctx, action, result)
    return result


def run(ctx, action='cycle'):
    ctx.results = {}
    ctx.check_package()
    navigate(ctx)
    actions = ('recruit', 'attack', 'defend') if action == 'cycle' else (action,)
    results = ctx.results
    for item in actions:
        result = recruit(ctx) if item == 'recruit' else fight(ctx, item)
        results[item] = result
        if result not in ('VERIFIED', 'SKIPPED_ARMY_LIMIT'):
            break
    return results
