# 奶奶看剧：项目开发约定

本文件是本仓库后续开发会话的首要上下文。开始工作前完整阅读本文件和 `README.md`；若文档与可运行代码不一致，以代码为当前事实，并在同一变更中修正文档。

## 1. 产品与体验不变量

这是只服务家里老人的极简 Android AI 短剧播放器。老人体验和稳定性优先于功能数量与架构新颖度。

奶奶模式必须保持：

- 点击桌面图标后直接播放或恢复，不出现首页选择。
- 上滑下一部、下滑上一部、单击暂停/继续，播放结束自动下一部。
- 不出现广告、登录、付费、评论、点赞、搜索、更新提示或管理入口。
- 网络异常时自动恢复、降级或换片，不展示技术错误码和堆栈。
- 返回键两次确认退出；横屏视频等比显示，应用保持竖屏。

家属负责首次设置、内容筛选、可信作者、过滤规则、历史和更新。未知作者只能进入候选区，未经家属确认不得进入奶奶播放队列。家属模式的破坏性操作必须明确且可恢复，更新只能由家属主动检查。

任何增加奶奶模式按钮、弹窗、选择或异常分支的功能，都必须先证明能改善老人体验。

## 2. 固定身份与技术基线

- 应用名：`奶奶看剧`
- namespace / 正式 applicationId：`com.xmoyi.nainaisv`
- Debug applicationId：`com.xmoyi.nainaisv.debug`
- minSdk 26；compileSdk / targetSdk 35
- JDK 17；Gradle 8.11.1；Android Gradle Plugin 8.9.1；Kotlin 2.1.20
- UI：Jetpack Compose + Material 3
- 播放：Media3 ExoPlayer
- 本地数据：Room + DataStore
- 网络：OkHttp
- 更新清单：`https://app.xmoyi.com/nainaisv/stable/update.json`
- 正式签名证书 SHA-256：`ad16d8bf93454913f9f6fc29b1040c18597959b42ab62f18ce6af8b263adb2a0`

applicationId、正式签名密钥和更新地址是长期兼容接口。不得修改包名或更换正式密钥，否则新版本无法覆盖旧安装。

不要顺手升级 Kotlin、AGP、Compose、Room 或 Media3。依赖升级必须作为独立变更，说明收益、兼容风险、APK 体积、minSdk 和 R8 影响，并完成 Debug、Lint、Release 与真机播放验证。

## 3. 架构与职责

依赖方向必须保持：`UI -> ViewModel -> Repository -> DAO / Network`。

- `NaiNaiApplication.kt` / `AppContainer`：集中装配数据库、设置、B站客户端、仓库和更新管理器。继续使用轻量手工依赖注入，除非模块和测试替换需求明显增长。
- `MainActivity.kt`：只负责奶奶/家属模式切换、首次设置状态和 PIN 验证。
- `player/GrandmaScreen.kt`：纯 Compose 展示与手势；不得直接访问网络、DAO、文件或解析 JSON。Pager 必须以 `DramaEntity.id` 作为 key；非当前页显示封面而不是黑屏；家属入口是按住左上角约三秒的自定义手势，不得消费普通点击和滑动。
- `player/PlayerViewModel.kt`：播放队列、双播放器池、进度与续播、720P→480P 降级和自动下一集。占位条目解析后必须把整部剧拼接进队列（`spliceResolved`）；`prepareSlot` 不得吞掉 `CancellationException`；切换播放前必须同时取消 `loadJob` 和 `preloadJob`；播放地址过期（90 分钟）后恢复播放要重新解析；反复失败只自动换片，不把内容永久标记为不可播（永久标记只由 `UnplayableDramaException` 路径触发）。
- `caregiver/CaregiverScreen.kt`：家属 UI，首次设置是两步引导（PIN → 添加内容），日常管理是内容/作者/历史/设置四个页签；`CaregiverViewModel.kt`：家属状态与协程入口，信任作者立即写库生效、作品同步放后台。
- `data/AppDatabase.kt`：Room Entity、DAO 和数据库；DAO 只允许 Repository 调用。
- `data/SettingsStore.kt`：DataStore 设置、PIN 摘要、同步时间和更新地址。
- `data/DramaRepository.kt`：导入、同步、过滤、占位解析、播放地址和进度保存；家属改动内容后递增 `contentVersion`，奶奶模式据此刷新队列。
- `data/Models.kt`：跨层领域模型。
- `network/BilibiliClient.kt`：B站接口、请求头、JSON 解析、分享跳转和播放地址。构造函数接受可注入的 `apiBase`，用于 MockWebServer 固定样例测试。
- `network/WbiSigner.kt`：WBI mixin key 与签名。
- `recommendation/RecommendationEngine.kt`：纯规则评分、过滤与排序。
- `update/UpdateManager.kt`：手动检查、下载、大小/SHA-256 校验和系统安装器调用。
- `ui/theme/NaiNaiTheme.kt`：全局 Material 3 深色配色主题。

`DramaEntity` 身份与剧集规则不可另起一套：

- 已解析内容：`id = "$bvid:$cid"`。
- 投稿占位：`id = "$bvid:0"` 且 `cid = 0`。
- 占位只在即将播放时通过 `ensureResolved` 展开为多 P/合集条目，返回完整有序集数列表。
- `seriesKey` 标识同一部剧：UGC 合集用 `season:<合集id>`，多 P 和单视频用 `bv:<bvid>`；空值按 `bv:<bvid>` 回退。`page` 是该集在剧中的序号。
- 播放队列必须保证同一 `seriesKey` 的集连续且按 `page` 排序——“播完自动下一部”播的是下一集，不是别的剧。剧与剧之间：看了一半的最前（最近优先），然后未看的按分数，最后是已看完的。

一个事实只能有一个数据源。优先扩展现有 `DramaItem`、`DramaEntity`、`PlaybackSource`、`WatchStateEntity`、DAO 查询和 Repository，不创建重叠的视频模型、历史表、作者缓存或 ViewModel 状态。

## 4. B站适配边界

B站网页接口不是稳定的正式开放 API，所有接口 URL、JSON 字段、Referer、User-Agent 和 WBI 细节必须留在 `network` 包；UI 和 ViewModel 不得感知这些细节。

- 不添加 B站账号登录、Cookie、会员/付费绕过、视频下载或重新托管。
- 只播放公开免费内容；过滤付费、充电专属、预览和失效内容。
- 当前本地队列优先于联网刷新；同步失败不能阻塞播放。
- 可信作者自动刷新至少间隔六小时，失败尝试也记录时间。
- 全局候选自动搜索最多每七天一次；家属可主动搜索。
- 遇到 412、风控、超时或字段变化时安全失败并保留本地内容，不高频重试。
- 新增或修改接口时扩展 `BilibiliClient`，至少添加固定样例解析测试；必要时追加受环境变量控制的 live smoke test。

## 5. 数据与兼容性

- 观看进度、可信作者和过滤规则属于用户数据，不得静默清空。
- 当前数据库版本为 2（1→2 增加 `seriesKey`/`seriesTitle` 并回填）。修改 Room Entity 或表结构时必须提升数据库版本、编写显式 Migration 并添加迁移测试；schema JSON 导出到 `app/schemas`。
- 禁止使用 `fallbackToDestructiveMigration`。
- 修改 DataStore key 时保留旧 key 的迁移或兼容读取。
- `versionCode` 和稳定三段式 `versionName` 每次正式发布都必须严格递增。

## 6. 开发流程

开始工作：

1. 运行 `git status --short --branch` 和 `git log --oneline -5`。
2. 阅读本文件、`README.md` 和目标模块；理解并保留现有工作区改动。
3. 用 `rg` 搜索已有类、状态、字符串和相似行为。
4. 确认职责归属，优先修改已有路径；现有职责确实无法容纳时才新增文件或抽象。

实现时：

- Composable 只渲染状态并发送用户意图；业务规则放在 ViewModel、Repository 或纯规则组件。
- 不在 ViewModel 拼 B站 URL/解析 JSON，不在 Network 层写推荐或数据库逻辑。
- 不复制网络解析代码，不创建泛化 `Utils`、`Helper`、`Manager` 临时收纳逻辑；共享抽象至少有两个真实调用方。
- 不为单一按钮、接口或字段新增架构层；删除废弃路径后再引入替代方案，避免长期双轨。
- 修复根因，不通过吞异常、无限重试、关闭 Lint 或扩大 ProGuard keep 规则掩盖问题。
- 新依赖必须说明现有标准库和依赖为何不足。

## 7. 验证标准

代码提交前按顺序运行，避免不同变体的 Gradle 缓存竞态：

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug assembleDebug
./gradlew assembleRelease
```

不要把 `lintDebug` 和 `assembleRelease` 放在同一个 Gradle 命令中。纯文档变更可省略 Android 构建，但仍需运行相关静态检查和远端 CI。

按变更补充验证：推荐/过滤覆盖允许、拦截、去重和排序（含剧集连续性）；WBI/解析使用固定样例（`BilibiliClientTest` 走 MockWebServer）；Room 添加 DAO/迁移测试（`MigrationTest` 在 androidTest 中直接执行迁移 SQL）；播放真机验证切换、后台恢复和结束续播；更新验证 HTTPS、大小、SHA-256、未知来源权限及同签名覆盖。

可选真实接口测试：

```bash
LIVE_BILI_TEST=1 ./gradlew testDebugUnitTest \
  --tests com.xmoyi.nainaisv.network.BilibiliLiveSmokeTest
```

Live test 可能被风控，不作为 CI 硬门槛。涉及公开视频详情或播放地址时仍需手动验证；涉及奶奶模式交互时，交付说明必须列出待真机验证场景。

## 8. 正式发布

- 发布入口：`.github/workflows/release-r2.yml`；上传逻辑：`scripts/publish-r2.sh`；R2 Bucket：`nainaisv-releases`。
- 只从 `main` 手动触发，必须勾选 `confirm_release`，并使用并发锁。`production` Environment 不设置 required reviewer 或 wait timer，发布触发后无需用户再次审批。
- 使用固定正式密钥签名并校验证书指纹；Secrets 只保存在 `production` Environment，不进入代码、PR、Issue 或日志。
- 先测试，再构建签名 APK；使用条件写入上传不可覆盖的版本化 APK；校验 R2 大小、SHA-256 元数据和公开地址；最后条件写入覆盖 `nainaisv/stable/update.json`。
- 清单发布前失败时删除本次新 APK。日志若已显示清单上传成功，不得用相同版本直接重跑，应先核对线上清单和缓存状态。
- 发布脚本变更至少执行 `bash -n`，并验证“APK 先于清单、版本不可倒退、远端校验、失败清理”。

不得先发布清单、覆盖旧 APK、复用/倒退版本号，或提交 keystore、密码、Cloudflare Token、生成的签名配置和 APK/AAB。

## 9. GitHub 与交付

- 仓库公开，默认分支 `main`；通过 PR + 必需的 `build` CI 合并，保持线性历史，禁止强推和删除 `main`。
- GitHub Actions 必须固定完整 commit SHA，只使用 GitHub 官方 Action 和 `gradle/actions`。
- 不在公开 Issue、PR、Actions 日志或设备日志中暴露凭据、Token、Cookie、PIN、路径中的秘密或完整敏感数据。
- 提交应小而完整，标题描述行为变化；不覆盖用户或其他会话的未提交修改，不使用破坏性重置。
- 提交前检查 `git status --short`、`git diff --check` 和相关测试；文档、代码与实际流程保持同步。
- 交付说明包含：改了什么、验证了什么、仍需用户账号或真机完成什么。

若用户只要求调研、诊断或解释，不得擅自修改功能、发布 APK 或操作 Cloudflare。
