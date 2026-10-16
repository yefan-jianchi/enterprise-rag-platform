<script setup>
/**
 * 主布局：左侧菜单 + 顶部用户信息栏 + 内容区
 */
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox, ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { useChatStore } from '@/stores/chat'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const chatStore = useChatStore()

// 侧边栏折叠状态
const collapsed = ref(false)

/**
 * 菜单项：与 router 配置的 children 保持一致
 * 单独声明是为了便于维护图标与排序
 */
const menus = [
  { path: '/chat', title: '对话中心', icon: 'ChatDotRound' },
  { path: '/kb', title: '知识库管理', icon: 'Collection' },
  { path: '/doc', title: '文档中心', icon: 'Document' },
  { path: '/cs', title: '智能客服', icon: 'Service' },
  { path: '/audit', title: '审计与成本', icon: 'DataLine' },
  { path: '/model', title: '模型配置', icon: 'Cpu' }
]

// 当前激活菜单：/doc 等带 query 的路径只取 pathname
const activeMenu = computed(() => route.path)

// 当前页面标题
const currentTitle = computed(() => (route.meta && route.meta.title) || '')

/**
 * 退出登录：二次确认后清理登录态并回到登录页
 */
async function handleLogout () {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch (e) {
    // 用户取消，不做任何处理
    return
  }
  chatStore.reset()
  userStore.logout()
  ElMessage.success('已退出登录')
  router.replace('/login')
}
</script>

<template>
  <el-container class="layout-root">
    <!-- 左侧菜单 -->
    <el-aside :width="collapsed ? '64px' : '220px'" class="layout-aside">
      <div class="logo">
        <el-icon class="logo-icon"><Cpu /></el-icon>
        <span v-show="!collapsed" class="logo-text">RAG 知识库平台</span>
      </div>
      <el-menu
        :default-active="activeMenu"
        :collapse="collapsed"
        :collapse-transition="false"
        router
        class="layout-menu"
      >
        <el-menu-item v-for="item in menus" :key="item.path" :index="item.path">
          <el-icon><component :is="item.icon" /></el-icon>
          <template #title>{{ item.title }}</template>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <!-- 顶部栏 -->
      <el-header class="layout-header">
        <div class="header-left">
          <el-icon class="collapse-btn" @click="collapsed = !collapsed">
            <component :is="collapsed ? 'Expand' : 'Fold'" />
          </el-icon>
          <span class="header-title">{{ currentTitle }}</span>
        </div>

        <div class="header-right">
          <el-tag size="small" type="info" effect="plain" class="tenant-tag">
            租户：{{ userStore.tenantCode }}
          </el-tag>
          <el-tag
            v-for="role in userStore.roles"
            :key="role"
            size="small"
            type="primary"
            effect="plain"
          >
            {{ role }}
          </el-tag>

          <el-dropdown trigger="click">
            <span class="user-info">
              <el-avatar :size="28" class="user-avatar">
                {{ userStore.displayName.slice(0, 1) }}
              </el-avatar>
              <span class="user-name">{{ userStore.displayName }}</span>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item disabled>
                  用户：{{ (userStore.userInfo && userStore.userInfo.username) || '-' }}
                </el-dropdown-item>
                <el-dropdown-item divided @click="handleLogout">
                  <el-icon><SwitchButton /></el-icon>
                  退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <!-- 内容区 -->
      <el-main class="layout-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout-root {
  height: 100vh;
}

.layout-aside {
  background-color: #ffffff;
  border-right: 1px solid var(--app-border);
  transition: width 0.2s;
  overflow-x: hidden;
}

.logo {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 56px;
  padding: 0 16px;
  border-bottom: 1px solid var(--app-border);
  white-space: nowrap;
  overflow: hidden;
}

.logo-icon {
  font-size: 20px;
  color: #409eff;
  flex-shrink: 0;
}

.logo-text {
  font-size: 15px;
  font-weight: 600;
  color: var(--app-text);
}

.layout-menu {
  border-right: none;
}

.layout-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 56px;
  padding: 0 20px;
  background-color: #ffffff;
  border-bottom: 1px solid var(--app-border);
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.collapse-btn {
  font-size: 18px;
  cursor: pointer;
  color: #606266;
}

.collapse-btn:hover {
  color: #409eff;
}

.header-title {
  font-size: 15px;
  font-weight: 600;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.tenant-tag {
  margin-right: 2px;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  outline: none;
  padding: 4px 6px;
  border-radius: 4px;
}

.user-info:hover {
  background-color: #f5f7fa;
}

.user-avatar {
  background-color: #409eff;
  color: #fff;
  font-size: 13px;
}

.user-name {
  font-size: 14px;
  color: var(--app-text);
}

.layout-main {
  padding: 16px;
  background-color: var(--app-bg);
  overflow: auto;
}
</style>
