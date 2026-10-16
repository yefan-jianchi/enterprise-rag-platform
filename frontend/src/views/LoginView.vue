<script setup>
/**
 * 登录页：租户编码 + 用户名 + 密码
 * 默认填充演示账号 demo / admin / admin123，方便直接体验
 */
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const formRef = ref(null)
const loading = ref(false)

// 记住上次使用的租户编码，减少重复输入
const REMEMBER_TENANT_KEY = 'rag_last_tenant'

const form = reactive({
  tenantCode: localStorage.getItem(REMEMBER_TENANT_KEY) || 'demo',
  username: 'admin',
  password: 'admin123'
})

// 表单校验规则
const rules = {
  tenantCode: [{ required: true, message: '请输入租户编码', trigger: 'blur' }],
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码长度不少于 6 位', trigger: 'blur' }
  ]
}

/**
 * 提交登录
 */
async function handleLogin () {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch (e) {
    // 校验未通过，直接返回
    return
  }

  loading.value = true
  try {
    await userStore.login({
      tenantCode: form.tenantCode.trim(),
      username: form.username.trim(),
      password: form.password
    })
    localStorage.setItem(REMEMBER_TENANT_KEY, form.tenantCode.trim())
    ElMessage.success('登录成功')
    // 支持从受保护页面跳转过来后回到原页面
    const redirect = route.query.redirect
    router.replace(typeof redirect === 'string' && redirect ? redirect : '/chat')
  } catch (e) {
    // 错误提示已在 axios 拦截器中统一处理
  } finally {
    loading.value = false
  }
}

/** 一键填充演示账号 */
function fillDemoAccount () {
  form.tenantCode = 'demo'
  form.username = 'admin'
  form.password = 'admin123'
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-header">
        <el-icon class="login-logo"><Cpu /></el-icon>
        <h1 class="login-title">企业级多模型 RAG 知识库与智能客服平台</h1>
        <p class="login-subtitle">多模型接入 · 检索增强生成 · 引用溯源 · 智能客服</p>
      </div>

      <el-alert type="info" :closable="false" class="demo-alert">
        <template #default>
          <span>演示账号：租户 <b>demo</b> / 用户名 <b>admin</b> / 密码 <b>admin123</b></span>
          <el-link type="primary" class="demo-link" @click="fillDemoAccount">一键填充</el-link>
        </template>
      </el-alert>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
        @keyup.enter="handleLogin"
      >
        <el-form-item label="租户编码" prop="tenantCode">
          <el-input v-model="form.tenantCode" placeholder="请输入租户编码" clearable>
            <template #prefix><el-icon><OfficeBuilding /></el-icon></template>
          </el-input>
        </el-form-item>

        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="请输入用户名" clearable>
            <template #prefix><el-icon><User /></el-icon></template>
          </el-input>
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            show-password
            clearable
          >
            <template #prefix><el-icon><Lock /></el-icon></template>
          </el-input>
        </el-form-item>

        <el-button
          type="primary"
          class="login-button"
          size="large"
          :loading="loading"
          @click="handleLogin"
        >
          {{ loading ? '登录中...' : '登 录' }}
        </el-button>
      </el-form>

      <p class="login-footer">Vue 3 + Vite + Element Plus + Pinia</p>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100vh;
  background: linear-gradient(135deg, #eef2f7 0%, #dbe6f4 100%);
}

.login-card {
  width: 420px;
  padding: 32px 32px 20px;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 6px 24px rgba(0, 0, 0, 0.08);
}

.login-header {
  text-align: center;
  margin-bottom: 20px;
}

.login-logo {
  font-size: 36px;
  color: #409eff;
}

.login-title {
  margin: 8px 0 4px;
  font-size: 18px;
  font-weight: 600;
  line-height: 1.4;
  color: var(--app-text);
}

.login-subtitle {
  margin: 0;
  font-size: 12px;
  color: var(--app-text-secondary);
}

.demo-alert {
  margin-bottom: 20px;
}

.demo-link {
  margin-left: 8px;
  font-size: 12px;
}

.login-button {
  width: 100%;
  margin-top: 4px;
  letter-spacing: 2px;
}

.login-footer {
  margin: 20px 0 0;
  text-align: center;
  font-size: 12px;
  color: #c0c4cc;
}
</style>
