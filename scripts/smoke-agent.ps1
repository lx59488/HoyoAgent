# scripts/smoke-agent.ps1
# 用法: pwsh -NoProfile -File .\scripts\smoke-agent.ps1 [-OutFile round1.md]
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$OutFile
)

$ErrorActionPreference = 'Stop'

# expect 是「工具名(参数)」的集合；空数组 = 期望不调用任何工具
$cases = @(
    @{ id = 1;  q = '萝卜有哪些原神角色';       expect = @('getPlayerCharacters(萝卜)') },
    @{ id = 2;  q = '萝卜的雷电将军练得怎么样'; expect = @('getCharacterDetail(雷电将军)') },
    @{ id = 3;  q = '萝卜今天树脂还剩多少';     expect = @('getDailyNote(萝卜)') },
    @{ id = 4;  q = '萝卜这期深渊打到哪了';     expect = @('getAbyssReport(萝卜)') },
    @{ id = 5;  q = '小明树脂满了吗';           expect = @('getDailyNote(小明)') },
    @{ id = 6;  q = '查查小明的深渊';           expect = @('getAbyssReport(小明)') },
    @{ id = 7;  q = '萝卜的胡桃多少命';         expect = @('getCharacterDetail(胡桃)') },
    @{ id = 8;  q = '今天天气怎么样';           expect = @() },
    @{ id = 9;  q = '萝卜昨天签到成功了吗';     expect = @() },
    @{ id = 10; q = '小明和萝卜谁的角色多';     expect = @('getPlayerCharacters(小明)', 'getPlayerCharacters(萝卜)') }
)

$rows = @()
$pass = 0

foreach ($c in $cases) {
    $url = "$BaseUrl/agent?q=$([uri]::EscapeDataString($c.q))"
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $r = Invoke-RestMethod $url -TimeoutSec 90
        $raw = $r.toolsCalled
        $actual =
        if ($null -eq $raw)            { @('NO-FIELD') }
        elseif (@($raw).Count -eq 0)   { @() }
        else                           { @($raw | ForEach-Object { $_.ToString().Trim() } | Sort-Object) }
    } catch {
        $actual = @("ERROR: $($_.Exception.Message)")
    }
    $sw.Stop()

    $expect = @($c.expect | Sort-Object)
    $hit = (($actual -join '|') -eq ($expect -join '|'))
    if ($hit) { $pass++ }

    $rows += [pscustomobject]@{
        Id     = $c.id
        Q      = $c.q
        Expect = if ($c.expect.Count) { $c.expect -join ' & ' } else { '(none)' }
        Actual = if ($actual.Count)   { $actual -join ' & ' }   else { '(none)' }
        Ms     = [int]$sw.Elapsed.TotalMilliseconds
        Hit    = if ($hit) { 'OK' } else { 'MISS' }
    }
}

$rows | Format-Table -AutoSize -Wrap
Write-Host ("命中 {0}/{1}" -f $pass, $cases.Count) -ForegroundColor Cyan

if ($OutFile) {
    $md = @('| # | 提问 | 期望 | 实际 | 命中 |', '|---|---|---|---|---|')
    foreach ($row in $rows) { $md += "| $($row.Id) | $($row.Q) | $($row.Expect) | $($row.Actual) | $($row.Hit) |" }
    $md | Set-Content -Path $OutFile -Encoding UTF8
    Write-Host "结果已写入 $OutFile"
}

if ($pass -lt $cases.Count) { exit 1 }