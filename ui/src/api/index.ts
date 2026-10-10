import { axiosInstance } from '@halo-dev/api-client'
import { SiteMessageV1alpha1ConsoleApi } from './generated'

export const messageApi = new SiteMessageV1alpha1ConsoleApi(undefined, '', axiosInstance)
