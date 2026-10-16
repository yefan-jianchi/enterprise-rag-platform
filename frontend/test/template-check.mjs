/**
 * 静态自检脚本：
 * 提取每个 .vue 文件 <script setup> 中定义的顶层标识符，
 * 再提取 <template> 中真正参与表达式求值的标识符（插值 {{ }}、:prop、@event、v-指令值），
 * 最后报告「模板里用到但 script 里没定义」的名字。
 *
 * 目的：提前发现只在运行时才会暴露的 ReferenceError（Vue 的模板报错信息通常很隐晦）。
 * 运行：node test/template-check.mjs
 */
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../src', import.meta.url))

/** 递归收集所有 .vue 文件 */
function collectVueFiles (dir, result = []) {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry)
    if (statSync(full).isDirectory()) {
      collectVueFiles(full, result)
    } else if (entry.endsWith('.vue')) {
      result.push(full)
    }
  }
  return result
}

/** 白名单：Vue 内置变量、全局对象、常见插槽变量 */
const BUILTIN = new Set([
  'true', 'false', 'null', 'undefined', 'this', 'new', 'typeof', 'instanceof', 'void', 'delete',
  'Math', 'Date', 'JSON', 'Number', 'String', 'Boolean', 'Array', 'Object', 'RegExp',
  'parseInt', 'parseFloat', 'isNaN', 'encodeURIComponent', 'decodeURIComponent',
  'console', 'window', 'document', 'navigator', 'localStorage', 'sessionStorage',
  'item', 'index', 'row', 'column', '$event', '$slots', '$props', '$attrs', '$refs', '$emit'
])

/**
 * 从 script setup 中提取顶层定义
 * 覆盖 import、顶层变量声明（含解构）、函数声明、defineXxx 宏解构
 */
function extractScriptIdentifiers (script) {
  const ids = new Set()
  let m

  // import { a, b as c } from 'x' / import d from 'x'
  const importRe = /import\s+([\s\S]*?)\s+from\s+['"][^'"]+['"]/g
  while ((m = importRe.exec(script)) !== null) {
    const clause = m[1].trim()
    const namedMatch = /\{([\s\S]*?)\}/.exec(clause)
    if (namedMatch) {
      for (const part of namedMatch[1].split(',')) {
        const name = part.trim().split(/\s+as\s+/).pop().trim()
        if (name) ids.add(name)
      }
    }
    const defaultMatch = /^([A-Za-z_$][\w$]*)/.exec(clause)
    if (defaultMatch) ids.add(defaultMatch[1])
  }

  // 顶层变量声明（含数组/对象解构）
  const declRe = /^(?:const|let|var)\s+([\s\S]*?)=\s*/gm
  while ((m = declRe.exec(script)) !== null) {
    const target = m[1].trim()
    if (target.startsWith('{') || target.startsWith('[')) {
      const inner = target.replace(/[{}[\]]/g, ' ')
      for (const part of inner.split(',')) {
        // 形如 a、a: b、a = 1 均取左侧名字
        const name = part.trim().split(/[:=]/)[0].trim()
        if (/^[A-Za-z_$][\w$]*$/.test(name)) ids.add(name)
      }
    } else {
      const name = target.trim()
      if (/^[A-Za-z_$][\w$]*$/.test(name)) ids.add(name)
    }
  }

  // 函数声明
  const fnRe = /^(?:async\s+)?function\s+([A-Za-z_$][\w$]*)/gm
  while ((m = fnRe.exec(script)) !== null) ids.add(m[1])

  // defineProps / defineEmits 等宏里解构出来的名字
  for (const macro of ['defineProps', 'defineEmits', 'defineOptions', 'defineExpose']) {
    const re = new RegExp(`${macro}\\s*\\(\\s*\\{([\\s\\S]*?)\\}\\s*\\)`, 'g')
    while ((m = re.exec(script)) !== null) {
      for (const part of m[1].split(/[,\n]/)) {
        const name = part.trim().split(':')[0].trim()
        if (/^[A-Za-z_$][\w$]*$/.test(name)) ids.add(name)
      }
    }
  }

  return ids
}

/**
 * 从模板中提取「参与表达式求值」的名字
 * 只收集 {{ }}、:prop="..."、@event="..."、v-xxx="..." 内部的标识符，
 * 并跳过属性访问（obj.name 里的 name）与对象字面量的键名
 */
function extractTemplateIdentifiers (template) {
  const expressions = []
  let m

  // 1. 插值表达式 {{ ... }}
  const mustacheRe = /\{\{([\s\S]*?)\}\}/g
  while ((m = mustacheRe.exec(template)) !== null) expressions.push(m[1])

  // 2. 动态绑定 :prop="..." 、事件 @click="..." 、v-if / v-for / v-model 等
  const attrRe = /(?:^|\s)(?::|@|v-)[\w:.\-\[\]]+\s*=\s*"([^"]*)"/g
  while ((m = attrRe.exec(template)) !== null) expressions.push(m[1])

  // 2.1 收集 v-for 的别名与插槽/箭头函数的参数——它们属于模板局部作用域，不算未定义
  const scoped = new Set()
  const vForRe = /v-for\s*=\s*"\s*\(?([^)"]*?)\)?\s+(?:in|of)\s/g
  while ((m = vForRe.exec(template)) !== null) {
    for (const part of m[1].split(',')) {
      const name = part.trim()
      if (/^[A-Za-z_$][\w$]*$/.test(name)) scoped.add(name)
    }
  }
  // 形如 #default="{ row }" 的插槽解构参数
  const slotRe = /#[\w-]+\s*=\s*"\s*\{([^}]*)\}/g
  while ((m = slotRe.exec(template)) !== null) {
    for (const part of m[1].split(',')) {
      const name = part.trim().split(':').pop().trim()
      if (/^[A-Za-z_$][\w$]*$/.test(name)) scoped.add(name)
    }
  }
  // 形如 (cmd) => ... 的箭头函数参数
  const arrowRe = /\(?\s*([A-Za-z_$][\w$]*)\s*\)?\s*=>/g
  while ((m = arrowRe.exec(template)) !== null) scoped.add(m[1])

  const ids = new Set()
  for (const expr of expressions) {
    // 去掉字符串字面量与对象字面量的键（key: value 中的 key）
    const cleaned = expr
      .replace(/'[^']*'/g, ' ')
      .replace(/"[^"]*"/g, ' ')
      .replace(/`[^`]*`/g, ' ')
      .replace(/\{[^}]*\}/g, ' ') // 内联对象字面量（含其键名）
      .replace(/\.\s*[A-Za-z_$][\w$]*/g, ' ') // 属性访问

    const idRe = /[A-Za-z_$][\w$]*/g
    let mm
    while ((mm = idRe.exec(cleaned)) !== null) {
      const word = mm[0]
      // 跳过关键字
      if (/^(if|else|return|in|of|new|typeof|instanceof|await|async|function|const|let|var|true|false|null|undefined|this)$/.test(word)) continue
      // 跳过作为对象键出现的名字（后面紧跟冒号）
      const after = cleaned.slice(mm.index + word.length).trimStart()
      if (after.startsWith(':')) continue
      ids.add(word)
    }
  }
  // 模板局部作用域中的名字不属于「未定义」
  for (const name of scoped) ids.delete(name)
  return ids
}

let problems = 0
let checked = 0

for (const file of collectVueFiles(root)) {
  const source = readFileSync(file, 'utf8')
  const scriptMatch = /<script setup>([\s\S]*?)<\/script>/.exec(source)
  if (!scriptMatch) continue
  // 只取第一个 <template> 块（App.vue 等）
  const templateMatch = /<template>([\s\S]*)<\/template>/.exec(source)
  checked++

  const scriptIds = extractScriptIdentifiers(scriptMatch[1])
  const templateIds = templateMatch ? extractTemplateIdentifiers(templateMatch[1]) : new Set()

  const missing = [...templateIds].filter((id) => !scriptIds.has(id) && !BUILTIN.has(id))

  const rel = relative(root, file).replace(/\\/g, '/')
  if (missing.length) {
    problems++
    console.log(`[需确认] ${rel}`)
    console.log(`         未在 script 中找到: ${missing.join(', ')}`)
  } else {
    console.log(`[通过]   ${rel}`)
  }
}

console.log(`\n共检查 ${checked} 个组件，可疑项 ${problems} 个`)
