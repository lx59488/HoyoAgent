# scripts/smoke-memory.ps1
# 用法: pwsh -NoProfile -File .\scripts\smoke-memory.ps1
#
# 判定思路：记忆的证据不是"它记得"，而是"它不用再查一次"。
# 所以 A / B 是一对差分用例，只跑 A 分不清"记得"和"恰好猜对"。
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
    [pscustomobject]@{
        Reply = $r.data.reply
        Tools = @($r.data.toolsCalled | Where-Object { $null -ne $_ })
    }
}

$run = [guid]::NewGuid().ToString('N').Substring(0, 8)
$rows = @()

# A. 正例：同一会话，第二问不该再调工具
$sa = "mem-$run-a"
$null = Ask '帮我签个到' $sa
$a = Ask '刚才那一次签到的结果是什么' $sa
$rows += [pscustomobject]@{
    Case = 'A 正例·同会话'; Expect = '不调工具'
    Actual = if ($a.Tools.Count) { $a.Tools -join ' & ' } else { '(none)' }
    Hit = ($a.Tools.Count -eq 0)
}

# B. 反例：不传会话，同一句话必须重新查
$b = Ask '刚才那一次签到的结果是什么' $null
$rows += [pscustomobject]@{
    Case = 'B 反例·无会话'; Expect = '必须调工具'
    Actual = if ($b.Tools.Count) { $b.Tools -join ' & ' } else { '(none)' }
    Hit = ($b.Tools.Count -gt 0)
}

# C. 隔离：甲签过，乙问同样的话，不该白拿甲的上下文
$sc = "mem-$run-c1"; $sd = "mem-$run-c2"
$null = Ask '帮我签个到' $sc
$c = Ask '刚才那一次签到的结果是什么' $sd
$rows += [pscustomobject]@{
    Case = 'C 隔离·跨会话'; Expect = '必须调工具'
    Actual = if ($c.Tools.Count) { $c.Tools -join ' & ' } else { '(none)' }
    Hit = ($c.Tools.Count -gt 0)
}

# D. 窗口：连问 N 轮，消息条数必须稳定在上限，不涨
$se = "mem-$run-d"
for ($i = 1; $i -le $WindowProbe; $i++) {
    $null = Ask "第 $i 句：今天签到了吗" $se
}
$view = Invoke-RestMethod "$BaseUrl/api/memory/$se" -TimeoutSec 30
$count = $view.data.messages
$rows += [pscustomobject]@{
    Case = "D 窗口·$WindowProbe 轮"; Expect = '<= max-messages'
    Actual = "$count 条"; Hit = ($count -gt 0 -and $count -le 20)
}

$rows | Format-Table -AutoSize
$pass = @($rows | Where-Object Hit).Count
Write-Host ("命中 {0}/{1}" -f $pass, $rows.Count) -ForegroundColor Cyan

Write-Host "`n── D 的窗口内容（看裁剪丢了什么就是看这里）──" -ForegroundColor Yellow
$view.data.content | ForEach-Object { Write-Host ("  {0,-22} {1}" -f $_.type, $_.text) }

if ($pass -lt $rows.Count) { exit 1 }