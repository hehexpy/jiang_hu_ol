"""
menu.py — 《江湖OL》自动化总入口：读组队账号 -> 选账号 -> 登录 -> (后续)组队/挂机操作

用法：
  python menu.py                                   # 交互：列出账号让你选一个登录
  python menu.py --list                            # 非交互：列出全部账号（不登录）
  python menu.py --ops                             # 非交互：列出可用操作
  python menu.py --account 队长                    # 登录指定账号
  python menu.py --op login                        # 多号串联：依次登录全部账号
  python menu.py --op login --dry-run              # 只打印串联计划，不碰设备
  python menu.py --op login --stop-on-fail         # 任一账号失败就中止后续
  python menu.py --account 队长 --op heartbeat      # 登录单个账号后执行操作

本机实测的调用方式（重要）：
  PATH 上的 `python` 是 **3.13 托管版且没有 cv2**，直接敲 `python menu.py` 必报
  `ModuleNotFoundError: No module named 'cv2'`。要用：
    "C:/Program Files/Python312/python.exe" menu.py --list
  （见 `HANDOFF.md` §2 的"解释器"一行）

`--op` 的 id 必须与 `operations.OPERATIONS` 注册表一致：不认识的 id **显式报错并列出可用 id**，
不再静默跳过（`HANDOFF.md` §5-4 的修复）。同理，未知的**命令行参数**也会报错 ——
原先 `--lis` 这种拼错会被无声吞掉，然后掉进交互式 `input()` 直接 `EOFError`（§5-3）。
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import auto_login
from operations import OPERATIONS, get_operation

_BASE = os.path.dirname(os.path.abspath(__file__))
ACCOUNTS_FILE = os.path.join(_BASE, "accounts.json")

USAGE = __doc__

# 已知开关（不带值）与已知选项（带值）—— 用于拒绝拼错的参数
_FLAGS = ("--list", "--ops", "--dry-run", "--stop-on-fail", "--help", "-h")
_OPTS = ("--account", "--op")


def load_team():
    """读取 accounts.json 的 team 结构，返回账号列表（含 role）。"""
    with open(ACCOUNTS_FILE, encoding="utf-8") as f:
        data = json.load(f)
    team = data.get("team", {})
    accs = []
    cap = team.get("captain")
    if cap:
        a = dict(cap); a["role"] = "captain"; accs.append(a)
    for m in team.get("members", []):
        a = dict(m); a["role"] = "member"; accs.append(a)
    return accs


def _role_of(acc):
    return "队长" if acc.get("role") == "captain" else "队员"


def list_accounts(accounts):
    """非交互列出账号。**不登录、不读设备**（`HANDOFF.md` §5-3）。"""
    if not accounts:
        print("（accounts.json 的 team 为空，请先配置账号）")
        return
    print(f"\n==== 组队账号（共 {len(accounts)} 个，来自 {os.path.basename(ACCOUNTS_FILE)}） ====")
    print(f"  {'#':<3}{'角色':<6}{'名称':<10}{'账号':<12}{'区':<12}{'设备':<16}包")
    for i, a in enumerate(accounts, 1):
        print(f"  {i:<3}{_role_of(a):<6}{str(a.get('name')):<10}{str(a.get('username')):<12}"
              f"{str(a.get('server')):<12}{str(a.get('serial')):<16}{a.get('pkg', '')}")
    print("  提示：登录单个号用 `--account <名称>`；全部依次登录用 `--op login`")


def list_operations():
    """非交互列出可用操作，让 `--op` 的取值可发现（不必翻源码）。"""
    print("\n==== 可用操作（--op <id>） ====")
    for op in OPERATIONS:
        tag = "" if op.get("ready", True) else "   ← 尚未实现，会被拒绝"
        print(f"  {op['id']:<12}{op['name']}{tag}")
    print("  提示：`--op login` 是「多号串联」，加 `--dry-run` 可先看计划不碰设备")


def pick_account(accounts):
    print("\n==== 选择要登录的账号 ====")
    for i, a in enumerate(accounts, 1):
        print(f"  {i}. [{_role_of(a)}] {a.get('name')}  {a.get('username')}"
              f"  (区:{a.get('server')} 设备:{a.get('serial')})")
    # 不能只靠 `sys.stdin.isatty()` 把关：**本机实测（Windows + Git Bash）**，
    # stdin 重定向自 `/dev/null` 时 `isatty()` 仍返回 **True**，于是原先会一路走到
    # `input()` 才抛 `EOFError` 并打出难看的长 traceback。用 `EOFError` 兜底才可靠。
    try:
        n = input(f"  请输入序号 [1-{len(accounts)}]: ").strip()
    except EOFError:
        print("\n❌ 当前不是交互终端（stdin 已结束），无法选择账号。")
        print("   非交互用法：`--list` 看账号｜`--account <名称>` 登单个号｜`--op login` 串联全部")
        return None
    if not n.isdigit() or not (1 <= int(n) <= len(accounts)):
        print(f"❌ 序号无效：{n!r}（应为 1~{len(accounts)}）")
        return None
    return accounts[int(n) - 1]


def login_account(acc):
    """登录指定账号（使用该账号自己的 serial/server/pkg）。不杀已启动的后台。"""
    print(f"\n>>> 登录 [{_role_of(acc)}] {acc.get('name')} {acc.get('username')}"
          f"  设备:{acc.get('serial')}  区:{acc.get('server')}")
    return auto_login.login(
        acc["username"], acc["password"],
        serial=acc.get("serial"),
        server=acc.get("server"),
        pkg=acc.get("pkg", "com.good.mmo2"),
    )


def parse_args(argv):
    """极简解析 + **严格校验**。返回 (opts dict, error str|None)。

    为什么不静默忽略未知参数：`--lis`（漏了个 t）原先会被当成"没有 --list"，
    然后掉进交互式 `input()` 在非交互环境里直接 `EOFError` —— 报错信息完全指不到病根。
    """
    opts = {"list": False, "ops": False, "dry_run": False, "stop_on_fail": False,
            "account": None, "op": None, "help": False}
    i = 0
    while i < len(argv):
        k = argv[i]
        if k in ("--help", "-h"):
            opts["help"] = True; i += 1
        elif k == "--list":
            opts["list"] = True; i += 1
        elif k == "--ops":
            opts["ops"] = True; i += 1
        elif k == "--dry-run":
            opts["dry_run"] = True; i += 1
        elif k == "--stop-on-fail":
            opts["stop_on_fail"] = True; i += 1
        elif k in _OPTS:
            if i + 1 >= len(argv):
                return opts, f"`{k}` 后面缺参数值"
            opts["op" if k == "--op" else "account"] = argv[i + 1]; i += 2
        else:
            hint = ""
            for known in _FLAGS + _OPTS:               # 拼错时给个最近的提示
                if known.startswith(k[:4]) or k.startswith(known[:4]):
                    hint = f"（是不是想写 `{known}`？）"
                    break
            return opts, f"不认识的参数 `{k}`{hint}"
    return opts, None


def main():
    opts, err = parse_args(sys.argv[1:])
    if err:
        print(f"❌ {err}\n")
        print(USAGE)
        return 2
    if opts["help"]:
        print(USAGE)
        return 0

    try:
        accounts = load_team()
    except FileNotFoundError:
        print(f"❌ 找不到账号文件：{ACCOUNTS_FILE}")
        return 1

    # ---- 非交互出口：列账号 / 列操作 ----
    if opts["list"]:
        list_accounts(accounts)
        return 0
    if opts["ops"]:
        list_operations()
        return 0

    if not accounts:
        print("❌ 请先在 accounts.json 里配置 team 账号。")
        return 1

    # ---- 解析目标账号 ----
    account = None
    if opts["account"]:
        account = next((a for a in accounts
                        if a.get("name") == opts["account"]
                        or a.get("username") == opts["account"]), None)
        if not account:
            names = ", ".join(str(a.get("name")) for a in accounts)
            print(f"❌ 未找到账号「{opts['account']}」（可用：{names}）")
            return 2

    # ---- 有 --op：先校验 id 一定在注册表里（不许静默跳过）----
    op = None
    if opts["op"]:
        op = get_operation(opts["op"])
        if op is None:
            valid = ", ".join(o["id"] for o in OPERATIONS)
            print(f"❌ 不认识的操作 id `{opts['op']}`（可用：{valid}）")
            print("   列出全部操作：`--ops`")
            return 2
        # 未实现的操作必须**在登录设备之前**拒绝 —— 否则会白等一次完整登录（约 100s）
        if not op.get("ready", True):
            print(f"❌ 操作 `{op['id']}`（{op['name']}）尚未实现，已在登录设备前拒绝。")
            print("   可用操作见 `--ops`")
            return 3

    # ---- 无 --op 且无 --account：走交互；但非交互环境必须显式报错而不是卡死 ----
    if not opts["op"] and not account:
        if not sys.stdin.isatty():
            print("❌ 没有指定 `--account` 或 `--op`，且当前不是交互终端，无法选择账号。")
            print("   非交互用法：`--list` 看账号 / `--account <名称>` 登单个号 / `--op login` 串联全部")
            return 2
        account = pick_account(accounts)
        if account is None:            # isatty() 在本机对 /dev/null 不可靠，靠这里兜底
            return 2

    # ---- 执行 ----
    if op:
        print(f"\n>>> 执行操作：{op['name']}（{op['id']}）")
        if op["id"] in ("login", "align_map"):
            # 多号串联（login=依次登录；align_map=登录并汇合襄阳城）
            # 指定 --account 就只串那一个，否则串整队
            targets = [account] if account else accounts
            try:
                ok = op["func"](accounts=targets, dry_run=opts["dry_run"],
                                stop_on_fail=opts["stop_on_fail"])
            except NotImplementedError as e:
                print(f"❌ 该操作尚未实现：{e}")
                return 3
            return 0 if ok else 1

        # 其余操作作用于**单个已登录账号**：不指定账号就拒绝，
        # 否则会拿默认 serial 去点设备（可能点错机器），或直接跑 1 小时循环（heartbeat）。
        if not account:
            print(f"❌ 操作 `{op['id']}` 作用于单个已登录账号，必须先用 `--account <名称>` 指定。")
            print("   （只有 `--op login` 可以不带账号，因为它就是「依次登录全部」）")
            return 2
        if not login_account(account):
            print("❌ 登录失败，结束。")
            return 1
        try:
            ok = op["func"](serial=account.get("serial"), accounts=[account])
        except NotImplementedError as e:
            print(f"❌ 该操作尚未实现：{e}")
            return 3
        return 0 if ok else 1

    # 只有 --account：登一个号即结束
    ok = login_account(account)
    if not ok:
        print("❌ 登录失败，结束。")
        return 1
    print("\n>>> 已登录。提示：`--op heartbeat` 可保持在线；`--op login` 可串联全部账号。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
