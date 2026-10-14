# 更新日志

## [1.1.0] - 2026-10-14

### 新增

- 在用户中心提供个人 Webhook 地址配置、清除和替换功能。
- 接入 Halo `reactive-notifier` 扩展点，按用户通知偏好转发站内消息、登录提醒及其他通知类型。
- 在 Webhook 设置页展示 JSON 请求格式和字段说明。
- 增加 HTTPS、DNS/IP、私网地址、重定向、超时和响应体大小安全限制。
- 增加用户配置 API、Secret 存储、角色权限模板和生成的 TypeScript API 客户端。

### 修复

- 兼容 Halo 容器未提供 `WebClient.Builder` Bean 的运行环境。
- 修复配置并发更新时的 Secret 版本冲突处理。

### 验证

- 后端 49 项测试、前端 17 项测试全部通过。
- 已在 Halo 2.26.1 隔离容器完成升级、启停、重启、用户中心和真实 HTTPS Webhook 投递验收。
