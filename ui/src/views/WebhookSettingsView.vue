<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Toast, VButton, VCard, VLoading, VPageHeader } from '@halo-dev/components'
import { stores } from '@halo-dev/ui-shared'
import { webhookApi } from '../api'
import type { WebhookRequest, WebhookResponse } from '../api/generated'

const userStore = stores.currentUser()
const loading = ref(true)
const saving = ref(false)
const testing = ref(false)
const busy = computed(() => saving.value || testing.value)
const configured = ref(false)
const url = ref('')
const template = ref('')
const method = ref('POST')
const headers = ref<{ name: string; value: string }[]>([])
const error = ref('')
const username = computed(() => userStore.currentUser?.user.metadata.name)
const sendsBody = computed(() => !['GET', 'DELETE'].includes(method.value))
const variables: [string, string][] = [
  ['version', '载荷版本'],
  ['title', '通知标题'],
  ['rawBody', '纯文本正文'],
  ['htmlBody', 'HTML 正文'],
  ['recipient', '接收用户名'],
  ['timestamp', '通知时间（UTC）'],
  ['subject', '完整主题对象'],
  ['subject.apiVersion', '主题 API 版本'],
  ['subject.kind', '主题类型'],
  ['subject.name', '主题名称'],
  ['subject.title', '主题标题'],
  ['subject.url', '主题链接'],
  ['attributes', '完整扩展属性'],
  ['attributes.key', '指定扩展属性'],
]
const templateExample = JSON.stringify(
  { title: '{{title}}', content: '{{rawBody}}', url: '{{subject.url}}' },
  null,
  2,
)

function placeholder(name: string) {
  return '{{' + name + '}}'
}

async function copyTemplateText(value: string) {
  try {
    await navigator.clipboard.writeText(value)
    Toast.success('已复制，可粘贴到正文模板中')
  } catch {
    Toast.error('复制失败，请选中文字后手动复制')
  }
}

function applyConfig(data: WebhookResponse) {
  configured.value = Boolean(data.configured)
  url.value = data.url ?? ''
  method.value = data.method ?? 'POST'
  headers.value = Object.entries(data.headers ?? {}).map(([name, value]) => ({ name, value }))
  template.value = data.template ?? ''
}

function request(): WebhookRequest | undefined {
  error.value = ''
  if (!/^https:\/\/[^\s]+$/i.test(url.value.trim())) {
    error.value = '请输入有效的 HTTPS Webhook 地址。'
    return
  }
  if (template.value.trim()) {
    try {
      const value = JSON.parse(template.value)
      if (value === null || typeof value !== 'object') throw new Error('invalid')
    } catch {
      error.value = '请求正文模板必须是合法的 JSON 对象或数组。'
      return
    }
  }
  const values: Record<string, string> = {}
  const names = new Set<string>()
  for (const header of headers.value) {
    const name = header.name.trim()
    if (!name && !header.value) continue
    if (!name || names.has(name.toLowerCase())) {
      error.value = '请填写请求头名称，并避免重复（不区分大小写）。'
      return
    }
    names.add(name.toLowerCase())
    values[name] = header.value
  }
  return {
    url: url.value.trim(),
    method: method.value,
    template: template.value.trim(),
    headers: values,
  }
}

async function load() {
  if (!username.value) return
  loading.value = true
  error.value = ''
  try {
    const { data } = await webhookApi.getWebhookConfig({ username: username.value })
    applyConfig(data)
  } catch {
    error.value = '无法读取 Webhook 配置，请刷新后重试。'
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!username.value || busy.value) return
  const body = request()
  if (!body) return
  saving.value = true
  try {
    const { data } = await webhookApi.saveWebhookConfig({
      username: username.value,
      webhookRequest: body,
    })
    applyConfig(data)
    Toast.success('Webhook 配置已保存')
  } catch {
    error.value = '保存失败。请检查公网 HTTPS 地址、JSON 模板和占位符名称。'
  } finally {
    saving.value = false
  }
}

function addHeader() {
  headers.value.push({ name: '', value: '' })
}

function removeHeader(index: number) {
  headers.value.splice(index, 1)
}

async function testWebhook() {
  if (!username.value || busy.value) return
  const body = request()
  if (!body) {
    Toast.error(error.value)
    return
  }
  testing.value = true
  try {
    const { data } = await webhookApi.testWebhookConfig({
      username: username.value,
      webhookRequest: body,
    })
    if (data.success) {
      Toast.success(`测试成功：${data.message}`)
    } else {
      Toast.error(`测试失败：${data.message}`)
    }
  } catch {
    error.value = '测试请求发送失败，请检查地址、请求方式、请求头和模板。'
    Toast.error(error.value)
  } finally {
    testing.value = false
  }
}

async function clear() {
  if (!username.value || busy.value) return
  error.value = ''
  saving.value = true
  try {
    await webhookApi.clearWebhookConfig({ username: username.value })
    applyConfig({ configured: false })
    Toast.success('Webhook 配置已清除')
  } catch {
    error.value = '清除失败，请重试。'
  } finally {
    saving.value = false
  }
}

watch(username, load, { immediate: true })
</script>

<template>
  <VPageHeader title="Webhook 通知" />
  <div class="webhook-settings">
    <VLoading v-if="loading" />
    <div v-else class="webhook-layout">
      <VCard class="webhook-editor">
        <template #header>
          <div class="p-4">
            <div class="flex items-center justify-between gap-3">
              <div>
                <div class="flex items-center gap-2">
                  <h2 class="text-base font-medium">自定义 Webhook</h2>
                  <span
                    class="rounded px-2 py-0.5 text-xs"
                    :class="
                      configured ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-600'
                    "
                    >{{ configured ? '已配置' : '未配置' }}</span
                  >
                </div>
                <p class="mt-1 text-sm text-gray-500">自定义 HTTP 请求</p>
              </div>
            </div>
          </div>
        </template>
        <div class="webhook-form">
          <div class="webhook-destination">
            <div>
              <FormKit
                v-model="url"
                type="url"
                label="Webhook 地址"
                placeholder="https://example.com/webhook"
                :disabled="busy"
              />
              <p class="text-xs text-gray-500">
                仅允许 HTTPS
                地址。插件会在服务端检查目标地址，拒绝本机、内网和云元数据地址。请求正文为 JSON。
              </p>
            </div>
            <div>
              <FormKit
                v-model="method"
                type="select"
                label="HTTP 方法"
                :options="
                  ['POST', 'PUT', 'PATCH', 'GET', 'DELETE'].map((value) => ({
                    label: value,
                    value,
                  }))
                "
                :disabled="busy"
              />
              <p v-if="!sendsBody" class="text-xs text-gray-500">
                GET 和 DELETE 不发送请求体，已填写的模板会保留。
              </p>
            </div>
          </div>
          <div class="webhook-template">
            <FormKit
              v-model="template"
              type="textarea"
              label="请求正文模板（可选）"
              :disabled="busy || !sendsBody"
              placeholder='{"title":"{{title}}","content":"{{rawBody}}"}'
              rows="10"
            />
          </div>
          <div>
            <div class="webhook-section-heading">
              <h3 class="text-sm font-medium text-gray-700">自定义请求头</h3>
              <VButton size="sm" type="secondary" :disabled="busy" @click="addHeader"
                >添加请求头</VButton
              >
            </div>
            <div v-for="(header, index) in headers" :key="index" class="webhook-header-row">
              <FormKit
                v-model="header.name"
                type="text"
                label="名称"
                placeholder="例如 Authorization"
                :disabled="busy"
              />
              <FormKit
                v-model="header.value"
                type="text"
                label="值"
                placeholder="请求头值"
                :disabled="busy"
              />
              <VButton size="sm" :disabled="busy" @click="removeHeader(index)">删除</VButton>
            </div>
            <p class="mt-2 text-xs text-gray-500">
              默认 Content-Type 为 application/json，可添加或覆盖请求头；Host、Content-Length
              等传输头由客户端管理。
            </p>
          </div>

          <p class="text-xs text-gray-500">
            发送测试使用当前编辑内容，向目标地址发送真实请求，不会保存配置。保存后请在个人中心“通知配置”中选择要接收的消息类型。
          </p>
          <p v-if="error" role="alert" class="text-sm text-red-600">{{ error }}</p>
        </div>
        <template #footer>
          <div class="webhook-actions">
            <VButton type="secondary" :disabled="busy" :loading="saving" @click="save"
              >保存配置</VButton
            >
            <VButton :disabled="busy" :loading="testing" @click="testWebhook">发送测试</VButton>
            <VButton :disabled="busy || !configured" @click="clear">清除配置</VButton>
          </div>
        </template>
      </VCard>
      <aside class="webhook-reference" aria-label="请求正文模板说明">
        <VCard>
          <template #header>
            <div class="p-4">
              <h2 class="text-sm font-medium">模板参考</h2>
              <p class="mt-1 text-xs text-gray-500">点击占位符复制，可边查看边编辑正文。</p>
            </div>
          </template>
          <div class="webhook-help space-y-4">
            <p class="text-xs text-gray-600">
              模板留空使用下方默认 JSON。占位符写在 JSON
              字符串值中，花括号内不要添加空格。完整占位符作为值时保留 JSON 类型；缺失字段为
              null，拼接文本中为空字符串。
            </p>
            <dl class="grid gap-2 text-xs">
              <div
                v-for="[name, description] in variables"
                :key="name"
                class="flex flex-wrap gap-2"
              >
                <dt>
                  <button
                    type="button"
                    class="webhook-placeholder rounded bg-gray-100 px-1 py-0.5"
                    :aria-label="'复制 ' + placeholder(name)"
                    @click="copyTemplateText(placeholder(name))"
                  >
                    <code>{{ placeholder(name) }}</code>
                  </button>
                </dt>
                <dd class="text-gray-600">{{ description }}</dd>
              </div>
            </dl>
            <div>
              <div class="webhook-section-heading">
                <h3 class="text-sm font-medium">模板示例</h3>
                <VButton size="sm" @click="copyTemplateText(templateExample)">复制示例</VButton>
              </div>
              <pre
                class="overflow-x-auto rounded border border-gray-200 bg-white p-3 text-xs"
              ><code>{{ templateExample }}</code></pre>
            </div>
            <h3 class="text-sm font-medium text-gray-700">通知 JSON 格式</h3>
            <p class="mt-1 text-xs text-gray-500">
              正式通知与测试请求均使用所选方法和请求头。不同通知类型的 subject 和 attributes
              内容可能不同。
            </p>
            <pre
              class="mt-2 overflow-x-auto whitespace-pre-wrap break-words text-xs text-gray-600"
            ><code>{
  "version": "1",
  "title": "系统维护通知",
  "rawBody": "今晚 22:00–23:00 进行系统维护。",
  "htmlBody": "&lt;div style=\"white-space: pre-wrap\"&gt;今晚 22:00–23:00 进行系统维护。&lt;/div&gt;",
  "recipient": "alice",
  "timestamp": "2026-10-14T10:00:00Z",
  "subject": {
    "apiVersion": "v1alpha1",
    "kind": "SiteMessageRecipient",
    "name": "message-001-alice",
    "title": "系统维护通知",
    "url": "/uc/notifications"
  },
  "attributes": {
    "title": "系统维护通知",
    "content": "今晚 22:00–23:00 进行系统维护。"
  }
}</code></pre>
          </div>
        </VCard>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.webhook-settings {
  width: 100%;
  min-width: 0;
  max-width: 1360px;
  box-sizing: border-box;
  margin: 0 auto;
  padding: 24px;
}
.webhook-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 360px;
  align-items: start;
  gap: 24px;
}
.webhook-editor,
.webhook-reference {
  min-width: 0;
}
.webhook-reference {
  position: sticky;
  top: 24px;
}
.webhook-placeholder {
  cursor: copy;
}
.webhook-placeholder:hover,
.webhook-placeholder:focus-visible {
  background: #e5e7eb;
}
.webhook-form {
  display: flex;
  flex-direction: column;
  gap: 24px;
  padding: 24px;
}
.webhook-destination {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 160px;
  gap: 20px;
}
.webhook-section-heading,
.webhook-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.webhook-section-heading {
  justify-content: space-between;
  margin-bottom: 12px;
}
.webhook-header-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 2fr) auto;
  align-items: end;
  gap: 12px;
  margin-bottom: 12px;
}
.webhook-form :deep(.formkit-outer) {
  padding: 0;
}
.webhook-form :deep(.formkit-wrapper) {
  gap: 8px;
}
.webhook-form :deep(.formkit-inner) {
  max-width: none;
}
.webhook-header-row :deep(.formkit-outer) {
  min-width: 0;
}
.webhook-form :deep(textarea) {
  font-family: ui-monospace, monospace;
  line-height: 1.6;
}
.webhook-help {
  min-width: 0;
  max-width: 100%;
  max-height: calc(100vh - 220px);
  overflow-y: auto;
  padding: 16px;
}
.webhook-help > div {
  min-width: 0;
  max-width: 100%;
}
.webhook-help pre {
  max-width: 100%;
  box-sizing: border-box;
  overflow-x: auto;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.webhook-actions {
  flex-wrap: wrap;
  padding: 16px 24px;
  border-top: 1px solid #e5e7eb;
}
@media (max-width: 1100px) {
  .webhook-layout {
    grid-template-columns: minmax(0, 1fr);
  }
  .webhook-reference {
    position: static;
  }
  .webhook-help {
    max-height: 480px;
  }
}
@media (max-width: 640px) {
  .webhook-settings {
    padding: 12px;
  }
  .webhook-form {
    padding: 16px;
  }
  .webhook-destination,
  .webhook-header-row {
    grid-template-columns: minmax(0, 1fr);
  }
  .webhook-header-row {
    padding-bottom: 16px;
    border-bottom: 1px solid #e5e7eb;
  }
  .webhook-header-row > :last-child {
    justify-self: end;
  }
}
</style>
