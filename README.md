# 奶奶看剧

[![Android CI](https://github.com/sagemoyi/nainaisv/actions/workflows/ci.yml/badge.svg)](https://github.com/sagemoyi/nainaisv/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

一个给家里老人使用的极简 Android AI 短剧播放器。应用直接播放 B 站公开免费视频，不需要 B 站账号；奶奶打开应用后会自动续播，播完自动换下一部，也可以像短视频应用一样上下滑动。

> 这是家庭个人项目。它不下载、转存或重新分发视频，不绕过会员、付费和作者权限。B 站网页接口并非稳定的正式开放 API，接口变化时需要更新客户端。

源码以 [MIT License](LICENSE) 公开，方便家庭自用、学习和改进。许可证只覆盖本仓库代码，不授予任何 B 站视频、作者作品、名称或第三方素材的权利。

## 已实现

- 原生 Kotlin、Jetpack Compose 和 Media3 ExoPlayer。
- 包名 `com.xmoyi.nainaisv`，最低 Android 8，界面固定竖屏。
- 上下滑动、单击暂停、结束自动下一集、重启恢复进度。
- 以“剧”为单位组织队列：同一部剧的各集连续按顺序播放，多 P 和 UGC 合集自动展开成整部剧。
- 双播放器池：当前播放时预解析并缓冲下一集；滑动时显示封面，不再黑屏。
- 标题、作者和集数在切换或暂停时显示几秒后自动淡出，细进度条常驻底部；奶奶模式隐藏系统状态栏和导航栏。
- 奶奶自己点的暂停会一直保持，切回前台不自动续播；被连续快速滑走的剧自动排到新剧后面。
- 公开 720P 播放，失败自动尝试 480P；地址过期后重新解析；反复失败自动换片。
- Room 保存可信作者、候选内容、过滤规则和观看历史（v2 起含剧集信息，带显式迁移）。
- 首次两步家属设置（PIN → 添加内容）、按住左上角约三秒的隐藏入口、加盐哈希 PIN。
- 家属管理分内容/作者/历史/设置四个页签，候选内容带封面并可跳浏览器预览。
- B 站关键词搜索、视频/合集/UP 主链接导入、WBI 投稿同步。
- 未知作者只能进入候选区，必须由家属信任后才能出现在奶奶模式。
- 家属改动内容后，奶奶端队列自动刷新，无需重启应用。
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
./gradlew testDebugUnitTest
./gradlew lintDebug assembleDebug
./gradlew assembleRelease
```

Release 构建在没有配置正式密钥时会生成未签名 APK，仅用于验证 R8 和资源压缩；可安装和发布的正式 APK 必须由下文的 GitHub Actions 使用固定密钥签名。

调试 APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

调试版本 applicationId 带有 `.debug` 后缀，可以和正式版同时安装。

## 首次使用

1. 安装 APK 后打开应用，首先进入两步家属设置。
2. 第一步：设置至少四位数字的家属 PIN。
3. 第二步：粘贴喜欢的 B 站视频、合集或 UP 主空间链接，或者搜索“AI短剧 全集”，然后信任喜欢的作者。
4. 至少信任一位作者即可完成设置（建议先添加三位以上，内容更丰富）。
5. 点击“完成设置，进入奶奶模式”。
6. 以后按住播放页左上角约三秒，输入 PIN 即可重新进入家属管理。

建议先由家属实际看过作者的几部作品，再把作者加入可信列表。关键词过滤只能辅助，不能完全替代人工判断。

## 奶奶模式操作

- 打开应用：自动播放或恢复上次进度；上次那集看完了就从下一集开始。
- 上滑：下一集（一部剧播完才进入下一部）。
- 下滑：上一集。
- 点一下画面：暂停或继续。暂停后不会自动恢复，需要再点一下。
- 视频播完：自动切换下一集。
- 标题和作者只在切换或暂停时显示几秒，底部细进度条一直可见。
- 返回键：需要在两秒内按两次才退出，第一次按会在屏幕中央提示。
- 家属管理页按返回键回到奶奶模式。

应用不会在奶奶模式展示更新、评论、点赞、搜索或 B 站网页。

## Cloudflare R2 发布

GitHub 仓库公开后，源码和 CI 日志对所有人可见，但奶奶端检查更新和下载 APK 仍只走 `app.xmoyi.com`，不依赖 GitHub 登录或 GitHub Release。正式签名、R2 Token 和其他秘密值始终只保存在 GitHub `production` Environment Secrets 中。

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

不需要配置 CORS。自定义域名必须绑定到这个 Bucket 的根目录，并且 HTTPS 已经生效；发布前可以先检查：

```bash
curl -I https://app.xmoyi.com/
```

即使返回 `404` 也没关系，但 TLS 握手必须成功。

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

### 3. 创建唯一的正式签名密钥

只生成一次正式签名密钥：

```bash
keytool -genkeypair -v \
  -keystore nainaisv-release.jks \
  -alias nainaisv \
  -keyalg RSA -keysize 4096 -validity 10000
```

把 keystore、alias 和两个密码保存在密码管理器及至少一份离线加密备份中。丢失或重新生成密钥后，旧安装将无法被新版本覆盖。

计算不敏感的签名证书 SHA-256 指纹，后续 workflow 会用它阻止误用另一把密钥：

```bash
read -s KEYSTORE_PASSWORD
export KEYSTORE_PASSWORD
keytool -exportcert \
  -keystore nainaisv-release.jks \
  -alias nainaisv \
  -storepass:env KEYSTORE_PASSWORD | sha256sum
unset KEYSTORE_PASSWORD
```

记录输出的 64 位十六进制值，不包含文件名和空格。

### 4. 配置 GitHub production 环境

在仓库 Settings → Environments 中创建 `production`：

- Deployment branches 只允许 `main`。
- 不配置 required reviewer 或 wait timer；工作流触发后无需再次人工审批。
- workflow 会检查 `main`、生产确认框和并发锁，并校验固定签名证书及 R2 条件写入。

在 `production` 环境中配置以下 Secrets。

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

将 keystore 转成单行 Base64，复制结果到 `ANDROID_KEYSTORE_BASE64`：

```bash
base64 -w 0 nainaisv-release.jks
```

macOS 可以使用：

```bash
base64 < nainaisv-release.jks | tr -d '\n'
```

再在 `production` 环境的 Variables 中添加：

```text
ANDROID_SIGNING_CERT_SHA256
```

值为上一步记录的 64 位证书指纹。证书指纹不是密码，可以作为 Variable 保存。

### 5. GitHub Actions 安全设置

公开仓库建议在 Settings → Actions → General 中：

- 保持默认 workflow 权限为只读；发布 workflow 自己也只申请 `contents: read`。
- 只允许 GitHub 官方 Actions 和 `gradle/actions`，或者启用“要求完整 commit SHA”；仓库内 workflow 已固定所有 Action 的 commit SHA。
- `main` 已要求通过 PR 和 `build` CI 合并，并禁止强推、删除和非线性历史。

不要把 R2 或签名 Secrets 配到普通 PR 使用的 job。来自 fork 的 PR 默认也不会得到这些 Secrets。

在 Settings → Security 中启用 Private vulnerability reporting。安全问题按 [SECURITY.md](SECURITY.md) 私密报告，不要在公开 Issue 中粘贴凭据或完整设备日志。

### 6. 首次发布

先提交并推送本次发布流程到 `main`，确认 `Android CI` 通过。然后在 GitHub Actions 手动运行 `Release APK to Cloudflare R2`，分支选择 `main`，首次正式版填写：

```text
version_name: 1.0.0
version_code: 1
release_notes: 首个家庭使用版本
confirm_release: 勾选
```

以后每次发布都必须使用新的三段式版本名，例如 `1.0.1`，并严格递增 `version_code`。同名 APK 永远不会被覆盖。

工作流会：

1. 运行单元测试。
2. 运行 Lint 并构建 Debug APK。
3. 构建 Release APK，验证 APK 签名和固定证书指纹。
4. 检查线上 `versionCode`，拒绝倒退或覆盖已有版本化 APK。
5. 上传 APK，校验 R2 文件大小、SHA-256 元数据和公开下载地址。
6. 最后更新 `update.json`，再从公开域名读取并核对清单内容。

发布完成后，先在浏览器或命令行检查：

```bash
curl -fsS https://app.xmoyi.com/nainaisv/stable/update.json | jq .
```

再把首次正式 APK 从 R2 安装到真机。之后才能验证“同签名覆盖安装”；Debug 版带 `.debug` 包名，不能代替这项测试。

应用不会自动请求更新清单。只有家属进入管理页并点击“检查更新”时才会访问：

```text
https://app.xmoyi.com/nainaisv/stable/update.json
```

Android 不允许普通应用静默更新。下载并校验完成后，仍需家属在系统安装界面确认；首次更新可能需要允许“安装未知应用”。

若 workflow 在上传 `update.json` 之前失败，脚本会清理本次新上传的 APK，可以修复配置后重跑。若日志明确显示 `update.json` 已上传，但最后的公开域名校验失败，不要直接用相同版本重跑；先检查 R2 自定义域名和缓存规则，并确认线上清单是否已经是新版本。

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
