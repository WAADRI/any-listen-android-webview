# AnyListen Android WebView

any-listen 网页版的 Android 壳应用：一个可配置服务器地址的 WebView App。
所有功能（播放、列表、歌词、扩展等）都来自你部署的 any-listen 网页版服务，本应用负责提供原生体验：后台播放、锁屏/通知栏媒体控制、自动切歌保活。

> **非官方项目**：本项目是社区第三方客户端，与 [any-listen](https://github.com/any-listen/any-listen) 官方无关联。
> 应用本身不包含 any-listen 的任何源码，也不内置任何服务器地址 —— 全部功能由你自行部署的服务器提供。

## 使用

1. 安装 [Releases](https://github.com/WAADRI/any-listen-android-webview/releases) 页面的 `app-release.apk`。
2. 首次启动填写你的服务器地址，例如 `https://music.example.com`。
3. 进入播放界面，行为与手机 Chrome 打开该站点一致（已验证 Chrome 内核下功能完整）。

> **签名差异提醒**：`app-debug.apk`（调试签名）与 `app-release.apk`（正式签名）签名不同，二者不能互相覆盖安装。
> 从调试包换成正式包时需先卸载，已填写的服务器地址与登录态会丢失。

## 发布流程

打 tag 即自动发布：

```bash
git tag -a v0.1.5 -m "v0.1.5: 变更说明"
git push origin v0.1.5
```

CI 会构建 debug / release 两个 APK，用正式 keystore 签名，并自动挂到对应的 GitHub Release。
构建日志中的 `apksigner --print-certs` 会打印签名证书指纹，用于确认没有退回 debug 签名。

tag 版本号应与 `app/build.gradle.kts` 的 `versionName` 保持一致。

## 构建

### 云端（推荐）

仓库内置 `.github/workflows/build-apk.yml`：

- push / PR / 手动触发都会构建 `debug` 与 `release` 两个 APK，作为 Actions artifact 提供下载（保留 90 天）；
- 打 `v*` tag 时，APK 会自动挂到 GitHub Release（长期保存、可直接分发）。

### 正式签名

已配置完成。keystore 以 Repository Secrets 保存，CI 构建时解码使用：

| Secret | 说明 |
| --- | --- |
| `KEYSTORE_BASE64` | keystore 文件的 base64 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | 别名 `anylisten` |
| `KEY_PASSWORD` | 密钥密码 |

> **务必离线备份 keystore 与密码。** keystore 丢失或更换后老用户无法覆盖升级，只能卸载重装。
> 当前签名证书 SHA-256：`90239544490da1b24799cfc24a1383f730c64b732b6180ab6cffe4ddf496a931`

备用生成流程（需本机 JDK）：

```bash
keytool -genkeypair -v -keystore anylisten.jks -storetype PKCS12 -alias anylisten \
  -keyalg RSA -keysize 2048 -validity 10950
```

### 本地

```bash
gradle assembleDebug   # 需本机安装 Gradle 8.4 + JDK 17，SDK 组件由 AGP 自动安装
```

## 项目结构

- `app/src/main/java/com/anylisten/mobile/` — 应用代码
  - `MainActivity` 启动分发（未配置 URL → 设置页；已配置 → 播放页）
  - `SetupActivity` 服务器 URL 设置页
  - `PlayerActivity` WebView 加载站点，注入 JS 桥，转发原生媒体指令
  - `MediaService` 前台媒体服务：`MediaSessionCompat` + 通知栏/锁屏控制 + 唤醒锁
  - `JsBridge` 页面 → 原生的状态通道（`anyListenNative.onMediaState`）
  - `Prefs` 配置存储
- `app/src/main/assets/bridge.js` — 注入页面的桥脚本，是兼容层的核心：
  - **垫片**：Android WebView 不实现 `navigator.mediaSession` / `window.MediaMetadata` / `setSinkId`，缺失时页面初始化会直接崩溃，故三者均提供垫片；
  - **上行**：hook `window.Audio` 捕获所有音频实例，把播放态/进度/元数据推给原生；
  - **下行**：hook `mediaSession.setActionHandler` 保存页面注册的真实处理函数，供 `window.__anylistenBridge.{play,pause,next,prev,seek,stop}` 调用。
- `.github/workflows/build-apk.yml` — 云端构建与发布

### 实现要点（踩坑记录）

- **不要调用 `webView.onPause()`**：它会挂起 WebView 的媒体管道，导致页面内 `audio.paused` 仍为 `false`（假性播放态，通知栏显示暂停图标却完全无声）。
- **不要额外 `requestAudioFocus()`**：WebView 的 Chromium 音频管道自行管理音频焦点，额外请求会抢焦点并让 Chromium 暂停播放，形成 play/pause 死循环。
- **播放态变化不能被节流丢弃**：音频暂停后不再产生任何事件，若这次上报被节流丢掉，原生侧状态会永久卡在 `playing=true`，表现为"点播放毫无反应且无日志"。
- **暂停态 `PlaybackStateCompat` 的 speed 必须为 0**：传 `1f` 会让系统把会话误判为"正在播放"，从而派发 `onPause`。

## 许可证

本项目以 [GNU AGPL-3.0](LICENSE) 授权。

上游 [any-listen](https://github.com/any-listen/any-listen) 采用基于 AGPL v3.0 的自定义许可证（附加禁止商业使用条款）。
本项目不包含其任何源码，属独立作品；若你同时分发 any-listen 本体，请另行遵守其自身许可条款。
