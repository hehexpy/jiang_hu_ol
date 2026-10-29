# clicker/ — 登录器使用说明

> 一句话：**驱动多开客户端，自动完成「进游戏 → 填号 → 选目标区 → 进世界」。**
> 目标区当前只有【九州揽月】有完整模板；换设备/换分辨率要先重截模板 + 重新标定坐标（见 `HANDOFF.md` §4.1）。

## 一、前置

| 项 | 要求 |
|---|---|
| Python | **`C:/Program Files/Python312/python.exe`**（cv2 5.0.0 / numpy 2.5.2）。PATH 上的 `python` 是 3.13 **没有 cv2** |
| adb | `D:/Android/Sdk/platform-tools/adb.exe` |
| 设备 | 雷电 `emulator-5554`，Android 9，1080×1920。**必须用户手动开**（工具启动会被回收） |
| 客户端 | 各账号绑定唯一包名，**同一账号禁止双开**（会互相顶号）。对照表见 `analysis/t046/ACCOUNTS_MAP.md` |

## 二、配置账号

`accounts.json` 里是 `team.captain` / `team.members`，每项含 `username` / `server` / `pkg`。

> ⚠️ 该文件**含真实密码，永久只读**：不打印、不复制进文档、不提交。
> 需要新号时让用户自己填，不要由 Agent 写入。

## 三、跑起来

```powershell
Set-Location D:/CodexProjects/jiang_hu_ol/clicker
$PY = 'C:/Program Files/Python312/python.exe'

& $PY menu.py --list                            # 列出账号（不碰设备）
& $PY menu.py --ops                             # 列出可用操作
& $PY menu.py --op login --dry-run              # 只看串联计划
& $PY menu.py --account 队长 --op login         # 登单个号
& $PY menu.py --op login                        # 按队列依次登录全部账号
& $PY -m py_compile *.py                        # 语法检查
```

Python 里直接调（可指定任意重开包）：

```python
import sys; sys.path.insert(0, '.')
import auto_login
auto_login.login('REDACTED_LOCAL_CREDENTIAL', '<密码>', server='九州揽月',
                 serial='emulator-5554', pkg='com.good.mmo2wxr2',
                 activity='com.good.mmo2.PipActivity')
```

## 四、它做的事

`auto_login.login()` = 组装 `Ctx` → 注册 10 个 Step → 跑 `Runner`，逐界面「识别 → 动作 → 验证」：

主菜单 → 「登陆游戏」→ 填登录框 → 判断面板当前区 → 不是目标区就「切换」并**滚动列表逐帧模板验证** → 选中 → 进角色选择 → 「进入游戏」→ 关 Canvas 弹窗 → 确认已进世界。

返回 `True` 时同时给出 `server_confirmed` 证据等级（`game` / `panel` / `row` / 空）——
**空表示"进了游戏但无法自证在哪个区"**，不要当成"进对了区"。

## 五、改东西之前

- **加新界面**：写一个 `Step` 子类 + `register()` 一行，不要动 `auto_login.py`（它是门面，应长期保持现状）。
- **加识别能力**：进 `recognition.py` / `vision.py`；**加设备交互**：进 `interaction.py`。
- **坐标/等待/模板映射**：一律进 `config.py`，不要散落在业务文件。
- **新模板**：存 `tpl_<用途>.png` 并登记进 `config.BUTTON_HINTS` / `config.SERVER_TPL`，否则会成为孤儿模板。
- 按钮定位用**局部区域匹配**（`ctx.tap_tpl_or` / `find_button_near`），**不要写死坐标**。

## 六、关键文件

| 文件 | 作用 |
|---|---|
| `auto_login.py` | 门面：组装 `Ctx`、注册步骤、跑 `Runner` |
| `steps.py` | `Ctx` / `Step` / `Runner` 框架 |
| `login_steps.py` | 10 个登录界面的 Step |
| `canvas_dialog.py` | Canvas 弹窗关闭（uiautomator 读不到的那类） |
| `menu.py` | 命令入口（`--list` / `--ops` / `--op login`） |
| `operations.py` | 操作注册表（`login` / `heartbeat`） |
| `accounts.json` | 账号/区/包名（**只读**） |
| `tpl_*.png` | 识别模板（与设备同源，换环境要重截） |
