import type { MessageRequest, SiteMessage } from '@/api/generated'

export const phaseLabels = {
  PENDING: '待发送',
  SCHEDULED: '等待发送',
  SENDING: '发送中',
  SENT: '已发送',
  PARTIAL_FAILED: '部分失败',
  FAILED: '发送失败',
  CANCELLED: '已取消',
} as const

export const targetLabels = { ALL: '全站用户', USERS: '指定用户', ROLES: '指定角色' } as const
export const typeLabels = { ANNOUNCEMENT: '公告', MESSAGE: '普通消息' } as const

export function phaseLabel(message: SiteMessage) {
  const phase = message.status?.phase
  return phase ? phaseLabels[phase] : '待发送'
}

export function canCancel(message: SiteMessage) {
  return message.spec?.sendMode === 'SCHEDULED' && message.status?.phase === 'SCHEDULED'
}

export function canRetry(message: SiteMessage) {
  return ['PARTIAL_FAILED', 'FAILED'].includes(message.status?.phase ?? '')
}

export function isActive(message: SiteMessage) {
  return ['PENDING', 'SCHEDULED', 'SENDING'].includes(message.status?.phase ?? '')
}

export function formatTime(value?: string | null) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(new Date(value))
}

export function parseScheduledInstant(date: string, time: string) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || !/^\d{2}:\d{2}(:\d{2})?$/.test(time)) {
    throw new Error('请选择完整的发送日期和时间')
  }
  const scheduled = new Date(`${date}T${time}`)
  const [year, month, day] = date.split('-').map(Number)
  const [hour, minute, second = 0] = time.split(':').map(Number)
  if (
    !Number.isFinite(scheduled.getTime()) ||
    scheduled.getFullYear() !== year ||
    scheduled.getMonth() + 1 !== month ||
    scheduled.getDate() !== day ||
    scheduled.getHours() !== hour ||
    scheduled.getMinutes() !== minute ||
    scheduled.getSeconds() !== second
  ) {
    throw new Error('发送时间格式不正确')
  }
  return scheduled.toISOString()
}

export function toScheduledInstant(date: string, time: string, now = Date.now()) {
  const scheduledAt = parseScheduledInstant(date, time)
  if (new Date(scheduledAt).getTime() <= now) throw new Error('发送时间必须晚于当前时间')
  return scheduledAt
}

export interface RequestIdentity {
  fingerprint: string
  requestId: string
}

export function getRequestIdentity(
  values: Omit<MessageRequest, 'requestId'>,
  previous?: RequestIdentity,
  now = Date.now(),
): RequestIdentity {
  const fingerprint = JSON.stringify(values)
  if (previous?.fingerprint === fingerprint) return previous
  if (
    values.sendMode === 'SCHEDULED' &&
    (!values.scheduledAt || new Date(values.scheduledAt).getTime() <= now)
  ) {
    throw new Error('发送时间必须晚于当前时间')
  }
  return { fingerprint, requestId: crypto.randomUUID() }
}
