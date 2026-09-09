# AnyListen Android

any-listen 网页版的 Android 壳应用：一个可配置服务器地址的 WebView App。
所有功能（播放、列表、歌词、扩展等）都来自你部署的 any-listen 网页版服务，本应用负责提供原生体验：后台播放、锁屏/通知栏媒体控制、自动切歌保活。

## 使用

1. 安装 APK（GitHub Actions 产物或 Release 附件）。
2. 首次启动填写你的服务器地址，例如 `https://music.example.com`。
3. 进入播放界面，行为与手机 Chrome 打开该站点一致（已验证 Chrome 内核下功能完整）。

## 构建

### 云端（推荐）

仓库已内置 `.github/workflows/build-apk.yml`：

- push / PR / 手动触发都会构建 `debug` 与 `release` 两个 APK，作为 Actions artifact（保留 90 天）提供下载；
- 打 `v1.0.0` 之类 tag 时，APK 会自动挂到 GitHub Release 页面（长期保存、可直接分发）。

### 正式签名（可选）

不配置时 `release` 包自动使用 debug 签名（可安装，但不适合长期更新分发）。需要正式签名时：

1. 本地生成 keystore（需 JDK，任意机器一次即可）：

   ```bash
   keytool -genkeypair -v -keystore anylisten.jks -alias anylisten \
     -keyalg RSA -keysize 2048 -validity 10000
   ```

2. 在仓库 Settings → Secrets and variables → Actions 添加：

   | Secret | 值 |
   | --- | --- |
   | `KEYSTORE_BASE64` | keystore 文件 base64（Windows: `certutil -encode anylisten.jks out.b64` 后取内容） |
   | `KEYSTORE_PASSWORD` | keystore 密码 |
   | `KEY_ALIAS` | 别名（上例 `anylisten`） |
   | `KEY_PASSWORD` | 密钥密码 |

   keystore 请妥善备份：换 keystore 后老用户无法直接覆盖升级。

### 本地

```bash
gradle assembleDebug   # 需本机安装 Gradle 8.4 + JDK 17，SDK 组件由 AGP 自动安装
```

## 项目结构

- `app/src/main/java/com/anylisten/mobile/` — 应用代码
  - `MainActivity` 启动分发（未配置 URL → 设置页；已配置 → 播放页）
  - `SetupActivity` 服务器 URL 设置页
  - `PlayerActivity` WebView 加载站点（自动播放解锁、崩溃恢复、站内跳转）
  - `Prefs` 配置存储
- `.github/workflows/build-apk.yml` — 云端构建
