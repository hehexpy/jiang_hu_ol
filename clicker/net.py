"""
net.py — 模拟器网络修复与**验证**

为什么要重写：原 `auto_login.fix_network()` 无论成败都打印「已配置」，
2026-10-02 实测它跑了之后 **路由没加、DNS 没设、依旧 100% 丢包**，
而调用方毫不知情。根因是它不检查返回码，且 `adb root` 会重启 adbd，
紧接着发的命令落在重启窗口里直接失败。

本模块的三条硬规矩：
  1. 每条命令都看 `returncode`，失败原样记录 stderr；
  2. `adb root` 之后**必须等 adbd 回来**再发下一条；
  3. 修完**必须实测**（不通就返回 ok=False），不许把「发了命令」当成「修好了」。
"""
import re
import time
from dataclasses import dataclass, field

# 模拟器常见网关（wlan0=192.168.232.x，radio0=192.168.200.x）
GATEWAYS = ("192.168.232.1", "192.168.200.1")
PROBE_HOSTS = ("www.baidu.com", "www.qq.com")
PROBE_IP = "114.114.114.114"


@dataclass
class NetReport:
    ok: bool = False
    stages: list = field(default_factory=list)   # [(stage, ok, detail)]
    evidence: str = ""

    def add(self, stage, ok, detail=""):
        self.stages.append((stage, bool(ok), detail))

    def text(self) -> str:
        return "\n".join(f"    {'✓' if ok else '✗'} {s}: {d}" for s, ok, d in self.stages)


def _run(inter, *args, timeout_note=""):
    """执行一条 shell 命令，返回 (returncode, stdout, stderr)。"""
    r = inter.shell(*args)
    out = r.stdout.decode("utf-8", "replace").strip()
    err = r.stderr.decode("utf-8", "replace").strip()
    return r.returncode, out, err


def probe(inter) -> tuple[bool, str]:
    """实测联网，返回 (是否在线, 原始证据文本)。**证据原样带上**，出问题能直接看。

    为什么不能只看 ICMP：本环境（模拟器 + 国内网络）**会过滤 ICMP**，
    实测 `ping 8.8.8.8` / `ping 114.114.114.114` 恒为 100% 丢包，
    但域名解析正常、游戏也能正常连服务器（游戏走 TCP）。
    因此判据分两级：
      * 强证据 —— ping 有回包（`bytes from`）；
      * 弱证据 —— ping 输出把域名解析成了 IP（说明 DNS 通道通），判为在线并注明。
    """
    lines = []
    resolved = None
    for host in PROBE_HOSTS:
        rc, out, _ = _run(inter, "ping", "-c", "2", "-W", "3", host)
        head = (out.splitlines() or [""])[0]
        lines.append(f"ping {host} -> rc={rc} : {head}")
        if "bytes from" in out:
            return True, "\n".join(lines)
        m = re.search(r"PING\s+\S+\s+\((\d+\.\d+\.\d+\.\d+)\)", out)
        if m:
            resolved = m.group(1)
    rc, out, _ = _run(inter, "ping", "-c", "2", "-W", "3", PROBE_IP)
    lines.append(f"ping {PROBE_IP} -> rc={rc} : {(out.splitlines() or [''])[0]}")
    if "bytes from" in out:
        return True, "\n".join(lines)
    if resolved:
        lines.append(f"注：DNS 已解析（例 {resolved}）、ICMP 无回包 —— 本环境过滤 ICMP，"
                     "以「能解析」判为在线")
        return True, "\n".join(lines)
    return False, "\n".join(lines)


def repair(inter, log=print) -> NetReport:
    """修网络：开 wifi -> root -> 等 adbd -> 补默认路由 -> 设 DNS。每步如实记录。"""
    rep = NetReport()

    rc, _, err = _run(inter, "svc", "wifi", "enable")
    rep.add("svc wifi enable", rc == 0, err or f"rc={rc}")
    time.sleep(3)

    rc, out, err = _run(inter, "root")
    rooted = rc == 0 and ("restarting" in out.lower() or "cannot run as root" not in out.lower())
    rep.add("adb root", rooted, (out or err)[:60])

    # 关键：adbd 重启期间发命令必然失败，必须等它回来（旧实现就栽在这）
    back = inter.wait_device(40)
    rep.add("等待 adbd 复活", back, "" if back else "设备未回来")
    if not back:
        return rep

    for gw in GATEWAYS:
        rc, _, err = _run(inter, "ip", "route", "add", "default", "via", gw, "dev", "wlan0")
        # 「File exists」也算成功（路由已在）
        ok = rc == 0 or "exists" in err.lower()
        rep.add(f"默认路由 via {gw}", ok, err or f"rc={rc}")
        if ok:
            break

    for prop, val in (("net.dns1", GATEWAYS[0]), ("net.dns2", "8.8.8.8")):
        rc, _, err = _run(inter, "setprop", prop, val)
        rep.add(f"{prop}={val}", rc == 0, err or f"rc={rc}")

    return rep


def ensure_online(inter, log=print, tries: int = 2) -> NetReport:
    """确保模拟器能上网。**只有实测通过才 ok=True**。

    实测背景（2026-10-02）：游戏「连接失败，请稍候重试!」的真因就是模拟器没网 ——
    而这台模拟器若从受网络限制的沙箱里启动，会全程拿不到外网，
    必须由不受限的进程启动（见 HANDOFF.md 环境一节）。
    """
    last = NetReport()
    for i in range(tries):
        ok, ev = probe(inter)
        last.evidence = ev
        if ok:
            last.ok = True
            last.add("联网实测", True, ev.splitlines()[0] if ev else "")
            if log:
                log("  [网络] 已在线")
            return last
        if log:
            log(f"  [网络] 第{i + 1}/{tries}次检测不通，尝试修复")
        rep = repair(inter, log=log)
        last.stages.extend(rep.stages)
        time.sleep(2)
    ok, ev = probe(inter)
    last.ok = ok
    last.evidence = ev
    last.add("联网实测", ok, ev.splitlines()[0] if ev else "")
    if log:
        log(f"  [网络] {'已在线' if ok else '仍不通'}")
        for ln in ev.splitlines():
            log(f"    {ln}")
    return last
