# 跟随 ntfy Android 上游升级

本仓库是真实 GitHub Fork：`dlaq/qiuqiu-push-android` → `binwiederhier/ntfy-android`。

- `main`：球球推送代码，当前功能基于已测试的 v1.25.2。
- `upstream-main`：初次发布时上游 main 的完整快照，保留上游进度；不代表已经合并进球球版本。
- 本地 `upstream`：原始仓库地址；`origin`：自己的 Fork。
- 发布时维持稳定版本基线，不把上游 main 未经验证的变化强行加入产品。

## 克隆及设置远程

```sh
git clone git@github.com:dlaq/qiuqiu-push-android.git
cd qiuqiu-push-android
git remote add upstream https://github.com/binwiederhier/ntfy-android.git
git fetch upstream --tags
```

开发机当前仓库已设置 origin/upstream，不用重复添加。

## 升级流程

1. `git fetch upstream --tags`，阅读上游 release notes，选定一个真实存在的稳定版本标签。
2. 从自己的 main 创建升级分支：`git switch main && git switch -c upgrade/ntfy-android-VERSION`。
3. `git merge --no-ff 上游版本标签`，按正常合并解决冲突，保留球球功能和上游修复。不要使用 `-s ours` 或丢弃自己的提交来假装完成升级。
4. 检查 applicationId、Firebase 配置、Keystore、Room schema、FCM 私有 Topic 取消息、业务渠道和点击链接。
5. 执行构建、单元测试、Lint、`scripts/check-background-policy.py`，在测试设备完成通知与低功耗回归。不要重新引入前台服务、周期轮询或 control keepalive。
6. 将升级分支推送到自己的 origin，通过 PR 审核并合入自己的 main。未经用户授权，不向上游项目发送 PR 或推送。

GitHub 的“同步 Fork”不能替代冲突处理和回归测试；不要选择会丢弃球球改动的操作。Firebase 服务端私钥、签名 JKS、密码文件、真实部署配置不入 Git。
