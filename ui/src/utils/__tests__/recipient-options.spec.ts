import { describe, expect, it, vi } from 'vitest'
import { createRecipientRemoteOptions, type RecipientOption } from '../recipient-options'

describe('接收候选回填', () => {
  it('搜索后仅回填精确已选值，保持顺序，不纳入其他候选', async () => {
    const load = vi.fn(async () => [
      { label: '用户 B', value: 'b' },
      { label: '用户 A', value: 'a' },
      { label: '其他用户', value: 'other' },
    ])
    const options = createRecipientRemoteOptions(load)
    expect(await options.findOptionsByValues(['a', 'b'])).toEqual([
      { label: '用户 A', value: 'a' },
      { label: '用户 B', value: 'b' },
    ])
    expect(load.mock.calls).toEqual([['a'], ['b']])
  })

  it('针对用户名查询，能够回填无关键词首 100 项以外的已选用户', async () => {
    const all: RecipientOption[] = Array.from({ length: 130 }, (_, index) => ({
      label: `用户 ${index}`,
      value: `user-${String(index).padStart(3, '0')}`,
    }))
    const options = createRecipientRemoteOptions(async (keyword) =>
      all.filter((option) => option.value.includes(keyword)).slice(0, 100),
    )
    expect(await options.search({ keyword: '', page: 1, size: 20 })).toMatchObject({ total: 100 })
    expect(await options.findOptionsByValues(['user-129'])).toEqual([all[129]])
  })

  it('回填失败或候选已失效时保留原选中值', async () => {
    const values = ['failed', 'missing']
    const options = createRecipientRemoteOptions(async (keyword) => {
      if (keyword === 'failed') throw new Error('网络失败')
      return []
    })
    expect(await options.findOptionsByValues(values)).toEqual([
      { label: 'failed', value: 'failed' },
      { label: 'missing', value: 'missing' },
    ])
    expect(values).toEqual(['failed', 'missing'])
  })

  it('搜索失败时抛出错误交由宿主处理，不产生空结果覆盖已选值', async () => {
    const options = createRecipientRemoteOptions(async () => {
      throw new Error('网络失败')
    })
    await expect(options.search({ keyword: 'a', page: 1, size: 20 })).rejects.toThrow('网络失败')
  })
})
