// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import WebhookSettingsView from '../WebhookSettingsView.vue'

const { http, success } = vi.hoisted(() => ({
  http: { getWebhookConfig: vi.fn(), saveWebhookConfig: vi.fn(), clearWebhookConfig: vi.fn() },
  success: vi.fn(),
}))

vi.mock('../../api', () => ({ webhookApi: http }))
vi.mock('@halo-dev/ui-shared', () => ({
  stores: { currentUser: () => ({ currentUser: { user: { metadata: { name: 'alice' } } } }) },
}))
vi.mock('@halo-dev/components', () => ({
  Toast: { success },
  VPageHeader: { template: '<div />' },
  VLoading: { template: '<div>加载中</div>' },
  VCard: {
    template: '<div><slot name="header" /><slot /><slot name="footer" /></div>',
  },
  VButton: {
    props: ['disabled', 'loading'],
    template: '<button :disabled="disabled || loading"><slot /></button>',
  },
}))

function render() {
  return mount(WebhookSettingsView, {
    global: {
      stubs: {
        FormKit: {
          props: ['modelValue'],
          emits: ['update:modelValue'],
          template:
            '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
        },
      },
    },
  })
}

describe('用户 Webhook 配置', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    http.getWebhookConfig.mockResolvedValue({
      data: { configured: true, url: 'https://example.com' },
    })
    http.saveWebhookConfig.mockResolvedValue({
      data: { configured: true, url: 'https://example.com' },
    })
    http.clearWebhookConfig.mockResolvedValue({ data: { configured: false } })
  })

  it('读取和保存后保持输入为空，避免把脱敏地址覆盖到真实配置', async () => {
    const wrapper = render()
    await flushPromises()
    expect(wrapper.get('input').element.value).toBe('')
    expect(wrapper.text()).toContain('当前已配置 Webhook')
    expect(wrapper.text()).toContain('"version": "1"')
    expect(wrapper.text()).toContain('"recipient": "alice"')
    await wrapper.get('input').setValue('https://example.com/hooks/secret?key=token')
    await wrapper.findAll('button')[1]!.trigger('click')
    await flushPromises()
    expect(http.saveWebhookConfig).toHaveBeenCalledWith({
      username: 'alice',
      webhookRequest: { url: 'https://example.com/hooks/secret?key=token' },
    })
    expect(wrapper.get('input').element.value).toBe('')
    await wrapper.findAll('button')[1]!.trigger('click')
    expect(http.saveWebhookConfig).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('拒绝 HTTP 地址，并将保存失败显示给用户', async () => {
    const wrapper = render()
    await flushPromises()
    await wrapper.get('input').setValue('http://example.com')
    await wrapper.findAll('button')[1]!.trigger('click')
    expect(http.saveWebhookConfig).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请输入有效的 HTTPS')
    http.saveWebhookConfig.mockRejectedValue(new Error('failed'))
    await wrapper.get('input').setValue('https://example.com/hook')
    await wrapper.findAll('button')[1]!.trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('保存失败')
    wrapper.unmount()
  })

  it('清除配置后显示未配置状态', async () => {
    const wrapper = render()
    await flushPromises()
    await wrapper.findAll('button')[0]!.trigger('click')
    await flushPromises()
    expect(http.clearWebhookConfig).toHaveBeenCalledWith({ username: 'alice' })
    expect(wrapper.text()).not.toContain('当前已配置 Webhook')
    expect(wrapper.findAll('button')[0]!.attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })
})
