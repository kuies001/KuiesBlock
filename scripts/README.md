# KuiesBlock 真機測試腳本

用 Windows 本機 `adb` 驅動 Samsung 測試機（SM-F7660 / Android 16）驗證 KuiesBlock 的 VPN 與
私人 DNS 行為。每個情境都遵守「**記錄起始狀態 → 執行操作 → 驗證實際狀態**」，並把證據留在
`artifacts\run-<時間>-<情境>\`，方便後續比對，不必手動匯出。

## 快速開始

```cmd
rem 唯讀：只記錄現況並驗證收集器可用（任何時候都能跑）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\device-test.ps1 -Scenario baseline

rem 切換私人 DNS（off / 自動 / strict），結束後還原原值
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\device-test.ps1 -Scenario private-dns -AllowDeviceChanges

rem 啟動／停止 KuiesBlock 防護，並在測試期間讓出 VPN 位置
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\device-test.ps1 -Scenario protection -AllowDeviceChanges -StopOtherVpn

rem 切換 debug 專用 DNS 宣告模式（baseline / e2 / e1）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\device-test.ps1 -Scenario dns-declaration -AllowDeviceChanges -StopOtherVpn

rem 第四階段：Wi-Fi ↔ 行動網路（必須是 USB 連線）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\device-test.ps1 -Scenario network-switch -AllowDeviceChanges
```

建置與安裝 APK（本機 `java`/`gradle` 不在 PATH，需先指定 JBR）：

```cmd
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr" && gradlew.bat :app:assembleDebug
adb -s <USB 序號> install -r app\build\outputs\apk\debug\app-debug.apk
```

## 參數

| 參數 | 說明 |
| --- | --- |
| `-Scenario` | `baseline`（唯讀）、`private-dns`、`protection`、`dns-declaration`、`network-switch` |
| `-Serial` | 指定裝置序號；未指定時若同時有 USB 與無線偵錯會自動選 USB |
| `-AllowDeviceChanges` | 允許會改變裝置狀態的步驟。沒有這個開關，腳本只讀不寫 |
| `-StopOtherVpn` | 測試前停用佔用 VPN 位置的其他 App（預設 `com.adguard.android`），結束後嘗試還原 |
| `-FullLogcat` | 收集完整 logcat（預設只抓 App 相關 tag） |
| `-TimeoutSeconds` | 單一步驟的等待上限，預設 45 |
| `-DnsProbeHosts` | 解析檢查用的主機，預設 `example.com`、`dns.kuies.tw` |

## 產出

每次執行建立一個目錄 `artifacts\run-yyyyMMdd-HHmmss-<情境>\`，內含：

- `summary.json` / `summary.md`：逐項 PASS / FAIL / INCONCLUSIVE、預期值、實際值
- `dumpsys-connectivity.txt`、`connectivity-<階段>.txt`：每個階段的完整網路狀態
- `settings-<階段>.json`：私人 DNS、飛航、Wi-Fi、行動數據、always-on VPN 快照
- `wifi-<階段>.txt`：`cmd wifi status`
- `logcat-<階段>.txt`：預設只含 `Kuies|DnsDiagnostics|AndroidRuntime|ActivityManager`
- `ui-*.xml`：UI 階層（UI 自動化失敗時可事後查看實際畫面結構）
- 情境專屬檔：`private-dns-observations.json`、`dns-declarations.json`、`network-steps.json`

退出碼：`0` 全數 PASS、`2` 有 FAIL、`3` 沒有 FAIL 但存在 INCONCLUSIVE、`1` 執行中止（錯誤內容寫在
`error.txt`）。INCONCLUSIVE 代表「無法判定」（網路識別不明確、平台無法回答該問題），
**它不會被算成 PASS**，也不會被壓成 FAIL——診斷資訊完整留在 `summary.json` / `summary.md`。

## 判定原則（重要）

- **不以狀態列圖示判定成功。** VPN 是否成立一律看 `dumpsys connectivity` 是否出現
  `extra=VPN:tw.kuies.block` 的網路，並檢查其 `VALIDATED`、DNS 宣告與底層網路。
- **`adb shell` 的名稱解析不經過 App 的 VPN**，所以它只能證明「系統 resolver 仍可用」，
  不能證明 App 的攔截有效；App 內的 DNS 行為必須在手機上的 App 內判定。
- 本機 `dumpsys netd` / `dumpsys dnsresolver` 一律回 `Can't find service`，因此 resolver
  狀態只從 `dumpsys connectivity` 與 logcat 取得，不把它們當唯一診斷來源。
- **私人 DNS 只認「實際承載網際網路的底層網路」。** 腳本從 `Active default network` 出發，遇到
  VPN 就沿 `UnderlyingNetworks` 逐層追蹤：每一跳都必須存在且唯一，最後那個網路必須具備
  `INTERNET` capability 與 `InterfaceName`；然後比對它的 `netId`、`Transports`、
  `LinkProperties`（`UsePrivateDns`、`PrivateDnsServerName`、`ValidatedPrivateDnsAddresses`）
  與驗證狀態。任何一步不明確（多個底層網路、netId 不存在、沒有 INTERNET）都輸出
  **INCONCLUSIVE**，不以 default netId 或第一筆 WIFI 網路推斷 DNS 路徑。
- 每次切換私人 DNS 前後各判定一次承載網路；若識別改變（例如行動網路換手），重新擷取並把該項
  標為 INCONCLUSIVE。

## 安全界線（腳本內建）

- 預設唯讀；任何寫入（`settings put`、`svc wifi/data`、啟動／停止 VPN）都需要 `-AllowDeviceChanges`。
- `network-switch` 在無線偵錯下直接拒絕執行——切網路會切斷 adb。
- **永不代替使用者按下 VPN 授權同意對話框**；缺少授權時回報為失敗並提示你手動允許一次。
- 被停用的其他 VPN 會記錄在 `$script:KuiesRestore` 並在結束時嘗試還原；無法自動還原時
  摘要會明確標示要你手動重新開啟。
- 私人 DNS 切換一律先記錄原值，結束後還原並驗證。
