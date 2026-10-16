import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'

/**
 * 路由表
 * meta.requiresAuth 为 true 的路由需要登录后才能访问
 * meta.title 用于浏览器标题与左侧菜单名称
 */
const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', public: true }
  },
  {
    path: '/',
    component: () => import('@/layout/BasicLayout.vue'),
    redirect: '/chat',
    children: [
      {
        path: 'chat',
        name: 'Chat',
        component: () => import('@/views/ChatView.vue'),
        meta: { title: '对话中心', icon: 'ChatDotRound', requiresAuth: true }
      },
      {
        path: 'kb',
        name: 'KnowledgeBase',
        component: () => import('@/views/KnowledgeBaseView.vue'),
        meta: { title: '知识库管理', icon: 'Collection', requiresAuth: true }
      },
      {
        path: 'doc',
        name: 'Document',
        component: () => import('@/views/DocumentView.vue'),
        meta: { title: '文档中心', icon: 'Document', requiresAuth: true }
      },
      {
        path: 'cs',
        name: 'CustomerService',
        component: () => import('@/views/CustomerServiceView.vue'),
        meta: { title: '智能客服', icon: 'Service', requiresAuth: true }
      },
      {
        path: 'audit',
        name: 'Audit',
        component: () => import('@/views/AuditView.vue'),
        meta: { title: '审计与成本', icon: 'DataLine', requiresAuth: true }
      },
      {
        path: 'model',
        name: 'ModelConfig',
        component: () => import('@/views/ModelConfigView.vue'),
        meta: { title: '模型配置', icon: 'Cpu', requiresAuth: true }
      }
    ]
  },
  // 未匹配到的路径统一回到对话中心，避免出现空白页
  {
    path: '/:pathMatch(.*)*',
    redirect: '/chat'
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

/**
 * 全局前置守卫：未登录访问受保护路由时跳转登录页
 * 已登录时不再允许回到登录页，直接进入对话中心
 */
router.beforeEach((to, from, next) => {
  const userStore = useUserStore()
  const isLogin = userStore.isLogin

  if (to.meta && to.meta.public) {
    if (isLogin && to.path === '/login') {
      next({ path: '/chat' })
    } else {
      next()
    }
    return
  }

  if (to.meta && to.meta.requiresAuth && !isLogin) {
    ElMessage.warning('请先登录后再访问')
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }

  next()
})

// 动态设置浏览器标题
router.afterEach((to) => {
  const base = '企业级多模型 RAG 知识库与智能客服平台'
  document.title = to.meta && to.meta.title ? `${to.meta.title} - ${base}` : base
})

export default router
