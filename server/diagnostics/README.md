# 江湖助手远程诊断

客户端在「记录 → 问题日志上传 · 检查更新」提交问题描述，成功后显示可复制的报告编号。
失败包保存在应用缓存，点「重试上次上传」。不会自动上传每次操作，也不会上传截图。

## 数据范围

- 应用版本、Android API、型号、当前任务状态、用户填写的问题描述。
- 最近应用日志和最近三次刷铜运行的 state/timing/actions 文本，每条限长。
- 使用当前账号配置对账号与密码做替换，并过滤凭证字段与 Bearer token。
- 不包含 accounts.json、SharedPreferences、完整目录、设备序列号、截图或其他应用数据。

接收服务仅允许白名单 ZIP，4 MiB 请求上限、6 MiB 展开上限、1 GiB 总容量，保留14天。
后台每小时清理超期报告；达到容量拒绝新报告。Nginx 每IP每分钟6次，突发3次。
上传凭证仅有提交权限；报告没有公网下载接口。APK内的上传凭证不应当作管理员凭证。

## 服务器

Python3 标准库，无第三方包。复制 app.py 到 /opt/jianghu-diagnostics/。
使用系统用户 jianghu-diag，systemd 的 StateDirectory 创建私有 /var/lib/jianghu-diagnostics/。
在 /etc/jianghu-diagnostics.env 保存 JH_UPLOAD_TOKEN（至少32字符，权限0600），安装 service 文件。
Nginx http 上下文声明 `limit_req_zone $binary_remote_addr zone=jianghu_reports:1m rate=6r/m;`，
在现有有效 HTTPS server 中 include nginx-location.conf，先 nginx -t 再 reload。

接收端仅绑定 127.0.0.1:28777，不需要把此端口暴露到公网；只通过现有 HTTPS 443 访问。
检查：systemctl status jianghu-diagnostics；curl http://127.0.0.1:28777/health。
排障报告位于 /var/lib/jianghu-diagnostics/reports/<报告编号>.zip，用 SSH/SFTP 取回。
请勿把该目录设为网页根目录。服务器无需游戏账号密码。

## GitHub 更新发布

使用一个公开的 GitHub 仓库，将代码与 Release 分开：代码仓库不收 APK，Release 附件收 APK 与 update.json。
客户端配置填写 owner/repo，使用 GitHub 官方 API 的最新正式 Release，不读取草稿/预发布。

每次发布递增 android/app/build.gradle 的 versionCode 和 versionName。必须用和已分发 APK 相同的签名密钥。
当前分发的是本机构建签名的 debug APK；切换签名会被客户端和系统拒绝覆盖更新。
签名私钥请单独备份，不上传仓库。新构建通过 JH_UPLOAD_TOKEN 环境变量注入受限上传凭证，源码里不保存它。

构建后生成清单，例如：

```
"C:/Program Files/Python312/python.exe" tools/release_manifest.py apk/t077/jianghu-helper-0.5.0.apk --metadata android/app/build/outputs/apk/debug/output-metadata.json --output apk/t077/update.json
```

把两个文件放在同一个正式 Release 中。客户端检查 versionCode、大小、SHA-256、包名、签名，再交给系统安装。
下载不成功、清单缺失、签名不符都不会替换当前应用。下载可重试，暂不支持断点续传。
Android 8+ 首次需允许江湖助手安装应用；返回后点「安装已下载更新」。
旧的0.4版本没有更新入口，需要先手动安装一次本次新版，之后可从应用检查更新。

## 验证边界

本次本地接收端12项、纯策略24项、生产Java诊断/上传/更新回放47断言通过，
公网HTTPS上传201并SSH核对字节一致。生产Java上传方法也已走真实HTTPS验证。
回放中的Android上下文和包签名返回由隔离替身提供，不代表系统安装器实测。
模拟器未在线，APK内真实点击/未知来源授权/覆盖安装还需设备验证。
