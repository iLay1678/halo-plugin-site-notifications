# 验证记录

日期：2026-10-12，时区 Asia/Shanghai。本记录对应下列实际安装验证的 **1.0.1** 制品，旧 1.0.0 包的大小、摘要和运行结果不作为本包的验收证明。

## 制品与构建

| 项目 | 值 |
| --- | --- |
| 文件 | `build/libs/halo-plugin-site-notifications-1.0.1.jar` |
| 版本 | `1.0.1` |
| 文件大小 | **100615 字节** |
| SHA-256 | `546A7BD7F83632464F7647B9464DBB031425751C5A9147CF946CF4AF6A35D8DC` |
| JAR 生成时间 | 2026-10-12 08:38:56 +08:00 |
| Java / Halo 目标依赖 | JDK 21 / Halo 2.26.0 |
| 最低 Halo 版本 | >=2.26.0 |

迁移包名时已执行 clean build。随后将版本号升至 1.0.1，执行：

```powershell
./build-local.ps1 -GradleArguments @("build", "--console=plain")
```

08:39 完成，`BUILD SUCCESSFUL in 49s`。后端 7 个测试类、31 项测试通过；前端 2 个测试文件、14 项测试通过；类型、ESLint、Prettier 检查通过。后端报告在 `build/reports/tests/test/index.html`。

构建有 Gradle 弃用提示及 Vue/Axios 与宿主共享依赖补丁版本差异提示，未导致检查失败，本次未升级依赖。

## 命名空间与打包

- 12 个生产文件、8 个测试文件迁至 `top.ilay.sitenotifications`，目录、package 和自有 import 同步更新。逐文件对比确认 Java 变更仅涉及包名。
- 根 Gradle group 为 `top.ilay`，UI group 为 `top.ilay.sitenotifications.ui`，README 路径同步更新。
- JAR 的 25 个业务 class 均在 `top/ilay/sitenotifications/`，无旧业务包 class。
- 自动生成的 `META-INF/plugin-components.idx` 中 7 个组件均指向新包。
- `plugin.yaml` 与 Manifest 的版本均为 1.0.1，Logo、ESM 入口、页面 chunk、CSS 和 `ui-plugin.json` 已包含。
- Halo API、BOM、DevTools 以及 Manifest 中宿主 BasePlugin 的 `run.halo` 引用保留。它们属于平台依赖，自有组件使用上述新索引注册。
- 插件标识、业务数据组及管理 API 地址保持原值，避免历史任务失联。

## 最终包运行验收

环境：隔离容器 `halo-site-notifications-review`，`http://localhost:8093`，仅绑定 `127.0.0.1`；镜像 `registry.fit2cloud.com/halo/halo-pro:2.26`，实际版本 2.26.1。H2 数据目录为 `workplace/review-20261012`。测试账户为 `reviewadmin` 和 guest 角色的 `reviewuser`。

08:41–08:44 在隔离实例卸载旧插件后，通过控制台本地上传 1.0.1 并确认启用，实际状态 `STARTED`，原有 6 条任务仍可读取。安装目录中 JAR 的大小和 SHA-256 与本文完全一致。**本次属于重新安装验证，不是覆盖升级验证。**

验收方式：浏览器自动化操作真实控制台与用户中心，真实 API 创建场景、临时禁用测试用户模拟失效，并读取任务及通知资源进行断言；没有模拟通知投递。不是用户亲自签字的人工验收。

| 场景 | 1.0.1 实测结果 |
| --- | --- |
| 安装、启用 | 控制台上传、启用成功；插件版本 1.0.1，状态 STARTED，菜单和记录页面加载正常 |
| 指定用户立即 | 请求省略 sendMode，按 IMMEDIATE 处理；08:42:50.708 完成 SENT、1/1，仅 reviewuser 收到 |
| 全站定时取消 | 计划 08:43:33.237，08:42:54.935 取消；过期后仍 CANCELLED、0/2，无通知 |
| 指定角色定时公告 | guest 角色，计划 08:44:09.700，08:44:09.705 开始、08:44:09.774 完成；SENT、1/1，到期前 SCHEDULED |
| 真实部分失败与重试 | 全站名单含两人，创建后临时禁用 reviewuser；到期 PARTIAL_FAILED、1/2。恢复账号并调用重试，08:43:26.781 完成 SENT、2/2；两名用户各一条通知，无重复 |
| 收件箱与正文 | 普通用户登录个人中心，看到立即消息、定时公告和重试消息；资源正文逐字比对一致，HTML 标签按纯文本转义 |
| 历史记录 | 重新安装后，旧包创建的六条任务仍可查询 |

最终断言检查四条新任务的状态、准确接收人、正文、定时开始不早于计划时间，以及取消后到期无通知、重试无重复。临时禁用的测试用户已恢复正常。

### 可复查任务

| 场景 / requestId | SiteMessage 名称 |
| --- | --- |
| `v101-immediate` | `sm-86d434b65bd4b48a298d7ece348d0657df587aea544f38fa5781325ae6ab25a8` |
| `v101-cancel` | `sm-fda1e7a5e07733de94b4ada4a56cf3f5a6db0587fa7f65be0df27727dff3f9b4` |
| `v101-scheduled` | `sm-478f830cae01eb89202325aa7ecb8dee4d8cce0720346215796e33e765c86239` |
| `v101-retry` | `sm-56f80f4d5c86b3da1489ec42c3dc77f0352683fe60b63041a6c47e19c4817539` |

本地详细证据位于忽略提交的 `build/review/`：`v101-created.json`、`v101-failure.json`、`v101-results.json`、`v101-records.png`。本表保存任务标识，复核可直接查询仍在运行的隔离实例。

边界：未配置 SMTP，测试邮箱使用 `example.invalid`；实际邮件投递、退订及恢复未验证。控制台取消确认弹窗、等待详情及 guest 管理接口 403 在本轮稍早的迁移版 1.0.0 验证过，未冒充 1.0.1 的逐项界面复测结果。docs 三张产品截图为历史界面示例。原 8090、8091、8092 实例未修改。

## 再次提交前核对

送审应使用上述实际安装并验收的同一个 JAR。提交前复查：

```powershell
Get-Item "build/libs/halo-plugin-site-notifications-1.0.1.jar" | Select-Object Name, Length
Get-FileHash "build/libs/halo-plugin-site-notifications-1.0.1.jar" -Algorithm SHA256
```

如果重新构建，或改用 GitHub Actions / Release 下载的 JAR，应以实际送审文件重新核对摘要并完成安装验证，再更新本文，不能沿用其他构建的摘要和运行记录。Git 提交日期不是构建或测试时间；上述时间均为实际执行时间。
