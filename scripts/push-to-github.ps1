# ============================================================
#  一键发布到 GitHub
#
#  用法（在本项目根目录执行）：
#     # remote 已配好时（本仓库已配），直接执行：
#     powershell -ExecutionPolicy Bypass -File .\scripts\push-to-github.ps1
#
#     # 换一个仓库/用户：
#     powershell -ExecutionPolicy Bypass -File .\scripts\push-to-github.ps1 -GitHubUser 你的用户名
#
#     # 不想在弹窗里输密码（token 只在命令行用一次，不写进 .git 配置）：
#     powershell -ExecutionPolicy Bypass -File .\scripts\push-to-github.ps1 -Token ghp_xxx
#
#  ⚠ 踩过的坑（改脚本前务必看）：
#   1) 引号包裹命令名会让 cmd /c 解析坏 → 本项目所有 cmd 调用都不给命令名加引号
#   2) PowerShell 5.1 调原生命令时，参数里含中文双引号会被拆断 →
#      提交信息一律走 git commit -F <文件>，不要拼在命令行里
#   3) GitHub 返回的 "Repository not found" 有两种含义：仓库不存在，或私有仓库无权限。
#      所以本脚本推之前先用 API 查一次，把两种情况区分开。
# ============================================================
param(
  [string]$GitHubUser = '',
  [string]$RepoName = '',
  [string]$Branch = 'main',
  [string]$Token = '',
  [string]$UserName = '',
  [string]$UserEmail = ''
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Git = Join-Path $Root 'tools\git\cmd\git.exe'
if (-not (Test-Path $Git)) { $Git = 'git' }   # 回退到系统 git

function Step($m) { Write-Host "`n== $m" -ForegroundColor Cyan }
function RunGit([string[]]$a) {
  & $Git @a
  if ($LASTEXITCODE -ne 0) { throw "git $($a -join ' ') 失败（退出码 $LASTEXITCODE）" }
}

Write-Host '============================================================' -ForegroundColor Green
Write-Host '  发布到 GitHub' -ForegroundColor Green
Write-Host '============================================================' -ForegroundColor Green

if (-not (Test-Path $Git)) { throw "找不到 git，请先安装 Git 或设置 PATH" }
Write-Host "`n  git: $(& $Git --version)"
Push-Location $Root

try {
  # ---------------------------------------------------------------- 0. 从已有 remote 推导参数
  Step '0) 解析仓库信息'
  $remotes = @(& $Git remote)
  $existingUrl = ''
  if ($remotes -contains 'origin') {
    $existingUrl = (& $Git remote get-url origin) 2>$null
    Write-Host "  已有 origin: $existingUrl"
    if ($existingUrl -match 'github\.com[:/]([^/]+)/([^/.]+)') {
      if (-not $GitHubUser) { $GitHubUser = $Matches[1] }
      if (-not $RepoName)   { $RepoName   = $Matches[2] }
    }
  }
  if (-not $GitHubUser) { $GitHubUser = Read-Host '  请输入 GitHub 用户名' }
  if (-not $RepoName)   { $RepoName   = 'enterprise-rag-platform' }
  $url = "https://github.com/$GitHubUser/$RepoName.git"
  Write-Host "  目标仓库: $url" -ForegroundColor Green

  # ---------------------------------------------------------------- 1. 先查仓库是否存在
  Step '1) 检查远端仓库是否存在（避免推到一半才发现没有）'
  $repoExists = $false
  try {
    $api = Invoke-RestMethod -Uri "https://api.github.com/repos/$GitHubUser/$RepoName" `
             -Headers @{ 'User-Agent' = 'rag-platform-push' } -TimeoutSec 20
    $repoExists = $true
    Write-Host "  ✓ 仓库存在：$($api.full_name)（$($api.visibility)，默认分支 $($api.default_branch)）" -ForegroundColor Green
  } catch {
    $code = $_.Exception.Response.StatusCode.value__
    if ($code -eq 404) {
      Write-Host "  ✗ 仓库不存在（或私有且当前未认证）：$url" -ForegroundColor Red
      Write-Host ''
      Write-Host '    请先在浏览器里创建空仓库：' -ForegroundColor Yellow
      Write-Host "      1) 打开 https://github.com/new"
      Write-Host "      2) Repository name 填  $RepoName"
      Write-Host "      3) 选 Public（放简历建议公开）"
      Write-Host "      4) 【不要】勾 Add a README / .gitignore / License"
      Write-Host '      5) 点 Create repository，然后重新执行本脚本'
      Write-Host ''
      Write-Host '    另外请确认用户名拼写正确（GitHub 上不存在该用户时也会 404）：' -ForegroundColor Yellow
      Write-Host "      https://api.github.com/users/$GitHubUser"
      throw '远端仓库不存在，已中止（未做任何推送）'
    }
    Write-Host "  ⚠ 查询仓库时 API 返回 HTTP $code，跳过存在性检查继续尝试推送" -ForegroundColor Yellow
  }

  # ---------------------------------------------------------------- 2. 提交身份
  Step '2) 提交身份'
  if (-not $UserName)  { $UserName  = $GitHubUser }
  if (-not $UserEmail) { $UserEmail = (& $Git config user.email) 2>$null }
  if (-not $UserEmail) { $UserEmail = "$GitHubUser@users.noreply.github.com" }
  RunGit @('config', 'user.name',  $UserName)
  if ($UserEmail) { RunGit @('config', 'user.email', $UserEmail) }
  Write-Host "  $UserName <$UserEmail>"
  Write-Host '  （GitHub 是按【邮箱】认领提交的，用户名只是显示名）' -ForegroundColor DarkGray

  # ---------------------------------------------------------------- 3. 安全检查
  Step '3) 安全检查（确认密钥/依赖/产物没被跟踪）'
  $tracked = @(& $Git ls-files)
  $leak = @()
  if ($tracked.Count -gt 0) {
    $leak += $tracked | Where-Object { $_ -match '(^|/)\.env$' -and $_ -notmatch '\.example$' }
    $leak += $tracked | Where-Object { $_ -match 'node_modules/|/target/|^tools/|^logs/|\.bak$' }
  }
  if ($leak.Count -gt 0) {
    Write-Host '  ✗ 以下文件不该进仓库：' -ForegroundColor Red
    $leak | Select-Object -Unique | ForEach-Object { Write-Host "      $_" }
    throw '请先清理或加入 .gitignore，再执行 git rm --cached <文件>'
  }
  Write-Host "  ✓ $($tracked.Count) 个被跟踪文件里没有密钥/依赖/构建产物"

  # ---------------------------------------------------------------- 4. 提交
  Step '4) 提交本地改动'
  RunGit @('add', '-A')
  $status = @(& $Git status --porcelain)
  if ($status.Count -gt 0) {
    $msgFile = Join-Path $Root 'logs\commit-msg.txt'
    if (-not (Test-Path $msgFile)) {
      $msgFile = Join-Path $env:TEMP 'rag-commit-msg.txt'
      Set-Content -LiteralPath $msgFile -Value 'chore: 同步本地改动' -Encoding UTF8
    }
    RunGit @('commit', '-F', $msgFile)
    Write-Host '  已提交'
  } else {
    Write-Host '  没有未提交的改动'
  }
  Write-Host "  提交数: $((& $Git rev-list --count HEAD))"
  Write-Host "  作者: $((& $Git log -1 --format='%an <%ae>'))"

  # ---------------------------------------------------------------- 5. remote
  Step '5) 配置 remote'
  if ($remotes -contains 'origin') { RunGit @('remote', 'set-url', 'origin', $url) }
  else { RunGit @('remote', 'add', 'origin', $url) }
  Write-Host "  origin -> $url"

  # ---------------------------------------------------------------- 6. push
  Step '6) 推送'
  if ($Token) {
    $pushUrl = "https://$GitHubUser`:$Token@github.com/$GitHubUser/$RepoName.git"
    & $Git push $pushUrl "HEAD:refs/heads/$Branch"
    if ($LASTEXITCODE -ne 0) { throw '推送失败（检查 token 是否有 repo 权限）' }
    RunGit @('branch', "--set-upstream-to=origin/$Branch", $Branch)
  } else {
    Write-Host '  接下来会提示输入用户名与密码：' -ForegroundColor Yellow
    Write-Host '    用户名 = 你的 GitHub 用户名'
    Write-Host '    密码   = 你的 Personal Access Token（不是账号密码！）'
    Write-Host '  没有 token 就去 https://github.com/settings/tokens 建一个，勾 repo 权限。'
    RunGit @('push', '-u', 'origin', $Branch)
  }

  Step '完成'
  Write-Host "  仓库地址： https://github.com/$GitHubUser/$RepoName" -ForegroundColor Green
} finally {
  Pop-Location
}
