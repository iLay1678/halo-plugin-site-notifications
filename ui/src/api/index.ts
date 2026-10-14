import { axiosInstance } from '@halo-dev/api-client'
import { SiteMessageV1alpha1ConsoleApi, SiteNotificationWebhookV1alpha1UcApi } from './generated'

export const messageApi = new SiteMessageV1alpha1ConsoleApi(undefined, '', axiosInstance)
export const webhookApi = new SiteNotificationWebhookV1alpha1UcApi(undefined, '', axiosInstance)
