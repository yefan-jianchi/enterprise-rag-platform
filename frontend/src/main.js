import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

import 'element-plus/dist/index.css'
import '@/styles/index.css'

import App from './App.vue'
import router from './router'

const app = createApp(App)

// 全局注册 Element Plus 图标组件，模板中可直接使用 <el-icon><ChatDotRound /></el-icon>
for (const [name, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(name, component)
}

app.use(createPinia())
app.use(router)
// 组件库使用中文语言包（分页、日期选择器等内置文案）
app.use(ElementPlus, { locale: zhCn })

app.mount('#app')
