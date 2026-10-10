import { describe, expect, it } from 'vitest'
import { getRequestIdentity, parseScheduledInstant, toScheduledInstant } from '../messages'
import type { MessageRequest } from '@/api/generated'

describe('定时发送时间', () => {
  it('将浏览器本地时间转换为 UTC Instant', () => {
    const local = new Date(2027, 0, 2, 14, 30)
    expect(toScheduledInstant('2027-01-02', '14:30', local.getTime() - 1)).toBe(local.toISOString())
  })

  it.each([
    ['', '14:30'],
    ['2027-01-02', ''],
    ['2027-02-30', '14:30'],
    ['2027-01-02', '24:00'],
    ['2027-01-02', '14:60'],
  ])('拒绝不完整或不存在的时间 %s %s', (date, time) => {
    expect(() => toScheduledInstant(date, time, 0)).toThrow()
  })

  it('拒绝当前和过去时间', () => {
    const local = new Date(2027, 0, 2, 14, 30).getTime()
    expect(() => toScheduledInstant('2027-01-02', '14:30', local)).toThrow(
      '发送时间必须晚于当前时间',
    )
    expect(() => toScheduledInstant('2027-01-02', '14:30', local + 1)).toThrow()
  })
})

describe('发送请求标识', () => {
  const scheduledAt = parseScheduledInstant('2027-01-02', '14:30')
  const due = new Date(scheduledAt).getTime()
  const values: Omit<MessageRequest, 'requestId'> = {
    title: '定时测试',
    content: '保持原内容重试',
    type: 'MESSAGE',
    target: 'USERS',
    usernames: ['user-a'],
    roles: [],
    sendMode: 'SCHEDULED',
    scheduledAt,
  }

  it('定时请求到期后复用原标识，保持原时间和内容', () => {
    const original = getRequestIdentity(values, undefined, due - 1)
    expect(getRequestIdentity(values, original, due + 1000)).toBe(original)
  })

  it('首次定时发送和内容发生变化的定时发送仍拒绝过期时间', () => {
    expect(() => getRequestIdentity(values, undefined, due)).toThrow('发送时间必须晚于当前时间')
    const original = getRequestIdentity(values, undefined, due - 1)
    expect(() => getRequestIdentity({ ...values, title: '修改标题' }, original, due)).toThrow(
      '发送时间必须晚于当前时间',
    )
  })

  it('新内容生成新标识，立即发送不需要计划时间', () => {
    const immediate = { ...values, sendMode: 'IMMEDIATE' as const, scheduledAt: undefined }
    const original = getRequestIdentity(immediate, undefined, due)
    expect(getRequestIdentity(immediate, original, due + 1000)).toBe(original)
    expect(getRequestIdentity({ ...immediate, content: '新内容' }, original).requestId).not.toBe(
      original.requestId,
    )
  })
})
