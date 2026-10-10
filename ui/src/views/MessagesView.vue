<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  Dialog,
  IconNotificationBadgeLine,
  Toast,
  VButton,
  VCard,
  VEmpty,
  VEntity,
  VEntityContainer,
  VEntityField,
  VLoading,
  VPageHeader,
  VPagination,
  VTag,
} from '@halo-dev/components'
import { messageApi } from '@/api'
import type { SiteMessage, SiteMessagePage } from '@/api/generated'
import ComposeMessage from '@/components/ComposeMessage.vue'
import MessageDetail from '@/components/MessageDetail.vue'
import {
  canCancel,
  canRetry,
  formatTime,
  isActive,
  phaseLabel,
  targetLabels,
} from '@/utils/messages'

const page = ref(1)
const size = ref(20)
const result = ref<SiteMessagePage>()
const loading = ref(true)
const loadError = ref(false)
const composing = ref(false)
const selected = ref<SiteMessage>()
const busy = ref(false)
const items = computed(() => result.value?.items ?? [])
let pollTimer: ReturnType<typeof setTimeout> | undefined
let stopped = false
let fetching = false
let refreshPending = false

async function refresh() {
  if (stopped) return
  if (fetching) {
    refreshPending = true
    return
  }
  fetching = true
  clearTimeout(pollTimer)
  const requestedPage = page.value
  const requestedSize = size.value
  try {
    const { data } = await messageApi.listSiteMessages({ page: requestedPage, size: requestedSize })
    if (stopped) return
    if (requestedPage !== page.value || requestedSize !== size.value) {
      refreshPending = true
      return
    }
    result.value = data
    loadError.value = false
    if (selected.value) {
      const selectedName = selected.value.metadata.name
      const current = data.items?.find(
        (item) => item.metadata.name === selected.value?.metadata.name,
      )
      if (current) selected.value = current
      else {
        const detail = await messageApi.getSiteMessage({ name: selectedName })
        if (!stopped && selected.value?.metadata.name === selectedName) selected.value = detail.data
      }
    }
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
    fetching = false
    if (refreshPending && !stopped) {
      refreshPending = false
      void refresh()
      return
    }
    if (
      !stopped &&
      !loadError.value &&
      (items.value.some(isActive) || (selected.value && isActive(selected.value)))
    ) {
      pollTimer = setTimeout(refresh, 5000)
    }
  }
}

function confirmCancel(message: SiteMessage) {
  Dialog.warning({
    title: '取消定时发送',
    description: `确认取消“${message.spec?.title}”的发送？取消后会保留记录，用户不会收到这条消息。`,
    confirmText: '取消发送',
    cancelText: '继续等待',
    confirmType: 'danger',
    onConfirm: async () => {
      busy.value = true
      try {
        const { data } = await messageApi.cancelSiteMessage({ name: message.metadata.name })
        if (selected.value?.metadata.name === data.metadata.name) selected.value = data
        Toast.success('已取消发送')
      } catch {
        Toast.error('未能取消，请检查任务是否已经开始发送')
      } finally {
        busy.value = false
        await refresh()
      }
    },
  })
}

function confirmRetry(message: SiteMessage) {
  Dialog.warning({
    title: '重试失败接收者',
    description: '仅重新投递给原名单中未成功的接收者，已成功的用户不会重复收到消息。',
    confirmText: '重试发送',
    cancelText: '关闭',
    onConfirm: async () => {
      busy.value = true
      try {
        await messageApi.retrySiteMessage({ name: message.metadata.name })
        Toast.success('已提交重试')
      } catch {
        Toast.error('未能重试，请刷新任务状态后再试')
      } finally {
        busy.value = false
        await refresh()
      }
    },
  })
}

function onSent() {
  page.value = 1
  void refresh()
}

onMounted(refresh)
onBeforeUnmount(() => {
  stopped = true
  clearTimeout(pollTimer)
})
</script>

<template>
  <VPageHeader title="站内通知">
    <template #icon><IconNotificationBadgeLine /></template>
    <template #actions>
      <VButton type="secondary" @click="composing = true">发送通知</VButton>
    </template>
  </VPageHeader>
  <div class="m-0 space-y-4 md:m-4">
    <VCard :body-class="['!p-0']">
      <template #header>
        <div class="flex flex-wrap items-center justify-between gap-3 p-4">
          <div>
            <h2 class="text-sm font-medium text-gray-900">发送记录</h2>
            <p class="mt-1 text-xs text-gray-500">查看投递进度，取消尚未开始发送的定时通知。</p>
          </div>
          <VButton size="sm" :loading="loading" @click="refresh">刷新</VButton>
        </div>
      </template>
      <VLoading v-if="loading" />
      <div v-else-if="loadError" role="alert" class="p-8 text-center text-sm text-gray-600">
        <p class="mb-4">无法加载发送记录，请检查登录状态和管理员权限后重试。</p>
        <VButton @click="refresh">重新加载</VButton>
      </div>
      <VEmpty
        v-else-if="!items.length"
        title="还没有发送记录"
        message="向用户发送公告或普通消息，可以立即发送，也可以安排时间。"
      >
        <template #actions
          ><VButton type="secondary" @click="composing = true">发送第一条通知</VButton></template
        >
      </VEmpty>
      <VEntityContainer v-else>
        <VEntity v-for="message in items" :key="message.metadata.name">
          <template #start>
            <VEntityField
              :description="`${message.spec?.target ? targetLabels[message.spec.target] : ''} · ${message.spec?.recipients?.length ?? 0} 人`"
            >
              <template #title>
                <button
                  class="max-w-xs truncate text-left text-sm font-medium text-gray-900 hover:text-blue-600 focus-visible:outline"
                  @click="selected = message"
                >
                  {{ message.spec?.type === 'ANNOUNCEMENT' ? '【公告】' : ''
                  }}{{ message.spec?.title }}
                </button>
              </template>
            </VEntityField>
            <VEntityField
              class="hidden lg:inline-flex"
              :title="
                message.spec?.sendMode === 'SCHEDULED'
                  ? formatTime(message.spec?.scheduledAt)
                  : '立即发送'
              "
              :description="
                message.spec?.sendMode === 'SCHEDULED'
                  ? '计划发送时间'
                  : formatTime(message.metadata.creationTimestamp)
              "
            />
          </template>
          <template #end>
            <VEntityField
              :description="`已发送 ${message.status?.delivered?.length ?? 0} / ${message.spec?.recipients?.length ?? 0}`"
            >
              <template #title
                ><VTag :theme="canRetry(message) ? 'danger' : 'default'">{{
                  phaseLabel(message)
                }}</VTag></template
              >
            </VEntityField>
            <VButton size="sm" @click="selected = message">详情</VButton>
            <VButton
              v-if="canCancel(message)"
              size="sm"
              :disabled="busy"
              @click="confirmCancel(message)"
              >取消发送</VButton
            >
            <VButton
              v-if="canRetry(message)"
              size="sm"
              :disabled="busy"
              @click="confirmRetry(message)"
              >重试</VButton
            >
          </template>
        </VEntity>
      </VEntityContainer>
      <template #footer>
        <VPagination
          v-model:page="page"
          v-model:size="size"
          :total="result?.total ?? 0"
          :size-options="[20, 50]"
          @change="refresh"
        />
      </template>
    </VCard>
    <p class="px-4 text-xs text-gray-500 md:px-0">
      用户在个人中心的“消息”中接收通知。接收名单在提交时固定；已开始投递的通知无法取消。
    </p>
  </div>
  <ComposeMessage v-if="composing" @close="composing = false" @sent="onSent" />
  <MessageDetail
    v-if="selected"
    :message="selected"
    :busy="busy"
    @close="selected = undefined"
    @cancel="confirmCancel"
    @retry="confirmRetry"
  />
</template>
