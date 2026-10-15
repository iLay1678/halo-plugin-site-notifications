// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import WebhookSettingsView from '../WebhookSettingsView.vue'

const { http, success, errorToast } = vi.hoisted(() => ({
  http: {
    getWebhookConfig: vi.fn(),
    saveWebhookConfig: vi.fn(),
    clearWebhookConfig: vi.fn(),
    testWebhookConfig: vi.fn(),
  },
  success: vi.fn(),
  errorToast: vi.fn(),
}))
vi.mock('../../api', () => ({ webhookApi: http }))
vi.mock('@halo-dev/ui-shared', () => ({
  stores: { currentUser: () => ({ currentUser: { user: { metadata: { name: 'alice' } } } }) },
}))
vi.mock('@halo-dev/components', () => ({
  Toast: { success, error: errorToast },
  VPageHeader: { template: '<div />' },
  VLoading: { template: '<div />' },
  VCard: { template: '<div><slot name="header" /><slot /><slot name="footer" /></div>' },
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
          props: ['modelValue', 'type', 'label', 'options', 'disabled'],
          emits: ['update:modelValue'],
          template: `<label>{{ label }}
      <select v-if="type === 'select'" :aria-label="label" :value="modelValue" :disabled="disabled" @change="$emit('update:modelValue', $event.target.value)"><option v-for="option in options" :key="option.value" :value="option.value">{{ option.label }}</option></select>
      <textarea v-else-if="type === 'textarea'" :aria-label="label" :value="modelValue" :disabled="disabled" @input="$emit('update:modelValue', $event.target.value)" />
      <input v-else :type="type" :aria-label="label" :value="modelValue" :disabled="disabled" @input="$emit('update:modelValue', $event.target.value)" />
    </label>`,
        },
      },
    },
  })
}
async function click(wrapper: ReturnType<typeof render>, label: string) {
  await wrapper
    .findAll('button')
    .find((button) => button.text() === label)!
    .trigger('click')
  await flushPromises()
}
const config = {
  configured: true,
  url: 'https://example.com/hooks/full?key=value',
  method: 'PATCH',
  template: '{"text":"{{title}}"}',
  headers: { Authorization: 'Bearer test' },
}

describe('用户 Webhook 配置', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    http.getWebhookConfig.mockResolvedValue({ data: config })
    http.saveWebhookConfig.mockImplementation(({ webhookRequest }) =>
      Promise.resolve({ data: { ...webhookRequest, configured: true } }),
    )
    http.clearWebhookConfig.mockResolvedValue({ data: { configured: false } })
    http.testWebhookConfig.mockResolvedValue({
      data: { success: true, statusCode: 202, message: '接收端返回 HTTP 202' },
    })
  })
  it('完整回显和保存请求配置，占位符无空格可复制', async () => {
    const wrapper = render()
    await flushPromises()
    expect(wrapper.get<HTMLInputElement>('[aria-label="Webhook 地址"]').element.value).toBe(
      config.url,
    )
    expect(wrapper.get<HTMLSelectElement>('select').element.value).toBe('PATCH')
    expect(wrapper.get<HTMLInputElement>('[aria-label="值"]').element.value).toBe('Bearer test')
    await wrapper.get('[aria-label="Webhook 地址"]').setValue('https://example.com/new')
    await wrapper.get('select').setValue('PUT')
    await click(wrapper, '保存配置')
    expect(http.saveWebhookConfig).toHaveBeenCalledWith({
      username: 'alice',
      webhookRequest: {
        url: 'https://example.com/new',
        method: 'PUT',
        template: config.template,
        headers: config.headers,
      },
    })
    expect(wrapper.get<HTMLInputElement>('[aria-label="Webhook 地址"]').element.value).toBe(
      'https://example.com/new',
    )
    expect(wrapper.get('aside').text()).toContain('{{title}}')
    expect(wrapper.text()).not.toContain('{{ title }}')
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    await wrapper.get('textarea').setValue('{"draft":"{{rawBody}}"}')
    await wrapper.get('[aria-label="复制 {{title}}"]').trigger('click')
    await flushPromises()
    expect(writeText).toHaveBeenLastCalledWith('{{title}}')
    await click(wrapper, '复制示例')
    expect(JSON.parse(writeText.mock.lastCall![0])).toEqual({
      title: '{{title}}',
      content: '{{rawBody}}',
      url: '{{subject.url}}',
    })
    expect(wrapper.get<HTMLTextAreaElement>('textarea').element.value).toBe(
      '{"draft":"{{rawBody}}"}',
    )
    expect(http.saveWebhookConfig).toHaveBeenCalledTimes(1)
    expect(wrapper.get<HTMLInputElement>('[aria-label="Webhook 地址"]').element.value).toBe(
      'https://example.com/new',
    )
    wrapper.unmount()
  })
  it('请求头可增删，空行不提交', async () => {
    const wrapper = render()
    await flushPromises()
    await click(wrapper, '删除')
    await click(wrapper, '添加请求头')
    await wrapper.get('[aria-label="名称"]').setValue('X-Test')
    await wrapper.get('[aria-label="值"]').setValue('custom')
    await click(wrapper, '添加请求头')
    await click(wrapper, '保存配置')
    expect(http.saveWebhookConfig.mock.calls[0]![0].webhookRequest.headers).toEqual({
      'X-Test': 'custom',
    })
    wrapper.unmount()
  })
  it('剪贴板不可用时提示手动复制并保留模板', async () => {
    Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true })
    const wrapper = render()
    await flushPromises()
    await click(wrapper, '复制示例')
    expect(errorToast).toHaveBeenCalledWith('复制失败，请选中文字后手动复制')
    expect(wrapper.get<HTMLTextAreaElement>('textarea').element.value).toBe(config.template)
    expect(http.saveWebhookConfig).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('测试使用未保存内容，显示远端状态且不保存', async () => {
    const wrapper = render()
    await flushPromises()
    await wrapper.get('[aria-label="Webhook 地址"]').setValue('https://example.com/draft')
    await click(wrapper, '发送测试')
    expect(http.testWebhookConfig).toHaveBeenCalledWith({
      username: 'alice',
      webhookRequest: {
        url: 'https://example.com/draft',
        method: 'PATCH',
        template: config.template,
        headers: config.headers,
      },
    })
    expect(http.saveWebhookConfig).not.toHaveBeenCalled()
    expect(success).toHaveBeenCalledWith('测试成功：接收端返回 HTTP 202')
    http.testWebhookConfig.mockResolvedValue({
      data: { success: false, statusCode: 503, message: '接收端返回 HTTP 503' },
    })
    await click(wrapper, '发送测试')
    expect(errorToast).toHaveBeenCalledWith('测试失败：接收端返回 HTTP 503')
    http.testWebhookConfig.mockRejectedValue(new Error('failed'))
    await click(wrapper, '发送测试')
    expect(wrapper.get('[role="alert"]').text()).toContain('测试请求发送失败')
    wrapper.unmount()
  })
  it('无效地址、JSON 和重复请求头阻止提交', async () => {
    const wrapper = render()
    await flushPromises()
    await wrapper.get('[aria-label="Webhook 地址"]').setValue('http://example.com')
    await click(wrapper, '发送测试')
    expect(wrapper.get('[role="alert"]').text()).toContain('请输入有效的 HTTPS')
    await wrapper.get('[aria-label="Webhook 地址"]').setValue(config.url)
    await wrapper.get('textarea').setValue('{invalid')
    await click(wrapper, '保存配置')
    expect(wrapper.get('[role="alert"]').text()).toContain('合法的 JSON')
    await wrapper.get('textarea').setValue(config.template)
    await click(wrapper, '添加请求头')
    await wrapper.findAll('[aria-label="名称"]')[1]!.setValue('authorization')
    await click(wrapper, '发送测试')
    expect(wrapper.get('[role="alert"]').text()).toContain('避免重复')
    expect(http.testWebhookConfig).not.toHaveBeenCalled()
    expect(http.saveWebhookConfig).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('GET 保留模板，清除重置所有字段', async () => {
    const wrapper = render()
    await flushPromises()
    await wrapper.get('select').setValue('GET')
    expect(wrapper.get('textarea').attributes('disabled')).toBeDefined()
    await click(wrapper, '保存配置')
    expect(http.saveWebhookConfig.mock.calls[0]![0].webhookRequest.template).toBe(config.template)
    await click(wrapper, '清除配置')
    expect(http.clearWebhookConfig).toHaveBeenCalledWith({ username: 'alice' })
    expect(wrapper.get<HTMLInputElement>('[aria-label="Webhook 地址"]').element.value).toBe('')
    expect(wrapper.get<HTMLSelectElement>('select').element.value).toBe('POST')
    expect(wrapper.get<HTMLTextAreaElement>('textarea').element.value).toBe('')
    expect(wrapper.findAll('[aria-label="名称"]')).toHaveLength(0)
    expect(wrapper.text()).toContain('未配置')
    wrapper.unmount()
  })
  it('保存失败保留编辑内容', async () => {
    const wrapper = render()
    await flushPromises()
    http.saveWebhookConfig.mockRejectedValue(new Error('failed'))
    await click(wrapper, '保存配置')
    expect(wrapper.get('[role="alert"]').text()).toContain('保存失败')
    expect(wrapper.get<HTMLInputElement>('[aria-label="Webhook 地址"]').element.value).toBe(
      config.url,
    )
    wrapper.unmount()
  })
})
