<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { Toast, VButton, VModal } from '@halo-dev/components'
import { submitForm } from '@formkit/core'
import { messageApi } from '@/api'
import type { MessageRequest } from '@/api/generated'
import { getRequestIdentity, parseScheduledInstant, type RequestIdentity } from '@/utils/messages'
import { createRecipientRemoteOptions } from '@/utils/recipient-options'

const emit = defineEmits<{ close: []; sent: [] }>()
const form = reactive({
  title: '',
  content: '',
  type: 'MESSAGE' as MessageRequest['type'],
  target: 'ALL' as MessageRequest['target'],
  usernames: [] as string[],
  roles: [] as string[],
  sendMode: 'IMMEDIATE' as NonNullable<MessageRequest['sendMode']>,
  date: '',
  time: '',
})
const modal = ref<InstanceType<typeof VModal>>()
const sending = ref(false)
const previewing = ref(false)
const previewCount = ref<number>()
const previewError = ref('')
const submitError = ref('')
const timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone
const today = new Date().toLocaleDateString('en-CA')
let previewTimer: ReturnType<typeof setTimeout> | undefined
let previewVersion = 0
let requestIdentity: RequestIdentity | undefined

const userOptions = createRecipientRemoteOptions(async (keyword) => {
  const { data } = await messageApi.listRecipientUsers({ keyword })
  return (data.items ?? []).flatMap((user) =>
    user.username
      ? [
          {
            label: user.displayName || user.username,
            value: user.username,
            description: user.username,
          },
        ]
      : [],
  )
})
const roleOptions = createRecipientRemoteOptions(async (keyword) => {
  const { data } = await messageApi.listRecipientRoles({ keyword })
  return (data.items ?? []).flatMap((role) =>
    role.name
      ? [{ label: role.displayName || role.name, value: role.name, description: role.name }]
      : [],
  )
})

const recipients = computed(() => ({
  target: form.target,
  usernames: form.target === 'USERS' ? form.usernames : [],
  roles: form.target === 'ROLES' ? form.roles : [],
}))

watch(
  recipients,
  () => {
    const version = ++previewVersion
    clearTimeout(previewTimer)
    previewCount.value = undefined
    previewError.value = ''
    if (
      (form.target === 'USERS' && form.usernames.length === 0) ||
      (form.target === 'ROLES' && form.roles.length === 0)
    ) {
      previewing.value = false
      return
    }
    previewing.value = true
    previewTimer = setTimeout(async () => {
      try {
        const { data } = await messageApi.previewRecipients({ recipientRequest: recipients.value })
        if (version === previewVersion) previewCount.value = data.total
      } catch {
        if (version === previewVersion)
          previewError.value = '无法预览接收人数，请检查接收对象后重试'
      } finally {
        if (version === previewVersion) previewing.value = false
      }
    }, 300)
  },
  { immediate: true, deep: true },
)

onBeforeUnmount(() => {
  previewVersion++
  clearTimeout(previewTimer)
})

async function submit() {
  if (sending.value) return
  submitError.value = ''
  let scheduledAt: string | undefined
  try {
    if (form.sendMode === 'SCHEDULED') scheduledAt = parseScheduledInstant(form.date, form.time)
  } catch (error) {
    submitError.value = error instanceof Error ? error.message : '请检查发送时间'
    return
  }
  if (!previewCount.value) {
    submitError.value = '请先选择有效的接收对象，并等待接收人数预览完成'
    return
  }
  const values = {
    title: form.title.trim(),
    content: form.content,
    type: form.type,
    ...recipients.value,
    sendMode: form.sendMode,
    scheduledAt,
  }
  try {
    requestIdentity = getRequestIdentity(values, requestIdentity)
  } catch (error) {
    submitError.value = error instanceof Error ? error.message : '请检查发送时间'
    return
  }
  sending.value = true
  try {
    await messageApi.createSiteMessage({
      messageRequest: { requestId: requestIdentity.requestId, ...values },
    })
    Toast.success(form.sendMode === 'SCHEDULED' ? '定时通知已安排' : '通知已提交发送')
    emit('sent')
    modal.value?.close()
  } catch {
    submitError.value = '提交失败，请检查页面提示后重试。保持内容不变重试，可避免重复提交。'
  } finally {
    sending.value = false
  }
}
</script>

<template>
  <VModal ref="modal" title="发送站内通知" :width="720" @close="emit('close')">
    <div class="site-message-compose">
      <FormKit
        id="site-message-form"
        type="form"
        :actions="false"
        :disabled="sending"
        @submit="submit"
      >
        <div class="compose-row">
          <FormKit
            v-model="form.type"
            type="select"
            name="type"
            label="消息类型"
            :options="[
              { label: '普通消息', value: 'MESSAGE' },
              { label: '公告', value: 'ANNOUNCEMENT' },
            ]"
            validation="required"
          />
          <FormKit
            v-model="form.sendMode"
            type="select"
            name="sendMode"
            label="发送方式"
            :options="[
              { label: '立即发送', value: 'IMMEDIATE' },
              { label: '指定时间', value: 'SCHEDULED' },
            ]"
            validation="required"
          />
        </div>
        <div v-if="form.sendMode === 'SCHEDULED'" class="compose-schedule">
          <div class="compose-row">
            <FormKit
              v-model="form.date"
              type="date"
              name="date"
              label="发送日期"
              :min="today"
              validation="required"
            />
            <FormKit
              v-model="form.time"
              type="time"
              name="time"
              label="发送时间"
              validation="required"
            />
          </div>
          <p class="text-xs text-gray-500">时区：{{ timeZone }}。开始发送前可以取消。</p>
        </div>
        <FormKit
          v-model="form.title"
          type="text"
          name="title"
          label="标题"
          placeholder="输入通知标题"
          validation="required|length:1,200"
          :maxlength="200"
        />
        <FormKit
          v-model="form.content"
          type="textarea"
          name="content"
          label="正文"
          placeholder="输入消息内容，支持换行"
          validation="required|length:1,20000"
          :maxlength="20000"
          :rows="7"
        />
        <FormKit
          v-model="form.target"
          type="select"
          name="target"
          label="接收范围"
          :options="[
            { label: '全站用户', value: 'ALL' },
            { label: '指定用户', value: 'USERS' },
            { label: '指定角色', value: 'ROLES' },
          ]"
          validation="required"
        />
        <FormKit
          v-if="form.target === 'USERS'"
          v-model="form.usernames"
          type="select"
          name="usernames"
          label="接收用户"
          placeholder="搜索用户名或显示名，可多选"
          remote
          :remote-option="userOptions"
          :remote-optimize="false"
          searchable
          multiple
          clearable
          :sortable="false"
          validation="required"
        />
        <FormKit
          v-if="form.target === 'ROLES'"
          v-model="form.roles"
          type="select"
          name="roles"
          label="接收角色"
          placeholder="搜索角色名称，可多选"
          remote
          :remote-option="roleOptions"
          :remote-optimize="false"
          searchable
          multiple
          clearable
          :sortable="false"
          validation="required"
        />
        <div class="compose-preview text-sm">
          <p v-if="previewing" aria-live="polite" class="text-gray-500">正在统计接收人数…</p>
          <p v-else-if="previewError" role="alert" class="text-red-600">{{ previewError }}</p>
          <p v-else-if="previewCount !== undefined">
            预计接收人数：<strong>{{ previewCount }}</strong> 人
          </p>
          <p v-else class="text-gray-500">选择用户或角色后预览接收人数</p>
          <p class="mt-1 text-xs text-gray-500">
            名单以提交时为准，定时等待期间新增的用户不会追加。用户在个人中心的“消息”中接收。
          </p>
        </div>
        <p v-if="submitError" role="alert" class="mt-3 text-sm text-red-600">{{ submitError }}</p>
      </FormKit>
    </div>
    <template #footer>
      <div class="compose-footer">
        <span class="text-xs text-gray-500">{{
          form.sendMode === 'SCHEDULED' ? '到指定时间开始发送' : '提交后开始发送'
        }}</span>
        <div class="compose-actions">
          <VButton :disabled="sending" @click="modal?.close()">关闭</VButton>
          <VButton
            type="secondary"
            :loading="sending"
            :disabled="previewing || !previewCount"
            @click="submitForm('site-message-form')"
            >{{ form.sendMode === 'SCHEDULED' ? '安排发送' : '立即发送' }}</VButton
          >
        </div>
      </div>
    </template>
  </VModal>
</template>

<style scoped>
.site-message-compose :deep(.formkit-form) {
  display: grid;
  gap: 20px;
}

/* Halo 的默认表单按整行设置内边距和分隔线，这里由网格统一控制间距。 */
.site-message-compose :deep(.formkit-form > *) {
  border-width: 0;
}

.site-message-compose :deep(.formkit-outer) {
  min-width: 0;
  padding: 0;
}

.site-message-compose :deep(.formkit-wrapper) {
  gap: 8px;
}

.site-message-compose :deep(.formkit-inner) {
  max-width: none;
}

.compose-row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-items: start;
  gap: 20px;
}

.compose-schedule {
  display: grid;
  gap: 8px;
}

.compose-preview {
  display: grid;
  gap: 4px;
}

.compose-footer {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.compose-actions {
  display: flex;
  flex-shrink: 0;
  gap: 8px;
  margin-left: auto;
}

@media (max-width: 639px) {
  .compose-row {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
