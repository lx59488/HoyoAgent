#!/usr/bin/env pwsh
<#
.SYNOPSIS
  扫码绑定米游社账号：建码 -> 存二维码 PNG -> 轮询到 BOUND。

.DESCRIPTION
  需要先启动服务：.\mvnw.cmd -B -ntp spring-boot:run
  成功后凭据写入 ~/.hoyo/credential.properties（含 stoken），重启服务仍有效。

.EXAMPLE
  pwsh -NoProfile -File .\scripts\login-qr.ps1
#>
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$OutFile = (Join-Path $env:TEMP 'hoyo-login-qr.png'),
    [int]$TimeoutSeconds = 240
)

$ErrorActionPreference = 'Stop'

Write-Host "创建登录二维码..."
$start = Invoke-RestMethod -Uri "$BaseUrl/api/auth/qr" -Method Post
$ticket = $start.data.ticket

Invoke-WebRequest -Uri "$BaseUrl/api/auth/qr/$ticket/image" -OutFile $OutFile | Out-Null
Write-Host "ticket : $ticket"
Write-Host "二维码 : $OutFile"
Write-Host "用米游社 App 扫码并确认（3 分钟内有效）"

# 自动弹出看图程序；不想弹可注释掉
Start-Process $OutFile

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$lastState = ''

while ((Get-Date) -lt $deadline) {
    $status = (Invoke-RestMethod -Uri "$BaseUrl/api/auth/qr/$ticket").data
    if ($status.state -ne $lastState) {
        Write-Host ("state: {0} {1}" -f $status.state, $status.message)
        $lastState = $status.state
    }

    switch ($status.state) {
        'BOUND' {
            $status | ConvertTo-Json -Depth 3
            Write-Host "登录成功：stoken/ltoken/cookie_token 已写入 ~/.hoyo/credential.properties"
            exit 0
        }
        'EXPIRED' {
            Write-Host "二维码已过期，重跑一次脚本即可"
            exit 2
        }
        'FAILED' {
            Write-Host ("失败：{0}" -f $status.message)
            exit 3
        }
    }

    Start-Sleep -Seconds 2
}

Write-Host "等待超时（$TimeoutSeconds 秒），二维码可能已过期"
exit 4
