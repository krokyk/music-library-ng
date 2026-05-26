import '@mdi/font/css/materialdesignicons.css'
import 'vuetify/styles'
import './styles.css'

import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import { createVuetify } from 'vuetify'
import * as components from 'vuetify/components'
import * as directives from 'vuetify/directives'

import App from './App.vue'
import LibraryView from './views/LibraryView.vue'
import CollectionsView from './views/CollectionsView.vue'
import ArtistsView from './views/ArtistsView.vue'
import SettingsView from './views/SettingsView.vue'

const vuetify = createVuetify({
  components,
  directives,
  defaults: {
    VBtn: {
      density: 'comfortable',
      rounded: 'lg',
    },
    VChip: {
      density: 'comfortable',
      variant: 'flat',
    },
    VSelect: {
      color: 'primary',
      density: 'compact',
      variant: 'outlined',
    },
    VTextField: {
      color: 'primary',
      density: 'compact',
      variant: 'outlined',
    },
  },
  theme: {
    defaultTheme: 'musicLibraryDark',
    themes: {
      musicLibraryDark: {
        dark: true,
        colors: {
          primary: '#00d5ff',
          secondary: '#a970ff',
          surface: '#141414',
          background: '#080808',
          error: '#ff5f6d',
          warning: '#f8c14a',
          success: '#38d996',
        },
      },
    },
  },
})

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: CollectionsView },
    { path: '/library', component: LibraryView },
    { path: '/artists', component: ArtistsView },
    { path: '/settings', component: SettingsView },
  ],
})

createApp(App)
  .use(createPinia())
  .use(router)
  .use(vuetify)
  .mount('#app')
