# FnosWebViewApp

这是一个面向车机/中控场景的 **Android 全屏 WebView 壳应用**，用于打开指定的登录页面与前端 UI 页面，并支持方向盘媒体键控制（下一曲/上一曲/播放暂停）。

## 功能

- 全屏展示 Web 页面（无浏览器地址栏/标签栏）
- 首次启动可配置：
  - 登录页 URL（`loginUrl`）
  - UI 页 URL（`uiUrl`）
- 登录后自动跳转到 UI 页（同一 WebView 内，保证 Cookie/登录态一致）
- 车机方向盘媒体键支持：
  - 下一曲（`KEYCODE_MEDIA_NEXT`）
  - 上一曲（`KEYCODE_MEDIA_PREVIOUS`）
  - 播放/暂停（`KEYCODE_MEDIA_PLAY_PAUSE`）
- 优先使用 SPlayer Control API：
  - `/api/control/next`
  - `/api/control/prev`
  - `/api/control/toggle`
  - 如果接口不可用，会 fallback 到 DOM 点击（尽量保证可用）

## 使用方法

1. 用 Android Studio 打开项目并运行到车机/设备。
2. 第一次启动会弹出“配置URL”对话框：
   - 登录 URL：例如 `https://example.com/`
   - UI URL：例如 `https://example.com/#/`
3. 在登录页完成手动登录后，应用会检测登录成功并自动进入 UI 页。

## 重置 URL 配置

- 在车机上按 **MENU 键**（`KEYCODE_MENU`）可以清空已保存的 URL，并重新弹出配置窗口。

## 兼容性说明（车机场景）

- 部分车机系统自带的 `Android System WebView` 版本较旧（例如 Chromium 74），可能导致现代网页出现白屏或脚本不兼容。
- 本项目在 App 侧做了常见兼容处理：
  - 软件渲染（`LAYER_TYPE_SOFTWARE`）以规避部分车机 GPU 兼容性问题
  - 桌面 UA
  - Mixed Content 允许
  - SSL 错误放行（自用车机环境）

如果你的页面在车机 WebView 仍然白屏，建议从网页控制台日志与网络请求入手定位，并考虑前端降级/Polyfill。

## 开源与安全

- 本项目不内置任何账号/密码。
- URL 可配置，不在代码中写死。

## 构建

- Android Studio：`Build -> Build APK(s)`

> Windows 下如遇到 `Unable to delete directory ...\outputs\apk\debug`，通常是文件被占用（资源管理器/杀软/adb）。关闭占用后删除目录再构建即可。

