# ============================================================
#  停止本地启动的服务（AI 服务 / 后端 / 前端）
#  用法： powershell -ExecutionPolicy Bypass -File .\scripts\stop-dev.ps1
# ============================================================
$ErrorActionPreference = 'Continue'

$targets = @(
  @{ Port = 8001; Name = 'AI 服务 (uvicorn)' },
  @{ Port = 8080; Name = '后端 (Spring Boot)' },
  @{ Port = 5173; Name = '前端 (Vite)' }
)

foreach ($t in $targets) {
  $conns = Get-NetTCPConnection -LocalPort $t.Port -State Listen -ErrorAction SilentlyContinue
  if (-not $conns) {
    Write-Host "  $($t.Name) 未在运行 (端口 $($t.Port))" -ForegroundColor DarkGray
    continue
  }
  foreach ($c in $conns) {
    $proc = Get-Process -Id $c.OwningProcess -ErrorAction SilentlyContinue
    if ($proc) {
      Write-Host "  停止 $($t.Name)：PID $($proc.Id) ($($proc.ProcessName))" -ForegroundColor Yellow
      Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    }
  }
}

# Vite / Maven 会派生子进程，按命令行兜底清理
Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
  Where-Object { $_.CommandLine -match 'uvicorn app\.main:app|spring-boot:run|vite' } |
  ForEach-Object {
    Write-Host "  兜底清理：PID $($_.ProcessId)" -ForegroundColor DarkYellow
    Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
  }

Write-Host ''
Write-Host '已停止。' -ForegroundColor Green
