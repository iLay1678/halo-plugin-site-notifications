import { definePlugin } from '@halo-dev/ui-shared'
import { IconNotificationBadgeLine } from '@halo-dev/components'
import { markRaw } from 'vue'

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: 'Root',
      route: {
        path: '/site-notifications',
        name: 'SiteNotifications',
        component: () => import('./views/MessagesView.vue'),
        meta: {
          title: '站内通知',
          permissions: ['plugin:halo-plugin-site-notifications:manage'],
          searchable: true,
          menu: {
            name: '站内通知',
            group: 'system',
            icon: markRaw(IconNotificationBadgeLine),
            priority: 45,
          },
        },
      },
    },
  ],
  ucRoutes: [
    {
      parentName: 'NotificationsRoot',
      route: {
        path: '/site-notifications-webhook',
        name: 'SiteNotificationsWebhook',
        component: () => import('./views/WebhookSettingsView.vue'),
        meta: {
          title: 'Webhook 通知',
          menu: {
            name: 'Webhook 通知',
            group: 'dashboard',
            icon: markRaw(IconNotificationBadgeLine),
            priority: 20,
            mobile: true,
          },
        },
      },
    },
  ],
  extensionPoints: {},
})
