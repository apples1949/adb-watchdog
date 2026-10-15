# ADB 看门狗（Android）

[English](README.md) | 简体中文

本设备端看门狗用于保住**无线调试**（wireless ADB）：轮询
`Settings.Global.adb_wifi_enabled`，一旦发现它被系统置为 0，优先通过
**安全设置直写**翻回 1；若该写入不被系统接受，则回退到**无障碍服务**
驱动设置界面去点开关。

> **本 fork 相比上游新增**：
> - **push 即自动构建发行**：提交到 main 后由 GitHub Actions 自动构建，
>   在 [Releases](../../releases/latest) 以 `latest` 发行版发布 APK，直接下载安装
> - **无 Wi-Fi 暂停**：手机没连 Wi-Fi 时暂停恢复尝试（无线调试绑定 Wi-Fi，
>   无网时恢复无意义），Wi-Fi 恢复后自动继续，通知栏会显示"已暂停（无 Wi-Fi）"
> - **界面中英文切换**：主界面底部「中文 / English」按钮，选择持久保存
> - 版本：v0.2.0（versionCode 2）

## 下载安装

到 [Releases 最新发行版](../../releases/latest) 下载 `adb-watchdog-latest.apk`
直接安装（debug 签名）。注意：

- 每次 CI 构建的 debug 签名都不同，**覆盖安装会失败**——先卸载旧版再装
- 重装后 `WRITE_SECURE_SETTINGS` 授权会丢，需要重新执行一次（见下文）

## 为什么要两条恢复路径

主路径直接写 `Settings.Global.adb_wifi_enabled = 1`。它需要
`WRITE_SECURE_SETTINGS` 权限，普通应用无法通过运行时弹窗申请，只能用
ADB 授予一次：

```sh
adb shell pm grant com.luffy.adbwatchdog android.permission.WRITE_SECURE_SETTINGS
```

该授权重启不失效。授完之后恢复就是瞬时、无感的。

某些 ColorOS 版本会接受写入但 `AdbService` 无视它（没有任何可观察效果）。
无障碍服务就是这种场景的兜底：通过 intent 打开**开发者选项**，找到
"无线调试"一行并点击开关。较慢（约 3–5 秒）、无需授权，但对设置界面
的改版比较脆弱。

## 手机端一次性设置

1. 安装 APK。
2. 打开 **ADB 看门狗**，打开「看门狗开关」。
3. 点「打开无障碍设置」→ 启用 **ADB Watchdog (UI fallback)**。
   *（确信 WRITE_SECURE_SETTINGS 可用时可以跳过，但建议留着当保险。）*
4. 点「关闭电池优化」→ 选择"不允许优化"。必须做，否则前台服务会被杀。
5. 电脑上 USB 连一次（或任何可用的 ADB 会话）执行：

   ```sh
   adb shell pm grant com.luffy.adbwatchdog android.permission.WRITE_SECURE_SETTINGS
   ```

6. 点「立即测试恢复」。状态面板应显示 `无线调试 : 开`，恢复计数 +1。

## 国产 ROM 保活清单

国产 ROM 会激进地杀后台服务。以下全做，否则看门狗会悄悄死掉：

**ColorOS（OPPO/一加/realme）**

- 设置 → 电池 → ADB Watchdog → 允许后台活动
- 设置 → 电池 → ADB Watchdog → 自启动
- 手机管家 → 隐私权限 → 启动管理 → 允许 ADB Watchdog

**MagicOS（荣耀/华为）实测路径**

- 设置 → 应用 → 应用启动管理 → ADB Watchdog → 改为手动管理，
  允许「自启动 / 关联启动 / 后台活动」
- 多任务界面下拉 ADB Watchdog 卡片**加锁**，防一键清理

通用：多任务里把卡片下拉加锁，防止被"一键清理"杀掉。如果通知栏那条
常驻通知消失了，说明服务被系统杀了，重新打开 app 即可复活。

> 已实测（HONOR ELP-AN00，Android 16 / MagicOS）：安全设置直写路径有效，
> 系统关闭无线调试后数秒内自动恢复。

## 它不做的事

- 不负责重新配对 ADB 主机。配对信息保存在手机侧；无线调试恢复后，
  之前配对过且在同一 Wi-Fi 的电脑可以再次 `adb connect <手机IP>:<端口>`。
  Android 11+ 每次会话端口随机——可在电脑端用 mDNS 发现
  （`adb mdns services`），一般会自动重连。
- 不能解锁 PIN/图案/密码锁屏（无障碍兜底只处理滑动锁屏）。
- 恢复出厂设置后授权失效，需重新授予 `WRITE_SECURE_SETTINGS`。
- **手机重启后无线调试仍需手动开一次**（系统安全设计，无 root 无解）；
  开完之后看门狗即接管一切。

## 构建

需要 JDK 17 和 Android SDK 34。在本目录：

```sh
# 如果还没有 gradle wrapper：
gradle wrapper --gradle-version 8.7

./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

或者直接用 Android Studio 打开导入。

本 fork 已配置 GitHub Actions（`.github/workflows/main.yml`）：push 到
main 即自动构建并把 APK 发布到 `latest` Release。

## 目录结构

```
app/src/main/
├── AndroidManifest.xml
├── kotlin/com/luffy/adbwatchdog/
│   ├── AdbWifiController.kt      # 读写安全设置 + 分发兜底恢复（含 Wi-Fi 检测）
│   ├── WatchdogService.kt        # 前台服务轮询（无 Wi-Fi 时暂停）
│   ├── WatchdogAccessibilityService.kt  # 界面兜底恢复
│   ├── BootReceiver.kt           # 开机自启（若开关已启用）
│   └── MainActivity.kt           # 状态界面 + 开关 + 中英文切换
└── res/
    ├── layout/activity_main.xml
    ├── values/{strings,themes}.xml        # 英文
    ├── values-zh/strings.xml              # 中文
    └── xml/accessibility_service_config.xml
```
