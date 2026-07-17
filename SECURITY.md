# 安全说明

## 支持范围

目前只维护 `main` 分支和最新正式 APK。旧版本发现安全问题后，应优先升级到最新版本。

## 报告安全问题

涉及以下内容时，请不要创建公开 Issue：

- Android 正式签名、覆盖安装或更新清单校验绕过。
- Cloudflare R2 凭据、GitHub Actions Secrets 或发布流程泄露。
- 可导致任意 APK 下载、安装或文件写入的问题。
- 可能泄露家属 PIN、观看数据或本地设置的问题。

请使用仓库 Security 页面中的 “Report a vulnerability” 私密报告入口。仓库公开后会启用 Private vulnerability reporting。

普通播放错误、B 站接口变化和界面问题可以通过公开 Issue 报告，但请先删除日志中的个人链接、设备标识和其他隐私信息。

## 凭据处理

本项目不会要求在 Issue、Pull Request 或聊天中提交 keystore、密码、Cloudflare Token、B 站 Cookie 或 GitHub Token。正式凭据只应保存在密码管理器、离线加密备份和 GitHub `production` Environment Secrets 中。
