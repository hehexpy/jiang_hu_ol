# 招兵 → 进攻 → 驻防

入口为 `campaign_cli.py`，独立于既有登录菜单。默认只显示计划、不连接设备。

```powershell
& 'C:/Program Files/Python312/python.exe' clicker/campaign_cli.py
& 'C:/Program Files/Python312/python.exe' clicker/campaign_cli.py --cycles 9999
# 检查旧脚本的 15 个目标包是否安装：
& 'C:/Program Files/Python312/python.exe' clicker/campaign_cli.py --inspect --serial <设备>
# 完成当前环境标定且游戏已登录后，从单包单轮开始：
& 'C:/Program Files/Python312/python.exe' clicker/campaign_cli.py --execute --serial <设备> --packages com.good.mmo2 --profile <标定JSON>
```

每轮依原脚本顺序完成 15 次「招兵+进攻」，再完成 15 次「招兵+驻防」。招兵包含原来的四页、11 个兵种。旧脚本重复点两下的按钮，改为点一次并验证结果，不盲目重复消耗资源。旧分辨率和 Delay 单位未经确认，未直接换算原像素或照搬 150/180。等待以画面结果为准，间隔与超时由标定文件按秒配置。

## 当前状态

编排和执行器已实现，**战斗界面尚未标定、尚未实机跑通**。示例 JSON 是待填写清单，不能直接执行。设备当前只装有原脚本里的 `com.good.mmo2`，其余 14 个包会被整组前置检查拒绝；明确 `--packages` 可选择已安装子集。

## 标定文件

复制 `campaign.example.json` 为本地文件。为每个 action 填入 `button`、`before`、`after`：

```json
{
  "template": "tpl_campaign_example.png",
  "hint": [0.5, 0.5],
  "pad": [0.08, 0.04],
  "threshold": 0.9
}
```

模板路径相对标定 JSON 所在目录；正式模板按工作区规范命名为 `clicker/tpl_<用途>.png`，并在 JSON 调用处登记。hint、pad 均是当前截图宽高的归一化比例。模板按当前模拟器截图裁取，以 1080 像素宽为基准；识别接口按屏幕宽度缩放。跨分辨率有黑边时需重新标定，不假定旧坐标可用。

`before` 应证明当前所在的页面和兵种；`after` 应是动作实际结果（例如对应兵种数量改变后的标记、征召成功提示、对应目标出征队列），且在点击前不能存在。不能用一个常驻菜单图标充当所有动作的成功判据，也不能把点兵种后弹出的确认框当作已经招募成功。分开标定 `recruit_<兵种>` 与 `confirm_<兵种>`。

必须对正负截图检查模板分值与余量，排除相同按钮出现在其他页面的情况；当前不提供未经实测的阈值保证。示例中的 marker 留空，防止把演示数据当作已标定参数。

执行前一次性校验所有动作的标定、包安装和设备。应用启动页由系统解析，前台包精确核对；动作按钮只在局部窗口匹配，无固定坐标兜底；结果未达成就停止，不自动重试招募/出征。Ctrl+C 停止；不保存自动续跑点，避免未知结果后重复提交。

## 验证

```powershell
& 'C:/Program Files/Python312/python.exe' -m unittest discover -s tests -p test_campaign.py -v
```

这些离线用例验证编排和失败时停止；不代表真实兵种招募或出征已验证。
