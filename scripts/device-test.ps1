#requires -version 5.1
<#
.SYNOPSIS
    KuiesBlock 真機測試驅動：每個情境都遵守「記錄起始狀態 -> 執行操作 -> 驗證實際狀態」。

.DESCRIPTION
    所有產出都寫到 artifacts\run-<時間>-<情境>\，內含 dumpsys connectivity、設定快照、
    過濾後的 logcat、UI 階層與 summary.json / summary.md，方便後續比對，不必手動匯出。

    安全預設：
      * 預設唯讀。任何會改變裝置狀態的步驟都必須明示 -AllowDeviceChanges。
      * network-switch 情境在無線偵錯下會直接拒絕執行（切網路會切斷 adb）。
      * 永不代替使用者按下 VPN 授權同意對話框；需要同意時回報為失敗並說明。
      * 被停用的其他 VPN（例如 AdGuard）會被記錄並嘗試還原，且一定在摘要中說明結果。

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\device-test.ps1 -Scenario baseline

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\device-test.ps1 -Scenario private-dns -AllowDeviceChanges
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('baseline', 'private-dns', 'protection', 'dns-declaration', 'network-switch', 'filter')]
    [string]$Scenario,

    [string]$Serial,
    [string]$AdbPath,

    # 允許執行會改變裝置狀態的步驟（VPN 啟停、私人 DNS 切換、網路切換）。
    [switch]$AllowDeviceChanges,

    # protection / dns-declaration / network-switch 情境：先停用佔用 VPN 位置的其他 App（目前是 AdGuard）。
    [switch]$StopOtherVpn,

    # network-switch：切換網路時讓 KuiesBlock 的 tunnel 保持運作，
    # 用來重現「行動網路 -> Wi-Fi 之後無法上網」。
    [switch]$WithKuiesTunnel,

    [switch]$FullLogcat,

    [int]$TimeoutSeconds = 45,

    # DNS 解析檢查用的主機（不含查詢內容以外的資訊，僅作連通性判定）。
    [string[]]$DnsProbeHosts = @('example.com', 'dns.kuies.tw'),

    [string]$AppId = 'tw.kuies.block',
    [string]$OtherVpnPackage = 'com.adguard.android',
    [string]$PrivateDnsSpecifier = 'dns.kuies.tw',

    # 私人 DNS 情境要驗證的模式順序。
    [string[]]$PrivateDnsModes = @('off', 'opportunistic', 'hostname')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'KuiesTest.Common.ps1')

$script:KuiesStartedAt = (Get-Date).ToString('o')
$script:KuiesNotes = New-Object System.Collections.ArrayList
$script:KuiesRestore = New-Object System.Collections.ArrayList
$script:KuiesNotificationPermissionGranted = $false
$script:KuiesAdbServiceBlocked = $false

function Add-KuiesNote {
    param([Parameter(Mandatory)][string]$Text)
    $script:KuiesNotes.Add($Text) | Out-Null
    Write-Host "  note: $Text" -ForegroundColor DarkGray
}

function Assert-KuiesDeviceChangesAllowed {
    param([Parameter(Mandatory)][string]$Reason)
    if (-not $AllowDeviceChanges) {
        throw "情境 '$Scenario' 需要 -AllowDeviceChanges（$Reason）。"
    }
}

function Enable-KuiesNotificationPermission {
    # App 第一次啟動會跳出「要允許 KuiesBlock 傳送通知嗎？」並停在那裡，UI 自動化會被它擋住。
    # 用 pm grant 直接授予（不去點那個對話框），並記錄是本次才給的，結束時撤銷。
    param([Parameter(Mandatory)][string]$PackageId)

    $lines = (Invoke-KuiesShell "dumpsys package $PackageId | grep -o 'POST_NOTIFICATIONS: granted=[a-z]*'" -AllowFailure).Lines
    # dumpsys 會列出多個 user 區段，第一筆才是 User 0。
    $first = $lines | Where-Object { $_ } | Select-Object -First 1
    if ($first -match 'granted=true') { return $false }

    Invoke-KuiesShell "pm grant $PackageId android.permission.POST_NOTIFICATIONS" | Out-Null
    $script:KuiesNotificationPermissionGranted = $true
    Add-KuiesNote '已用 pm grant 授予 POST_NOTIFICATIONS（避免被通知權限對話框擋住），結束時會撤銷。'
    return $true
}

function Restore-KuiesNotificationPermission {
    param([Parameter(Mandatory)][string]$PackageId)
    if (-not $script:KuiesNotificationPermissionGranted) { return }
    Invoke-KuiesShell "pm revoke $PackageId android.permission.POST_NOTIFICATIONS" -AllowFailure | Out-Null
    $script:KuiesNotificationPermissionGranted = $false
}

function Save-KuiesStateBundle {
    param(
        [Parameter(Mandatory)][string]$Label,
        [switch]$SkipLogcat
    )
    $text = Get-KuiesConnectivityText -FileName "connectivity-$Label.txt"
    $settings = Get-KuiesSettingMap
    Save-KuiesText -Name "settings-$Label.json" -Content ($settings | ConvertTo-Json -Depth 4) | Out-Null
    $wifi = Get-KuiesWifiStatus
    Save-KuiesText -Name "wifi-$Label.txt" -Content $wifi | Out-Null
    if (-not $SkipLogcat) {
        Save-KuiesLogcat -Full:$FullLogcat -FileName "logcat-$Label.txt" | Out-Null
    }
    $networks = Convert-KuiesConnectivityText -Text $text
    Save-KuiesText -Name "networks-$Label.json" -Content ($networks | ConvertTo-Json -Depth 4) | Out-Null
    return [pscustomobject]@{
        label        = $Label
        connectivity = $text
        settings     = $settings
        wifiStatus   = $wifi
        networks     = $networks
        defaultNetId = Get-KuiesDefaultNetId -Text $text
    }
}

function Get-KuiesNetworkById {
    param([object[]]$Networks, [Nullable[int]]$NetId)
    if ($null -eq $NetId) { return $null }
    return ($Networks | Where-Object { $_.netId -eq $NetId } | Select-Object -First 1)
}

function Resolve-KuiesUnderlyingNetwork {
    # VPN 會成為 default network，但它底下才是真正的 Wi-Fi / 行動網路。
    param([object[]]$Networks, [Nullable[int]]$DefaultNetId)
    $net = Get-KuiesNetworkById -Networks $Networks -NetId $DefaultNetId
    while ($net -and $net.niTransport -eq 'VPN' -and $net.underlying) {
        $underId = [int]($net.underlying -split ',' | Select-Object -First 1).Trim()
        $under = Get-KuiesNetworkById -Networks $Networks -NetId $underId
        if (-not $under) { break }
        $net = $under
    }
    return $net
}

function Resolve-KuiesDnsCarrierNetwork {
    <#
        判定「目前實際承載裝置網際網路的底層網路」——也就是私人 DNS 真正生效的那個網路。

        每一跳都必須驗證：
          1. `Active default network` 必須存在且在網路清單中；
          2. 只要是 VPN，就必須回報「唯一一個」UnderlyingNetworks，且該 netId 存在（多個或缺失都不判定）；
          3. 最終網路必須具備 INTERNET capability 並有 InterfaceName。

        任何一步不明確都回 INCONCLUSIVE，並附上原因，絕不用 default netId 或第一筆 WIFI 網路推斷。
    #>
    param([Parameter(Mandatory)][AllowEmptyString()][string]$ConnectivityText)

    $nets = Convert-KuiesConnectivityText -Text $ConnectivityText
    $result = [ordered]@{
        status       = 'INCONCLUSIVE'
        reason       = ''
        hops         = @()
        viaVpn       = ''
        network      = $null
        defaultNetId = Get-KuiesDefaultNetId -Text $ConnectivityText
    }

    if ($null -eq $result.defaultNetId) {
        $result.reason = 'dumpsys connectivity 沒有回報 Active default network'
        return [pscustomobject]$result
    }

    $result.hops += $result.defaultNetId
    $current = Get-KuiesNetworkById -Networks $nets -NetId $result.defaultNetId
    if (-not $current) {
        $result.reason = "預設網路 netId $($result.defaultNetId) 不在網路清單中"
        return [pscustomobject]$result
    }

    $guard = 0
    while ($current.niTransport -eq 'VPN') {
        if ($guard -ge 4) {
            $result.reason = '追蹤 VPN 底層網路超過 4 層，無法判定'
            return [pscustomobject]$result
        }
        $guard++
        $result.viaVpn = $current.extra

        $underIds = @()
        if ($current.underlying) {
            $underIds = @($current.underlying -split ',' |
                ForEach-Object { $_.Trim() } |
                Where-Object { $_ -match '^\d+$' } |
                ForEach-Object { [int]$_ })
        }
        if ($underIds.Count -eq 0) {
            $result.reason = "VPN（$($current.extra)）沒有回報 UnderlyingNetworks"
            return [pscustomobject]$result
        }
        if ($underIds.Count -gt 1) {
            $result.reason = "VPN（$($current.extra)）回報多個底層網路（$($current.underlying)），無法判定實際 DNS 路徑"
            return [pscustomobject]$result
        }
        $next = Get-KuiesNetworkById -Networks $nets -NetId $underIds[0]
        if (-not $next) {
            $result.reason = "VPN 的底層網路 netId $($underIds[0]) 不在網路清單中"
            return [pscustomobject]$result
        }
        $result.hops += $next.netId
        $current = $next
    }

    if ($current.capabilities -notmatch 'INTERNET') {
        $result.reason = "底層網路 netId $($current.netId)（transports=$($current.transports)）沒有 INTERNET capability，可能不是實際承載網路"
        return [pscustomobject]$result
    }
    if (-not $current.interfaceName) {
        $result.reason = "底層網路 netId $($current.netId) 沒有 InterfaceName"
        return [pscustomobject]$result
    }
    if ($current.capabilities -match 'LOCAL_NETWORK' -and $current.capabilities -notmatch 'INTERNET') {
        $result.reason = "底層網路 netId $($current.netId) 只具備 LOCAL_NETWORK"
        return [pscustomobject]$result
    }

    $result.status = 'OK'
    $result.network = $current
    return [pscustomobject]$result
}

function Test-KuiesDnsResolution {
    # ping 的名稱解析走裝置的系統 resolver（受私人 DNS 影響），但不會經過 App 的 VPN。
    param([Parameter(Mandatory)][string]$HostName)
    $result = Invoke-KuiesShell "ping -c 1 -W 2 $HostName" -AllowFailure
    $text = $result.Text
    if ($text -match 'unknown host|Name or service not known|not known') {
        return [pscustomobject]@{ host = $HostName; resolved = $false; address = ''; output = $text }
    }
    if ($text -match 'PING\s+\S+\s+\(([0-9a-fA-F\.:]+)\)') {
        return [pscustomobject]@{ host = $HostName; resolved = $true; address = $Matches[1]; output = $text }
    }
    return [pscustomobject]@{ host = $HostName; resolved = $false; address = ''; output = $text }
}

function Get-KuiesTunnelNetwork {
    param([Parameter(Mandatory)][AllowEmptyString()][string]$ConnectivityText)
    $vpns = Get-KuiesVpnNetworks -Text $ConnectivityText
    return ($vpns | Where-Object { $_.extra -match [regex]::Escape($AppId) } | Select-Object -First 1)
}

function Start-KuiesTunnel {
    param([int]$WaitSeconds = 0)
    if ($WaitSeconds -le 0) { $WaitSeconds = $TimeoutSeconds }

    # 已經有 tunnel 在跑（例如使用者剛手動同意過）就沿用現況，不要再去點按鈕。
    $existing = Get-KuiesTunnelNetwork -ConnectivityText (Invoke-KuiesShell 'dumpsys connectivity --short').Text
    if ($existing) {
        Add-KuiesNote "tunnel 已在執行中（netId $($existing.netId)），沿用現況。"
        return $existing
    }

    $service = "$AppId/.vpn.KuiesVpnService"
    $actionStart = 'tw.kuies.block.action.VPN_START'
    $method = 'adb-am'

    # 實測：這個 VpnService 沒有 exported，adb（shell UID）啟動會被拒：
    # "Requires permission not exported from uid ..."。第一次失敗後就不再重試。
    if ($script:KuiesAdbServiceBlocked) {
        $am = [pscustomobject]@{ ExitCode = 1; Text = '已知 adb 無法啟動非匯出的 VpnService，直接走 UI 自動化。' }
    } else {
        $am = Invoke-KuiesShell "am start-foreground-service -n $service -a $actionStart" -AllowFailure
        if ($am.Text -match 'not exported') { $script:KuiesAdbServiceBlocked = $true }
    }
    if ($am.ExitCode -ne 0) {
        Add-KuiesNote "以 adb 啟動服務失敗，改用 UI 自動化：$($am.Text.Trim())"
        $method = 'ui-automation'
        Invoke-KuiesShell "am start -n $AppId/.MainActivity" -AllowFailure | Out-Null
        $node = Find-KuiesUiNodeWithRetry -Texts @('啟用防護', '重試啟用') -FileName 'ui-before-start.xml'
        if (-not $node) {
            Add-KuiesNote '找不到「啟用防護」按鈕。'
            $blockers = (Invoke-KuiesShell "dumpsys window | grep -o -e 'com.android.vpndialogs[^ ]*' -e 'GrantPermissionsActivity' | sort -u" -AllowFailure).Text.Trim()
            if ($blockers) {
                Add-KuiesNote "畫面上有會擋住操作的系統視窗：$blockers"
            } else {
                Add-KuiesNote '畫面上沒有已知的系統對話框；可能是螢幕已鎖或 App 未在前景。'
            }
            return $null
        }
        Invoke-KuiesUiTap -X $node.x -Y $node.y
    }

    $tunnel = Wait-KuiesCondition -TimeoutSeconds $WaitSeconds -Description "等候 KuiesBlock VPN 網路出現" -Condition {
        $text = (Invoke-KuiesShell 'dumpsys connectivity --short').Text
        Get-KuiesTunnelNetwork -ConnectivityText $text
    }
    if ($tunnel) {
        Add-KuiesNote "tunnel 啟動方式：$method"
        return $tunnel
    }

    # 最常見的停止點：App 已被帶到前景並按下按鈕，但系統跳出 VPN 連線要求，等不到人按。
    # 腳本依規定不代按，只在這裡指出畫面狀態，讓使用者知道要按什麼。
    $dialogs = (Invoke-KuiesShell "dumpsys window | grep -c vpndialogs" -AllowFailure).Text.Trim()
    if ($dialogs -and $dialogs -ne '0') {
        Add-KuiesNote '畫面上有系統的 VPN 連線要求對話框（com.android.vpndialogs）：請在手機上點「確定」允許一次，同一個安裝之後就不會再問。'
    }
    return $null
}

function Find-KuiesUiNodeWithRetry {
    # 冷啟動的 Compose 畫面要幾秒才畫得出來，單次 dump 常常找不到按鈕。
    param(
        [Parameter(Mandatory)][string[]]$Texts,
        [string]$FileName = 'ui-search.xml',
        [int]$Attempts = 4,
        [int]$DelaySeconds = 3
    )
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        $xml = Save-KuiesUiDump -FileName $FileName
        foreach ($text in $Texts) {
            $node = Find-KuiesUiNode -XmlText $xml -Text $text
            if ($node) { return $node }
        }
        if ($attempt -lt $Attempts) { Start-Sleep -Seconds $DelaySeconds }
    }
    return $null
}

function Stop-KuiesTunnelViaUi {
    Invoke-KuiesShell "am start -n $AppId/.MainActivity" -AllowFailure | Out-Null
    $node = Find-KuiesUiNodeWithRetry -Texts @('停用防護', '取消啟用') -FileName 'ui-before-stop.xml'
    if (-not $node) { return $false }
    Invoke-KuiesUiTap -X $node.x -Y $node.y
    return $true
}

function Stop-KuiesTunnel {
    # 本來就沒有 tunnel（例如 App 已依 fail-open 自行停止）就直接算成功，
    # 免得白跑 UI 搜尋、更避免不必要的 force-stop。
    if (-not (Get-KuiesTunnelNetwork -ConnectivityText (Invoke-KuiesShell 'dumpsys connectivity --short').Text)) { return $true }

    $service = "$AppId/.vpn.KuiesVpnService"
    $actionStop = 'tw.kuies.block.action.VPN_STOP'

    if (-not $script:KuiesAdbServiceBlocked) {
        $am = Invoke-KuiesShell "am start-foreground-service -n $service -a $actionStop" -AllowFailure
        if ($am.Text -match 'not exported') { $script:KuiesAdbServiceBlocked = $true }
        if ($am.ExitCode -eq 0) {
            $stopped = Wait-KuiesCondition -TimeoutSeconds 20 -Description '等候 KuiesBlock VPN 網路消失' -Condition {
                $text = (Invoke-KuiesShell 'dumpsys connectivity --short').Text
                if (-not (Get-KuiesTunnelNetwork -ConnectivityText $text)) { return $true }
                return $false
            }
            if ($stopped) { return $true }
            Add-KuiesNote 'ACTION_STOP 沒有讓 tunnel 消失，改用 UI 自動化。'
        }
    }

    # 用 UI 按「停用防護」，而不是 am force-stop：實測 force-stop 之後 App 再啟動時，
    # 系統又重新跳出 VPN 連線要求，等於把使用者已經給過的同意弄掉。
    if (Stop-KuiesTunnelViaUi) {
        $gone = Wait-KuiesCondition -TimeoutSeconds 25 -Description '等候 KuiesBlock VPN 網路消失（UI 停用後）' -Condition {
            $text = (Invoke-KuiesShell 'dumpsys connectivity --short').Text
            if (-not (Get-KuiesTunnelNetwork -ConnectivityText $text)) { return $true }
            return $false
        }
        if ($gone) { return $true }
        Add-KuiesNote 'UI 停用後 tunnel 仍在，改用 am force-stop。'
    } else {
        Add-KuiesNote '找不到「停用防護」按鈕，改用 am force-stop。'
    }

    Invoke-KuiesShell "am force-stop $AppId" -AllowFailure | Out-Null
    Add-KuiesNote '已用 am force-stop 強制停止；下次啟動可能需要重新同意 VPN 授權。'
    $gone = Wait-KuiesCondition -TimeoutSeconds 15 -Description '等候 force-stop 後 VPN 網路消失' -Condition {
        $text = (Invoke-KuiesShell 'dumpsys connectivity --short').Text
        if (-not (Get-KuiesTunnelNetwork -ConnectivityText $text)) { return $true }
        return $false
    }
    return [bool]$gone
}

function Get-KuiesOtherVpnNetwork {
    param([Parameter(Mandatory)][AllowEmptyString()][string]$ConnectivityText)
    $vpns = Get-KuiesVpnNetworks -Text $ConnectivityText
    return ($vpns | Where-Object { $_.extra -and $_.extra -notmatch [regex]::Escape($AppId) } | Select-Object -First 1)
}

function Stop-KuiesOtherVpn {
    param([Parameter(Mandatory)][string]$PackageId)

    $text = (Invoke-KuiesShell 'dumpsys connectivity --short').Text
    $other = Get-KuiesOtherVpnNetwork -ConnectivityText $text
    if (-not $other) { return $true }

    if (-not $StopOtherVpn) {
        Add-KuiesNote "另一個 VPN 正在運作（$($other.extra)，netId $($other.netId)）。要繼續請加 -StopOtherVpn。"
        return $false
    }

    Add-KuiesNote "記錄並停用其他 VPN：$($other.extra)（netId $($other.netId)）"
    Invoke-KuiesShell "am force-stop $PackageId" -AllowFailure | Out-Null
    $stopped = Wait-KuiesCondition -TimeoutSeconds 30 -Description "等候 $PackageId 的 VPN 網路消失" -Condition {
        $t = (Invoke-KuiesShell 'dumpsys connectivity --short').Text
        if (-not (Get-KuiesOtherVpnNetwork -ConnectivityText $t)) { return $true }
        return $false
    }
    if ($stopped) {
        $script:KuiesRestore.Add([pscustomobject]@{ package = $PackageId; reason = 'test-run' }) | Out-Null
    }
    return [bool]$stopped
}

function Restore-KuiesOtherVpn {
    if ($script:KuiesRestore.Count -eq 0) { return $null }

    $outcomes = New-Object System.Collections.ArrayList
    foreach ($entry in $script:KuiesRestore) {
        $packageId = $entry.package
        # 實測：AdGuard 被 force-stop 後，只有用 launcher 類別啟動才會把 VPN 帶回來，
        # 直接 am start -n <SplashActivity> 等了 60 秒仍然沒有恢復。
        Invoke-KuiesShell "monkey -p $packageId -c android.intent.category.LAUNCHER 1" -AllowFailure | Out-Null
        $back = Wait-KuiesCondition -TimeoutSeconds 60 -Description "等候 $packageId 的 VPN 網路恢復" -Condition {
            $t = (Invoke-KuiesShell 'dumpsys connectivity --short').Text
            Get-KuiesOtherVpnNetwork -ConnectivityText $t
        }
        if (-not $back) {
            $activity = (Invoke-KuiesShell "cmd package resolve-activity --brief $packageId" -AllowFailure).Text.Trim()
            if ($activity -match "$([regex]::Escape($packageId))/\S+") {
                Add-KuiesNote "launcher 方式未能恢復 $packageId，改試 $activity。"
                Invoke-KuiesShell "am start -n $activity" -AllowFailure | Out-Null
                $back = Wait-KuiesCondition -TimeoutSeconds 30 -Description "等候 $packageId 的 VPN 網路恢復（第二次嘗試）" -Condition {
                    $t = (Invoke-KuiesShell 'dumpsys connectivity --short').Text
                    Get-KuiesOtherVpnNetwork -ConnectivityText $t
                }
            }
        }
        $outcomes.Add([pscustomobject]@{ package = $packageId; restored = [bool]$back }) | Out-Null
    }
    return $outcomes
}

# ---------------------------------------------------------------- 情境：baseline（唯讀）

function Invoke-ScenarioBaseline {
    $bundle = Save-KuiesStateBundle -Label 'before'
    $device = Get-KuiesDeviceSummary
    $app = Get-KuiesAppInfo
    Save-KuiesText -Name 'device-summary.json' -Content ($device | ConvertTo-Json -Depth 4) | Out-Null
    Save-KuiesText -Name 'app-info.json' -Content ($app | ConvertTo-Json -Depth 4) | Out-Null

    Add-KuiesCheck -Name '裝置已授權且可回應' -Passed $true -Actual $device.serial -Note "連線方式：$($device.transport)"
    Add-KuiesCheck -Name '可讀取 dumpsys connectivity' -Passed ($bundle.networks.Count -gt 0) -Expected '至少 1 個網路' -Actual "$($bundle.networks.Count) 個"
    Add-KuiesCheck -Name '可辨識預設網路' -Passed ($null -ne $bundle.defaultNetId) -Expected 'Active default network: <netId>' -Actual "$($bundle.defaultNetId)"
    Add-KuiesCheck -Name '可讀取私人 DNS 設定' -Passed ([bool]$bundle.settings.'global:private_dns_mode') -Expected '非空值' -Actual "$($bundle.settings.'global:private_dns_mode') / $($bundle.settings.'global:private_dns_specifier')"
    Add-KuiesCheck -Name '可讀取 cmd wifi status' -Passed ($bundle.wifiStatus.Length -gt 0) -Expected '非空輸出' -Actual "$($bundle.wifiStatus.Length) 字元"

    $default = Get-KuiesNetworkById -Networks $bundle.networks -NetId $bundle.defaultNetId
    if ($default) {
        Add-KuiesCheck -Name '預設網路的底層傳輸可判定' -Passed $true -Actual "$($default.niTransport) / transports=$($default.transports)" -Note "underlying=$($default.underlying)"
    }

    $resolutions = @()
    foreach ($hostName in $DnsProbeHosts) {
        $resolutions += Test-KuiesDnsResolution -HostName $hostName
    }
    Save-KuiesText -Name 'dns-probe-before.json' -Content ($resolutions | ConvertTo-Json -Depth 4) | Out-Null
    foreach ($resolution in $resolutions) {
        Add-KuiesCheck -Name "系統 resolver 可解析 $($resolution.host)" -Passed $resolution.resolved -Expected '解析出 IP' -Actual $resolution.address
    }

    Add-KuiesCheck -Name 'App 已安裝' -Passed $app.installed -Expected "$AppId 已安裝" -Actual ($app.installed.ToString()) -Note "版本=$($app.versionName)"
    Add-KuiesCheck -Name '螢幕未鎖定（UI 自動化前提）' -Passed ($device.keyguard -eq 'false') -Expected 'keyguard 未顯示' -Actual "$($device.keyguard)"
}

# ---------------------------------------------------------------- 情境：private-dns

function Invoke-ScenarioPrivateDns {
    Assert-KuiesDeviceChangesAllowed -Reason '本情境會切換 private_dns_mode'

    $original = Get-KuiesPrivateDns
    Add-KuiesNote "原始私人 DNS：mode=$($original.mode) specifier=$($original.specifier)"
    Save-KuiesText -Name 'private-dns-original.json' -Content ($original | ConvertTo-Json -Depth 3) | Out-Null

    $observations = New-Object System.Collections.ArrayList
    try {
        foreach ($mode in $PrivateDnsModes) {
            $specifier = if ($mode -eq 'hostname') { $PrivateDnsSpecifier } else { '' }
            Write-Host "== 切換私人 DNS -> $mode ==" -ForegroundColor Cyan
            # 切換前先確認「實際承載網際網路的底層網路」，之後只驗證它，不從 default netId
            # 或第一筆 WIFI 網路推斷 DNS 路徑。
            $beforeCarrier = Resolve-KuiesDnsCarrierNetwork -ConnectivityText (Invoke-KuiesShell 'dumpsys connectivity --short').Text
            Set-KuiesPrivateDns -Mode $mode -Specifier $specifier | Out-Null

            $carrier = Wait-KuiesCondition -TimeoutSeconds $TimeoutSeconds -Description "等候 $mode 反映在實際承載網路上" -Condition {
                $text = (Invoke-KuiesShell 'dumpsys connectivity --short').Text
                $c = Resolve-KuiesDnsCarrierNetwork -ConnectivityText $text
                if ($c.status -ne 'OK') { return $null }
                switch ($mode) {
                    'off' { if (-not $c.network.usePrivateDns) { return $c } }
                    'hostname' { if ($c.network.privateDnsServer -eq $PrivateDnsSpecifier) { return $c } }
                    default { return $c }
                }
                return $null
            }

            $label = "private-dns-$mode"
            $bundle = Save-KuiesStateBundle -Label $label -SkipLogcat
            # 量測時再判定一次；若期間網路換了，下面會標成 INCONCLUSIVE 而不是硬算 PASS。
            $afterCarrier = Resolve-KuiesDnsCarrierNetwork -ConnectivityText $bundle.connectivity
            if (-not $carrier) { $carrier = $afterCarrier }
            $dnsOk = @()
            foreach ($hostName in $DnsProbeHosts) { $dnsOk += Test-KuiesDnsResolution -HostName $hostName }
            Save-KuiesText -Name "dns-probe-$mode.json" -Content ($dnsOk | ConvertTo-Json -Depth 4) | Out-Null

            $carrierNet = $afterCarrier.network
            $carrierNetId = $null
            $carrierTransports = ''
            $carrierInterface = ''
            $carrierUsePrivateDns = $null
            $carrierServer = ''
            $carrierValidatedAddress = ''
            $carrierValidated = $false
            if ($carrierNet) {
                $carrierNetId = $carrierNet.netId
                $carrierTransports = $carrierNet.transports
                $carrierInterface = $carrierNet.interfaceName
                $carrierUsePrivateDns = $carrierNet.usePrivateDns
                $carrierServer = $carrierNet.privateDnsServer
                $carrierValidatedAddress = $carrierNet.validatedPrivateDns
                $carrierValidated = $carrierNet.validated
            }
            $beforeCarrierNetId = $null
            if ($beforeCarrier.network) { $beforeCarrierNetId = $beforeCarrier.network.netId }

            $observation = [pscustomobject]@{
                requestedMode           = $mode
                requestedSpecifier      = $specifier
                settingMode             = $bundle.settings.'global:private_dns_mode'
                settingSpecifier        = $bundle.settings.'global:private_dns_specifier'
                carrierStatus           = $afterCarrier.status
                carrierReason           = $afterCarrier.reason
                carrierHops             = @($afterCarrier.hops)
                carrierViaVpn           = $afterCarrier.viaVpn
                carrierNetId            = $carrierNetId
                carrierTransports       = $carrierTransports
                carrierInterface        = $carrierInterface
                carrierUsePrivateDns    = $carrierUsePrivateDns
                carrierPrivateDnsServer = $carrierServer
                carrierValidatedAddress = $carrierValidatedAddress
                carrierValidated        = $carrierValidated
                beforeCarrierStatus     = $beforeCarrier.status
                beforeCarrierNetId      = $beforeCarrierNetId
                dnsProbeResolved        = @($dnsOk | ForEach-Object { $_.resolved })
            }
            $observations.Add($observation) | Out-Null

            if ($afterCarrier.status -ne 'OK') {
                Add-KuiesCheck -Name "[$mode] 判定實際承載 DNS 路徑的網路" -Passed $false -Inconclusive -Expected '從預設網路沿 UnderlyingNetworks 找到唯一的 INTERNET 底層網路' -Actual "無法判定：$($afterCarrier.reason)" -Note "追蹤路徑 netId：$($afterCarrier.hops -join ' -> ')"
            } else {
                Add-KuiesCheck -Name "[$mode] 判定實際承載 DNS 路徑的網路" -Passed $true -Actual "netId=$($carrierNetId) transports=$($carrierTransports) iface=$($carrierInterface)" -Note "追蹤路徑 netId：$($afterCarrier.hops -join ' -> ')；capabilities 含 INTERNET"

                if ($beforeCarrier.status -eq 'OK' -and $beforeCarrierNetId -ne $carrierNetId) {
                    Add-KuiesCheck -Name "[$mode] 測試期間底層網路是否維持不變" -Passed $false -Inconclusive -Expected "維持 netId $beforeCarrierNetId" -Actual "切換前 netId $beforeCarrierNetId -> 量測時 netId $($carrierNetId)，已重新擷取"
                }

                switch ($mode) {
                    'off' {
                        Add-KuiesCheck -Name '關閉私人 DNS 後實際承載網路不再使用 Private DNS' -Passed ($carrierUsePrivateDns -eq $false) -Expected '該網路未使用 Private DNS（dumpsys 不輸出 UsePrivateDns）' -Actual "usePrivateDns=$($carrierUsePrivateDns) server='$($carrierServer)'"
                    }
                    'hostname' {
                        Add-KuiesCheck -Name 'strict 私人 DNS 生效於實際承載網路' -Passed ($carrierServer -eq $PrivateDnsSpecifier) -Expected $PrivateDnsSpecifier -Actual "$($carrierServer)"

                        # 驗證位址不是切換後立刻出現，要另外等，否則會誤判成失敗。
                        $validated = Wait-KuiesCondition -TimeoutSeconds $TimeoutSeconds -Description '等候 strict 私人 DNS 完成驗證' -Condition {
                            $t = (Invoke-KuiesShell 'dumpsys connectivity --short').Text
                            $c = Resolve-KuiesDnsCarrierNetwork -ConnectivityText $t
                            if ($c.status -ne 'OK') { return $null }
                            if ($c.network.validatedPrivateDns) { return $c }
                            return $null
                        }
                        if ($validated) {
                            $observation.carrierValidatedAddress = $validated.network.validatedPrivateDns
                            $observation.carrierUsePrivateDns = $validated.network.usePrivateDns
                        }
                        Add-KuiesCheck -Name 'strict 私人 DNS 已完成驗證' -Passed ([bool]$validated) -Expected 'ValidatedPrivateDnsAddresses 非空' -Actual "$($observation.carrierValidatedAddress)" -Note "等待上限 $TimeoutSeconds 秒；逾時可加大 -TimeoutSeconds"
                    }
                    default {
                        Add-KuiesCheck -Name '自動（opportunistic）模式設定已套用' -Passed ($observation.settingMode -eq 'opportunistic') -Expected 'opportunistic' -Actual $observation.settingMode
                        Add-KuiesCheck -Name '機會式 DoT 是否真的在該網路上生效' -Passed $false -Inconclusive -Expected '無法由 dumpsys 判定（opportunistic 只在找到可用 DoT 伺服器時才啟用）' -Actual "usePrivateDns=$($carrierUsePrivateDns) server='$($carrierServer)'"
                    }
                }
            }

            foreach ($probe in $dnsOk) {
                Add-KuiesCheck -Name "[$mode] 系統 resolver 可解析 $($probe.host)" -Passed $probe.resolved -Expected '解析出 IP' -Actual $probe.address
            }
        }
    } finally {
        Write-Host '== 還原私人 DNS ==' -ForegroundColor Cyan
        Set-KuiesPrivateDns -Mode $original.mode -Specifier $original.specifier | Out-Null
        $restored = Get-KuiesPrivateDns
        $ok = ($restored.mode -eq $original.mode) -and (($restored.specifier -eq $original.specifier) -or ($original.mode -ne 'hostname'))
        Add-KuiesCheck -Name '私人 DNS 已還原為原始設定' -Passed $ok -Expected "mode=$($original.mode) specifier=$($original.specifier)" -Actual "mode=$($restored.mode) specifier=$($restored.specifier)"
        Save-KuiesStateBundle -Label 'after-restore' -SkipLogcat | Out-Null
    }

    Save-KuiesText -Name 'private-dns-observations.json' -Content ($observations | ConvertTo-Json -Depth 5) | Out-Null
}

# ---------------------------------------------------------------- 情境：protection

function Invoke-ScenarioProtection {
    Assert-KuiesDeviceChangesAllowed -Reason '本情境會啟動與停止 App 的 VPN'

    $app = Get-KuiesAppInfo
    Add-KuiesCheck -Name 'App 已安裝（debug APK）' -Passed $app.installed -Expected "$AppId 已安裝" -Actual ($app.installed.ToString()) -Note "版本=$($app.versionName)"
    if (-not $app.installed) { return }

    $device = Get-KuiesDeviceSummary
    if ($device.keyguard -eq 'true') {
        Add-KuiesNote '螢幕目前是鎖定的；若 adb 啟動服務無效，UI 自動化會失敗，請先解鎖。'
    }

    $before = Save-KuiesStateBundle -Label 'before'
    $other = Get-KuiesOtherVpnNetwork -ConnectivityText $before.connectivity
    if ($other) {
        Add-KuiesNote "測試前有其他 VPN：$($other.extra)（netId $($other.netId)）"
    }
    $slotFree = Stop-KuiesOtherVpn -PackageId $OtherVpnPackage
    Add-KuiesCheck -Name 'VPN 位置未被其他 App 佔用' -Passed $slotFree -Expected '沒有其他 active VPN' -Actual $(if ($other) { $other.extra } else { 'none' })

    try {
        if (-not $slotFree) { return }

        Enable-KuiesNotificationPermission -PackageId $AppId | Out-Null
        # 先確保是乾淨的「未啟動」狀態，後面的「已建立」檢查才有意義。
        if (Get-KuiesTunnelNetwork -ConnectivityText (Invoke-KuiesShell 'dumpsys connectivity --short').Text) {
            Add-KuiesNote '測試前已有一個 KuiesBlock tunnel，先停掉再測啟動。'
            Stop-KuiesTunnel | Out-Null
        }
        # 不用 force-stop：實測 force-stop 會讓系統重新跳出 VPN 連線要求。
        Invoke-KuiesShell "am start -n $AppId/.MainActivity" -AllowFailure | Out-Null
        Clear-KuiesLogcat
        $tunnel = Start-KuiesTunnel
        $started = Save-KuiesStateBundle -Label 'started'
        $tunnel = Get-KuiesTunnelNetwork -ConnectivityText $started.connectivity

        $passed = ($null -ne $tunnel)
        Add-KuiesCheck -Name 'KuiesBlock VPN 已建立（非僅靠狀態列）' -Passed $passed -Expected 'connectivity 出現 extra=VPN:tw.kuies.block 的網路' -Actual $(if ($tunnel) { "netId=$($tunnel.netId) iface=$($tunnel.interfaceName)" } else { '未出現' })
        if (-not $tunnel) {
            $log = Save-KuiesLogcat -Full:$FullLogcat -FileName 'logcat-start-attempt.txt'
            if ($log -match 'prepare|consent|授權') {
                Add-KuiesNote 'logcat 顯示可能尚未取得 VPN 授權：請在手機上對 KuiesBlock 允許一次 VPN 連線，再重跑本情境。'
            }
            return
        }

        # VALIDATED 是 ConnectivityService 之後才加上的：tunnel 剛出現時常常還沒有，
        # 所以要有上限地等，否則會把正常的啟動誤判成失敗（同一模式先前曾直接通過）。
        $validatedTunnel = Wait-KuiesCondition -TimeoutSeconds $TimeoutSeconds -Description '等候 tunnel 取得 VALIDATED' -Condition {
            $t = (Invoke-KuiesShell 'dumpsys connectivity --short').Text
            $n = Get-KuiesTunnelNetwork -ConnectivityText $t
            if ($n -and $n.validated) { return $n }
            return $null
        }
        if ($validatedTunnel) { $tunnel = $validatedTunnel }
        Add-KuiesCheck -Name 'tunnel 網路已通過驗證' -Passed $tunnel.validated -Expected 'Capabilities 含 VALIDATED' -Actual "validated=$($tunnel.validated) caps=$($tunnel.capabilities)" -Note "等待上限 $TimeoutSeconds 秒；逾時仍為 false 才算失敗"
        Add-KuiesCheck -Name 'tunnel 有 IPv4 位址與介面' -Passed ([bool]$tunnel.interfaceName) -Expected 'InterfaceName 非空' -Actual "$($tunnel.interfaceName)"
        # 依專案決定（commit 3780269 的 E2）：strict 私人 DNS 模式下 tunnel 刻意不宣告 DNS，
        # 其餘模式才用會攔截的 BASELINE 宣告。所以期待值取決於當下的私人 DNS 模式。
        $strictMode = $started.settings.'global:private_dns_mode' -eq 'hostname'
        if ($strictMode) {
            Add-KuiesCheck -Name 'strict 模式下 tunnel 不宣告 DNS（E2 契約）' -Passed (-not [bool]$tunnel.dnsAddresses) -Expected 'tunnel 不宣告 DNS' -Actual "dns='$($tunnel.dnsAddresses)'" -Note "私人 DNS=$($started.settings.'global:private_dns_mode')/$($started.settings.'global:private_dns_specifier')"
        } else {
            Add-KuiesCheck -Name '非 strict 模式下 tunnel 宣告攔截用 DNS' -Passed ([bool]$tunnel.dnsAddresses) -Expected 'DnsAddresses 非空' -Actual "dns='$($tunnel.dnsAddresses)'" -Note "私人 DNS=$($started.settings.'global:private_dns_mode')"
        }

        $underlying = Get-KuiesNetworkById -Networks $started.networks -NetId ([int]($tunnel.underlying -split ',' | Select-Object -First 1))
        Add-KuiesCheck -Name 'tunnel 有底層網路' -Passed ($null -ne $underlying) -Expected 'UnderlyingNetworks 指向存在的 netId' -Actual "underlying=$($tunnel.underlying)"

        $dnsAfter = @()
        foreach ($hostName in $DnsProbeHosts) { $dnsAfter += Test-KuiesDnsResolution -HostName $hostName }
        Save-KuiesText -Name 'dns-probe-protection-on.json' -Content ($dnsAfter | ConvertTo-Json -Depth 4) | Out-Null
        Add-KuiesNote '提醒：adb shell 的解析不經過 App 的 VPN，這裡只證明系統 resolver 仍可用，不代表攔截有效。'

        $stopped = Stop-KuiesTunnel
        $after = Save-KuiesStateBundle -Label 'stopped'
        $stillThere = Get-KuiesTunnelNetwork -ConnectivityText $after.connectivity
        Add-KuiesCheck -Name '停止後 tunnel 已消失' -Passed (($null -eq $stillThere) -and $stopped) -Expected '沒有 extra=VPN:tw.kuies.block 的網路' -Actual $(if ($stillThere) { "仍存在 netId=$($stillThere.netId)" } else { '已消失' })

        $anonLost = $after.networks | Where-Object { $_.niTransport -eq 'VPN' }
        Add-KuiesCheck -Name '停止後未殘留任何本 App 的 VPN 網路' -Passed (@($anonLost | Where-Object { $_.extra -match [regex]::Escape($AppId) }).Count -eq 0) -Expected '0 個' -Actual "$(@($anonLost | Where-Object { $_.extra -match [regex]::Escape($AppId) }).Count) 個"
    } finally {
        $restore = Restore-KuiesOtherVpn
        if ($restore) {
            foreach ($item in $restore) {
                Add-KuiesCheck -Name "已還原其他 VPN：$($item.package)" -Passed $item.restored -Expected 'VPN 網路重新出現' -Actual $(if ($item.restored) { '已恢復' } else { '未自動恢復，需要你手動重新開啟該 App 的防護' })
            }
        }
        Restore-KuiesNotificationPermission -PackageId $AppId
        Save-KuiesStateBundle -Label 'after-restore' -SkipLogcat | Out-Null
    }
}

# ---------------------------------------------------------------- 情境：dns-declaration

function Invoke-ScenarioDnsDeclaration {
    Assert-KuiesDeviceChangesAllowed -Reason '本情境會 arm debug 實驗模式並重啟 tunnel'

    $app = Get-KuiesAppInfo
    Add-KuiesCheck -Name 'App 已安裝（必須是 debug APK）' -Passed $app.installed -Expected "$AppId 已安裝" -Actual ($app.installed.ToString()) -Note "版本=$($app.versionName)"
    if (-not $app.installed) { return }

    $slotFree = Stop-KuiesOtherVpn -PackageId $OtherVpnPackage
    Add-KuiesCheck -Name 'VPN 位置未被其他 App 佔用' -Passed $slotFree -Expected '沒有其他 active VPN' -Actual ''
    if (-not $slotFree) { return }

    $observations = New-Object System.Collections.ArrayList
    try {
        Enable-KuiesNotificationPermission -PackageId $AppId | Out-Null
        # 順序刻意讓 baseline 排最後：strict 私人 DNS 下 arm baseline 會宣告虛擬 DNS，
        # 依 E2 的調查結論那會讓裝置 DNS 暫時失效，所以把最具破壞性的一項放到最後測。
        foreach ($mode in @('e2', 'e1', 'baseline')) {
            Write-Host "== arm DNS 宣告模式：$mode ==" -ForegroundColor Cyan
            $arm = Invoke-KuiesShell "am broadcast -n $AppId/.debug.DebugDnsExperimentReceiver -a tw.kuies.block.debug.SET_DNS_EXPERIMENT --es mode $mode" -AllowFailure
            Add-KuiesCheck -Name "arm $mode 廣播被接受" -Passed ($arm.Text -match 'Broadcast completed') -Expected 'Broadcast completed' -Actual $arm.Text.Trim()

            $tunnel = Start-KuiesTunnel
            if (-not $tunnel) {
                Add-KuiesCheck -Name "[$mode] tunnel 可建立" -Passed $false -Expected 'tunnel 出現' -Actual '未出現（可能尚未授權 VPN）'
                continue
            }
            $bundle = Save-KuiesStateBundle -Label "declaration-$mode"
            $tunnel = Get-KuiesTunnelNetwork -ConnectivityText $bundle.connectivity
            $routeCount = ([regex]::Matches($tunnel.raw, 'mtu \d+')).Count
            $underlyingNet = Get-KuiesNetworkById -Networks $bundle.networks -NetId ([int]($tunnel.underlying -split ',' | Select-Object -First 1))
            $expectedDot = if ($underlyingNet) { $underlyingNet.validatedPrivateDns } else { '' }
            $observations.Add([pscustomobject]@{
                mode                = $mode
                netId               = $tunnel.netId
                interfaceName       = $tunnel.interfaceName
                dnsAddresses        = $tunnel.dnsAddresses
                validated           = $tunnel.validated
                routeEntryCount     = $routeCount
                underlyingValidated = $expectedDot
            }) | Out-Null

            # 契約來自 DnsExperimentPlanner.declarationFor：
            #   baseline 宣告虛擬 DNS、e2 刻意不宣告、e1 宣告真實 DoT 位址且刻意不加該位址路由。
            switch ($mode) {
                'e2' {
                    Add-KuiesCheck -Name 'e2：tunnel 刻意不宣告 DNS' -Passed (-not [bool]$tunnel.dnsAddresses) -Expected '不宣告 DNS' -Actual "dns='$($tunnel.dnsAddresses)'"
                }
                'e1' {
                    $e1Ok = [bool]$tunnel.dnsAddresses
                    $firstExpected = if ($expectedDot) { ($expectedDot -split ',')[0].Trim() } else { '' }
                    if ($firstExpected) { $e1Ok = $e1Ok -and ($tunnel.dnsAddresses -match [regex]::Escape($firstExpected)) }
                    Add-KuiesCheck -Name 'e1：tunnel 宣告真實 DoT 位址' -Passed $e1Ok -Expected $firstExpected -Actual "dns='$($tunnel.dnsAddresses)'" -Note 'e1 刻意不為該位址加路由'
                }
                default {
                    Add-KuiesCheck -Name 'baseline：tunnel 宣告虛擬 DNS' -Passed ([bool]$tunnel.dnsAddresses) -Expected 'DnsAddresses 非空' -Actual "dns='$($tunnel.dnsAddresses)'"
                }
            }
            # 只有出貨模式 e2 要求 tunnel 可用（VALIDATED）。e1/baseline 在 strict 私人 DNS 下
            # 刻意宣告無法回應的 DNS，App 會依 fail-open 規則自行停止防護 —— 那是這個實驗
            # 要觀察的結果，不是斷言，所以只記錄下來。
            if ($mode -eq 'e2') {
                Add-KuiesCheck -Name 'e2：tunnel 已通過驗證' -Passed $tunnel.validated -Expected 'VALIDATED' -Actual "validated=$($tunnel.validated) routes=$routeCount"
                Stop-KuiesTunnel | Out-Null
            } else {
                Start-Sleep -Seconds 8
                $after = (Invoke-KuiesShell 'dumpsys connectivity --short').Text
                $stillUp = [bool](Get-KuiesTunnelNetwork -ConnectivityText $after)
                Save-KuiesText -Name "connectivity-declaration-$mode-after8s.txt" -Content $after | Out-Null
                Add-KuiesCheck -Name "[$mode] 宣告結果已記錄（觀察項，非斷言）" -Passed $true -Actual "validated=$($tunnel.validated) routes=$routeCount 8 秒後 tunnel=$(if ($stillUp) { '仍在' } else { '已自行結束' })" -Note 'strict 模式下宣告無法回應的 DNS：tunnel 仍建立但未被 VALIDATED；先前同一模式的後續觀察曾看到 App 已自行停止防護（fail-open），實際延遲未在此量測'
                if ($stillUp) { Stop-KuiesTunnel | Out-Null }
            }
        }
    } finally {
        Invoke-KuiesShell "am broadcast -n $AppId/.debug.DebugDnsExperimentReceiver -a tw.kuies.block.debug.SET_DNS_EXPERIMENT --es mode baseline" -AllowFailure | Out-Null
        Stop-KuiesTunnel | Out-Null
        $restore = Restore-KuiesOtherVpn
        if ($restore) {
            foreach ($item in $restore) {
                Add-KuiesCheck -Name "已還原其他 VPN：$($item.package)" -Passed $item.restored -Expected 'VPN 網路重新出現' -Actual $(if ($item.restored) { '已恢復' } else { '未自動恢復，需要你手動重新開啟該 App 的防護' })
            }
        }
        Restore-KuiesNotificationPermission -PackageId $AppId
    }
    Save-KuiesText -Name 'dns-declarations.json' -Content ($observations | ConvertTo-Json -Depth 5) | Out-Null
}

# ---------------------------------------------------------------- 情境：network-switch

function Get-KuiesNetworkDiagnosis {
    <#
        把「現在到底哪一層壞掉」拆成可分辨的觀察值：
          carrier  —— 實際承載網際網路的底層網路（沿 UnderlyingNetworks 追蹤）
          wifi     —— Wi-Fi 網路是否存在並取得 INTERNET，以及它是否通過 VALIDATED
          vpn      —— VPN 是否存在、其 UnderlyingNetworks 是否指向現存的網路
          privateDns —— 承載網路上的私人 DNS 設定與驗證狀態
        只回傳觀察值，判定留給呼叫端，這樣五種原因才能分別斷言。
    #>
    param([Parameter(Mandatory)][AllowEmptyString()][string]$ConnectivityText)

    $nets = Convert-KuiesConnectivityText -Text $ConnectivityText
    $carrier = Resolve-KuiesDnsCarrierNetwork -ConnectivityText $ConnectivityText
    $vpn = @(Get-KuiesVpnNetworks -Text $ConnectivityText) | Select-Object -First 1
    $wifi = @($nets | Where-Object {
            $_.niTransport -ne 'VPN' -and $_.transports -match 'WIFI' -and $_.capabilities -match 'INTERNET'
        }) | Select-Object -First 1

    $carrierNet = $carrier.network
    $carrierNetId = $null
    $carrierTransports = ''
    $carrierInterface = ''
    $carrierCapabilities = ''
    $carrierValidated = $false
    $carrierUsePrivateDns = $null
    $carrierPrivateDnsServer = ''
    $carrierValidatedAddress = ''
    if ($carrierNet) {
        $carrierNetId = $carrierNet.netId
        $carrierTransports = $carrierNet.transports
        $carrierInterface = $carrierNet.interfaceName
        $carrierCapabilities = $carrierNet.capabilities
        $carrierValidated = $carrierNet.validated
        $carrierUsePrivateDns = $carrierNet.usePrivateDns
        $carrierPrivateDnsServer = $carrierNet.privateDnsServer
        $carrierValidatedAddress = $carrierNet.validatedPrivateDns
    }

    $wifiNetId = $null
    $wifiValidated = $false
    $wifiInterface = ''
    $wifiCapabilities = ''
    if ($wifi) {
        $wifiNetId = $wifi.netId
        $wifiValidated = $wifi.validated
        $wifiInterface = $wifi.interfaceName
        $wifiCapabilities = $wifi.capabilities
    }

    $vpnNetId = $null
    $vpnExtra = ''
    $vpnUnderlying = ''
    $vpnUnderlyingIds = @()
    if ($vpn) {
        $vpnNetId = $vpn.netId
        $vpnExtra = $vpn.extra
        $vpnUnderlying = $vpn.underlying
        if ($vpn.underlying) {
            $vpnUnderlyingIds = @($vpn.underlying -split ',' |
                ForEach-Object { $_.Trim() } |
                Where-Object { $_ -match '^\d+$' } |
                ForEach-Object { [int]$_ })
        }
    }
    $vpnUnderlyingExists = $false
    if ($vpnUnderlyingIds.Count -eq 1) {
        $vpnUnderlyingExists = [bool](Get-KuiesNetworkById -Networks $nets -NetId $vpnUnderlyingIds[0])
    }

    return [pscustomobject]@{
        defaultNetId            = Get-KuiesDefaultNetId -Text $ConnectivityText
        carrierStatus           = $carrier.status
        carrierReason           = $carrier.reason
        carrierHops             = @($carrier.hops)
        carrierNetId            = $carrierNetId
        carrierTransports       = $carrierTransports
        carrierInterface        = $carrierInterface
        carrierCapabilities     = $carrierCapabilities
        carrierValidated        = $carrierValidated
        carrierUsePrivateDns    = $carrierUsePrivateDns
        carrierPrivateDnsServer = $carrierPrivateDnsServer
        carrierValidatedAddress = $carrierValidatedAddress
        wifiConnected           = [bool]$wifi
        wifiNetId               = $wifiNetId
        wifiValidated           = $wifiValidated
        wifiInterface           = $wifiInterface
        wifiCapabilities        = $wifiCapabilities
        vpnNetId                = $vpnNetId
        vpnExtra                = $vpnExtra
        vpnUnderlying           = $vpnUnderlying
        vpnUnderlyingIds        = @($vpnUnderlyingIds)
        vpnUnderlyingExists     = $vpnUnderlyingExists
        networks                = $nets
    }
}

function Invoke-ScenarioNetworkSwitch {
    Assert-KuiesDeviceChangesAllowed -Reason '本情境會關閉 Wi-Fi / 行動數據'

    if ((Get-KuiesTransportKind) -ne 'usb') {
        throw 'network-switch 會切斷網路，只能在 USB 連線下執行。目前是無線偵錯，請插上 USB 後再跑。'
    }

    $originalSettings = Get-KuiesSettingMap
    $originalWifi = $originalSettings.'global:wifi_on' -eq '1'
    $originalData = $originalSettings.'global:mobile_data' -eq '1'
    $strictPrivateDns = $originalSettings.'global:private_dns_mode' -eq 'hostname'
    Add-KuiesNote "起始：wifi_on=$($originalSettings.'global:wifi_on') mobile_data=$($originalSettings.'global:mobile_data') private_dns=$($originalSettings.'global:private_dns_mode')/$($originalSettings.'global:private_dns_specifier')"

    $steps = New-Object System.Collections.ArrayList
    $tunnelUp = $false

    function Measure-KuiesSwitchStep {
        <#
        針對每一步量測「到底是哪一層壞掉」，把五種可能原因分開成獨立斷言：
        #   (a) Wi-Fi 未連線        (b) Wi-Fi 未通過 VALIDATED
        #   (c) VPN 底層網路未更新  (d) 私人 DNS 驗證失敗
        #   (e) DNS 查詢失敗
        # 逾時也會留下量測結果，不會因為等不到就沒證據。
        #>
        param(
            [Parameter(Mandatory)][string]$Label,
            [Parameter(Mandatory)][string]$ExpectedTransport,   # WIFI | CELLULAR | any
            [int]$WaitSeconds = 0
        )
        Write-Host "== 量測步驟：$Label（期待承載網路：$ExpectedTransport）==" -ForegroundColor Cyan

        if ($WaitSeconds -le 0) { $WaitSeconds = $TimeoutSeconds }
        $deadline = (Get-Date).AddSeconds($WaitSeconds)
        $diag = $null
        do {
            $text = (Invoke-KuiesShell 'dumpsys connectivity --short').Text
            $diag = Get-KuiesNetworkDiagnosis -ConnectivityText $text
            if ($ExpectedTransport -eq 'any') { break }
            if ($diag.carrierStatus -eq 'OK' -and $diag.carrierTransports -match $ExpectedTransport) { break }
            Start-Sleep -Seconds 2
        } while ((Get-Date) -lt $deadline)

        # 平台在舊的底層網路消失時會先把 VPN 的 UnderlyingNetworks 清成 Null，App 隨後才重新宣告，
        # 因此底層網路要在這裡等一個有上限的穩定期再斷言 —— 否則量到的是轉換瞬間而不是結果。
        # 斷言本身沒有放寬：等不到仍然 FAIL，只是不再把「宣告還沒落地」誤判成失敗。
        $transientUnderlying = $diag.vpnUnderlying
        $carrierNetIdForWait = $diag.carrierNetId
        if ($tunnelUp -and $carrierNetIdForWait) {
            $settledDiag = Wait-KuiesCondition -TimeoutSeconds ([Math]::Min($TimeoutSeconds, 20)) -Description "等候 $Label 的 VPN 底層網路穩定到承載網路 netId $carrierNetIdForWait" -Condition {
                $t = (Invoke-KuiesShell 'dumpsys connectivity --short').Text
                $c = Get-KuiesNetworkDiagnosis -ConnectivityText $t
                if ($c.vpnUnderlyingIds -contains $carrierNetIdForWait) { return $c }
                return $null
            }
            if ($settledDiag) {
                if ($transientUnderlying -ne $settledDiag.vpnUnderlying) {
                    Add-KuiesNote "[$Label] 底層網路轉換瞬間為 '$transientUnderlying'，穩定後為 '$($settledDiag.vpnUnderlying)'（平台宣告更新有延遲）。"
                }
                $diag = $settledDiag
            }
        }

        Save-KuiesStateBundle -Label $Label -SkipLogcat | Out-Null
        # 這一步另外留下系統網路 tag 的 logcat，用來判斷底層是誰沒有更新。
        Save-KuiesLogcat -SystemNetworkTags -FileName "logcat-$Label.txt" | Out-Null
        Save-KuiesText -Name "diagnosis-$Label.json" -Content ($diag | ConvertTo-Json -Depth 4) | Out-Null

        $dnsProbes = @()
        foreach ($hostName in $DnsProbeHosts) { $dnsProbes += Test-KuiesDnsResolution -HostName $hostName }
        Save-KuiesText -Name "dns-probe-$Label.json" -Content ($dnsProbes | ConvertTo-Json -Depth 4) | Out-Null

        # 私人 DNS 的驗證位址不會立刻出現，另外有上限地等一次。
        $privateDnsValidated = ''
        if ($strictPrivateDns -and $diag.carrierStatus -eq 'OK' -and $diag.carrierNetId) {
            $validatedNet = Wait-KuiesCondition -TimeoutSeconds 20 -Description "等候 $Label 的私人 DNS 完成驗證" -Condition {
                $t = (Invoke-KuiesShell 'dumpsys connectivity --short').Text
                $c = Resolve-KuiesDnsCarrierNetwork -ConnectivityText $t
                if ($c.status -eq 'OK' -and $c.network.validatedPrivateDns) { return $c.network }
                return $null
            }
            if ($validatedNet) { $privateDnsValidated = $validatedNet.validatedPrivateDns }
        }

        # (1) 承載網路是否為期待的那一種
        if ($diag.carrierStatus -ne 'OK') {
            Add-KuiesCheck -Name "[$Label] 判定實際承載網路" -Passed $false -Inconclusive -Expected "承載網路為 $ExpectedTransport" -Actual "無法判定：$($diag.carrierReason)"
        } else {
            $carrierOk = ($ExpectedTransport -eq 'any') -or ($diag.carrierTransports -match $ExpectedTransport)
            Add-KuiesCheck -Name "[$Label] 承載網路為 $ExpectedTransport" -Passed $carrierOk -Expected "transports 含 $ExpectedTransport" -Actual "netId=$($diag.carrierNetId) transports=$($diag.carrierTransports) iface=$($diag.carrierInterface)"
        }

        # (2)(3) Wi-Fi 是否連線、是否通過 VALIDATED —— 這兩項要分開看才分得清原因
        if ($ExpectedTransport -eq 'WIFI') {
            $wifiActual = '找不到帶 INTERNET 的 WIFI 網路'
            if ($diag.wifiConnected) { $wifiActual = "netId=$($diag.wifiNetId) iface=$($diag.wifiInterface)" }
            Add-KuiesCheck -Name "[$Label] Wi-Fi 已連線並取得 INTERNET" -Passed $diag.wifiConnected -Expected '存在帶 INTERNET 的 WIFI 網路' -Actual $wifiActual
            Add-KuiesCheck -Name "[$Label] Wi-Fi 網路已通過 VALIDATED" -Passed $diag.wifiValidated -Expected 'Capabilities 含 VALIDATED' -Actual "validated=$($diag.wifiValidated) caps=$($diag.wifiCapabilities)"
        }

        # (4) VPN 的底層網路是否更新到現在的承載網路
        if ($tunnelUp) {
            if (-not $diag.vpnNetId) {
                Add-KuiesCheck -Name "[$Label] KuiesBlock tunnel 仍在運作" -Passed $false -Expected 'tunnel 網路仍存在' -Actual 'tunnel 已消失'
            } else {
                Add-KuiesCheck -Name "[$Label] VPN 底層網路仍指向存在的網路" -Passed $diag.vpnUnderlyingExists -Expected 'UnderlyingNetworks 指向存在的 netId' -Actual "vpn netId=$($diag.vpnNetId) underlying='$($diag.vpnUnderlying)'"
                if ($diag.carrierNetId) {
                    $underlyingMatches = ($diag.vpnUnderlyingIds -contains $diag.carrierNetId)
                    Add-KuiesCheck -Name "[$Label] VPN 底層網路已更新為實際承載網路" -Passed $underlyingMatches -Expected "underlying = netId $($diag.carrierNetId)" -Actual "underlying='$($diag.vpnUnderlying)'"
                }
            }
        }

        # (5) 私人 DNS 是否生效並完成驗證
        if ($strictPrivateDns) {
            Add-KuiesCheck -Name "[$Label] 承載網路的私人 DNS 設定生效" -Passed ($diag.carrierPrivateDnsServer -eq $PrivateDnsSpecifier) -Expected $PrivateDnsSpecifier -Actual "$($diag.carrierPrivateDnsServer)"
            Add-KuiesCheck -Name "[$Label] 私人 DNS 已完成驗證" -Passed ([bool]$privateDnsValidated) -Expected 'ValidatedPrivateDnsAddresses 非空' -Actual "$privateDnsValidated" -Note '等待上限 20 秒'
        }

        # (6) DNS 查詢是否成功
        foreach ($probe in $dnsProbes) {
            Add-KuiesCheck -Name "[$Label] 系統 resolver 可解析 $($probe.host)" -Passed $probe.resolved -Expected '解析出 IP' -Actual $probe.address
        }

        $steps.Add([pscustomobject]@{
                label                   = $Label
                expectedTransport       = $ExpectedTransport
                defaultNetId            = $diag.defaultNetId
                carrierStatus           = $diag.carrierStatus
                carrierNetId            = $diag.carrierNetId
                carrierTransports       = $diag.carrierTransports
                carrierInterface        = $diag.carrierInterface
                carrierValidated        = $diag.carrierValidated
                carrierUsePrivateDns    = $diag.carrierUsePrivateDns
                carrierPrivateDnsServer = $diag.carrierPrivateDnsServer
                carrierValidatedAddress = $diag.carrierValidatedAddress
                privateDnsValidated     = $privateDnsValidated
                wifiConnected           = $diag.wifiConnected
                wifiNetId               = $diag.wifiNetId
                wifiValidated           = $diag.wifiValidated
                vpnNetId                = $diag.vpnNetId
                vpnExtra                = $diag.vpnExtra
                vpnUnderlying           = $diag.vpnUnderlying
                dnsProbeResolved        = @($dnsProbes | ForEach-Object { $_.resolved })
            }) | Out-Null

        return $diag
    }

    $other = Get-KuiesOtherVpnNetwork -ConnectivityText (Get-KuiesConnectivityText -FileName 'connectivity-initial.txt')
    if ($other) {
        Add-KuiesNote "測試期間有其他 VPN（$($other.extra)）；default network 會是 VPN，腳本會自動追到底層網路再判定。"
    }

    try {
        if ($WithKuiesTunnel) {
            $app = Get-KuiesAppInfo
            Add-KuiesCheck -Name 'App 已安裝（要讓 tunnel 一起參與切換）' -Passed $app.installed -Expected "$AppId 已安裝" -Actual ($app.installed.ToString()) -Note "版本=$($app.versionName)"
            if (-not $app.installed) { return }

            Enable-KuiesNotificationPermission -PackageId $AppId | Out-Null
            $slotFree = Stop-KuiesOtherVpn -PackageId $OtherVpnPackage
            Add-KuiesCheck -Name 'VPN 位置已讓出給 KuiesBlock' -Passed $slotFree -Expected '沒有其他 active VPN' -Actual ''
            if (-not $slotFree) { return }

            $tunnel = Start-KuiesTunnel
            $tunnelUp = [bool]$tunnel
            $tunnelActual = '未出現'
            if ($tunnel) { $tunnelActual = "netId=$($tunnel.netId) iface=$($tunnel.interfaceName)" }
            Add-KuiesCheck -Name '切換前 KuiesBlock tunnel 已建立' -Passed $tunnelUp -Expected '出現 extra=VPN:tw.kuies.block 的網路' -Actual $tunnelActual
            if (-not $tunnelUp) { return }
        }

        # 步驟 1：起始狀態（Wi-Fi）
        Measure-KuiesSwitchStep -Label 'wifi-initial' -ExpectedTransport 'WIFI' | Out-Null

        # 步驟 2：Wi-Fi -> 行動網路（已知正常，作為對照）
        Invoke-KuiesShell 'svc wifi disable' -AllowFailure | Out-Null
        Measure-KuiesSwitchStep -Label 'wifi-off' -ExpectedTransport 'CELLULAR' | Out-Null

        # 步驟 3：行動網路 -> Wi-Fi（回報會壞掉的方向）
        Invoke-KuiesShell 'svc wifi enable' -AllowFailure | Out-Null
        Measure-KuiesSwitchStep -Label 'wifi-back' -ExpectedTransport 'WIFI' -WaitSeconds ([math]::Max($TimeoutSeconds, 60)) | Out-Null

        # 步驟 4：靜置後再量一次，分辨「一直沒回來」與「只是慢」
        Start-Sleep -Seconds 20
        Measure-KuiesSwitchStep -Label 'wifi-back-settled' -ExpectedTransport 'WIFI' -WaitSeconds 30 | Out-Null
    } finally {
        if ($tunnelUp) {
            Write-Host '== 停止 KuiesBlock tunnel ==' -ForegroundColor Cyan
            $stopped = Stop-KuiesTunnel
            Add-KuiesCheck -Name '已停止 KuiesBlock tunnel' -Passed $stopped -Expected 'tunnel 消失' -Actual "$stopped"
        }
        $restore = Restore-KuiesOtherVpn
        if ($restore) {
            foreach ($item in $restore) {
                Add-KuiesCheck -Name "已還原其他 VPN：$($item.package)" -Passed $item.restored -Expected 'VPN 網路重新出現' -Actual $(if ($item.restored) { '已恢復' } else { '未自動恢復，需要你手動重新開啟該 App 的防護' })
            }
        }
        Restore-KuiesNotificationPermission -PackageId $AppId
        Write-Host '== 還原無線設定 ==' -ForegroundColor Cyan
        if ($originalWifi) {
            Invoke-KuiesShell 'svc wifi enable' -AllowFailure | Out-Null
        } else {
            Invoke-KuiesShell 'svc wifi disable' -AllowFailure | Out-Null
        }
        if ($originalData) {
            Invoke-KuiesShell 'svc data enable' -AllowFailure | Out-Null
        } else {
            Invoke-KuiesShell 'svc data disable' -AllowFailure | Out-Null
        }
        $restored = Get-KuiesSettingMap
        Add-KuiesCheck -Name 'Wi-Fi 狀態已還原' -Passed ($restored.'global:wifi_on' -eq $originalSettings.'global:wifi_on') -Expected "wifi_on=$($originalSettings.'global:wifi_on')" -Actual "wifi_on=$($restored.'global:wifi_on')"
        Add-KuiesCheck -Name '行動數據狀態已還原' -Passed ($restored.'global:mobile_data' -eq $originalSettings.'global:mobile_data') -Expected "mobile_data=$($originalSettings.'global:mobile_data')" -Actual "mobile_data=$($restored.'global:mobile_data')"
        Save-KuiesStateBundle -Label 'after-restore' -SkipLogcat | Out-Null
    }

    Save-KuiesText -Name 'network-steps.json' -Content ($steps | ConvertTo-Json -Depth 4) | Out-Null
}

function Get-KuiesFilterProbeResult {
    # 解析 App 自己發出的查詢結果：probe domain=<d> rcode=<r> answers=<n> ... locallyBlocked=<c>
    param([Parameter(Mandatory)][AllowEmptyString()][string]$LogText, [Parameter(Mandatory)][string]$Domain)

    $pattern = 'probe domain=' + [regex]::Escape($Domain) + '\s+rcode=(\S+)\s+answers=(\S+)[^\r\n]*locallyBlocked=(\d+)'
    $match = [regex]::Match($LogText, $pattern)
    if (-not $match.Success) { return $null }
    return [pscustomobject]@{
        rcode          = $match.Groups[1].Value
        answers        = $match.Groups[2].Value
        locallyBlocked = [int]$match.Groups[3].Value
    }
}

function Invoke-KuiesFilterProbe {
    param([Parameter(Mandatory)][string]$Domain, [int]$TimeoutSeconds = 20)

    # 先清 logcat：同一個網域會 probe 多次（規則前／規則後），不清的話會讀到上一次的舊結果。
    Clear-KuiesLogcat
    Invoke-KuiesShell "am broadcast -n $AppId/.debug.DebugFilterReceiver -a tw.kuies.block.debug.PROBE_DNS --es domain $Domain" -AllowFailure | Out-Null
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 500
        $text = Save-KuiesLogcat -FileName "logcat-filter-probe-$Domain.txt"
        $result = Get-KuiesFilterProbeResult -LogText $text -Domain $Domain
        if ($result) { return $result }
    }
    return $null
}

function Invoke-ScenarioFilter {
    # 本機規則是否真的生效，必須由 App 自己送出的查詢來證明：
    #  * `adb shell` 的解析不經過 App 的 VPN，ping 不能拿來當證據；
    #  * 上游 AdGuard Home 對它自己封鎖的名稱也會回 NXDOMAIN，所以 rcode 單獨不足以證明是本機擋的。
    # 因此本情境用 debug receiver 灌入已知規則，再由 App 對虛擬 DNS 送出真查詢，比對
    # rcode / answer 數 / 本機封鎖計數三者。
    Assert-KuiesDeviceChangesAllowed -Reason '本情境會啟動與停止 App 的 VPN'

    $app = Get-KuiesAppInfo
    Add-KuiesCheck -Name 'App 已安裝（debug APK，含測試用 receiver）' -Passed $app.installed -Expected "$AppId 已安裝" -Actual ($app.installed.ToString()) -Note "版本=$($app.versionName)"
    if (-not $app.installed) { return }

    $blockedHost = 'example.com'
    $controlHost = 'dns.kuies.tw'

    $before = Save-KuiesStateBundle -Label 'before'
    $strict = $before.settings.'global:private_dns_mode' -eq 'hostname'
    Add-KuiesNote "私人 DNS=$($before.settings.'global:private_dns_mode')（strict=$strict）；本機過濾只在非 strict 模式下可能生效。"

    $other = Get-KuiesOtherVpnNetwork -ConnectivityText $before.connectivity
    $slotFree = Stop-KuiesOtherVpn -PackageId $OtherVpnPackage
    Add-KuiesCheck -Name 'VPN 位置未被其他 App 佔用' -Passed $slotFree -Expected '沒有其他 active VPN' -Actual $(if ($other) { $other.extra } else { 'none' })
    if (-not $slotFree) { return }

    $permissionGranted = $false
    $tunnelUp = $false
    try {
        Enable-KuiesNotificationPermission -PackageId $AppId | Out-Null
        $permissionGranted = $true
        Invoke-KuiesShell "am start -n $AppId/.MainActivity" -AllowFailure | Out-Null
        Clear-KuiesLogcat
        $tunnel = Start-KuiesTunnel
        $started = Save-KuiesStateBundle -Label 'started'
        $tunnel = Get-KuiesTunnelNetwork -ConnectivityText $started.connectivity
        $tunnelUp = ($null -ne $tunnel)
        Add-KuiesCheck -Name 'KuiesBlock VPN 已建立' -Passed $tunnelUp -Expected 'connectivity 出現 extra=VPN:tw.kuies.block' -Actual $(if ($tunnel) { "netId=$($tunnel.netId)" } else { '未出現' })
        if (-not $tunnelUp) { return }

        # 灌入已知規則：example.com 由本機封鎖，dns.kuies.tw 不在規則內（上游照常解析）。
        Invoke-KuiesShell "am broadcast -n $AppId/.debug.DebugFilterReceiver -a tw.kuies.block.debug.SET_FILTER_RULES --es domains $blockedHost" -AllowFailure | Out-Null
        Start-Sleep -Milliseconds 500
        Invoke-KuiesShell "am broadcast -n $AppId/.debug.DebugFilterReceiver -a tw.kuies.block.debug.RESET_FILTER_COUNTER" -AllowFailure | Out-Null
        Start-Sleep -Milliseconds 300

        # E2 契約：strict 私人 DNS 模式下 tunnel 不宣告 DNS（其餘模式才宣告攔截用的虛擬 DNS）。
        # 注意：strict 模式下 /32 路由仍然存在，所以「明確送往 10.111.222.2」的封包還是會進隧道；
        # 系統 resolver 不會用它，因此系統層級的攔截並未生效（見下方說明與 UI 狀態）。
        if ($strict) {
            Add-KuiesCheck -Name 'strict 模式：tunnel 不宣告 DNS（E2 契約）' -Passed (-not [bool]$tunnel.dnsAddresses) -Expected 'DnsAddresses 為空' -Actual "dns='$($tunnel.dnsAddresses)'"
            Add-KuiesNote 'strict 模式下系統 resolver 不使用虛擬 DNS，本機過濾對系統層級查詢不生效；下列 probe 送出的封包是「明確送往虛擬 DNS」，因此仍會進隧道並被本機規則回答。'
        } else {
            Add-KuiesCheck -Name '非 strict 模式：tunnel 宣告攔截用虛擬 DNS' -Passed ([bool]$tunnel.dnsAddresses) -Expected 'DnsAddresses 非空' -Actual "dns='$($tunnel.dnsAddresses)'"
        }

        $blockedProbe = Invoke-KuiesFilterProbe -Domain $blockedHost
        Save-KuiesText -Name 'filter-probe-blocked.json' -Content ($blockedProbe | ConvertTo-Json -Depth 4) | Out-Null
        $blockedOk = ($null -ne $blockedProbe) -and ($blockedProbe.rcode -eq 'NXDOMAIN') -and ($blockedProbe.answers -eq '0') -and ($blockedProbe.locallyBlocked -ge 1)
        Add-KuiesCheck -Name '本機規則命中：查詢被本機擋下（NXDOMAIN 且本機計數增加）' -Passed $blockedOk -Expected 'rcode=NXDOMAIN answers=0 locallyBlocked>=1' -Actual $(if ($blockedProbe) { "rcode=$($blockedProbe.rcode) answers=$($blockedProbe.answers) locallyBlocked=$($blockedProbe.locallyBlocked)" } else { '沒有回應' }) -Note 'NXDOMAIN 本身不足以證明是本機擋的（上游也會回 NXDOMAIN）；本機封鎖計數才是證據。'
        if (-not $blockedOk) { return }

        # 未命中的名稱仍必須走原本的 DoH 路徑（有答案），且不受本機規則影響。
        $controlProbe = Invoke-KuiesFilterProbe -Domain $controlHost
        Save-KuiesText -Name 'filter-probe-control.json' -Content ($controlProbe | ConvertTo-Json -Depth 4) | Out-Null
        $controlOk = ($null -ne $controlProbe) -and ($controlProbe.rcode -eq 'NOERROR') -and ([int]$controlProbe.answers -ge 1)
        Add-KuiesCheck -Name '未命中的名稱仍由上游 DoH 正常解析' -Passed $controlOk -Expected 'rcode=NOERROR 且 answers>=1' -Actual $(if ($controlProbe) { "rcode=$($controlProbe.rcode) answers=$($controlProbe.answers) locallyBlocked=$($controlProbe.locallyBlocked)" } else { '沒有回應' })

        if ($blockedProbe -and $controlProbe) {
            Add-KuiesCheck -Name '未命中的查詢沒有被本機規則攔下（計數未增加）' -Passed ($controlProbe.locallyBlocked -eq $blockedProbe.locallyBlocked) -Expected "locallyBlocked=$($blockedProbe.locallyBlocked)" -Actual "locallyBlocked=$($controlProbe.locallyBlocked)"
        }

        # 清掉規則後同一個名稱必須恢復正常解析：證明先前的 NXDOMAIN 來自本機規則。
        Invoke-KuiesShell "am broadcast -n $AppId/.debug.DebugFilterReceiver -a tw.kuies.block.debug.CLEAR_FILTER_RULES" -AllowFailure | Out-Null
        Start-Sleep -Milliseconds 500
        $clearedProbe = Invoke-KuiesFilterProbe -Domain $blockedHost
        Save-KuiesText -Name 'filter-probe-cleared.json' -Content ($clearedProbe | ConvertTo-Json -Depth 4) | Out-Null
        $clearedOk = ($null -ne $clearedProbe) -and ($clearedProbe.rcode -eq 'NOERROR') -and ([int]$clearedProbe.answers -ge 1)
        Add-KuiesCheck -Name '清空規則後同一名稱恢復解析（證明剛剛是本機規則擋下的）' -Passed $clearedOk -Expected 'rcode=NOERROR 且 answers>=1' -Actual $(if ($clearedProbe) { "rcode=$($clearedProbe.rcode) answers=$($clearedProbe.answers)" } else { '沒有回應' })

        $probeLog = Save-KuiesLogcat -Full:$FullLogcat -FileName 'logcat-filter.txt'
    } finally {
        Invoke-KuiesShell "am broadcast -n $AppId/.debug.DebugFilterReceiver -a tw.kuies.block.debug.CLEAR_FILTER_RULES" -AllowFailure | Out-Null
        if ($tunnelUp) { Stop-KuiesTunnel | Out-Null }
        $restore = Restore-KuiesOtherVpn
        if ($restore) {
            foreach ($item in $restore) {
                Add-KuiesCheck -Name "已還原其他 VPN：$($item.package)" -Passed $item.restored -Expected 'VPN 網路重新出現' -Actual $(if ($item.restored) { '已恢復' } else { '未自動恢復，需要你手動重新開啟該 App 的防護' })
            }
        }
        if ($permissionGranted) { Restore-KuiesNotificationPermission -PackageId $AppId }
        Save-KuiesStateBundle -Label 'after-restore' -SkipLogcat | Out-Null
    }
}

# ---------------------------------------------------------------- 主流程

try {
    $serial = Initialize-KuiesTest -AdbPath $AdbPath -Serial $Serial
    $runDir = New-KuiesRunDirectory -Scenario $Scenario
    Write-Host "KuiesBlock 測試：情境=$Scenario 裝置=$serial（$(Get-KuiesTransportKind)）" -ForegroundColor Cyan
    Write-Host "產出目錄：$runDir" -ForegroundColor Cyan

    Save-KuiesText -Name 'run-header.json' -Content ([pscustomobject]@{
            scenario  = $Scenario
            serial    = $serial
            transport = Get-KuiesTransportKind
            startedAt = $script:KuiesStartedAt
            switches  = @{
                allowDeviceChanges = [bool]$AllowDeviceChanges
                stopOtherVpn       = [bool]$StopOtherVpn
                fullLogcat         = [bool]$FullLogcat
            }
        } | ConvertTo-Json -Depth 4) | Out-Null

    switch ($Scenario) {
        'baseline' { Invoke-ScenarioBaseline }
        'private-dns' { Invoke-ScenarioPrivateDns }
        'protection' { Invoke-ScenarioProtection }
        'dns-declaration' { Invoke-ScenarioDnsDeclaration }
        'network-switch' { Invoke-ScenarioNetworkSwitch }
        'filter' { Invoke-ScenarioFilter }
    }

    $note = ($script:KuiesNotes -join ' / ')
    $summary = Write-KuiesSummary -Scenario $Scenario -RunDir $runDir -Note $note
    $passedCount = $summary.totalChecks - $summary.failedCount - $summary.inconclusiveCount
    Write-Host ("完成：PASS {0}、FAIL {1}、INCONCLUSIVE {2}（共 {3} 項）" -f $passedCount, $summary.failedCount, $summary.inconclusiveCount, $summary.totalChecks) -ForegroundColor Cyan
    if ($summary.failedCount -gt 0) { exit 2 }
    if ($summary.inconclusiveCount -gt 0) { exit 3 }
    exit 0
} catch {
    Write-Host "測試中止：$($_.Exception.Message)" -ForegroundColor Red
    if ($script:KuiesRunDir) {
        Save-KuiesText -Name 'error.txt' -Content ($_ | Out-String) | Out-Null
        Write-KuiesSummary -Scenario $Scenario -RunDir $script:KuiesRunDir -Note "中止：$($_.Exception.Message)" | Out-Null
    }
    exit 1
}
