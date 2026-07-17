# 奶奶看剧

一个给家里老人使用的极简 Android AI 短剧播放器。应用直接播放 B 站公开免费视频，不需要 B 站账号；奶奶打开应用后会自动续播，播完自动换下一部，也可以像短视频应用一样上下滑动。

> 这是家庭个人项目。它不下载、转存或重新分发视频，不绕过会员、付费和作者权限。B 站网页接口并非稳定的正式开放 API，接口变化时需要更新客户端。

## 已实现

- 原生 Kotlin、Jetpack Compose 和 Media3 ExoPlayer。
- 包名 `com.xmoyi.nainaisv`，最低 Android 8，界面固定竖屏。
- 上下滑动、单击暂停、结束自动下一部、重启恢复进度。
- 双播放器池：当前播放时预解析并缓冲下一部。
- 公开 720P 播放，失败自动尝试 480P；地址过期后重新解析。
- 多 P 和 UGC 合集展开，付费、充电专属和失效视频自动排除。
- Room 保存可信作者、候选内容、过滤规则和观看历史。
- 首次家属设置、隐藏长按入口、加盐哈希 PIN。
- B 站关键词搜索、视频/合集/UP 主链接导入、WBI 投稿同步。
- 未知作者只能进入候选区，必须由家属信任后才能出现在奶奶模式。
- 手动检查 R2 更新，APK 文件大小与 SHA-256 校验后调用系统安装器。
- GitHub Actions 构建测试，以及“先 APK、后 update.json”的 R2 发布流程。

## 开发环境

- JDK 17
- Android SDK Platform 35 / Build Tools 35.0.0
- Android Studio 或命令行 Gradle Wrapper

在本仓库根目录配置 `local.properties`：

```properties
sdk.dir=/path/to/Android/Sdk
```

构建与测试：

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

调试 APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

调试版本 applicationId 带有 `.debug` 后缀，可以和正式版同时安装。

## 首次使用

1. 安装 APK 后打开应用，首先进入家属设置。
2. 设置至少四位数字的家属 PIN。
3. 粘贴喜欢的 B 站视频、合集或 UP 主空间链接，或者搜索“AI短剧 全集”。
4. 至少确认三个可信 UP 主。
5. 点击“完成设置，进入奶奶模式”。
6. 以后长按播放页左上角约三秒，输入 PIN 即可重新进入家属管理。

建议先由家属实际看过作者的几部作品，再把作者加入可信列表。关键词过滤只能辅助，不能完全替代人工判断。

## 奶奶模式操作

- 打开应用：自动播放或恢复上次进度。
- 上滑：下一部。
- 下滑：上一部。
- 点一下画面：暂停或继续。
- 视频播完：自动切换下一部。
- 返回键：需要在两秒内按两次才退出。

应用不会在奶奶模式展示更新、评论、点赞、搜索或 B 站网页。

## Cloudflare R2 发布

### 1. 创建存储

在 Cloudflare 创建 R2 Bucket：

```text
nainaisv-releases
```

给 Bucket 绑定自定义域名：

```text
app.xmoyi.com
```

为 `nainaisv/stable/update.json` 创建绕过缓存规则。版本化 APK 可以长期缓存。

最终目录：

```text
nainaisv/stable/update.json
nainaisv/releases/nainaisv-1.0.0.apk
```

### 2. 创建 R2 API Token

创建具有该 Bucket 读写权限的 R2 S3 API Token，记录：

- Account ID
- Access Key ID
- Secret Access Key

### 3. 配置 GitHub Secrets

先把本项目提交并推送到你自己的 GitHub 仓库，然后在仓库 Settings → Secrets and variables → Actions 中配置以下 Secrets。

Cloudflare：

```text
CLOUDFLARE_ACCOUNT_ID
R2_ACCESS_KEY_ID
R2_SECRET_ACCESS_KEY
R2_BUCKET_NAME
```

Android 签名：

```text
ANDROID_KEYSTORE_BASE64
SIGNING_STORE_PASSWORD
SIGNING_KEY_ALIAS
SIGNING_KEY_PASSWORD
```

生成正式签名密钥示例：

```bash
keytool -genkeypair -v \
  -keystore nainaisv-release.jks \
  -alias nainaisv \
  -keyalg RSA -keysize 4096 -validity 10000
```

将 keystore 转为单行 Base64 后保存到 `ANDROID_KEYSTORE_BASE64`。签名密钥不要提交到仓库，也不能丢失；后续版本必须使用同一密钥才能覆盖安装。

### 4. 发布

在 GitHub Actions 手动运行 `Release APK to Cloudflare R2`，填写递增的 `version_code`、版本名和更新说明。

工作流会：

1. 运行单元测试。
2. 构建并签名 release APK。
3. 上传不可变的版本化 APK。
4. 通过 R2 HeadObject 校验文件大小。
5. 最后更新 `update.json`。

应用不会自动请求更新清单。只有家属进入管理页并点击“检查更新”时才会访问：

```text
https://app.xmoyi.com/nainaisv/stable/update.json
```

Android 不允许普通应用静默更新。下载并校验完成后，仍需家属在系统安装界面确认；首次更新可能需要允许“安装未知应用”。

## 内容与播放策略

默认优先词：

```text
全集,完结,合集,加长版,家庭,年代,甜宠,轻喜,治愈,美食,田园
```

默认过滤词：

```text
教程,课程,制作,变现,接稿,赚钱,软件,预告,试看,付费,擦边,成人,恐怖,血腥
```

家属可以在管理页修改这些规则。可信作者最多每六小时自动刷新一次；全局候选搜索最多每七天后台执行一次，也可以由家属主动搜索，避免高频请求触发平台风控。

## 仍需真机完成的验收

- Android 10、12、14、16 的播放、恢复和覆盖更新。
- 连续播放两小时以及连续切换 100 次。
- 弱网、CDN 403、B 站 412 限流和视频删除场景。
- 奶奶至少一周真实试用，再调整字号、手势和内容规则。

项目不收集遥测数据，也不包含广告、账号密码或 B 站 Cookie。
