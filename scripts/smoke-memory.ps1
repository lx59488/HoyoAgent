# scripts/smoke-memory.ps1
# 用法: pwsh -NoProfile -File .\scripts\smoke-memory.ps1
#
# 2026-11-22 重写：网页会话不再有平台身份，签到类工具一律回「没有绑定」，
# 原来「第二问不调工具」的判据失效。现在只用纯对话验证记忆：
# 记住一个随机数 → 同会话能复述、新会话不能复述、窗口条数稳定。
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [int]$WindowProbe = 12
)

$ErrorActionPreference = 'Stop'

function Ask([string]$Question, [string]$Session) {
    $url = "$BaseUrl/agent?q=$([uri]::EscapeDataString($Question))"
    $p = @{ Uri = $url; TimeoutSec = 90 }
    if ($Session) { $p.Headers = @{ 'X-Session-Id' = $Session } }
    $r = Invoke-RestMethod @p
    if ($null -ne $r.code -and $r.code -ne 200) { throw "API code=$($r.code): $($r.message)" }
    $r.data.reply
}

$run = [guid]::NewGuid().ToString('N').Substring(0, 8)
$secret = Get-Random -Minimum 100000 -Maximum 999999
$rows = @()

# A. 正例：同一会话，第二问要能复述刚才的数字
$sa = "mem-$run-a"
$null = Ask "记住这个数字：$secret。只回复 OK。" $sa
$a = Ask '我刚才让你记住的数字是多少？只回复数字。' $sa
$rows += [pscustomobject]@{
    Case   = 'A 正例·同会话'
    Expect = "$secret"
    Actual = $a
    Hit    = ($a -match $secret)
}

# B. 反例：全新会话，不该知道这个数字
$b = Ask '我刚才让你记住的数字是多少？只回复数字。' "mem-$run-b"
$rows += [pscustomobject]@{
    Case   = 'B 反例·新会话'
    Expect = "不含 $secret"
    Actual = $b
    Hit    = -not ($b -match $secret)
}

# C. 隔离：另一个会话问同样的话，不该拿到 A 的上下文
$c = Ask '我刚才让你记住的数字是多少？只回复数字。' "mem-$run-c"
$rows += [pscustomobject]@{
    Case   = 'C 隔离·跨会话'
    Expect = "不含 $secret"
    Actual = $c
    Hit    = -not ($c -match $secret)
}

# D. 窗口：连问 N 轮，消息条数必须稳定在上限，不涨
$sd = "mem-$run-d"
for ($i = 1; $i -le $WindowProbe; $i++) {
    $null = Ask "第 $i 句：用一句话说明你在做什么" $sd
}
$view = Invoke-RestMethod "$BaseUrl/api/memory/$sd" -TimeoutSec 30
$count = $view.data.messages
$rows += [pscustomobject]@{
    Case   = "D 窗口·$WindowProbe 轮"
    Expect = '<= max-messages'
    Actual = "$count 条"
    Hit    = ($count -gt 0 -and $count -le 20)
}

$rows | Format-Table -AutoSize -Wrap
$pass = @($rows | Where-Object Hit).Count
Write-Host ("命中 {0}/{1}" -f $pass, $rows.Count) -ForegroundColor Cyan

Write-Host "`n── D 的窗口内容（看裁剪丢了什么就是看这里）──" -ForegroundColor Yellow
$view.data.content | ForEach-Object { Write-Host ("  {0,-22} {1}" -f $_.type, $_.text) }

if ($pass -lt $rows.Count) { exit 1 }