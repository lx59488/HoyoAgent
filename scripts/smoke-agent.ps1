# scripts/smoke-agent.ps1
# 用法: pwsh -File scripts\smoke-agent.ps1 [-BaseUrl http://localhost:8080]
param(
    [string]$BaseUrl = 'http://localhost:8080'
)

$ErrorActionPreference = 'Stop'

$cases = @(
    @{ q = '萝卜有哪些原神角色';       expect = 'getPlayerCharacters' },
    @{ q = '萝卜的雷电将军练得怎么样'; expect = 'getCharacterDetail'   },
    @{ q = '萝卜今天树脂还剩多少';     expect = 'getDailyNote'         },
    @{ q = '萝卜这期深渊打到哪了';     expect = 'getAbyssReport'       },
    @{ q = '今天天气怎么样';           expect = '(none)'               }
)

$rows = @()
$pass = 0

foreach ($c in $cases) {
    $url  = "$BaseUrl/agent?q=$([uri]::EscapeDataString($c.q))"
    $sw   = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $r      = Invoke-RestMethod $url -TimeoutSec 60
        $actual =
        if ($null -eq $r.toolsCalled)        { 'NO-FIELD' }
        elseif ($r.toolsCalled.Count -eq 0)  { '(none)' }
        else                                 { ($r.toolsCalled -join ',') }
    } catch {
        $actual = "ERROR: $($_.Exception.Message)"
    }
    $sw.Stop()

    $ok = $actual -like "*$($c.expect)*"
    if ($ok) { $pass++ }

    $rows += [pscustomobject]@{
        Q      = $c.q
        Expect = $c.expect
        Actual = $actual
        Ms     = [int]$sw.Elapsed.TotalMilliseconds
        Hit    = if ($ok) { 'OK' } else { 'MISS' }
    }
}

$rows | Format-Table -AutoSize -Wrap
Write-Host ("命中 {0}/{1}" -f $pass, $cases.Count) -ForegroundColor Cyan

if ($pass -lt $cases.Count) { exit 1 }