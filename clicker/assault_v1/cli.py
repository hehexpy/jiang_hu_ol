"""单账号有限执行入口，不启用旧脚本或root网络修复。"""
import argparse
import contextlib
from datetime import datetime
import io
import json
import os
from pathlib import Path
from interaction import AdbInteraction
from . import config
from .flow import Context, StopRun, run
from .recognition import AssaultRecognizer


class LoggedInteraction(AdbInteraction):
    def __init__(self, serial, output):
        super().__init__(serial)
        self.output = output

    def capture(self, path):
        target = Path(path).resolve()
        if not target.is_relative_to(self.output.resolve()):
            target = self.output / ('_login_' + target.name)
        return super().capture(str(target))


def login_account(acc, inter, output):
    import auto_login
    contexts = []
    original = auto_login.build_ctx

    def capture_context(*args, **kwargs):
        ctx = original(*args, **kwargs)
        contexts.append(ctx)
        return ctx

    text = io.StringIO()
    auto_login.configure(inter=inter)
    auto_login.build_ctx = capture_context
    try:
        with contextlib.redirect_stdout(text), contextlib.redirect_stderr(text):
            ok = auto_login.login(acc['username'], acc['password'], server=acc['server'],
                                  serial=os.environ['ADB_SERIAL'], pkg=acc['pkg'],
                                  check_network=False, cold_start=True, deadline=210)
    finally:
        auto_login.build_ctx = original
        redacted = text.getvalue()
        for secret in (acc.get('username'), acc.get('password'), acc.get('serial'), os.environ['ADB_SERIAL']):
            if secret:
                redacted = redacted.replace(secret, '[redacted]')
        (output / 'login.log').write_text(redacted, encoding='utf-8')
    evidence = contexts[-1].flags.get('server_confirmed') if contexts else None
    if not ok or evidence not in ('row', 'panel', 'game'):
        raise StopRun('登录或目标区确认失败，见脱敏login.log')
    return {'package': acc['pkg'], 'server': acc['server'], 'server_evidence': evidence}


def main(argv=None):
    parser = argparse.ArgumentParser(description='江湖OL 单账号攻防第一版（单轮）')
    parser.add_argument('--account', default='captain', help='账号配置名称；默认队长，不显示凭据')
    parser.add_argument('--action', choices=('cycle', 'recruit', 'attack', 'defend'), default='cycle')
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args(argv)
    if args.dry_run:
        print(json.dumps({'package': config.PACKAGE, 'server': config.SERVER,
                          'action': args.action, 'max_dispatch_per_action': 1,
                          'recruit': '红巾轻步兵10级/15000铜币/保持国库来源',
                          'city': '大理', 'attack_target': '棒棒糖青春无敌!',
                          'stop_on': '拒绝、未知、识别失败；不自动重发'}, ensure_ascii=False))
        return 0
    output = config.OUTPUT / datetime.now().strftime('%Y%m%d_%H%M%S_%f')
    output.mkdir(parents=True, exist_ok=False)
    report = {'action': args.action, 'results': {}, 'status': 'STOPPED'}
    pending_dir = config.OUTPUT / 'pending' / config.PACKAGE
    active = config.OUTPUT / 'execution.lock'
    owns_lock = False
    ctx = None
    print('[步骤1] 开始单账号登录，日志已脱敏；输出：' + str(output))
    try:
        if pending_dir.exists() and list(pending_dir.glob('pending_*.json')):
            raise StopRun('存在提交后未知的动作；先核对游戏与pending记录，不得重新发出')
        with active.open('x', encoding='utf-8') as f:
            f.write(str(os.getpid()))
        owns_lock = True
        from menu import load_team
        accounts = [a for a in load_team() if (a.get('role') == 'captain' if args.account == 'captain'
                                               else a.get('name') == args.account)]
        if len(accounts) != 1:
            raise StopRun('账号配置必须唯一匹配')
        acc = accounts[0]
        if acc.get('pkg') != config.PACKAGE or acc.get('server') != config.SERVER:
            raise StopRun('首版只标定现有队长客户端和九州揽月')
        serial = os.environ.get('ADB_SERIAL') or acc.get('serial')
        if not serial:
            raise StopRun('缺少ADB_SERIAL')
        os.environ['ADB_SERIAL'] = serial
        inter = LoggedInteraction(serial, output)
        if not inter.wait_device(45):
            raise StopRun('设备未就绪，请先运行tools/sim.py start --window及wait')
        report['identity'] = login_account(acc, inter, output)
        print('[步骤2] 已确认目标区，开始城战单次流程')
        ctx = Context(inter, AssaultRecognizer(), output,
                      dispatch_dir=pending_dir)
        report['results'] = run(ctx, args.action)
        report['status'] = ('VERIFIED' if all(x == 'VERIFIED' for x in report['results'].values())
                            else 'STOPPED')
    except (StopRun, ValueError, FileExistsError) as exc:
        report['reason'] = str(exc)
    except Exception as exc:
        # 不写可能带凭据的异常文本；详细位置可在受控离线测试中排查。
        report['reason'] = '运行异常：' + type(exc).__name__
    finally:
        if ctx is not None:
            report['results'] = getattr(ctx, 'results', report['results'])
        for journal in output.glob('dispatch_*.json'):
            record = json.loads(journal.read_text(encoding='utf-8'))
            report['results'].setdefault(record['action'], record['result'])
        (output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        if owns_lock:
            active.unlink(missing_ok=True)
    print('[结果] ' + json.dumps(report, ensure_ascii=False))
    return 0 if report['status'] == 'VERIFIED' else 2
