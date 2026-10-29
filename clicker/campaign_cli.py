"""独立战役入口；默认只显示计划，--execute 才执行已标定流程。"""
import argparse
import json
import os
from pathlib import Path

from campaign import (PACKAGES, CampaignError, CampaignRunner, action_keys,
                      build_plan, installed_packages)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--packages", nargs="+", default=PACKAGES)
    parser.add_argument("--cycles", type=int, default=1)
    parser.add_argument("--profile", type=Path)
    parser.add_argument("--serial", default=os.environ.get("ADB_SERIAL"))
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--inspect", action="store_true", help="检查安装包，不点击")
    args = parser.parse_args(argv)
    try:
        # 不展开 9999 轮；仅预览第一轮。
        next(build_plan(args.packages, args.cycles))
        if not args.execute and not args.inspect:
            print(f"[计划] 共 {args.cycles} 轮，每轮 {len(args.packages) * 2} 次应用访问")
            for visit in build_plan(args.packages, 1):
                print(f"[计划] {visit.phase} {visit.package}：招兵 + 阶段动作 ({len(action_keys(visit.phase))} 步)")
            print("[计划] 未连接设备；执行须指定 --execute --profile 和设备")
            return 0
        if not args.serial:
            raise CampaignError("[配置] 请用 ADB_SERIAL 或 --serial 明确指定设备")
        from interaction import AdbInteraction
        inter = AdbInteraction(args.serial)
        if args.inspect:
            if args.execute:
                raise CampaignError("[配置] --inspect 与 --execute 不能同时使用")
            if not inter.wait_device():
                raise CampaignError("[设备] 设备不在线")
            installed = installed_packages(inter)
            for package in args.packages:
                print(f"[检查] {package}：{'已安装' if package in installed else '缺失'}")
            return 0 if set(args.packages) <= installed else 1
        if args.profile is None:
            raise CampaignError("[标定] 执行必须指定 --profile")
        from recognition import OpenCVRecognizer
        with args.profile.open(encoding="utf-8") as handle:
            profile = json.load(handle)
        runner = CampaignRunner(inter, OpenCVRecognizer(), profile, args.profile.resolve().parent,
                                Path(__file__).parent / "_sim")
        count = runner.run(args.packages, args.cycles)
        print(f"[完成] 已验证 {count} 次应用访问")
        return 0
    except (CampaignError, OSError, ValueError) as exc:
        print(f"[停止] {exc}")
        return 1
    except KeyboardInterrupt:
        print("[停止] 用户中断；再次运行会从头开始，不自动重放提交")
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
