# 验证记录

日期：2026-10-10。目标依赖 Halo 2.26.0；实际联调镜像 `registry.fit2cloud.com/halo/halo-pro:2.26`，实例报告版本 2.26.1。Java 为 JDK 21，目录 `D:/jdk/jdk-21.0.12.15`。

## 自动化与源码审查

- 后端最终 **31 项测试全部通过**（7 个测试类，0 失败/错误）。独立审查发现默认 Jackson Instant 解码接受数字时间戳，随后添加严格 Jackson 3 请求字段解码与两项路由回归测试；完整构建确认修复。
- 测试覆盖默认立即、三个接收范围、去重和失效账户过滤、未来时间和偏移换算、两种取消/开始版本竞争顺序、重复提交、取消幂等、取消不能重试、到期/启停恢复、失败重试、已读保留、用户删除后不再生成、创建成功但返回异常、HTML 转义和普通用户/匿名拒绝。
- UI 最终 **14 项测试全部通过**，包含时间转换、非法日期、过去时间、同内容到期重试、候选精确回填和失败保留原值。类型、lint、Prettier 检查全部通过。
- `generateApiClient -x build` 从独立实例真实 `/v3/api-docs/siteNotifications` 生成成功；未手写生成契约。
- 通知中心接入：投递改为创建自定义 `ReasonType` / `Reason` 并调用 `NotificationCenter`，启动时幂等注册 `NotificationTemplate`，站内与邮件正文均直接使用填写的正文。
- 邮件提醒依赖 Halo 的标准邮件通知器：必须启用邮件通知器、配置可用 SMTP、验证用户邮箱并在用户通知偏好中启用该通知类型。插件不会绕过用户偏好强制发信；关闭邮件后仍保留站内消息。
- 详细验证、构建和联调边界记录在本文档中。

## 独立 Halo 集成证据

联调使用独立容器 `halo-site-notifications-dev`、地址 `http://localhost:8091`，数据保存在工程 `workplace/halo`。所有测试账号与消息只存在此实例，原 `8090` Halo 未改动。

| 场景 | 结果 |
| --- | --- |
| 插件安装和启用 | Scheme 注册、Clock 注入、Reconciler 启动及管理 API 正常 |
| 指定用户立即发送 | `PENDING → SENT`，投递 1/1；原生收件箱收到 |
| 换行与 HTML | 原生详情保留换行，`<script>` 显示为文字，阅读后已读 |
| 指定角色定时公告 | 等待期无投递；计划 `03:43:37.824878Z`，完成 `03:43:38.021564406Z`，投递 1/1 |
| 全站范围定时取消 | 取消后保留操作人和时间；到期后仍 `CANCELLED`，投递 0/1 |
| 三种人数预览 | 创建测试普通用户后，ALL=2、USERS=1、guest ROLES=1 |
| 普通用户权限 | 读取任务、取消接口及原生 SiteMessage 资源列表均 403 |
| 停用期间到期 | 测试用户通知数为 0；启用后收到“启停恢复联调”且为未读 |
| 已取消任务经历启停 | 保留取消状态，无通知 |
| 含 UI 插件升级 | ESM 主入口及动态 chunk 200，控制台菜单和发送记录正常加载 |
| UI 用户搜索、多选、立即发送 | 先选择 notifytest 再搜索 pluginadmin，搜索期间预览仍 1；添加后预览 2，最终名单恰为这两人，投递 2/2 |
| UI 角色搜索、多选 | 选择 guest，再搜索 super-role，仅保留这两个标签，预览 2，提交成功 |
| UI 指定时间与取消确认 | 日期/时间为空时阻止提交；填写未来时间后创建 SCHEDULED，通过 Halo 确认弹窗取消，最终 CANCELLED、投递 0/2 |
| 390×844 窄屏 | 表单双列变单列，弹窗正文可滚动、提交/关闭按钮可操作；发送记录、状态和详情无横向溢出 |
| 数字时间输入 | 最终运行包收到数字 scheduledAt 返回 400 |

独立 8091 实例未配置可投递的 SMTP 账号，因此本轮只验证了通知中心资源注册和原生收件箱链路，没有把测试邮件发送到真实邮箱。邮件发送由 Halo `EmailNotifier` 异步处理；配置 SMTP 后可用同一条发送流程验证，并在 Halo 日志中查看连接、认证或退信错误。

实例初始化时预置主题下载失败，因此 Halo 全局主题检查有 404；这是测试实例缺少激活主题的宿主响应，不影响插件 API 或原生收件箱。未修改主题。

## 构建与运行边界

Windows 本地 Node 模式下 Gradle 的 pnpm.cmd 未在 PATH，通过工程内已下载的 pnpm.cjs + node 启动修复，未全局安装。宿主共享 Vue/Axios 版本与本机最新依赖有补丁版本差异，实际运行使用 Halo ESM 提供的共享模块，已在 2.26.1 联调。

机器曾出现可用内存不足导致 Node/Rolldown 分配失败。构建改为串行类型检查与打包，Gradle 限制一个 worker；关闭本任务浏览器并停止空闲 Gradle daemon 后继续，未修改系统设置。

完整命令 `./build-local.ps1 -GradleArguments @("build", "--console=plain")` 于 2026-10-10 09:15 完成，`BUILD SUCCESSFUL in 36s`，后端 31 项、前端 14 项全部通过。JAR 资源包含 `plugin.yaml`、严格时间解码器、`ui/ui-plugin.json`、ESM 主入口和动态 MessagesView chunk。资源复制任务使用 Sync，后续重建同步清除历史带 hash 的 UI 资源，避免旧包体累积。

最终交付文件 `build/libs/halo-plugin-site-notifications-1.0.0.jar` 大小 **100563 字节**，SHA256 为 `97509FE1052EF7E2E0613D7F417044DA9B47608D5AEF12F41933ED704EC0BE09`。包内包含新的 `NotificationResources`、通知中心投递逻辑、`plugin.yaml`、`ui/ui-plugin.json`、ESM 主入口和动态 MessagesView chunk。

独立 `8091` 实例已验证通知中心资源注册和控制器启动，已有 7 条任务记录可读取；本次源码变更后的最终 JAR 尚未覆盖安装到该容器，未对 `8091` 发送新的测试消息。

自动化验证覆盖客户端同内容到期重试和响应丢失后的身份复用；浏览器未模拟丢弃成功响应这一网络故障。未通过浏览器创建超 100 个真实用户，首 100 项之外的候选回填由专门测试覆盖。浏览器截图 `compose-mobile.png`、`records-mobile.png` 保存在本目录。

未获取原 `http://localhost:8090` 管理员会话，因此没有在原实例安装或向其用户发消息。交付 JAR 可从该实例控制台上传启用。没有运行 Git 初始化、分支、提交或推送。

## 插件命名与描述更新

按用户提供的 Passkey 插件资料完善作者、描述、主页、仓库、问题反馈与许可证链接；用户明确无需保留旧标识，当前插件标识及 Gradle 项目名统一为 `halo-plugin-site-notifications`，仓库地址配置为 `https://github.com/ilay1678/halo-plugin-site-notifications`，作者为 `ilay`。未创建或发布 GitHub 仓库，未复制另一个插件的应用市场 app-id。README 补齐功能表、截图、安装与切换说明、发送/定时/取消/重试操作、开发和 API 示例。

前端菜单权限同步为 `plugin:halo-plugin-site-notifications:manage`。UI 构建任务将 `plugin.yaml` 纳入输入，清单变化会触发重新打包；业务数据模型与 API 命名空间沿用，不涉及数据搬迁。

历史构建和旧标识制品记录保留在此文档中；本次最终交付以 2026-10-10 09:15 的构建和 SHA256 为准。原 `8090` 实例未改动。README 中引用的 Logo、许可证、三张截图与验证记录文件均存在。

