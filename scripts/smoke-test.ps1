# ============================================================
#  全链路冒烟验收：前端 → 后端 → AI 服务 → MySQL
#  覆盖：登录鉴权 / 知识库 / 文档上传+索引流水线 / RAG 流式对话 /
#        智能客服 Tool Calling / 审计与成本
#
#  用法（后端起来后执行）：
#     powershell -ExecutionPolicy Bypass -File .\scripts\smoke-test.ps1
#     powershell -ExecutionPolicy Bypass -File .\scripts\smoke-test.ps1 -Base http://localhost:8080
#
#  ⚠ 本脚本踩过并规避的两个 PowerShell 5.1 陷阱（注释保留，避免以后重犯）：
#    1) Invoke-RestMethod 对 Content-Type 没带 charset 的 UTF-8 响应会按 Latin-1 解，
#       中文全变乱码（äº§å...）。这里统一改成 Invoke-WebRequest + 手动 UTF-8 解码。
#    2) 原生程序（curl.exe）的 stdout 经 PS 管道会被按控制台编码（GBK）解码，
#       中文同样乱码。这里统一让它写文件再按 UTF-8 读。
# ============================================================
param(
  [string]$Base = 'http://localhost:8080',
  [string]$AiBase = 'http://localhost:8001',
  [string]$TenantCode = 'demo',
  [string]$Username = 'admin',
  [string]$Password = 'admin123',
  [int]$IndexTimeoutSec = 90
)

$ErrorActionPreference = 'Continue'
$script:Pass = 0
$script:Fail = 0
$script:Rows = @()
$script:Tmp = Join-Path $env:TEMP ('ragsmoke_' + [Guid]::NewGuid().ToString('N').Substring(0, 6))
New-Item -ItemType Directory -Force -Path $script:Tmp | Out-Null

function Check($name, $ok, $detail) {
  if ($ok) { $script:Pass++; $color = 'Green'; $mark = 'PASS' } else { $script:Fail++; $color = 'Red'; $mark = 'FAIL' }
  Write-Host ("  [{0}] {1,-32} {2}" -f $mark, $name, $detail) -ForegroundColor $color
  $script:Rows += [pscustomobject]@{ 项目 = $name; 结果 = $mark; 说明 = $detail }
}

# ---- 统一 UTF-8 解码的 HTTP 调用 ----
function Invoke-Api($method, $path, $body, $token) {
  $headers = @{}
  if ($token) { $headers['Authorization'] = "Bearer $token" }
  $reqArgs = @{ Method = $method; Uri = "$Base$path"; Headers = $headers
                TimeoutSec = 90; UseBasicParsing = $true }
  if ($body) {
    $reqArgs['ContentType'] = 'application/json; charset=utf-8'
    $reqArgs['Body'] = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 8))
  }
  try {
    $resp = Invoke-WebRequest @reqArgs
    $text = [Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())
    return ($text | ConvertFrom-Json)
  } catch {
    # 4xx/5xx 时把错误响应体也解出来，便于看到后端的中文错误信息
    try {
      $stream = $_.Exception.Response.GetResponseStream()
      $reader = New-Object System.IO.StreamReader($stream, [Text.Encoding]::UTF8)
      return ($reader.ReadToEnd() | ConvertFrom-Json)
    } catch {
      return [pscustomobject]@{ code = -1; message = $_.Exception.Message; data = $null }
    }
  }
}

# ---- 原生 curl 输出写文件后按 UTF-8 读，避免 GBK 乱码 ----
function Invoke-CurlText([string[]]$curlArgs) {
  $outFile = Join-Path $script:Tmp ('curl_' + [Guid]::NewGuid().ToString('N').Substring(0, 6) + '.txt')
  $all = @('-s') + $curlArgs + @('-o', $outFile)
  & curl.exe @all 2>$null | Out-Null
  if (Test-Path $outFile) {
    $txt = [System.IO.File]::ReadAllText($outFile, [Text.Encoding]::UTF8)
    Remove-Item $outFile -Force -ErrorAction SilentlyContinue
    return $txt
  }
  return ''
}

Write-Host ''
Write-Host '============================================================' -ForegroundColor Cyan
Write-Host '  企业级 RAG 平台 · 全链路冒烟验收' -ForegroundColor Cyan
Write-Host "  后端 $Base   AI 服务 $AiBase" -ForegroundColor Cyan
Write-Host '============================================================' -ForegroundColor Cyan

# ---------------------------------------------------------------- 0. 依赖服务
Write-Host "`n[0] 依赖服务健康检查" -ForegroundColor Yellow
try {
  $ai = Invoke-RestMethod "$AiBase/health" -TimeoutSec 5
  Check 'AI 服务 /health' ($ai.status -eq 'ok') ("faiss={0} langchain={1} embed={2} llm={3}" -f `
    $ai.faiss_available, $ai.langchain.available, $ai.embed_provider, $ai.llm_provider)
} catch { Check 'AI 服务 /health' $false "不可达：$($_.Exception.Message)" }

$mysqlOk = [bool](Get-NetTCPConnection -LocalPort 3306 -State Listen -ErrorAction SilentlyContinue)
Check 'MySQL 3306' $mysqlOk $(if ($mysqlOk) { '监听中' } else { '未监听' })

# ---------------------------------------------------------------- 1. 登录
Write-Host "`n[1] 认证与权限" -ForegroundColor Yellow
$login = Invoke-Api 'POST' '/api/auth/login' @{ tenantCode = $TenantCode; username = $Username; password = $Password }
$token = $null
if ($login.code -eq 0 -and $login.data.token) {
  $token = $login.data.token
  Check '登录拿 token' $true ("用户={0} 租户={1}(id={2}) 角色={3} 权限{4}项" -f `
    $login.data.user.username, $login.data.user.tenantCode, $login.data.user.tenantId,
    ($login.data.user.roles -join ','), $login.data.user.permissions.Count)
} else {
  Check '登录拿 token' $false "code=$($login.code) msg=$($login.message)"
}
if (-not $token) {
  Write-Host "`n登录失败，后续用例无法执行。" -ForegroundColor Red
  Remove-Item $script:Tmp -Recurse -Force -ErrorAction SilentlyContinue
  exit 1
}
$me = Invoke-Api 'GET' '/api/auth/me' $null $token
Check 'GET /api/auth/me' ($me.code -eq 0) "username=$($me.data.username)"

# ---------------------------------------------------------------- 2. 知识库
Write-Host "`n[2] 知识库管理" -ForegroundColor Yellow
$kbs = Invoke-Api 'GET' '/api/kb/list' $null $token
Check 'GET /api/kb/list' ($kbs.code -eq 0 -and $kbs.data.Count -ge 1) "共 $($kbs.data.Count) 个知识库"
$kbId = if ($kbs.data.Count -gt 0) { $kbs.data[0].id } else { $null }
if ($kbId) {
  $kb = $kbs.data | Where-Object { $_.id -eq $kbId } | Select-Object -First 1
  Write-Host ("        使用知识库：#{0} {1}（文档 {2} / 分块 {3}）" -f $kb.id, $kb.kbName, $kb.docCount, $kb.chunkCount) -ForegroundColor DarkGray
}

# ---------------------------------------------------------------- 3. 文档
Write-Host "`n[3] 文档上传与索引流水线" -ForegroundColor Yellow
$sample = Join-Path (Split-Path -Parent $PSScriptRoot) 'ai-service\data\samples\宿舍与后勤服务.docx'
if (-not (Test-Path $sample)) { $sample = Join-Path (Split-Path -Parent $PSScriptRoot) 'ai-service\data\samples\产品与售后手册.md' }
$docId = $null
if ($kbId -and (Test-Path $sample)) {
  $uTxt = Invoke-CurlText @('-X','POST',"$Base/api/doc/upload",'-H',"Authorization: Bearer $token",
                            '-F',"file=@$sample",'-F',"kbId=$kbId")
  $upJson = $uTxt | ConvertFrom-Json
  if ($upJson.code -eq 0) {
    # ★ 注意：上传接口返回 KbDtos.DocListItem，文档主键字段名是 id（不是 docId）
    $docId = $upJson.data.id
    Check 'POST /api/doc/upload' ($null -ne $docId) "docId=$docId 名称=$($upJson.data.docName) 状态=$($upJson.data.status)"
  } else {
    Check 'POST /api/doc/upload' $false "code=$($upJson.code) msg=$($upJson.message)"
  }
} else { Check 'POST /api/doc/upload' $false "缺少知识库或样例文件" }

if ($docId) {
  $deadline = (Get-Date).AddSeconds($IndexTimeoutSec)
  $status = 'PENDING'; $chunkCount = 0; $errMsg = ''
  while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 2
    $list = Invoke-Api 'GET' "/api/doc/list?kbId=$kbId" $null $token
    $d = $list.data | Where-Object { $_.id -eq $docId } | Select-Object -First 1
    if ($d) { $status = $d.status; $chunkCount = $d.chunkCount; $errMsg = $d.errorMsg }
    if ($status -in 'INDEXED', 'FAILED') { break }
  }
  Check '文档流水线到 INDEXED' ($status -eq 'INDEXED') "状态=$status 分块=$chunkCount $(if ($errMsg) { "错误=$errMsg" })"

  $chunks = Invoke-Api 'GET' "/api/doc/$docId/chunks" $null $token
  $cnt = if ($chunks.code -eq 0 -and $chunks.data) { @($chunks.data).Count } else { 0 }
  $withPage = if ($cnt -gt 0) { @($chunks.data | Where-Object { $_.pageNo -ne $null }).Count } else { 0 }
  Check '分块落库且带页码' ($cnt -gt 0 -and $withPage -gt 0) "$withPage/$cnt 块有页码"

  # AI 侧索引是否真的建起来了（这一步最能说明「检索真的可用」）
  try {
    $st = Invoke-RestMethod "$AiBase/api/v1/kb/$kbId/stats" -TimeoutSec 5
    Check 'AI 侧向量索引已建立' ($st.vectors -gt 0) "vectors=$($st.vectors) bm25=$($st.bm25_docs) dim=$($st.embed_dim)"
  } catch { Check 'AI 侧向量索引已建立' $false "查不到索引：$($_.Exception.Message)" }
}

# ---------------------------------------------------------------- 4. RAG 流式对话
Write-Host "`n[4] RAG 流式对话（SSE）" -ForegroundColor Yellow
$sess = Invoke-Api 'POST' '/api/chat/session' @{ kbId = $kbId; title = '冒烟测试会话'; providerCode = 'mock' } $token
$sessUuid = if ($sess.code -eq 0) { $sess.data.sessionUuid } else { $null }
Check 'POST /api/chat/session' ($null -ne $sessUuid) "sessionUuid=$sessUuid"

if ($sessUuid) {
  $pf = Join-Path $script:Tmp 'chat.json'
  $payload = @{ sessionUuid = $sessUuid; question = '宿舍用电超出部分怎么算？'; kbId = $kbId; providerCode = 'mock' } | ConvertTo-Json
  [System.IO.File]::WriteAllText($pf, $payload, (New-Object Text.UTF8Encoding($false)))
  $sseText = Invoke-CurlText @('-N','-X','POST',"$Base/api/chat/stream",'-H',"Authorization: Bearer $token",
                               '-H','Content-Type: application/json','--data-binary',"@$pf")
  $hasMeta = $sseText -match 'event:\s*meta'
  $hasDelta = $sseText -match 'event:\s*delta'
  $hasDone = $sseText -match 'event:\s*done'
  Check 'SSE meta 事件（引用先行）' $hasMeta $(if ($hasMeta) { '收到' } else { "片段：" + $sseText.Substring(0,[Math]::Min(120,$sseText.Length)) })
  Check 'SSE delta 事件（逐字流式）' $hasDelta ("收到 {0} 个 delta" -f ([regex]::Matches($sseText, 'event:\s*delta')).Count)
  Check 'SSE done 事件（收尾统计）' $hasDone $(if ($hasDone) { '收到' } else { '未收到' })

  $citeCount = 0
  $m = [regex]::Match($sseText, 'event:\s*meta\s*data:\s*(\{.*?\})\s*\n')
  if ($m.Success) {
    $metaObj = $m.Groups[1].Value | ConvertFrom-Json
    $citeCount = @($metaObj.citations).Count
  }
  Check '引用溯源有内容' ($citeCount -gt 0) "$citeCount 条引用"

  $msgs = Invoke-Api 'GET' "/api/chat/messages?sessionUuid=$sessUuid" $null $token
  $mc = if ($msgs.code -eq 0 -and $msgs.data) { @($msgs.data).Count } else { 0 }
  Check '对话落库（含引用 JSON）' ($mc -ge 2) "$mc 条消息"
}

# ---------------------------------------------------------------- 5. 智能客服
Write-Host "`n[5] 智能客服 Tool Calling" -ForegroundColor Yellow
$orders = Invoke-Api 'GET' '/api/cs/orders' $null $token
$oc = if ($orders.code -eq 0 -and $orders.data) { @($orders.data).Count } else { 0 }
$firstOrder = ''
if ($oc -gt 0) { $firstOrder = "$($orders.data[0].orderNo) $($orders.data[0].statusText)" }
Check 'GET /api/cs/orders' ($oc -gt 0) "查到 $oc 条订单 $(if ($oc -gt 0) { "（首条 $firstOrder）" })"
$tickets = Invoke-Api 'GET' '/api/cs/tickets' $null $token
$tc = if ($tickets.code -eq 0 -and $tickets.data) { @($tickets.data).Count } else { 0 }
Check 'GET /api/cs/tickets' ($tc -gt 0) "查到 $tc 条工单"

$orderNo = if ($oc -gt 0) { $orders.data[0].orderNo } else { 'SO20261016001' }
$pf2 = Join-Path $script:Tmp 'cs.json'
$csBody = @{ sessionUuid = [guid]::NewGuid().ToString(); question = "帮我查一下订单 $orderNo 到哪了"; providerCode = 'mock' } | ConvertTo-Json
[System.IO.File]::WriteAllText($pf2, $csBody, (New-Object Text.UTF8Encoding($false)))
$csText = Invoke-CurlText @('-N','-X','POST',"$Base/api/cs/stream",'-H',"Authorization: Bearer $token",
                            '-H','Content-Type: application/json','--data-binary',"@$pf2")
Check '客服 SSE 流式返回' ($csText -match 'event:') "收到 $(([regex]::Matches($csText, 'event:')).Count) 个事件"
$hitOrder = $csText -match [regex]::Escape($orderNo)
Check 'Tool Calling 命中订单' $hitOrder $(if ($hitOrder) { "回答里出现订单号 $orderNo" } else { '未见订单号（模型未触发工具）' })

$handoff = Invoke-Api 'POST' '/api/cs/handoff' @{ sessionUuid = $sessUuid; reason = '冒烟测试：模型置信度低' } $token
Check 'POST /api/cs/handoff' ($handoff.code -eq 0) "code=$($handoff.code) $(if ($handoff.code -ne 0) { $handoff.message })"

# ---------------------------------------------------------------- 6. 审计与成本
Write-Host "`n[6] 审计与成本统计" -ForegroundColor Yellow
$audit = Invoke-Api 'GET' '/api/admin/audit?page=1&size=10' $null $token
Check 'GET /api/admin/audit' ($audit.code -eq 0) "共 $($audit.data.total) 条审计日志"
$cost = Invoke-Api 'GET' '/api/admin/cost?days=7' $null $token
# 注意：只判 code==0 太宽松——曾经出现过「接口成功但恒返回 0」的 JPA 映射 bug，
# 所以这里必须断言真实有数据，否则成本审计等于没做。
$costOk = ($cost.code -eq 0 -and [long]$cost.data.totalCalls -gt 0 -and [long]$cost.data.totalTokens -gt 0)
Check 'GET /api/admin/cost（成本真记账）' $costOk ("总 token={0} 成本=￥{1} 调用={2} 次" -f $cost.data.totalTokens, $cost.data.totalCost, $cost.data.totalCalls)
$models = Invoke-Api 'GET' '/api/admin/models' $null $token
Check 'GET /api/admin/models' ($models.code -eq 0) "$(@($models.data).Count) 个模型提供方"
$prompts = Invoke-Api 'GET' '/api/admin/prompts' $null $token
Check 'GET /api/admin/prompts' ($prompts.code -eq 0) "$(@($prompts.data).Count) 个 Prompt 模板"

# ---------------------------------------------------------------- 汇总
Remove-Item $script:Tmp -Recurse -Force -ErrorAction SilentlyContinue
Write-Host ''
Write-Host '============================================================' -ForegroundColor Cyan
Write-Host ("  验收结果：通过 {0} 项，失败 {1} 项" -f $script:Pass, $script:Fail) -ForegroundColor $(if ($script:Fail -eq 0) { 'Green' } else { 'Yellow' })
Write-Host '============================================================' -ForegroundColor Cyan
$script:Rows | Format-Table -AutoSize | Out-String | Write-Host

if ($script:Fail -gt 0) { exit 1 }
