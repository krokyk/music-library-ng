import '@mdi/font/css/materialdesignicons.css'
import 'vuetify/styles'

import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import { createVuetify } from 'vuetify'
import * as components from 'vuetify/components'
import * as directives from 'vuetify/directives'

import App from './App.vue'
import LibraryView from './views/LibraryView.vue'
import SourcesView from './views/SourcesView.vue'

const vuetify = createVuetify({
  components,
  directives,
  theme: {
    defaultTheme: 'musicLibraryLight',
    themes: {
      musicLibraryLight: {
        dark: false,
        colors: {
          primary: '#1f6feb',
          secondary: '#57606a',
          surface: '#ffffff',
          background: '#f6f8fa',
        },
      },
    },
  },
})

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: LibraryView },
    { path: '/sources', component: SourcesView },
  ],
})

createApp(App)
  .use(createPinia())
  .use(router)
  .use(vuetify)
  .mount('#app')
