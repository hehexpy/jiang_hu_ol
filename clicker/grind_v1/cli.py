"""PC入口：只控制 ADB_SERIAL 指定的前台已登录客户端。"""
import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
from interaction import AdbInteraction
from .engine import GrindRunner
from .recognition import GrindRecognizer


def main(argv=None):
    parser = argparse.ArgumentParser(description='单怪重复战斗，先在游戏中追踪到无生谷三鹿教先锋')
    parser.add_argument('--rounds', type=int, default=3)
    parser.add_argument('--max-seconds', type=float, default=600)
    parser.add_argument('--workflow', type=Path, help='已标定的任务流程JSON；未知节点停止')
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parents[1]/'_sim'/'t029')
    args = parser.parse_args(argv)
    serial = os.environ.get('ADB_SERIAL')
    if not serial:
        parser.error('请先设置 ADB_SERIAL；本脚本不读取账号密码')
    output = args.output / datetime.now().strftime('%Y%m%d_%H%M%S_%f')
    output.mkdir(parents=True)
    lock = args.output / ('device_'+hashlib.sha256(serial.encode()).hexdigest()[:16]+'.lock')
    owns_lock = False
    try:
        with lock.open('x', encoding='utf-8') as stream:
            stream.write(str(os.getpid()))
        owns_lock = True
        inter = AdbInteraction(serial)
        if not inter.wait_device(30):
            raise RuntimeError('设备未就绪')

        def emit(event):
            with (output/'events.jsonl').open('a', encoding='utf-8') as stream:
                stream.write(json.dumps(event, ensure_ascii=False)+'\n')
            if event['event'] in ('round_completed', 'finished'):
                print('[刷怪] '+json.dumps(event, ensure_ascii=False), flush=True)

        if args.workflow:
            from .workflow import WorkflowRecognizer, WorkflowRunner
            runner = WorkflowRunner(inter, WorkflowRecognizer(args.workflow), output, callback=emit)
            report = runner.run(args.rounds, args.max_seconds)
        else:
            runner = GrindRunner(inter, GrindRecognizer(), output, callback=emit)
            report = runner.run(args.rounds, args.max_seconds).to_dict()
        (output/'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        return 0 if report['status'] == 'COMPLETED' else 2
    except FileExistsError:
        print('[停止] 当前设备已有刷怪实例；请先核实运行状态')
        return 2
    finally:
        if owns_lock:
            lock.unlink(missing_ok=True)
