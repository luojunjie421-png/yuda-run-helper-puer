# 权哥帮你跑-云农大 · 离线版

在手机上回放云南农业大学普洱倚象校区的 GPX 跑道。可选择 1 次或 2 次，每次 3.00 公里，约 30 秒。Android 6.0 及以上支持安装。

轨迹预览在本机运行，应用无需联网。GPX 仅包含跑道坐标。

## 构建

准备 JDK 21、Android SDK Platform 35 和 Build Tools 35.0.0，在 PowerShell 7 运行：

```powershell
./build.ps1 -Jdk <JDK目录> -Sdk <SDK目录> -Output ./quange-yunnongda-offline-v1.0.0.apk
```

构建脚本使用本机开发签名。