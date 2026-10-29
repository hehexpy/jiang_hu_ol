# 江湖助手

Android多客户端登录组队、角色轮换和按实际战斗场数刷铜助手。
提供手机端任务列表、悬浮进度、问题日志上传和GitHub Release联网更新。

## 构建

Android SDK 35、JDK 17+、Python 3.12、Gradle wrapper。
本机使用 C:/Program Files/Python312/python.exe（Chaquopy buildPython路径见android/app/build.gradle）。
在android/local.properties中配置SDK路径，再在android目录运行gradlew assembleDebug。
设置JH_UPLOAD_TOKEN环境变量可注入仅用于提交报告的凭证；不要提交它或签名私钥。
账号密码仅在用户应用中填写，不随源码分发。
旧账号绑定的char_ids.json已清空；实际角色通过游戏画面采集。

远程诊断与更新发布见 [部署说明](server/diagnostics/README.md)。
更新仓库填写在support_config.json，或应用「记录→问题日志上传·检查更新→服务设置」。

此仓库为当前源代码的独立导出，不含原工作区Git历史、真实账号、私钥、运行截图和日志。
