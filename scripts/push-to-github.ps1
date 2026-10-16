# ============================================================
#  一键发布到 GitHub
#
#  用法（在本项目根目录执行）：
#     powershell -ExecutionPolicy Bypass -File .\scripts\push-to-github.ps1 -GitHubUser 你的用户名
#
#  想一次到位、不在弹窗里输密码（token 只在命令行用一次，不写进 .git 配置）：
#     powershell -ExecutionPolicy Bypass -File .\scripts\push-to-github.ps1 -GitHubUser 你的用户名 -Token ghp_xxx
#
#  前置：先在 GitHub 网页上新建一个**空仓库**（不要勾 README / .gitignore / License），
#        仓库名与 -RepoName 保持一致。
# ============================================================
param(
  [Parameter(Mandatory = $true)][string]$GitHubUser,
  [string]$RepoName = 'enterprise-rag-platform',
  [string]$Branch = 'main',
  [string]$Token = '',
  [string]$UserName = '',
  [string]$UserEmail = '',
  [switch]$Force
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Git = Join-Path $Root 'tools\git\cmd\git.exe'
if (-not (Test-Path $Git)) { $Git = 'git' }   # 回退到系统 git

function Step($m) { Write-Host "`n== $m" -ForegroundColor Cyan }
function RunGit([string[]]$a) { & $Git @a; if ($LASTEXITCODE -ne 0) { throw "git $($a -join ' ') 失败（退出码 $LASTEXITCODE）" } }

Write-Host '============================================================' -ForegroundColor Green
Write-Host "  发布 $RepoName 到 GitHub（用户：$GitHubUser）" -ForegroundColor Green
Write-Host '============================================================' -ForegroundColor Green

# ---------------------------------------------------------------- 0. 环境
Step '0) 检查环境'
if (-not (Test-Path $Git)) { throw "找不到 git，请先安装 Git 或把 git 加入 PATH" }
Write-Host "  git: $(& $Git --version)"
Push-Location $Root

try {
  # ---------------------------------------------------------------- 1. 仓库
  Step '1) 初始化仓库'
  if (-not (Test-Path (Join-Path $Root '.git'))) {
    RunGit @('init', '-b', $Branch)
    Write-Host '  已 git init'
  } else {
    Write-Host '  仓库已存在，跳过 init'
  }

  # 提交身份：不传就按 GitHub 用户名推导（noreply 邮箱能把提交算到你账号下）
  if (-not $UserName)  { $UserName  = $GitHubUser }
  if (-not $UserEmail) { $UserEmail = "$GitHubUser@users.noreply.github.com" }
  RunGit @('config', 'user.name',  $UserName)
  RunGit @('config', 'user.email', $UserEmail)
  Write-Host "  提交身份: $UserName <$UserEmail>"

  # 如果之前已经用占位身份提交过，把已有提交的作者改成当前身份
  $commitCount = ((& $Git rev-list --count HEAD) 2>$null)
  if ($commitCount -and [int]$commitCount -ge 1) {
    $authorNow = (& $Git log -1 --format='%an <%ae>') 2>$null
    if ($authorNow -ne "$UserName <$UserEmail>") {
      if ([int]$commitCount -eq 1) {
        RunGit @('commit', '--amend', '--reset-author', '--no-edit')
        Write-Host "  已把现有提交作者重置为 $UserName <$UserEmail>"
      } else {
        Write-Host "  ⚠ 仓库有 $commitCount 个提交，--amend 只能改最后一个。" -ForegroundColor Yellow
        Write-Host '     如需全部改写作者，用： git rebase -i --root --exec "git commit --amend --reset-author --no-edit"'
      }
    }
  }

  # ---------------------------------------------------------------- 2. 检查不该提交的东西
  Step '2) 安全检查（确认密钥/依赖/产物没被跟踪）'
  $tracked = & $Git ls-files
  $leak = @()
  if ($tracked) {
    $leak += $tracked | Where-Object { $_ -match '(^|/)\.env$' -and $_ -notmatch '\.example$' }
    $leak += $tracked | Where-Object { $_ -match 'node_modules/' }
    $leak += $tracked | Where-Object { $_ -match '/target/' }
    $leak += $tracked | Where-Object { $_ -match '^tools/' }
    $leak += $tracked | Where-Object { $_ -match 'application-(local|secret)\.ya?ml$' }
  }
  if ($leak.Count -gt 0) {
    Write-Host '  ✗ 以下文件不该进仓库：' -ForegroundColor Red
    $leak | Select-Object -Unique | ForEach-Object { Write-Host "      $_" }
    throw '请先在 .gitignore 里排除，再执行 git rm --cached <文件>'
  }
  Write-Host '  ✓ 没有发现被跟踪的密钥/依赖/构建产物'

  # ---------------------------------------------------------------- 3. 提交
  Step '3) 提交'
  RunGit @('add', '-A')
  $status = & $Git status --porcelain
  if ($status) {
    $msg = @(
      'feat: 企业级多模型 RAG 知识库与智能客服平台',
      '',
      '- 后端：Spring Boot 3 + Java 21，JWT/RBAC、多租户行级隔离、文档流水线、',
      '  多模型适配层（DeepSeek/通义/OpenAI + 离线 Mock，按优先级 Fallback）、',
      '  Tool Calling 智能客服、审计与成本记账',
      '- AI 服务：FastAPI + LangChain + FAISS，自研 BM25/RRF 混合检索与重排，',
      '  引用溯源到页码；PDF/Word/Markdown 解析；查询改写 + HyDE',
      '- 前端：Vue3 + Vite + Element Plus，SSE 流式对话与引用溯源面板',
      '- 存储：MySQL 19 张表 + Redis/MinIO（可切换实现）',
      '- 部署：Docker Compose 7 容器 + Nginx 反代（SSE 三件套）',
      '- 交付：一键启停脚本、全链路冒烟验收（24 项）、部署静态校验、5 篇文档'
    ) -join "`n"
    RunGit @('commit', '-m', $msg)
    Write-Host '  已提交'
  } else {
    Write-Host '  没有变更需要提交'
  }
  Write-Host "  提交数: $((& $Git rev-list --count HEAD))"

  # ---------------------------------------------------------------- 4. remote
  Step '4) 配置 remote'
  $url = "https://github.com/$GitHubUser/$RepoName.git"
  $existing = (& $Git remote) 2>$null
  if ($existing -contains 'origin') {
    RunGit @('remote', 'set-url', 'origin', $url)
    Write-Host "  已更新 origin -> $url"
  } else {
    RunGit @('remote', 'add', 'origin', $url)
    Write-Host "  已添加 origin -> $url"
  }

  # ---------------------------------------------------------------- 5. push
  Step '5) 推送'
  if ($Token) {
    # token 只在这一次命令里出现，不落盘到 .git/config
    $pushUrl = "https://$GitHubUser`:$Token@github.com/$GitHubUser/$RepoName.git"
    & $Git push $pushUrl "HEAD:refs/heads/$Branch" --force-with-lease 2>&1 | ForEach-Object { Write-Host "  $_" }
    if ($LASTEXITCODE -ne 0) { throw '推送失败（检查 token 是否有 repo 权限、仓库是否已创建）' }
    RunGit @('branch', "--set-upstream-to=origin/$Branch", $Branch)
  } else {
    Write-Host '  接下来会提示输入用户名与密码：' -ForegroundColor Yellow
    Write-Host '    用户名 = 你的 GitHub 用户名'
    Write-Host '    密码   = 你的 Personal Access Token（不是账号密码！）'
    Write-Host '  或者按 Ctrl+C，改用 -Token 参数重跑。'
    RunGit @('push', '-u', 'origin', $Branch)
  }

  Step '完成'
  Write-Host "  仓库地址： https://github.com/$GitHubUser/$RepoName" -ForegroundColor Green
} finally {
  Pop-Location
}
