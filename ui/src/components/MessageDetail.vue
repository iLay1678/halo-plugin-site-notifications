<script setup lang="ts">
import { VButton, VModal } from '@halo-dev/components'
import type { SiteMessage } from '@/api/generated'
import {
  canCancel,
  canRetry,
  formatTime,
  phaseLabel,
  targetLabels,
  typeLabels,
} from '@/utils/messages'

defineProps<{ message: SiteMessage; busy: boolean }>()
const emit = defineEmits<{
  close: []
  cancel: [message: SiteMessage]
  retry: [message: SiteMessage]
}>()
</script>

<template>
  <VModal title="通知详情" :width="760" @close="emit('close')">
    <div class="mb-5 flex flex-wrap items-center gap-2">
      <span class="rounded bg-gray-100 px-2 py-1 text-xs text-gray-600">{{
        phaseLabel(message)
      }}</span>
      <span class="text-xs text-gray-500">{{
        message.spec?.type ? typeLabels[message.spec.type] : ''
      }}</span>
    </div>
    <h2 class="mb-4 break-words text-lg font-medium text-gray-900">{{ message.spec?.title }}</h2>
    <dl class="grid grid-cols-1 gap-4 text-sm sm:grid-cols-2">
      <div>
        <dt class="text-xs text-gray-500">发送者</dt>
        <dd class="mt-1">{{ message.spec?.senderDisplayName }}（{{ message.spec?.sender }}）</dd>
      </div>
      <div>
        <dt class="text-xs text-gray-500">接收范围</dt>
        <dd class="mt-1">
          {{ message.spec?.target ? targetLabels[message.spec.target] : '' }} ·
          {{ message.spec?.recipients?.length ?? 0 }} 人
        </dd>
      </div>
      <div>
        <dt class="text-xs text-gray-500">发送方式</dt>
        <dd class="mt-1">{{ message.spec?.sendMode === 'SCHEDULED' ? '指定时间' : '立即发送' }}</dd>
      </div>
      <div>
        <dt class="text-xs text-gray-500">计划发送时间</dt>
        <dd class="mt-1">{{ formatTime(message.spec?.scheduledAt) }}</dd>
      </div>
      <div>
        <dt class="text-xs text-gray-500">提交时间</dt>
        <dd class="mt-1">{{ formatTime(message.metadata.creationTimestamp) }}</dd>
      </div>
      <div>
        <dt class="text-xs text-gray-500">开始 / 完成时间</dt>
        <dd class="mt-1">
          {{ formatTime(message.status?.startedAt) }} /
          {{ formatTime(message.status?.completedAt) }}
        </dd>
      </div>
      <div v-if="message.status?.phase === 'CANCELLED'">
        <dt class="text-xs text-gray-500">取消操作人</dt>
        <dd class="mt-1">{{ message.status.cancelledBy }}</dd>
      </div>
      <div v-if="message.status?.phase === 'CANCELLED'">
        <dt class="text-xs text-gray-500">取消时间</dt>
        <dd class="mt-1">{{ formatTime(message.status.cancelledAt) }}</dd>
      </div>
    </dl>
    <div class="my-5 border-t border-gray-100 pt-5">
      <h3 class="mb-3 text-sm font-medium">正文</h3>
      <p class="whitespace-pre-wrap break-words text-sm leading-7 text-gray-700">
        {{ message.spec?.content }}
      </p>
    </div>
    <div class="border-t border-gray-100 pt-4 text-sm">
      <h3 class="mb-2 font-medium">投递情况</h3>
      <p class="text-gray-600">
        已发送 {{ message.status?.delivered?.length ?? 0 }} /
        {{ message.spec?.recipients?.length ?? 0 }} 人
      </p>
      <div v-if="Object.keys(message.status?.failures ?? {}).length" class="mt-3 space-y-2">
        <p
          v-for="(reason, username) in message.status?.failures"
          :key="username"
          class="break-words text-red-600"
        >
          {{ username }}：{{ reason }}
        </p>
      </div>
      <details class="mt-4">
        <summary class="cursor-pointer text-gray-500">查看提交时的接收名单</summary>
        <p class="mt-2 break-words leading-6 text-gray-600">
          {{ message.spec?.recipients?.join('、') }}
        </p>
      </details>
    </div>
    <template #footer>
      <div class="flex flex-wrap items-center justify-between gap-3">
        <span class="text-xs text-gray-500"
          >时间按 {{ Intl.DateTimeFormat().resolvedOptions().timeZone }} 显示</span
        >
        <VButton
          v-if="canCancel(message)"
          type="danger"
          :loading="busy"
          @click="emit('cancel', message)"
          >取消发送</VButton
        >
        <VButton v-if="canRetry(message)" :loading="busy" @click="emit('retry', message)"
          >重试失败接收者</VButton
        >
      </div>
    </template>
  </VModal>
</template>
