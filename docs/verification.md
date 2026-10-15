# 验证记录

## 2026-10-15 1.3.0 构建与界面验收

- 完整执行 `build-local.ps1 -GradleArguments @("build")`，后端 63 项测试、前端 21 项测试、Vue 类型检查、ESLint、Prettier 及 JAR 打包全部通过。
- 生产和测试源码均已移除 Halo `JsonUtils`；改用现有 Jackson 依赖，编译不再出现该类的废弃待删除警告。配置头、历史配置迁移及失败重试由现有行为测试验证。
- 产物：`build/libs/halo-plugin-site-notifications-1.3.0.jar`，152291 字节，SHA-256：`3512567D4B6DB85864FA78B9C48D01E3BE370DD0F759EC6AE1016ECBF14A0E8A`。
- 最终产物已安装至已有 `halo-site-notifications-review` 容器（Halo 2.26.1，8093），日志确认插件 1.3.0 为 STARTED；8091 与 8092 容器保持停止。
- 通过真实普通用户中心检查最终页面：1440px 桌面视口中编辑区与 360px 模板参考栏并排，参考内容独立滚动前后操作区纵坐标均为 847.90625px；390px 移动视口中改为单列，说明顶部 1237.90625px 位于操作区底部 1201.90625px 之后。两种视口的 document.scrollWidth 均等于视口宽度，无横向溢出。
- 实际点击占位符复制后，未保存草稿仍为 draft-editing，浏览器弹出复制成功提示；通过 Ctrl+V 粘贴得到 `{{title}}`。点击“复制示例”后粘贴得到完整 JSON，两个页面 JSON 示例均通过 JSON.parse 校验；复制过程未保存配置或发送请求。单元测试另覆盖剪贴板不可用时提示手动复制且保留模板。
- 已替换 `docs/webhook-user-center.png`，截图来自最终安装包的 PC 页面，使用未保存的 example.com、X-Demo 和截图示例内容，无私人地址或凭据。README 继续只引用原先要求的五张 PC 截图。
- 本轮验证 JSON 读写、历史迁移、界面和包装；真实 HTTPS PATCH 接收与状态码证据来自本次开发过程中此前的容器联调，不将其描述为最终包的再次公网投递。
- 仍有既有 Gradle 弃用、JVM 动态代理和 Vue/Axios 宿主版本差异提示，未通过升级核心依赖来隐藏。


## 2026-10-14 1.2.0 构建与验收

本次完善用户中心 Webhook 模板、按用户隔离的 ConfigMap 地址与模板存储、历史配置迁移和用户删除清理。版本由 1.1.0 升至 **1.2.0**，最低 Halo 版本保持 >=2.26.0。

| 项目 | 值 |
| --- | --- |
| 文件 | `build/libs/halo-plugin-site-notifications-1.2.0.jar` |
| 文件大小 | **139988 字节** |
| SHA-256 | `2C1482CA708B0C864950E35E1ECA120DA15B73824CD4F993C968A7F6FD660FE7` |
| JAR 生成时间 | 2026-10-14 23:36:32 +08:00 |
| 构建完成 | 2026-10-14 23:38 +08:00，`BUILD SUCCESSFUL in 3m 24s` |
| 目标依赖 | JDK 21 / Halo 2.26.0，最低版本仍为 >=2.26.0 |

执行 `build-local.ps1 -JdkPath "D:/jdk/jdk-21.0.12.15" -GradleArguments @("check", "--console=plain")`，后端测试、前端类型检查、ESLint、Prettier 和单元测试全部通过。发布包将由同一版本的完整构建生成。

- 后端覆盖配置隔离与清除、乐观锁冲突重试、未知用户无配置、自身用户鉴权、地址脱敏、非法地址 400、JSON POST 映射、未配置跳过、错误与超时隔离、真实 HTTP 客户端禁止重定向和 64 KiB 响应限制、IPv4/IPv6 私网与特殊地址、实际 DNS 结果校验。
- 前端覆盖读取与保存后输入保持为空、避免脱敏地址覆盖真实配置、HTTPS 校验、保存错误提示和清除后的配置状态。
- JAR 已核对包含 Webhook 通知器、端点、地址解析器等 class，`extensions/webhook-notifier.yaml`、`extensions/role-template-webhook.yaml`、ESM 入口、Webhook 页面 chunk 与组件索引。
- 已依据 Halo 2.26.0 源码核对通知偏好调用路径、NotifierDescriptor、角色聚合 label 及 userspace 请求资源名；复用原生通知偏好选择接收类型，地址配置使用插件自己的受保护 API。

### 真实运行环境

复用原有隔离容器 `halo-site-notifications-review`，地址 `http://localhost:8093`，仅绑定 `127.0.0.1`；镜像 `registry.fit2cloud.com/halo/halo-pro:2.26`，实际 Halo 版本 2.26.1，数据目录 `workplace/review-20261012`。未修改 8091 开发容器或 8092 OIDC 容器。

本次完成 1.2.0 发布构建；此前 1.1.0 隔离容器中的端到端投递记录作为历史兼容性证据保留，当前构建已通过完整自动化检查。

验收使用超级管理员 `reviewadmin` 和 guest 角色的普通用户 `reviewuser`。通过 agent-browser 操作真实用户中心，API 读取状态并作断言。HTTPS 接收端为独立 webhook.site 测试地址，仅发送人工测试消息和测试账号的登录提醒，没有使用模拟 HTTP 交换函数代替本节投递证据。

### 运行验收结果

| 场景 | 实测结果 |
| --- | --- |
| 用户中心与普通用户权限 | “消息 → Webhook 通知”页面正常；真实浏览器保存成功，输入随后为空；桌面及 390 × 844 移动视口页面正常 |
| 个人配置隔离及脱敏 | reviewuser 已配置时 reviewadmin 仍未配置；查询只返回 `https://webhook.site`，不泄露完整路径 |
| 越权与非法地址 | 普通用户读取、保存他人配置均 403；保存 `https://127.0.0.1/hook` 为 400；匿名请求跳转登录（302） |
| 通知偏好 | 原生“我的 → 通知配置”出现 Webhook 通道；站内通知与新设备登录可独立选择 |
| 本插件消息 | 实际收到 JSON POST，`version=1`、recipient 为 reviewuser，标题及正文一致，Content-Type 为 application/json |
| Halo 系统消息 | 以独立 Android User-Agent 实际登录触发“新设备登录”；接收端收到该通知，subject.kind 为 Device |
| 关闭类型偏好 | 关闭站内通知的 Webhook 通道后，新测试任务仍 SENT，接收端请求总数不增加 |
| 清除地址 | 重新开启通道但清除地址后，新任务仍 SENT，接收端请求总数不增加 |
| 生成客户端及页面保存 | 从真实 OpenAPI 生成 UC 客户端；安装包含该客户端的最终 JAR，页面保存后新消息实际投递成功 |
| 插件启停 | DISABLED → STARTED；用户 ConfigMap 中地址和模板保留，重新启用后实际投递成功 |
| 容器重启 | 重启后插件 STARTED、地址保留；接收端恢复 200 后新消息投递成功 |
| 接收端失败隔离 | 接收端实际返回 503；站内消息仍 SENT 且已投递给 reviewuser，日志仅含用户名及 IllegalStateException |

本地证据位于忽略提交的 `build/review-webhook/`：各场景的 `*-created.json`、接收端 `requests-*.json`、最终七个任务及系统事件断言 `runtime-assertions.json`、权限断言 `access-results.json`、启停 `lifecycle.json`、重启 `restarted.json`、失败 `remote-failure-result.json`、打包 `package-check.json`、构建及生成日志、用户中心截图。接收端共记录六次预期请求，每条应转发的消息各一次；关闭偏好和清除地址的两条消息没有转发。产品截图为 [用户中心配置页](webhook-user-center.png)。

验收结束后通过真实用户中心清除了测试 Webhook 地址，并将站内通知及新设备登录的偏好恢复为邮件通道。review 容器保持运行，插件状态 STARTED；清理结果见 `cleanup.json`。

关键可复查任务：页面保存后的 `sm-e52d6c387a39f33d34a0b397ce9cddaac7621286db4c1cfd84a4ff9c4bd62515`，接收端 503 的 `sm-6eaf88f1a7fbf959054dfe387413430252477e9b4686992bbde870eb4ebea56f`，重启恢复的 `sm-f35047cb864bea13caaed80da35fe7ddadd7bcef5dfcc0bccd2073ff33a7f7ca`。

### 调试修复与验证边界

- 实际安装发现 Halo 插件容器不提供 `WebClient.Builder` Bean；通知器改为自己创建和管理客户端，已重新安装并验证启动及真实投递。
- Windows 下当前生成器解析 OpenAPI 3.1 文件会出现 URI 路径错误。隔离实例改用 `springdoc.api-docs.version: OPENAPI_3_0` 后，`generateApiClient -PhaloExternalUrl=http://localhost:8093` 成功。发布整理时仅将生成文件中的 BASE_PATH 恢复为原默认开发地址 8091；运行时客户端显式传入空 basePath，使用宿主同源请求。
- 系统其他类型的真实投递验证使用“新设备登录”。评论和第三方插件通知沿用同一扩展点，本轮未逐一制造这些事件；直接写入 Notification 资源的消息不经过通知中心，不会触发此通道。
- 本轮未配置 SMTP，未验证真实邮件投递。禁止重定向、响应大小及 DNS rebinding 防护由后端测试验证，未在公网接收端逐项制造攻击场景。

构建提示：Gradle 弃用信息、Halo `JsonUtils` 的弃用信息及现有 Vue/Axios 与宿主共享依赖版本差异；均未导致检查失败，未升级依赖。

## 2026-10-12 历史包安装验收

日期：2026-10-12，时区 Asia/Shanghai。下列内容仅对应当时实际安装验证的 **1.0.1** 制品，不能作为 2026-10-14 新构建的运行证明。

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

当前 1.2.0 发布应使用本次完整构建生成的同一个 JAR。提交前复查：

```powershell
Get-Item "build/libs/halo-plugin-site-notifications-1.2.0.jar" | Select-Object Name, Length
Get-FileHash "build/libs/halo-plugin-site-notifications-1.2.0.jar" -Algorithm SHA256
```

如果重新构建，或改用 GitHub Actions / Release 下载的 JAR，应以实际送审文件重新核对摘要并完成安装验证，再更新本文，不能沿用其他构建的摘要和运行记录。Git 提交日期不是构建或测试时间；上述时间均为实际执行时间。
