# ============================================================
#  本地一键启动（不需要 Docker）
#  用途：本机已经装了 MySQL，但没有 Redis/MinIO/Docker 时，
#        用 memory 缓存 + local 文件存储把整套系统跑起来。
#
#  用法：
#      powershell -ExecutionPolicy Bypass -File .\scripts\dev.ps1
#      powershell -ExecutionPolicy Bypass -File .\scripts\dev.ps1 -Only ai
#
#  ⚠ 踩过的坑（改这个脚本前务必看）：
#   1) 用 Start-Process -RedirectStandardOutput 启长驻进程时，
#      **父 shell 退出会等重定向句柄，导致脚本挂住不返回**。
#      所以这里统一改成 `cmd /c "... > log 2>&1"`，让 cmd 做重定向，
#      PowerShell 侧不持有任何句柄。
#   2) mysql 客户端的密码警告写 stderr，在 $ErrorActionPreference='Stop'
#      下会被包装成 NativeCommandError 直接中断脚本；光加 2>$null 挡不住，
#      必须临时把 EAP 降成 Continue。
#   3) 启动后不要用固定 Start-Sleep 猜时间，要**轮询端口**直到就绪。
#   4) 【重要】前端跑着的时候，不要在项目里执行会重写工作区的 git 命令
#      （git rebase / checkout / reset --hard / pull）。Vite 会监听 vite.config.js，
#      文件被重写的瞬间它会重启并「加载配置失败」，然后退回"无配置"状态，表现为：
#        · @ 别名失效（Failed to resolve import "@/..."）
#        · .vue 报 "Install @vitejs/plugin-vue to handle .vue files"
#        · 启动日志里 host 变回 localhost，并出现 "Could not auto-determine entry point"
#      配置本身没坏，**重启 Vite 即可恢复**（先删 node_modules\.vite 更稳）。
# ============================================================
param(
  [ValidateSet('all', 'db', 'ai', 'backend', 'frontend')]
  [string]$Only = 'all',
  [string]$MysqlUser = 'root',
  [string]$MysqlPassword = '123456',
  [int]$WaitSeconds = 120
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$LogDir = Join-Path $Root 'logs'
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

# ---------- 便携工具链（本机没有全局 Java/Node）----------
$JdkHome  = Join-Path $Root 'tools\jdk-21'
$NodeDir  = Join-Path $Root 'tools\node'
$GitDir   = Join-Path $Root 'tools\git\cmd'
$MavenBin = 'D:\操做系统\apache-maven\apache-maven-3.8.4\bin'
$Python   = 'C:\python\python.exe'

if (Test-Path $JdkHome) { $env:JAVA_HOME = $JdkHome; $env:PATH = "$JdkHome\bin;" + $env:PATH }
if (Test-Path $NodeDir) { $env:PATH = "$NodeDir;" + $env:PATH }
if (Test-Path $GitDir)  { $env:PATH = "$GitDir;"  + $env:PATH }
if (Test-Path $MavenBin) { $env:PATH = "$MavenBin;" + $env:PATH }
$env:PYTHONIOENCODING = 'utf-8'

function Write-Step($msg) { Write-Host "[$(Get-Date -Format 'HH:mm:ss')] $msg" -ForegroundColor Cyan }
function Test-Port($port) {
  # 双重判活：Get-NetTCPConnection 偶尔看不到某些绑定，再用 TCP 连接兜底
  if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { return $true }
  try {
    $c = New-Object System.Net.Sockets.TcpClient
    $c.Connect('127.0.0.1', $port)
    $ok = $c.Connected
    $c.Close()
    return $ok
  } catch { return $false }
}
function Wait-Port($port, $seconds, $name) {
  $deadline = (Get-Date).AddSeconds($seconds)
  while ((Get-Date) -lt $deadline) {
    if (Test-Port $port) { return $true }
    Start-Sleep -Seconds 2
  }
  return $false
}
# 用 cmd 做重定向启动，避免父进程持有句柄
# ⚠ 实测坑（四种写法对比过）：**绝对不要给命令名加引号**。
#     cmd /c "npm" run dev        → 子进程立刻退出、日志为空（失败）
#     cmd /c npm run dev          → 正常（成功）
#     cmd /c C:\...\npm.cmd run dev → 正常（成功）
#     cmd /c "C:\...\npm.cmd" run dev → 失败
#   正确做法：整条命令作为一个参数传给 /c，由 PowerShell 加外层引号。
function Start-Detached($exe, $arguments, $workDir, $outLog) {
  if ($exe -match '\s') {
    Write-Host "  ⚠ 路径含空格（$exe），cmd /c 的引号解析容易出问题，建议换到无空格路径" -ForegroundColor Yellow
  }
  $cmd = "$exe $arguments > `"$outLog`" 2>&1"
  Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', $cmd -WorkingDirectory $workDir -WindowStyle Hidden | Out-Null
}

Write-Host ''
Write-Host '============================================================' -ForegroundColor Green
Write-Host '  企业级多模型 RAG 知识库与智能客服平台 · 本地启动' -ForegroundColor Green
Write-Host '============================================================' -ForegroundColor Green

# ---------- 1. 数据库 ----------
if ($Only -in 'all', 'db') {
  Write-Step '检查 MySQL ...'
  if (-not (Test-Port 3306)) {
    Write-Host '  MySQL 未监听 3306，请先启动 MySQL80 服务' -ForegroundColor Red
  } else {
    $cli = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
    if (Test-Path $cli) {
      $eap = $ErrorActionPreference
      $ErrorActionPreference = 'Continue'
      $exists = ''
      try {
        $exists = (& $cli -u $MysqlUser "-p$MysqlPassword" -N `
                    -e "select count(*) from information_schema.schemata where schema_name='rag_platform';" 2>$null)
      } catch { $exists = '' }
      $ErrorActionPreference = $eap
      $exists = ("$exists").Trim()
      if ($exists -ne '1') {
        Write-Step '初始化数据库（schema + seed）...'
        & (Join-Path $PSScriptRoot 'init-db.ps1') -MysqlUser $MysqlUser -MysqlPassword $MysqlPassword
      } else {
        Write-Host '  数据库 rag_platform 已存在，跳过初始化' -ForegroundColor DarkGray
      }
    } else {
      Write-Host '  未找到 mysql 客户端，跳过数据库检查' -ForegroundColor DarkYellow
    }
  }
}

# ---------- 2. AI 服务 ----------
if ($Only -in 'all', 'ai') {
  if (Test-Port 8001) {
    Write-Host '  AI 服务已在 8001 运行，跳过' -ForegroundColor DarkGray
  } else {
    Write-Step '启动 AI 服务 (Python + LangChain + FAISS) ...'
    Start-Detached $Python '-m uvicorn app.main:app --host 0.0.0.0 --port 8001' `
      (Join-Path $Root 'ai-service') (Join-Path $LogDir 'ai-service.out.log')
    if (Wait-Port 8001 60 'AI 服务') {
      Write-Host '  AI 服务已就绪： http://localhost:8001/docs' -ForegroundColor Green
    } else {
      Write-Host '  AI 服务启动失败，看 logs\ai-service.out.log' -ForegroundColor Red
    }
  }
}

# ---------- 3. 后端 ----------
if ($Only -in 'all', 'backend') {
  if (Test-Port 8080) {
    Write-Host '  后端已在 8080 运行，跳过' -ForegroundColor DarkGray
  } else {
    Write-Step '启动后端 (Spring Boot + Java 21) ...'
    $backendDir = Join-Path $Root 'backend'
    # 只用明确的 jar 名，避免 sources/javadoc/备份副本被 Select-Object -First 1 选错
    $jar = Get-ChildItem (Join-Path $backendDir 'target') -Filter 'enterprise-rag-backend*.jar' -ErrorAction SilentlyContinue |
           Where-Object { $_.Name -notmatch 'sources|javadoc|original|\.bak|good' } | Select-Object -First 1
    if ($jar) {
      Write-Host "  使用已打包的 $($jar.Name)（$([math]::Round($jar.Length/1MB)) MB）" -ForegroundColor DarkGray
      Start-Detached (Join-Path $JdkHome 'bin\java.exe') `
        "-Dfile.encoding=UTF-8 -jar `"$($jar.FullName)`" --spring.profiles.active=dev" `
        $backendDir (Join-Path $LogDir 'backend.out.log')
    } else {
      Write-Host '  未找到 jar，改用 mvn spring-boot:run（首次会下载依赖，较慢）' -ForegroundColor DarkGray
      Start-Detached 'mvn' 'spring-boot:run -Dspring-boot.run.profiles=dev' `
        $backendDir (Join-Path $LogDir 'backend.out.log')
    }
    if (Wait-Port 8080 $WaitSeconds '后端') {
      Write-Host '  后端已就绪： http://localhost:8080' -ForegroundColor Green
    } else {
      Write-Host "  后端在 $WaitSeconds 秒内未就绪，看 logs\backend.out.log" -ForegroundColor Yellow
    }
  }
}

# ---------- 4. 前端 ----------
if ($Only -in 'all', 'frontend') {
  if (Test-Port 5173) {
    Write-Host '  前端已在 5173 运行，跳过' -ForegroundColor DarkGray
  } else {
    $feDir = Join-Path $Root 'frontend'
    if (Test-Path (Join-Path $feDir 'package.json')) {
      Write-Step '启动前端 (Vue3 + Vite) ...'
      if (-not (Test-Path (Join-Path $feDir 'node_modules'))) {
        Write-Host '  首次启动，先 npm install ...' -ForegroundColor DarkGray
        Start-Detached "$NodeDir\npm.cmd" 'install --registry=https://registry.npmmirror.com' $feDir (Join-Path $LogDir 'npm-install.log')
        while (-not (Test-Path (Join-Path $feDir 'node_modules'))) {
          Start-Sleep -Seconds 5
          Write-Host '    ... npm install 进行中' -ForegroundColor DarkGray
        }
      }
      Start-Detached "$NodeDir\npm.cmd" 'run dev' $feDir (Join-Path $LogDir 'frontend.out.log')
      if (Wait-Port 5173 60 '前端') {
        Write-Host '  前端已就绪： http://localhost:5173' -ForegroundColor Green
      } else {
        Write-Host '  前端启动失败，看 logs\frontend.out.log' -ForegroundColor Red
      }
    } else {
      Write-Host '  前端目录还没有 package.json，跳过' -ForegroundColor DarkGray
    }
  }
}

Write-Host ''
Write-Host '------------------------------------------------------------'
Write-Host '  访问地址' -ForegroundColor Green
Write-Host '    前端页面    http://localhost:5173       （默认账号 demo / admin / admin123）'
Write-Host '    后端接口    http://localhost:8080/api'
Write-Host '    AI 接口文档 http://localhost:8001/docs'
Write-Host ''
Write-Host "  日志目录    $LogDir"
Write-Host '  停止服务    powershell -File .\scripts\stop-dev.ps1'
Write-Host '  全链路验收  powershell -File .\scripts\smoke-test.ps1'
Write-Host '------------------------------------------------------------'
