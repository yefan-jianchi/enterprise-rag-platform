# ============================================================
#  初始化数据库：建库建表 + 导入种子数据
#  幂等：schema.sql 里每张表都是 DROP IF EXISTS + CREATE，
#        可以反复执行；seed.sql 也是先删再加。
#
#  用法：
#     powershell -ExecutionPolicy Bypass -File .\scripts\init-db.ps1
#     powershell -ExecutionPolicy Bypass -File .\scripts\init-db.ps1 -MysqlPassword 你的密码
# ============================================================
param(
  [string]$MysqlUser = 'root',
  [string]$MysqlPassword = '123456',
  [string]$MysqlHost = '127.0.0.1',
  [int]$MysqlPort = 3306
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot

# ---- 找 mysql 客户端 ----
$cli = @(
  'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
  'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe',
  'C:\Program Files\MySQL\MySQL Server 8.1\bin\mysql.exe'
) | Where-Object { Test-Path $_ } | Select-Object -First 1

if (-not $cli) {
  $found = Get-Command mysql -ErrorAction SilentlyContinue
  if ($found) { $cli = $found.Source }
}
if (-not $cli) { throw '找不到 mysql 客户端，请把 MySQL 的 bin 目录加入 PATH' }

Write-Host "使用客户端：$cli" -ForegroundColor Cyan

# ---- 注意：mysql 客户端的 source 命令对中文路径不友好，
#      所以先把 SQL 复制到纯英文的临时目录再执行 ----
$tmpDir = Join-Path $env:TEMP ("rag-init-" + [Guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null

try {
  foreach ($pair in @(@('schema.sql', 'a.sql'), @('seed.sql', 'b.sql'))) {
    $src = Join-Path $Root ("db\" + $pair[0])
    $dst = Join-Path $tmpDir $pair[1]
    Copy-Item -LiteralPath $src -Destination $dst -Force
    Write-Host "执行 $($pair[0]) ..." -ForegroundColor Yellow
    # mysql 客户端会把密码警告写到 stderr；EAP=Stop 下会被当成致命错误中断脚本，
    # 所以这里临时降级为 Continue，并用 2>&1 合并后再过滤掉警告行。
    $ErrorActionPreference = 'Continue'
    $out = & $cli -h $MysqlHost -P $MysqlPort -u $MysqlUser "-p$MysqlPassword" `
      --default-character-set=utf8mb4 -e "source $($dst -replace '\\','/')" 2>&1 |
      Where-Object { $_ -notmatch 'Using a password on the command line' }
    if ($out) { $out | ForEach-Object { "  $_" } }
  }
} finally {
  Remove-Item -LiteralPath $tmpDir -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host ''
Write-Host '数据库初始化完成 ✔' -ForegroundColor Green
Write-Host '  库名     rag_platform'
Write-Host '  表数量   19'
Write-Host '  演示账号 demo / admin / admin123（首次启动后端时自动创建）'
