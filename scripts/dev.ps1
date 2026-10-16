# ============================================================
#  本地一键启动（不需要 Docker）
#  用途：本机已经装了 MySQL，但没有 Redis/MinIO/Docker 时，
#        用 memory 缓存 + local 文件存储把整套系统跑起来。
#
#  用法：
#      powershell -ExecutionPolicy Bypass -File .\scripts\dev.ps1
#      powershell -ExecutionPolicy Bypass -File .\scripts\dev.ps1 -Only ai
# ============================================================
param(
  [ValidateSet('all', 'db', 'ai', 'backend', 'frontend')]
  [string]$Only = 'all',
  [string]$MysqlUser = 'root',
  [string]$MysqlPassword = '123456'
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$LogDir = Join-Path $Root 'logs'
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

# ---------- 便携工具链（本机没有全局 Java/Node）----------
$JdkHome = Join-Path $Root 'tools\jdk-21'
$NodeDir = Join-Path $Root 'tools\node'
$MavenBin = 'D:\操做系统\apache-maven\apache-maven-3.8.4\bin'
$Python = 'C:\python\python.exe'

if (Test-Path $JdkHome) { $env:JAVA_HOME = $JdkHome }
if (Test-Path $NodeDir) { $env:PATH = "$NodeDir;" + $env:PATH }
if (Test-Path $MavenBin) { $env:PATH = "$MavenBin;" + $env:PATH }
if (Test-Path $JdkHome) { $env:PATH = "$JdkHome\bin;" + $env:PATH }
$env:PYTHONIOENCODING = 'utf-8'

function Write-Step($msg) { Write-Host "[$(Get-Date -Format 'HH:mm:ss')] $msg" -ForegroundColor Cyan }
function Test-Port($port) {
  return [bool](Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
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
      # ⚠ 踩过的坑：mysql 客户端会把「Using a password on the command line is insecure」
      # 这种警告写到 stderr，PowerShell 5.1 在 $ErrorActionPreference='Stop' 下会把它
      # 包装成 NativeCommandError 并**直接中断脚本** —— 光写 2>$null 也挡不住。
      # 正确做法：临时把 EAP 降成 Continue，再用 try/catch + 2>$null 吞掉。
      $eap = $ErrorActionPreference
      $ErrorActionPreference = 'Continue'
      $exists = ''
      try {
        $exists = (& $cli -u $MysqlUser "-p$MysqlPassword" -N `
                    -e "select count(*) from information_schema.schemata where schema_name='rag_platform';" 2>$null)
      } catch {
        $exists = ''
      }
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
    $aiDir = Join-Path $Root 'ai-service'
    Start-Process -FilePath $Python `
      -ArgumentList '-m', 'uvicorn', 'app.main:app', '--host', '0.0.0.0', '--port', '8001' `
      -WorkingDirectory $aiDir -WindowStyle Hidden `
      -RedirectStandardOutput (Join-Path $LogDir 'ai-service.out.log') `
      -RedirectStandardError  (Join-Path $LogDir 'ai-service.err.log') | Out-Null
    Start-Sleep -Seconds 4
    if (Test-Port 8001) { Write-Host '  AI 服务已就绪： http://localhost:8001/docs' -ForegroundColor Green }
    else { Write-Host '  AI 服务启动失败，看 logs\ai-service.err.log' -ForegroundColor Red }
  }
}

# ---------- 3. 后端 ----------
if ($Only -in 'all', 'backend') {
  if (Test-Port 8080) {
    Write-Host '  后端已在 8080 运行，跳过' -ForegroundColor DarkGray
  } else {
    Write-Step '启动后端 (Spring Boot 3 + Java 21) ...'
    $backendDir = Join-Path $Root 'backend'
    $jar = Get-ChildItem (Join-Path $backendDir 'target') -Filter '*.jar' -ErrorAction SilentlyContinue |
           Where-Object { $_.Name -notmatch 'sources|javadoc|original' } | Select-Object -First 1
    if ($jar) {
      Write-Host "  使用已打包的 $($jar.Name)" -ForegroundColor DarkGray
      Start-Process -FilePath (Join-Path $JdkHome 'bin\java.exe') `
        -ArgumentList '-jar', $jar.FullName, '--spring.profiles.active=dev' `
        -WorkingDirectory $backendDir -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $LogDir 'backend.out.log') `
        -RedirectStandardError  (Join-Path $LogDir 'backend.err.log') | Out-Null
    } else {
      Write-Host '  未找到 jar，改用 mvn spring-boot:run（首次会下载依赖，较慢）' -ForegroundColor DarkGray
      Start-Process -FilePath 'cmd.exe' `
        -ArgumentList '/c', "mvn spring-boot:run -Dspring-boot.run.profiles=dev > `"$LogDir\backend.out.log`" 2>&1" `
        -WorkingDirectory $backendDir -WindowStyle Hidden | Out-Null
    }
    Start-Sleep -Seconds 15
    if (Test-Port 8080) { Write-Host '  后端已就绪： http://localhost:8080' -ForegroundColor Green }
    else { Write-Host '  后端可能还在启动中，看 logs\backend.out.log' -ForegroundColor Yellow }
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
        Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', 'npm install --registry=https://registry.npmmirror.com' `
          -WorkingDirectory $feDir -WindowStyle Hidden -Wait
      }
      Start-Process -FilePath 'cmd.exe' `
        -ArgumentList '/c', "npm run dev > `"$LogDir\frontend.out.log`" 2>&1" `
        -WorkingDirectory $feDir -WindowStyle Hidden | Out-Null
      Start-Sleep -Seconds 8
      if (Test-Port 5173) { Write-Host '  前端已就绪： http://localhost:5173' -ForegroundColor Green }
      else { Write-Host '  前端启动失败，看 logs\frontend.out.log' -ForegroundColor Red }
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
Write-Host '------------------------------------------------------------'
