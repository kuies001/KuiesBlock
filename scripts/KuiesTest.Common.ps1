#requires -version 5.1
<#
    KuiesBlock 真機測試：共用工具函式。

    隱私原則（依專案規則）：
      * 只收集判定問題所需的最小資料，不上傳任何內容。
      * 預設不抓取完整 logcat，而是用 App 相關 tag 過濾；要完整內容需明示 -FullLogcat。
      * 不記錄 DNS 查詢內容或封包內容。
#>
Set-StrictMode -Version Latest

$script:KuiesAdb = 'C:\adb\platform-tools\adb.exe'
$script:KuiesSerial = $null
$script:KuiesRunDir = $null
$script:KuiesChecks = New-Object System.Collections.ArrayList
$script:KuiesAppId = 'tw.kuies.block'
$script:KuiesTagFilter = 'Kuies|DnsDiagnostics|AndroidRuntime|ActivityManager'
# 只有在診斷網路行為時才額外抓取的系統 tag（只留在本機，不上傳）。
$script:KuiesSystemTagFilter = 'ConnectivityService|DnsManager|NetworkMonitor|Vpn|WifiClientModeImpl|WifiServiceImpl|WifiConfigManager'

function Initialize-KuiesTest {
    param([string]$AdbPath, [string]$Serial)

    if ($AdbPath) { $script:KuiesAdb = $AdbPath }
    if (-not (Test-Path -LiteralPath $script:KuiesAdb)) { throw "找不到 adb：$($script:KuiesAdb)" }

    $deviceLines = @(& $script:KuiesAdb devices -l 2>&1 |
        Select-Object -Skip 1 |
        Where-Object { $_ -match '\sdevice(\s|$)' })
    if ($deviceLines.Count -eq 0) { throw 'adb devices 為空：沒有已授權的裝置（請確認 USB 偵錯與授權對話框）。' }

    $serials = @($deviceLines | ForEach-Object { ($_ -split '\s+')[0] })
    if ($Serial) {
        $script:KuiesSerial = $Serial
    } elseif ($serials.Count -eq 1) {
        $script:KuiesSerial = $serials[0]
    } else {
        # 無線偵錯與 USB 同時存在時，優先走 USB：只有 USB 不會因為切換網路而斷線。
        $usbSerials = @($serials | Where-Object { $_ -notmatch ':\d+$' })
        if ($usbSerials.Count -eq 1) {
            $script:KuiesSerial = $usbSerials[0]
            Write-Host "偵測到多個連線，改用 USB：$($script:KuiesSerial)" -ForegroundColor Yellow
        } else {
            throw "偵測到多台裝置，請用 -Serial 指定：$($serials -join ' ; ')"
        }
    }

    if ($deviceLines -notmatch [regex]::Escape($script:KuiesSerial)) {
        throw "指定的序號 $($script:KuiesSerial) 不在裝置清單中：$($deviceLines -join ' ; ')"
    }
    return $script:KuiesSerial
}

function Get-KuiesTransportKind {
    # 無線偵錯的序號長成 host:port；USB 的是硬體序號。
    if ($script:KuiesSerial -match ':\d+$') { return 'wireless' }
    return 'usb'
}

function Invoke-KuiesAdb {
    param(
        [Parameter(Mandatory)][string[]]$Command,
        [switch]$AllowFailure
    )
    $full = @()
    if ($script:KuiesSerial) { $full += @('-s', $script:KuiesSerial) }
    $full += $Command

    # 原生命令寫 stderr 時，PowerShell 5.1 在 ErrorActionPreference='Stop' 下會丟出
    # NativeCommandError。這裡只關心輸出與 exit code，所以呼叫期間強制 Continue。
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $raw = & $script:KuiesAdb @full 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }
    $lines = @($raw | ForEach-Object { if ($null -eq $_) { '' } else { $_.ToString().TrimEnd() } })
    $result = [pscustomobject]@{
        ExitCode = $code
        Lines    = $lines
        Text     = ($lines -join "`n")
        Command  = 'adb ' + ($full -join ' ')
    }
    if ($code -ne 0 -and -not $AllowFailure) {
        throw "$($result.Command) 失敗（exit $code）：`n$($result.Text)"
    }
    return $result
}

function Invoke-KuiesShell {
    param([Parameter(Mandatory)][string]$Command, [switch]$AllowFailure)
    return Invoke-KuiesAdb -Command @('shell', $Command) -AllowFailure:$AllowFailure
}

function Invoke-KuiesExecOut {
    # exec-out 不會經過 shell 的換行轉換，適合讀 XML 等結構化輸出。
    param([Parameter(Mandatory)][string[]]$Command, [switch]$AllowFailure)
    return Invoke-KuiesAdb -Command (@('exec-out') + $Command) -AllowFailure:$AllowFailure
}

function New-KuiesRunDirectory {
    param([Parameter(Mandatory)][string]$Scenario, [string]$ProjectRoot)

    if (-not $ProjectRoot) { $ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path }
    $root = Join-Path $ProjectRoot 'artifacts'
    if (-not (Test-Path -LiteralPath $root)) { New-Item -ItemType Directory -Path $root | Out-Null }

    $name = 'run-{0}-{1}' -f (Get-Date -Format 'yyyyMMdd-HHmmss'), $Scenario
    $dir = Join-Path $root $name
    New-Item -ItemType Directory -Path $dir | Out-Null
    $script:KuiesRunDir = $dir
    return $dir
}

function Save-KuiesText {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][AllowEmptyString()][string]$Content,
        [string]$Directory
    )
    if (-not $Directory) {
        if (-not $script:KuiesRunDir) { throw '尚未建立 run 目錄' }
        $Directory = $script:KuiesRunDir
    }
    $path = Join-Path $Directory $Name
    $Content | Set-Content -LiteralPath $path -Encoding UTF8
    return $path
}

function Wait-KuiesCondition {
    param(
        [Parameter(Mandatory)][scriptblock]$Condition,
        [int]$TimeoutSeconds = 30,
        [int]$IntervalMs = 1000,
        [string]$Description = 'condition'
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $value = & $Condition
        if ($value) { return $value }
        Start-Sleep -Milliseconds $IntervalMs
    } while ((Get-Date) -lt $deadline)
    Write-Host "  (等待逾時：$Description，${TimeoutSeconds}s)" -ForegroundColor Yellow
    return $null
}

function Get-KuiesDeviceSummary {
    $props = @{}
    $raw = (Invoke-KuiesShell 'getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.build.type; getprop ro.debuggable; getprop ro.secure').Lines
    $keys = @('manufacturer', 'model', 'release', 'sdk', 'buildType', 'debuggable', 'secure')
    for ($i = 0; $i -lt $keys.Count; $i++) {
        $value = if ($i -lt $raw.Count) { $raw[$i].Trim() } else { '' }
        $props[$keys[$i]] = $value
    }

    # 不用 grep -m1：提早關閉管線會讓裝置端 dumpsys 收到 SIGPIPE 而寫 stderr。
    $wakeText = (Invoke-KuiesShell "dumpsys power | grep -o 'mWakefulness=[A-Za-z]*'" -AllowFailure).Text
    $wake = if ($wakeText -match 'mWakefulness=([A-Za-z]+)') { $Matches[1] } else { 'unknown' }
    $keyguardText = (Invoke-KuiesShell "dumpsys window | grep -o 'isKeyguardShowing=[a-z]*'" -AllowFailure).Text
    $keyguard = if ($keyguardText -match 'isKeyguardShowing=(true|false)') { $Matches[1] } else { 'unknown' }
    $lockDisabled = (Invoke-KuiesShell 'cmd lock_settings get-disabled' -AllowFailure).Text.Trim()

    return [pscustomobject]@{
        serial        = $script:KuiesSerial
        transport     = Get-KuiesTransportKind
        manufacturer  = $props['manufacturer']
        model         = $props['model']
        android       = $props['release']
        sdk           = $props['sdk']
        buildType     = $props['buildType']
        debuggable    = $props['debuggable']
        secure        = $props['secure']
        wakefulness  = $wake
        keyguard      = $keyguard
        lockDisabled  = $lockDisabled
        adbPath       = $script:KuiesAdb
        capturedAt    = (Get-Date).ToString('o')
    }
}

function Get-KuiesSettingMap {
    $keys = @(
        'global:private_dns_mode',
        'global:private_dns_specifier',
        'secure:always_on_vpn_app',
        'secure:always_on_vpn_lockdown',
        'global:airplane_mode_on',
        'global:wifi_on',
        'global:mobile_data'
    )
    $map = [ordered]@{}
    foreach ($key in $keys) {
        $parts = $key.Split(':')
        $value = (Invoke-KuiesShell "settings get $($parts[0]) $($parts[1])" -AllowFailure).Text.Trim()
        $map[$key] = $value
    }
    return [pscustomobject]$map
}

function Get-KuiesPrivateDns {
    param([object]$Settings)
    if (-not $Settings) { $Settings = Get-KuiesSettingMap }
    return [pscustomobject]@{
        mode      = $Settings.'global:private_dns_mode'
        specifier = $Settings.'global:private_dns_specifier'
    }
}

function Set-KuiesPrivateDns {
    # 需 -AllowDeviceChanges 授權後才可呼叫；呼叫端負責記錄與還原原值。
    param([Parameter(Mandatory)][string]$Mode, [string]$Specifier)

    Invoke-KuiesShell "settings put global private_dns_mode $Mode" | Out-Null
    if ($Mode -eq 'hostname') {
        if (-not $Specifier) { throw "mode=hostname 必須提供 -Specifier" }
        Invoke-KuiesShell "settings put global private_dns_specifier $Specifier" | Out-Null
    }
    return Get-KuiesPrivateDns
}

function Get-KuiesConnectivityText {
    param([string]$FileName = 'dumpsys-connectivity.txt', [string]$Directory)
    $result = Invoke-KuiesShell 'dumpsys connectivity --short'
    if ($FileName) { Save-KuiesText -Name $FileName -Content $result.Text -Directory $Directory | Out-Null }
    return $result.Text
}

function Convert-KuiesConnectivityText {
    param([Parameter(Mandatory)][AllowEmptyString()][string]$Text)

    $networks = New-Object System.Collections.ArrayList
    foreach ($line in ($Text -split "`n")) {
        if ($line -notmatch 'NetworkAgentInfo\{network\{(\d+)\}') { continue }
        $netId = [int]$Matches[1]
        $entry = [ordered]@{
            netId                = $netId
            niTransport          = ''
            extra                = ''
            interfaceName        = ''
            transports           = ''
            dnsAddresses         = ''
            # 網路自己的 capabilities（取自 nc{[ ... ]} 區塊），用來確認它真的承載 INTERNET。
            capabilities         = ''
            # 實測：LinkProperties 只在「有在用 Private DNS」時才輸出 UsePrivateDns 欄位，
            # 關閉或未使用時整個欄位不存在。因此欄位缺席等同 false，而不是未知。
            usePrivateDns        = $false
            privateDnsServer     = ''
            validatedPrivateDns  = ''
            vpnSession           = ''
            underlying           = ''
            ownerUid             = ''
            validated            = $false
            raw                  = $line.Trim()
        }
        if ($line -match 'ni\{(\S+)') { $entry.niTransport = $Matches[1] }
        if ($line -match 'ni\{[^}]*extra:\s*([^}]*)\}') { $entry.extra = $Matches[1].Trim() }
        if ($line -match 'InterfaceName:\s*(\S+)') { $entry.interfaceName = $Matches[1] }
        if ($line -match 'Transports:\s*([A-Z|]+)') { $entry.transports = $Matches[1] }
        # capability 名稱含底線（例如 NOT_METERED&INTERNET&VALIDATED），字元類別必須含底線，
        # 否則會截斷成 "NOT"，把有 INTERNET 的網路誤判成沒有。
        if ($line -match 'Capabilities:\s*([A-Z0-9_&|]+)') { $entry.capabilities = $Matches[1] }
        if ($line -match 'DnsAddresses:\s*\[\s*([^\]]*)\]') { $entry.dnsAddresses = $Matches[1].Trim() }
        if ($line -match 'UsePrivateDns:\s*(true|false)') { $entry.usePrivateDns = ($Matches[1] -eq 'true') }
        if ($line -match 'PrivateDnsServerName:\s*(\S+)') { $entry.privateDnsServer = $Matches[1] }
        if ($line -match 'ValidatedPrivateDnsAddresses:\s*\[\s*([^\]]*)\]') { $entry.validatedPrivateDns = $Matches[1].Trim() }
        if ($line -match 'VpnTransportInfo\{type=\d+,\s*sessionId=([^,]+)') { $entry.vpnSession = $Matches[1].Trim() }
        if ($line -match 'UnderlyingNetworks:\s*\[([0-9,\s]*)\]') { $entry.underlying = $Matches[1].Trim() }
        if ($line -match 'OwnerUid:\s*(\d+)') { $entry.ownerUid = $Matches[1] }
        if ($line -match 'Capabilities:[^\]]*VALIDATED') { $entry.validated = $true }
        $networks.Add([pscustomobject]$entry) | Out-Null
    }
    return $networks
}

function Get-KuiesDefaultNetId {
    param([Parameter(Mandatory)][AllowEmptyString()][string]$Text)
    if ($Text -match 'Active default network:\s*(\d+)') { return [int]$Matches[1] }
    if ($Text -match 'Active default network:\s*none') { return $null }
    return $null
}

function Get-KuiesVpnNetworks {
    param([Parameter(Mandatory)][AllowEmptyString()][string]$Text)
    return @(Convert-KuiesConnectivityText -Text $Text | Where-Object { $_.niTransport -eq 'VPN' })
}

function Get-KuiesWifiStatus {
    return (Invoke-KuiesShell 'cmd wifi status' -AllowFailure).Text
}

function Get-KuiesAppInfo {
    $installed = (Invoke-KuiesShell "pm list packages $($script:KuiesAppId)" -AllowFailure).Text -match [regex]::Escape($script:KuiesAppId)
    $versionName = ''
    $versionCode = ''
    if ($installed) {
        $dump = (Invoke-KuiesShell "dumpsys package $($script:KuiesAppId)" -AllowFailure).Text
        if ($dump -match 'versionName=(\S+)') { $versionName = $Matches[1] }
        if ($dump -match 'versionCode=(\d+)') { $versionCode = $Matches[1] }
    }
    return [pscustomobject]@{
        packageId   = $script:KuiesAppId
        installed   = [bool]$installed
        versionName = $versionName
        versionCode = $versionCode
    }
}

function Save-KuiesLogcat {
    param(
        [switch]$Full,
        [switch]$SystemNetworkTags,
        [string]$FileName = 'logcat.txt',
        [string]$Directory
    )

    if ($Full) {
        $command = 'logcat -d -v threadtime -b main -b system'
    } else {
        $filter = $script:KuiesTagFilter
        # 系統網路 tag 記在 system buffer，只讀 main 會什麼都抓不到。
        $buffers = '-b main'
        if ($SystemNetworkTags) {
            $filter = "$filter|$($script:KuiesSystemTagFilter)"
            $buffers = '-b main -b system'
        }
        $command = "logcat -d -v threadtime $buffers -e '$filter'"
    }
    $result = Invoke-KuiesShell $command -AllowFailure
    Save-KuiesText -Name $FileName -Content $result.Text -Directory $Directory | Out-Null
    return $result.Text
}

function Clear-KuiesLogcat {
    Invoke-KuiesShell 'logcat -c' -AllowFailure | Out-Null
}

function Save-KuiesUiDump {
    param([string]$DevicePath = '/sdcard/kuies-ui-dump.xml', [string]$FileName = 'ui-hierarchy.xml', [string]$Directory)

    $dump = Invoke-KuiesShell "uiautomator dump $DevicePath" -AllowFailure
    if ($dump.ExitCode -ne 0) { return $null }
    $xml = Invoke-KuiesExecOut @('cat', $DevicePath) -AllowFailure
    Invoke-KuiesShell "rm -f $DevicePath" -AllowFailure | Out-Null
    if ($xml.ExitCode -ne 0 -or -not $xml.Text.Trim()) { return $null }
    Save-KuiesText -Name $FileName -Content $xml.Text -Directory $Directory | Out-Null
    return $xml.Text
}

function Find-KuiesUiNode {
    param(
        [Parameter(Mandatory)][AllowEmptyString()][string]$XmlText,
        [string]$Text,
        [string]$ContentDescription,
        [string]$ResourceId
    )
    if (-not $XmlText.Trim()) { return $null }
    try { $doc = [xml]$XmlText } catch { return $null }

    $xpath = '//node'
    $conditions = @()
    if ($Text) { $conditions += "@text='$Text'" }
    if ($ContentDescription) { $conditions += "@content-desc='$ContentDescription'" }
    if ($ResourceId) { $conditions += "@resource-id='$ResourceId'" }
    if ($conditions.Count -eq 0) { return $null }
    $xpath = '//node[' + ($conditions -join ' and ') + ']'

    $nodes = @($doc.SelectNodes($xpath))
    if ($nodes.Count -eq 0) { return $null }
    $bounds = $nodes[0].GetAttribute('bounds')
    if ($bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { return $null }
    $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]; $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
    return [pscustomobject]@{
        text   = $nodes[0].GetAttribute('text')
        x      = [int](($x1 + $x2) / 2)
        y      = [int](($y1 + $y2) / 2)
        bounds = $bounds
    }
}

function Invoke-KuiesUiTap {
    param([Parameter(Mandatory)][int]$X, [Parameter(Mandatory)][int]$Y)
    Invoke-KuiesShell "input tap $X $Y" | Out-Null
    Start-Sleep -Milliseconds 500
}

function Add-KuiesCheck {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][bool]$Passed,
        [string]$Expected = '',
        [string]$Actual = '',
        [string]$Note = '',
        # 無法判定時用這個，不要讓不明確的狀態被算成 PASS。
        [switch]$Inconclusive
    )
    $status = if ($Inconclusive) { 'INCONCLUSIVE' } elseif ($Passed) { 'PASS' } else { 'FAIL' }
    $script:KuiesChecks.Add([pscustomobject]@{
        name     = $Name
        status   = $status
        passed   = ($status -eq 'PASS')
        expected = $Expected
        actual   = $Actual
        note     = $Note
    }) | Out-Null

    if ($status -eq 'PASS') {
        Write-Host ("  [PASS] {0}" -f $Name) -ForegroundColor Green
    } elseif ($status -eq 'INCONCLUSIVE') {
        Write-Host ("  [INCONCLUSIVE] {0} / 無法判定：{1}" -f $Name, $Actual) -ForegroundColor Yellow
    } else {
        Write-Host ("  [FAIL] {0} / 預期：{1} / 實際：{2}" -f $Name, $Expected, $Actual) -ForegroundColor Red
    }
}

function Write-KuiesSummary {
    param(
        [Parameter(Mandatory)][string]$Scenario,
        [Parameter(Mandatory)][string]$RunDir,
        [string]$Note = ''
    )
    $failed = @($script:KuiesChecks | Where-Object { $_.status -eq 'FAIL' })
    $inconclusive = @($script:KuiesChecks | Where-Object { $_.status -eq 'INCONCLUSIVE' })
    $summary = [pscustomobject]@{
        scenario          = $Scenario
        startedAt         = $script:KuiesStartedAt
        finishedAt        = (Get-Date).ToString('o')
        serial            = $script:KuiesSerial
        transport         = Get-KuiesTransportKind
        totalChecks       = $script:KuiesChecks.Count
        failedCount       = $failed.Count
        inconclusiveCount = $inconclusive.Count
        note              = $Note
        checks            = $script:KuiesChecks
    }
    $summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $RunDir 'summary.json') -Encoding UTF8

    $lines = New-Object System.Collections.ArrayList
    $lines.Add("# KuiesBlock 測試摘要") | Out-Null
    $lines.Add('') | Out-Null
    $lines.Add("- 情境：$Scenario") | Out-Null
    $lines.Add("- 裝置：$($script:KuiesSerial)（$(Get-KuiesTransportKind)）") | Out-Null
    $lines.Add("- 結果：$($script:KuiesChecks.Count) 項中 PASS $($script:KuiesChecks.Count - $failed.Count - $inconclusive.Count)、FAIL $($failed.Count)、INCONCLUSIVE $($inconclusive.Count)") | Out-Null
    if ($Note) { $lines.Add("- 備註：$Note") | Out-Null }
    $lines.Add('') | Out-Null
    foreach ($check in $script:KuiesChecks) {
        $lines.Add("- [$($check.status)] $($check.name)") | Out-Null
        # FAIL 與 INCONCLUSIVE 都要留下完整診斷資訊，方便下次比對。
        if ($check.status -ne 'PASS') {
            $lines.Add("  - 預期：$($check.expected)") | Out-Null
            $lines.Add("  - 實際：$($check.actual)") | Out-Null
        }
        if ($check.note) { $lines.Add("  - 備註：$($check.note)") | Out-Null }
    }
    ($lines -join "`n") | Set-Content -LiteralPath (Join-Path $RunDir 'summary.md') -Encoding UTF8
    return $summary
}
