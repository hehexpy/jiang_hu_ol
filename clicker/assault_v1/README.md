# 江湖OL 单账号攻防第一版

入口是仓库根目录的 `tools/assault_v1.py`。使用现有队长账号，启动 `com.good.mmo2`，登录九州揽月，进入大理城市争夺战。只控制这一个客户端，单轮后退出脚本。设备通过 `ADB_SERIAL` 指定；未设置时只在内存中使用账号配置的设备值。

```powershell
& 'C:/Program Files/Python312/python.exe' tools/sim.py start --window
& 'C:/Program Files/Python312/python.exe' tools/sim.py wait
& 'C:/Program Files/Python312/python.exe' tools/assault_v1.py
```

查看计划不连接设备、不读取账号：

```powershell
& 'C:/Program Files/Python312/python.exe' tools/assault_v1.py --dry-run
```

默认流程：冷启动登录并确认区 → 城战入口 → 征兵 → 读取我的士兵两页 → 招募一次红巾轻步兵10级（15000铜币、保持国库来源）→ 核对军队总数及该兵种数量各增加1 → 进攻一次 → 驻防一次。任何拒绝、识别失败或结果未知都停止。明确“军队达到招募上限”时，关闭提示并复核军队未变且非空，将招募标记为跳过，使用现有军队继续。

单独实测某一步：`--action recruit`、`--action attack`、`--action defend`。这些参数会实际提交一次动作。默认进攻目标是历史标定的“棒棒糖青春无敌!”；列表第三行按钮只用于打开确认框，提交必须再次识别含完整国名的确认文字。列表长名称滚动不作为最终目标证据。首版尚无任意国家/任意城市/其他客户端标定入口。

所有运行记录在 `clicker/_sim/t027/<运行时间>/`：脱敏 `login.log`、逐步截图、`events.jsonl`、`report.json`。`VERIFIED` 表示已核实结果；`SKIPPED_ARMY_LIMIT` 表示满员且军队未变；`REJECTED_ACTIVITY_CLOSED` 表示游戏明确拒绝；`UNKNOWN_AFTER_DISPATCH` 表示可能已发出但效果不能证明。返回码0仅代表请求的全部动作通过验证，2代表停止。跳过招募不会冒充招募成功。

`pending/<包名>/pending_*.json` 在提交前持久化，只有明确结果才解除；未知结果跨进程、跨新运行目录阻止再次提交。必须先在游戏中人工核对结果，才能处理该记录；不要直接删除后重跑。`execution.lock` 防止同一入口并发控制客户端；异常断电可能保留它，确认旧进程退出后再处理。旧运行目录的完整记录一直保留。

识别使用34项模板，仅OpenCV局部匹配和归一化坐标，无OCR、模型或root。模板存放在 `clicker/tpl_assault_<用途>.png`，锚点/模板登记/等待配置集中在本目录 `config.py`，不改全局配置。来源保存在 `templates.json`。`build_assets.py` 只供开发重建，运行时不依赖历史截图。

首版局限：军队统计目前只标定2页；默认账号已满4队；活动未开放时不能验证有效出征、驻防。尚无攻防成功素材及能归属于本账号的独立判据，回到占领页只会标记未知，禁止据此循环。登录复用既有模块，区证据区分row/panel/game；不是游戏世界内再次读取区名，也没有新加角色名核验。不要将该版本当成已通过整轮有效战斗的挂机成品。

开发验证：

```powershell
& 'C:/Program Files/Python312/python.exe' analysis/t027/verify.py
```

其中17项首版用例和旧43项判据/Canvas/滚动测试在本机真实素材上执行；部分历史图片在gitignore内，换电脑需补齐才能复现素材测试。`analysis/t027/scores.json` 保留局部正负样本分值与余量。
