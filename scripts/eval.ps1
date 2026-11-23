# scripts/eval.ps1
# 用法: 先启动服务（.\mvnw.cmd spring-boot:run），再
#   pwsh -NoProfile -File .\scripts\eval.ps1
#
# 这是 C4 的「基线」，不是及格线：它记的是**当前分数**，用来判断下一次改提示词/改工具
# 是变好还是变坏。所以第一版允许有 FAIL —— 那正是要改的地方。
#
# 两条边界（别再追问"为什么测不到"）：
#   1. 走 /agent，没有平台身份 → 签到类工具一律回「没有绑定」，所以这里测的是
#      「模型选没选对工具 + 话怎么说」，**不测真实签到结果**（那要靠 QQ 真机 + P9 观察）。
#   2. tools=[] 的用例在只有 2 个工具时大多是恒真的（模型没有别的工具可误调），
#      所以标了 trivial，汇总时分开放——恒真通过不算证据（P3 的 DecoyTool 教训）。
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$Cases = 'scripts/eval/cases.jsonl',
    [string]$OutFile = 'target/eval-last.json'
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path $Cases)) { throw "找不到用例文件：$Cases（在仓库根目录运行）" }

function Ask([string]$Question, [string]$Session) {
    $url = "$BaseUrl/agent?q=$([uri]::EscapeDataString($Question))"
    $p = @{ Uri = $url; TimeoutSec = 120; Headers = @{ 'X-Session-Id' = $Session } }
    $r = Invoke-RestMethod @p
    if ($null -ne $r.code -and $r.code -ne 200) { throw "API code=$($r.code): $($r.message)" }
    $r.data
}

$run = [guid]::NewGuid().ToString('N').Substring(0, 6)
# 千万别把解析结果赋给 $cases / $Cases：$Cases 是 [string] 参数，PowerShell 变量名大小写不敏感，
# 赋值时会把数组强行转成一个字符串（20 条用例变 1 条、$case[0] 变成 Char）。踩过一次。
$items = Get-Content $Cases | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json }

$pass = 0
$trivial = 0
$rows = @()

foreach ($case in $items) {
    $reasons = @()
    $reply = ''
    $actual = @()

    try {
        $data = Ask $case.q "eval-$run-$($case.id)"
        $reply = [string]$data.reply
        # toolsCalled 是「名字(参数)」格式（ToolCallRecorder 故意把参数也带上），判定只比名字
        $actual = @($data.toolsCalled | Where-Object { $_ } | ForEach-Object { $_.Split('(')[0] })
    } catch {
        $reasons += "请求失败：$($_.Exception.Message)"
    }

    # 工具集合必须完全相等（多调一个也算错，沿用 P3 的判定口径）
    $expected = @($case.tools | Where-Object { $_ })
    if ((($expected | Sort-Object) -join ',') -ne (($actual | Sort-Object) -join ',')) {
        $reasons += "工具期望[$($expected -join ',')] 实际[$($actual -join ',')]"
    }

    foreach ($k in @($case.contains | Where-Object { $_ })) {
        if (-not $reply.Contains($k)) { $reasons += "缺少「$k」" }
    }
    foreach ($k in @($case.notContains | Where-Object { $_ })) {
        if ($reply.Contains($k)) { $reasons += "不该出现「$k」" }
    }
    if ($case.noMarkdown -and ($reply -match '\*\*|(?m)^#{1,6} |(?m)^\|.*\|')) {
        $reasons += '用了 markdown（QQ 不渲染）'
    }
    if ($case.maxChars -and $reply.Length -gt [int]$case.maxChars) {
        $reasons += "过长 $($reply.Length) > $($case.maxChars)"
    }

    $ok = ($reasons.Count -eq 0)
    if ($ok) { $pass++ }
    if ($case.trivial) { $trivial++ }

    $tag = if ($ok) { 'PASS' } else { 'FAIL' }
    $note = if ($case.trivial) { ' (恒真)' } else { '' }
    '{0} {1,-10} {2}{3}' -f $tag, $case.id, ($reasons -join ' / '), $note

    $rows += [pscustomobject]@{
        id = $case.id; ok = $ok; trivial = [bool]$case.trivial
        tools = ($actual -join ','); chars = $reply.Length
        reason = ($reasons -join ' / '); reply = $reply
    }
}

$total = @($items).Count
$effective = $total - $trivial
''
'==== 基线 {0}/{1} 通过（{2:P0}） · 有效 {3} 条，恒真 {4} 条不计入证据 ====' -f `
    $pass, $total, ($pass / [double]$total), $effective, $trivial
''

$rows | ConvertTo-Json -Depth 5 | Set-Content -Path $OutFile -Encoding utf8
"明细已写入 $OutFile（含每条原话，便于事后对照）"