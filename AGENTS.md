# 奶奶看剧：项目级开发约定

本文件是本仓库所有后续开发会话的首要上下文。开始修改前，先完整阅读本文件和 `README.md`。除非用户明确要求改变产品方向，否则以下约束视为项目不变量。

## 1. 项目目标

这是一个只服务家里老人的极简 Android AI 短剧播放器。

最重要的产品指标不是功能数量，而是：

- 奶奶点击桌面图标后能直接看剧。
- 不出现广告、登录、付费、评论、搜索或复杂导航。
- 视频结束后自动播放下一部，不会被困在错误页。
- 家属负责内容和更新维护，奶奶模式不承担管理操作。
- 已经稳定可用的路径优先于“更先进”但复杂的新架构。

任何功能如果会增加奶奶模式的选择、弹窗、按钮或异常分支，必须先证明它确实改善老人体验。

## 2. 固定技术与身份信息

- 应用名：`奶奶看剧`
- namespace / 正式 applicationId：`com.xmoyi.nainaisv`
- Debug applicationId：`com.xmoyi.nainaisv.debug`
- 最低 Android：API 26
- compileSdk / targetSdk：35
- JDK：17
- Gradle：8.11.1
- Android Gradle Plugin：8.9.1
- Kotlin：2.1.20
- UI：Jetpack Compose + Material 3
- 播放：Media3 ExoPlayer
- 本地数据：Room + DataStore
- 网络：OkHttp
- 更新地址：`https://app.xmoyi.com/nainaisv/stable/update.json`

不要修改包名。包名变化会导致正式版无法覆盖安装。

不要随意升级 Kotlin、AGP、Compose、Room 或 Media3。依赖升级必须作为独立变更，先说明收益和兼容风险，再完成 Debug、Lint、Release 和真机播放验证。

## 3. 当前架构与职责

依赖方向必须保持：`UI -> ViewModel -> Repository -> DAO / Network`。

### 应用入口

- `NaiNaiApplication.kt`：创建 `AppContainer`，集中装配数据库、设置、B站客户端、仓库和更新管理器。
- `MainActivity.kt`：只负责奶奶模式/家属模式切换、首次设置状态和 PIN 验证。

项目当前使用轻量手工依赖注入。除非模块数量和测试替换需求明显增长，不要引入 Hilt、Koin 或新的服务定位器。

### 奶奶模式

- `player/GrandmaScreen.kt`：纯 Compose 展示和手势交互。
- `player/PlayerViewModel.kt`：播放队列、双播放器池、进度保存、续播、重试、720P→480P 降级和自动下一部。

不允许在 Composable 中直接发网络请求、访问 DAO、解析 B站 JSON 或操作文件。

### 家属模式

- `caregiver/CaregiverScreen.kt`：首次设置、PIN、候选内容、可信作者、过滤规则、历史和更新 UI。
- `caregiver/CaregiverViewModel.kt`：家属操作的状态与协程入口。

未知作者只能进入候选区。未经家属确认，不得进入奶奶播放队列。

### 数据层

- `data/AppDatabase.kt`：Room Entity、DAO 和数据库。
- `data/SettingsStore.kt`：DataStore 设置、PIN 摘要、同步时间和更新地址。
- `data/DramaRepository.kt`：内容导入、同步、过滤、占位内容解析、播放地址获取和进度保存。
- `data/Models.kt`：跨层领域模型。

DAO 只允许由 Repository 使用。不要让 Screen 或 ViewModel 绕过 Repository 直接操作数据库。

`DramaEntity` 的身份规则：

- 已解析内容：`id = "$bvid:$cid"`
- 尚未解析的投稿占位：`id = "$bvid:0"` 且 `cid = 0`
- 占位内容只在即将播放时通过 `ensureResolved` 展开为多 P/合集条目。

不要再创建另一套“视频模型”“播放历史表”或“作者缓存”。优先扩展现有模型和 DAO 查询。

### B站适配

- `network/BilibiliClient.kt`：搜索、详情、多 P/UGC 合集、公开播放地址、分享链接跳转、作者信息和投稿列表。
- `network/WbiSigner.kt`：WBI mixin key 与签名。
- `recommendation/RecommendationEngine.kt`：纯规则评分、过滤和队列排序。

B站接口不是稳定正式开放 API，因此：

- 所有接口 URL、JSON 解析、Referer/User-Agent 和 WBI 细节必须留在 `network` 包。
- UI 和 ViewModel 不得依赖 B站 JSON 字段或接口路径。
- 不添加 B站账号登录、Cookie、会员绕过、付费绕过、视频下载或重新托管。
- 只播放公开免费内容；付费、充电专属、预览、失效内容必须过滤。
- 当前队列永远优先于联网刷新；同步失败不能阻塞播放。
- 可信作者自动刷新间隔至少六小时。失败尝试也记录时间，避免每次启动撞风控。
- 全局候选搜索最多每七天自动执行一次；家属可以主动搜索。
- 遇到 412、风控校验失败、超时或字段变化时应安全失败并保留本地内容，不要高频重试。

新增 B站接口时，应扩展 `BilibiliClient`，并至少添加解析测试或受环境变量控制的 live smoke test。

### 更新与发布

- `update/UpdateManager.kt`：手动检查、下载、大小/SHA-256 校验和系统安装器调用。
- `scripts/publish-r2.sh`：上传版本化 APK、校验 R2 对象、最后上传 `update.json`。
- `.github/workflows/release-r2.yml`：正式签名与 R2 发布。

发布顺序是强约束：

1. 测试。
2. 使用固定正式密钥签名 APK。
3. 上传不可覆盖的版本化 APK。
4. 校验远端文件大小。
5. 最后覆盖 `nainaisv/stable/update.json`。

不要先发布清单。不要覆盖旧版本 APK。不要把 keystore、密码、Cloudflare Token 或生成后的敏感配置提交到 Git。

## 4. 修改前必须做的事

每次实现前按这个顺序：

1. 阅读 `README.md`、本文件和目标模块。
2. 使用 `rg` 搜索已有类、函数、状态、字符串和相似行为。
3. 确认功能应属于 UI、ViewModel、Repository、Network、DAO 还是 Update。
4. 优先修改已有路径；只有现有职责确实无法容纳时才新增文件或抽象。
5. 检查工作区已有改动，不覆盖用户或其他会话的修改。

若发现 README、本文件和代码不一致，以可运行代码为当前事实，但必须在同一变更中修正文档。

## 5. 防止重复和技术债的规则

- 一个事实只能有一个数据源：不要在多个 ViewModel 重复保存可信作者、过滤词或当前播放进度。
- 不复制粘贴网络解析代码；共用逻辑放回 `BilibiliClient` 内的私有函数。
- 不创建泛化 `Utils`、`Manager`、`Helper` 作为临时垃圾桶。共享抽象至少应有两个真实调用方。
- 不为单一按钮、单一接口或单一字段引入新架构层。
- 不把业务规则写进 Composable；Composable 只渲染状态并发送用户意图。
- 不在 ViewModel 中拼 B站 URL 或解析 JSON。
- 不在 Network 层写推荐排序或数据库逻辑。
- 不创建与现有 `DramaItem`、`DramaEntity`、`PlaybackSource`、`WatchStateEntity` 重叠的数据类。
- 新依赖必须说明现有标准库或依赖为什么无法满足，并评估 APK 体积、minSdk 和 R8。
- 优先删除废弃路径，再添加替代实现；不要长期保留两套并行方案。
- 保持函数和状态命名表达业务含义，避免 `data2`、`tempManager`、`newPlayer` 等过渡命名进入主干。
- 修复根因，不通过吞异常、无限重试、关闭 Lint 或扩大 ProGuard keep 规则掩盖问题。

## 6. 数据库与兼容性

- 观看进度、可信作者和过滤规则属于用户数据，不能静默清空。
- 修改 Room Entity 或表结构时必须：提升数据库版本、编写显式 Migration、添加迁移测试。
- 禁止恢复 `fallbackToDestructiveMigration`。
- 修改 DataStore key 时要保留旧 key 的迁移或兼容读取。
- 正式签名密钥、applicationId 和 R2 更新地址视为长期兼容接口。
- `versionCode` 必须递增；`versionName` 使用语义化版本。

## 7. UX 不变量

奶奶模式必须保持：

- 启动即播或恢复，不出现首页选择。
- 上滑下一部、下滑上一部、单击暂停/继续。
- 视频结束后自动下一部。
- 不出现更新提示、搜索、评论、点赞、登录和付费入口。
- 网络失败自动恢复或换片，不展示技术堆栈和接口错误码。
- 返回键两次确认退出。
- 横屏内容等比显示，应用本身保持竖屏。

家属模式可以复杂一些，但所有破坏性操作应明确、可恢复。更新只能由家属主动点击检查。

## 8. 测试与完成标准

提交前至少按顺序运行，避免 AGP/KAPT/Lint 在同一 Gradle 调用中分析不同变体时发生缓存竞态：

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug assembleDebug
./gradlew assembleRelease
```

不要把 `lintDebug` 和 `assembleRelease` 放在同一个 Gradle 命令中。

可选真实接口测试：

```bash
LIVE_BILI_TEST=1 ./gradlew testDebugUnitTest \
  --tests com.xmoyi.nainaisv.network.BilibiliLiveSmokeTest
```

Live test 可能因 B站风控失败，不应作为 CI 硬门槛；但公开视频详情和播放地址改动必须在交付前手动验证一次。

不同类型变更的最低验证：

- 推荐/过滤：单元测试覆盖允许、拦截、不重复和排序。
- WBI/解析：固定样例单元测试；必要时追加受控 live test。
- Room：DAO 或迁移测试。
- 播放：Debug 构建 + 真机切换、后台恢复、结束自动下一部。
- 更新：清单解析、HTTPS、大小、SHA-256、未知来源权限和同签名覆盖。
- 发布：脚本 `bash -n`、R2 APK 先于清单、远端大小校验。

构建成功不等于体验完成。涉及奶奶模式交互时，交付说明必须列出仍需真机验证的场景。

## 9. Git 与交付习惯

- 默认分支：`main`
- 提交应小而完整，标题说明行为变化，例如 `feat: add trusted creator import`。
- 不提交：`local.properties`、构建目录、APK/AAB、keystore、密码、Token、IDE 配置。
- 提交前检查 `git status --short` 和 `git diff --check`。
- 不使用破坏性重置覆盖未提交工作。
- 每次交付说明包含：改了什么、测试了什么、仍需用户账号/真机完成什么。

## 10. 新会话快速接续清单

新会话开始后：

1. 运行 `git status --short --branch`。
2. 阅读最近提交：`git log --oneline -5`。
3. 阅读本文件与 README。
4. 用 `rg` 定位目标路径，不从空白重新实现。
5. 若当前工作树有改动，先理解并保留它们。
6. 修改后更新相关测试和文档。
7. 按第 8 节顺序验证。

若用户只要求调研、诊断或解释，不要擅自修改功能、发布 APK 或操作 Cloudflare。
