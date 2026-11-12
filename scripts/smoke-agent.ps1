# scripts/smoke-agent.ps1
# 用法: pwsh -NoProfile -File .\scripts\smoke-agent.ps1 [-OutFile round3.md]
#
# ── 2026-11-07 重写说明（旧用例的去向）────────────────────────────────────
# 本脚本原名下是 P3 的 10 条用例，前提是「4 个工具 + 按玩家昵称查人」。
# P6 接真数据后这个前提被推翻了：真接口的身份来自 cookie，一个进程只代表一个
# 绑定账号，「查谁」不再是参数。所以不是用例过期，是**抽象永久错了**——
# 不存在「架构回退后又能复用」的场景。
#
# 旧 10 条的判定表仍在 docs/ROADMAP.md（P3）与 scripts/round1.md / round2.md，
# 那是需要长期保留的「结果」；脚本本身是「工具」，跑不了就没有价值，故直接覆盖。
# 要取回旧脚本: git log -p -- scripts/smoke-agent.ps1
#
# ── 能力边界测试（第 7~8 条）──────────────────────────────────────────────
# 默认 profile 下系统里没有树脂/深渊工具，这两条「不调用」是恒真的，
# 证明不了什么。要做成真测试，用诱饵 profile 启动：
#   .\mvnw.cmd -B -ntp spring-boot:run "-Dspring-boot.run.profiles=decoy"
# 此时系统里存在 3 个描述很诱人的假工具（DecoyTool），而 SystemMessage 明令禁止
# 使用它们。模型若调用了，toolsCalled 里会出现 decoy 名字 —— 那才是要抓的失败。
#
# 第 6 条自 P9 起从「能力外」变成正例：CheckinTool 已经存在，签到会真的调用它。
# expect 是「工具名(参数)」的集合；空数组 = 期望不调用任何工具。
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$OutFile
)

$ErrorActionPreference = 'Stop'

$cases = @(
    @{ id = 1; cat = '正例·列表';      q = '我有哪些原神角色';        expect = @('getPlayerCharacters()') },
    @{ id = 2; cat = '边界·口语';      q = '谁满级了';                expect = @('getPlayerCharacters()') },
    @{ id = 3; cat = '正例·单角色';    q = '安柏多少级了';            expect = @('getPlayerCharacters()') },
    @{ id = 4; cat = '负例·无关';      q = '今天天气怎么样';          expect = @() },
    @{ id = 5; cat = '负例·身份不可指定'; q = '查一下小明的原神角色';  expect = @() },
    @{ id = 6; cat = '正例·签到';      q = '帮我签个到';              expect = @('checkinToday()') },
    @{ id = 7; cat = '负例·能力外';    q = '我树脂还剩多少';          expect = @() },
    @{ id = 8; cat = '负例·能力外';    q = '我深渊打到哪了';          expect = @() }
)

$rows = @()
$misses = @()
$pass = 0

foreach ($c in $cases) {
    $url = "$BaseUrl/agent?q=$([uri]::EscapeDataString($c.q))"
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $reply = ''
    try {
        $r = Invoke-RestMethod $url -TimeoutSec 90
        # 统一封皮：{code, message, data:{reply, toolsCalled}}
        if ($null -ne $r.code -and $r.code -ne 200) {
            throw "API code=$($r.code): $($r.message)"
        }
        $reply = $r.data.reply
        $raw = $r.data.toolsCalled
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
        Cat    = $c.cat
        Q      = $c.q
        Expect = if ($c.expect.Count) { $c.expect -join ' & ' } else { '(none)' }
        Actual = if ($actual.Count)   { $actual -join ' & ' }   else { '(none)' }
        Ms     = [int]$sw.Elapsed.TotalMilliseconds
        Hit    = if ($hit) { 'OK' } else { 'MISS' }
    }

    # MISS 时把模型的原话留档：判定错了和模型错了是两回事
    if (-not $hit) {
        $misses += [pscustomobject]@{ Id = $c.id; Q = $c.q; Reply = $reply }
    }
}

$rows | Format-Table -AutoSize -Wrap
Write-Host ("命中 {0}/{1}" -f $pass, $cases.Count) -ForegroundColor Cyan

if ($misses.Count) {
    Write-Host "`n── 未命中的原始回复 ──" -ForegroundColor Yellow
    foreach ($m in $misses) {
        Write-Host ("[{0}] {1}" -f $m.Id, $m.Q) -ForegroundColor Yellow
        Write-Host ("    {0}" -f $m.Reply)
    }
}

if ($OutFile) {
    $md = @('| # | 类别 | 提问 | 期望 | 实际 | 命中 |', '|---|---|---|---|---|---|')
    foreach ($row in $rows) { $md += "| $($row.Id) | $($row.Cat) | $($row.Q) | $($row.Expect) | $($row.Actual) | $($row.Hit) |" }
    $md | Set-Content -Path $OutFile -Encoding UTF8
    Write-Host "结果已写入 $OutFile"
}

if ($pass -lt $cases.Count) { exit 1 }
