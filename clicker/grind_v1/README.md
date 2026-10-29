# 单怪循环战斗第一版

已经在当前安卓9模拟器上连续执行2轮：无生谷 → 三鹿教先锋 → 自动战斗 → 返回原场景 → 再次点击。默认3轮；不自动接任务、交任务、换地图、吃药、购买或复活。

## 直接使用（PowerShell，仓库根目录）

先用游戏里的任务追踪进入**无生谷**，关闭面板，确保能看到红字**三鹿教先锋**。保持游戏原有自动战斗设置。

```powershell
$env:ADB_SERIAL = '<adb devices 中的目标设备>'
& 'C:/Program Files/Python312/python.exe' tools/grind_v1.py --rounds 10 --max-seconds 900
```

`Ctrl+C` 停止。每轮进度、停止原因写入 `clicker/_sim/t029/<时间>/report.json` 和 `events.jsonl`；现场图也在该目录。默认输出不入库。`rounds` 范围1..1000，总时限至多86400秒。脚本不读取账号密码、不登录、不校验服务器；由调用方先完成登录与追踪。

## 判据与异常

- 点击前同时核对地图名称、世界任务底栏和目标怪名，使用局部匹配得到的位置点击。
- 必须观察到战斗铜币图标且世界任务底栏消失，才记录进入战斗。
- 同地图、世界底栏和同名怪连续两帧恢复，才计为一轮结束。
- CCOEFF 对压暗不敏感；额外的绝对像素误差校验阻止弹窗背景被当成可点击场景。
- 错地图、其他App、未知面板、点击失败、未进入战斗或战斗超时都会停止；不会自动重发未知动作。运行开始时已在战斗中也会停止，须等场景恢复再启动。

`BATTLE_RETURNED` 表示已观察战斗结束并返回场景，**不等于已经核实胜利或铜币增加**。`copper_delta=null` 是刻意保留的未知值，不能把循环次数乘任务奖励当作刷钱收益。此次任务交付奖励1620铜钱，属于交任务奖励；单怪重复掉钱仍需另测。

## 接入软件

```python
from threading import Event
from grind_v1 import GrindRunner, GrindRecognizer

stop = Event()
runner = GrindRunner(inter, GrindRecognizer(), output_dir,
                     callback=on_progress, cancel=stop)
# 放到工作线程中；on_progress 将字典转发给界面。
result = runner.run(rounds=10, max_seconds=900)
# 界面停止按钮：stop.set()
```

`inter` 遵循 `interaction.Interaction`；识别器继承 `recognition.Recognizer`，增加 `is_world(frame)`、`is_battle(frame)`、`enemy_position(frame)`。更换地图时提供独立的 `Anchor` 字典与同源模板，注入 `GrindRecognizer(anchors=..., template_root=...)`；四类锚点必须齐全。坐标、模板与等待参数统一在本包 `config.py`，不修改共享 `clicker/config.py`。

核心流程由 `AttackMonsterStep` / `WaitBattleStep` 两个 Step 构成，注册在本Runner实例，不污染登录步骤注册表。可注入回调、停止事件和其他 Interaction 实现。**当前交付是可调用的PC Python模块；打包Android仍需实现Android端交互、截屏与运行时适配，没有生成APK。**

当前模板同源于1080×2340模拟器，支持按宽度缩放；跨设备布局、其他分辨率尚未实机验证。新环境需重新采模板并复核负样本。

## 验证

```powershell
& 'C:/Program Files/Python312/python.exe' tests/test_grind_v1.py
```

17项离线用例用本次 `clicker/_sim/t028` 实机截图重放，包含卡战斗、未进入、暗色遮罩、错误地图、错误前台、取消、失败点击、任务状态/旧怪/移动NPC/满包及后置判据反例；该诊断素材按工作区要求不入库，换机器运行测试需复制本地素材目录。单怪模式只依赖根目录4张已登记 `tpl_grind_*.png`。

## 部分6-6任务模式

```powershell
& 'C:/Program Files/Python312/python.exe' tools/grind_v1.py --workflow clicker/grind_v1/quest_assets/workflow.json --rounds 2 --max-seconds 900
```

需先处于已标定的6-6节点；26节点、38个根目录`tpl_quest_*.png`在JSON登记。按任务全名、地图、任务白绿状态和完成印章选择操作，NPC/已知怪在场景ROI重新定位。未知分支、歧义与满包停机；未包含清包、猴子、全部6-6转场、6-7或6-8。此模式没有6-8计数节点，不能把上述命令理解成完成两轮6-8。

完整要求、出售授权、APK解析、当前状态和后续接手步骤见[统一交接](../../analysis/t030/HANDOFF.md)。最终须封装安卓App，先本机模拟器验证、后雷神适配；当前未交付安卓自动化引擎。
