# android/ —— 江湖OL助手（Android App）

## 2026-10-20 / v0.2.1 当前版本（T-039，进行中）

下方旧版0.1.0说明仅为历史记录。当前是原生安卓四页控制台：修行/百宝/队伍/记录，深墨绿、米白、朱砂配色。已通过本机Android Studio对应的Gradle8.8离线构建、雷电安装与四页界面实测。

刷铜按钮已真实调起OL清包45原生引擎与四份原Lua：1轮镖局打手、1次触发、native退出0，结果回传App。背包整理原F21已接入并实测。新增任务邮件领取按钮：原F20无任务邮件分支、返回游戏、结果回传实际通过；单页已删除/附件/满包分支仍未验收。

**当前需要电脑调试桥连接，手机独立运行及两助手全部功能移植尚未完成。** 登录/征兵/进攻/驻防连接既有电脑侧入口，尚未本轮逐项重新验收；组队/轮换/15包攻防待接入。出售在电脑开发探针中沿原F22实卖中兴剑一把，游戏回执8000铜钱；本地字形代替OCR，未知名称保留。批量清包、真实宝石样本与APK出售按钮未验收，百宝页仅标部分接入。

工程可直接由本机Android Studio打开本目录。APK：`app/build/outputs/apk/debug/app-debug.apk`；启动桥：仓库根目录 `analysis/t039/start.ps1 -Device <实际设备>`。完整功能表、命令、验证和下一步见 [T039说明](../analysis/t039/README.md)。App只增加INTERNET权限，连接固定回环地址，启动时配对随机令牌；不在App里保存游戏密码。

---

## 以下为0.1.0历史说明

阶段 5 的可安装形态。

> **本版范围**：只做「**能装、能启动、能看日志**」这一层地基。
> **尚未接入自动化引擎** —— 原因见文末「待裁决」。

---

## 构建

前置：JDK 17+（本机用 `C:\Program Files\BellSoft\LibericaJDK-21`）、
Android SDK（`D:\Android\Sdk`，需 platform `android-35`、build-tools `34.0.0`）。

```bash
cd android
export JAVA_HOME="/c/Program Files/BellSoft/LibericaJDK-21"
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

`gradle-wrapper.properties` 的 `distributionUrl` 已指向腾讯镜像
（官方 `services.gradle.org` 在本机历史上不可达）。

本机还有一条**免下载**的路（直接用已缓存的发行版，实测 40s 构建完成）：

```bash
~/.gradle/wrapper/dists/gradle-8.8-bin/4u0rgm4geyrm56fyhoco0c9in/gradle-8.8/bin/gradle assembleDebug
```

## 验收

```bash
python android/tools/app_test.py emulator-5556
```

一条命令走完：`install -r` → `am start` → 三路抓日志 → 截图，
并对日志内容做 7 项断言。

## 三条日志通道

App 内产生的日志**同时**走三条路，任选其一：

| 通道 | 方式 |
|---|---|
| **A 文件** | `adb shell cat /sdcard/Android/data/com.jianghuol.helper/files/logs/app.log` |
| **B logcat** | `adb logcat -s JiangHuOL` |
| **C 界面** | App 内实时显示（另带「导出日志」「复制全部」按钮） |

日志文件以 append 模式写，**App 崩溃后内容仍在**，适合无人值守排查。

日志目录用 App 私有外部目录 `/sdcard/Android/data/<pkg>/files/logs/`，
**不需要任何存储权限**，adb 也能直接读。

## 代码结构

| 文件 | 职责 |
|---|---|
| `LogBus.java` | 进程内日志总线。线程安全、环形缓冲（3000 条）、多路扇出；内置 `LogcatSink` |
| `LogFile.java` | 落盘 sink，每条 flush（保证 adb 随时读到完整内容） |
| `SelfCheck.java` | 环境自检：设备/系统/ABI/屏幕/内存/root/无障碍，**这些都是后续自动化的前置条件** |
| `Heartbeat.java` | 心跳线程。存在的意义是提前验证「后台线程 → 日志」这条链路（自动化引擎今后的形态） |
| `MainActivity.java` | 界面：顶部环境摘要 + 下方日志流；本身就是一个 `LogBus.Sink` |

首版**零第三方依赖**（只用 Android framework），构建最快、出错面最小。

## 待裁决：自动化引擎怎么接

PC 侧的 `clicker/` 靠 `adb shell input tap` 控制设备。搬进 App 后**这条路不通** ——
Android 应用既没有 `adb` 二进制，也没有 shell 权限，而技术红线 §3-2 要求不依赖 root。

无 root 下的可选通道：

| 通道 | 截图 | 点击 | 代价 |
|---|---|---|---|
| **Shizuku** | ✅ 借 adb | ✅ 借 adb | 用户需装 Shizuku + 无线调试激活一次；**最接近现有 Python 逻辑** |
| **MediaProjection + AccessibilityService** | ✅ | ✅ | 完全自包含，但需两次授权，无障碍截图有速率限制 |
| **ADB over loopback**（Android 11+） | ✅ | ✅ | 每次要开无线调试 |

识别层（OpenCV 模板匹配）可选 **Chaquopy 内嵌 Python**（复用现有 `clicker/` 逻辑）
或 **Kotlin 重写**。

**未裁决前不进入实现。**
