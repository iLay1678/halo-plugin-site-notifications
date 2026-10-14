<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Toast, VButton, VCard, VLoading, VPageHeader } from '@halo-dev/components'
import { stores } from '@halo-dev/ui-shared'
import { webhookApi } from '../api'

const userStore = stores.currentUser()
const loading = ref(true)
const saving = ref(false)
const configured = ref(false)
const url = ref('')
const template = ref('')
const error = ref('')
const username = computed(() => userStore.currentUser?.user.metadata.name)

async function load() {
  if (!username.value) return
  loading.value = true
  error.value = ''
  try {
    const { data } = await webhookApi.getWebhookConfig({ username: username.value })
    configured.value = Boolean(data.configured)
    url.value = ''
    template.value = data.template ?? ''
  } catch {
    error.value = '无法读取 Webhook 配置，请刷新后重试。'
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!username.value || saving.value) return
  error.value = ''
  if ((!configured.value || url.value.trim()) && !/^https:\/\/[^\s]+$/i.test(url.value.trim())) {
    error.value = '请输入有效的 HTTPS Webhook 地址。'
    return
  }
  if (template.value.trim()) {
    try {
      const parsed = JSON.parse(template.value)
      if (parsed === null || typeof parsed !== 'object') throw new Error('invalid')
    } catch {
      error.value = '请求正文模板必须是合法的 JSON 对象或数组。'
      return
    }
  }
  saving.value = true
  try {
    const { data } = await webhookApi.saveWebhookConfig({
      username: username.value,
      webhookRequest: { url: url.value.trim() || undefined, template: template.value.trim() },
    })
    configured.value = Boolean(data.configured)
    url.value = ''
    template.value = data.template ?? ''
    Toast.success('Webhook 配置已保存')
  } catch {
    error.value = '保存失败。请检查公网 HTTPS 地址、JSON 模板和占位符名称。'
  } finally {
    saving.value = false
  }
}

async function clear() {
  if (!username.value || saving.value) return
  error.value = ''
  saving.value = true
  try {
    await webhookApi.clearWebhookConfig({ username: username.value })
    configured.value = false
    url.value = ''
    template.value = ''
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
  <div class="mx-auto max-w-3xl p-4">
    <VLoading v-if="loading" />
    <VCard v-else>
      <template #header>
        <div class="p-4">
          <h2 class="text-base font-medium">接收通知到 Webhook</h2>
          <p class="mt-1 text-sm text-gray-500">
            配置后，在个人中心“通知配置”中勾选“Webhook 通知”对应的消息类型。
          </p>
        </div>
      </template>
      <div class="space-y-4 p-4">
        <FormKit
          v-model="url"
          type="url"
          label="Webhook 地址"
          placeholder="https://example.com/webhook"
          :disabled="saving"
        />
        <p v-if="configured" class="text-sm text-green-600">
          当前已配置 Webhook。地址留空可保留原地址，只更新正文模板。
        </p>
        <p class="text-xs text-gray-500">
          仅允许 HTTPS 地址。插件会在服务端检查目标地址，拒绝本机、内网和云元数据地址。请求正文为
          JSON。
        </p>
        <FormKit
          v-model="template"
          type="textarea"
          label="请求正文模板（可选）"
          :disabled="saving"
          placeholder='{"title":"{{title}}","content":"{{rawBody}}"}'
          rows="8"
        />
        <p v-pre class="text-xs text-gray-500">
          留空使用下方默认 JSON。占位符写在 JSON 字符串值中，可用 {{ title }}、{{ rawBody }}、{{
            htmlBody
          }}、{{ recipient }}、{{ timestamp }}、{{ subject }}、{{ subject.name }}、{{
            subject.url
          }}、{{ attributes }} 和 {{ attributes.key }}。 完整占位符作为值时保留 JSON
          类型；缺失字段为 null，拼接文本中为空字符串。
        </p>
        <div class="rounded-md bg-gray-50 p-3">
          <h3 class="text-sm font-medium text-gray-700">通知 JSON 格式</h3>
          <p class="mt-1 text-xs text-gray-500">
            每条通知发送一次 JSON POST。不同通知类型的 subject 和 attributes 内容可能不同。
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
        <p v-if="error" role="alert" class="text-sm text-red-600">{{ error }}</p>
      </div>
      <template #footer>
        <div class="flex justify-end gap-2 p-4">
          <VButton :disabled="saving || !configured" @click="clear">清除配置</VButton>
          <VButton type="secondary" :loading="saving" @click="save">保存</VButton>
        </div>
      </template>
    </VCard>
  </div>
</template>
