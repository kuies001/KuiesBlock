# KuiesBlock 真機自動化能力清單與限制

實測日期 2026-10-12，裝置 Samsung SM-F7660（Android 16 / SDK 36，`user` build，無 root）。
「實測」= 這次真的跑過並留下證據；「未實測」= 指令存在但尚未執行過。

## 可以無人介入完成（已實測）

| 能力 | 做法 | 備註 |
| --- | --- | --- |
| 編譯 / 安裝 Debug APK | `gradlew.bat :app:assembleDebug`、`adb install -r` | 需先 `set JAVA_HOME=<Android Studio jbr>` |
| 裝置探測與版本 | `adb devices -l`、`getprop` | USB 與無線同時存在時腳本自動選 USB |
| 讀取私人 DNS 與網路設定 | `settings get global/secure …` | 含 `private_dns_mode`、`always_on_vpn_app` |
| 讀取網路 / VPN / 私人 DNS 狀態 | `dumpsys connectivity --short` | 每網路可取得 transports、`UsePrivateDns`、`PrivateDnsServerName`、`ValidatedPrivateDnsAddresses`、routes、底層網路 |
| 切換私人 DNS | `settings put global private_dns_mode/specifier` | 已實測 off / opportunistic / hostname 並還原 |
| 系統 DNS 是否可用 | `adb shell ping -c 1 <host>` | 只證明系統 resolver，**不經過 App 的 VPN** |
| UI 自動化 | `uiautomator dump`、`input tap`、`wm dismiss-keyguard` | 按鈕文字為「啟用防護／停用防護」 |
| 影像證據 | `screencap` | |
| logcat | `logcat -d -v threadtime -e '<tag 正則>'` | 預設只抓 App 相關 tag，保留隱私 |
| 啟動 / 停止 App | `am start -n tw.kuies.block/.MainActivity`、`am force-stop` | |
| 授予執行期權限 | `pm grant`（例如 `POST_NOTIFICATIONS`） | 未實測 |
| debug 專用 DNS 宣告切換 | `am broadcast -n tw.kuies.block/.debug.DebugDnsExperimentReceiver -a tw.kuies.block.debug.SET_DNS_EXPERIMENT --es mode baseline|e2|e1` | 僅 debug build、僅當前 process；未實測 |
| 網路切換 | `svc wifi enable|disable`、`svc data enable|disable`、`cmd wifi set-wifi-enabled` | 尚未實測（第四階段） |

## 無法無人介入完成（需人工）

| 項目 | 原因 |
| --- | --- |
| **VPN 授權同意** | `VpnService.prepare()` 的同意對話框無法用 adb 授予；每次重新安裝後第一次需要真人在手機上點一次。腳本不會代按。 |
| **解除螢幕鎖** | `cmd lock_settings get-disabled` = false（有鎖定畫面）；若有 PIN／圖形，螢幕熄滅後無法無人解鎖。不得為了測試清除鎖定。 |
| **還原 AdGuard 之類的第三方 VPN** | 停用可自動（`am force-stop`），但能否自動恢復取決於該 App；無法恢復時必須由使用者手動開啟。 |

## 平台限制（本機／此裝置無法觀測）

| 項目 | 現象 |
| --- | --- |
| `dumpsys netd` / `dumpsys dnsresolver` | 一律回 `Can't find service`，resolver 內部不可觀測 |
| `cmd vpn_management` | `No shell command implementation`，沒有專用 VPN 指令 |
| `adb shell` 的 DNS 解析 | 不經過 App 的 VPN，不能證明攔截有效 |
| `UsePrivateDns` 欄位 | 沒有在用私人 DNS 時整個欄位不存在（不是 `false`） |
| `ValidatedPrivateDnsAddresses` | 切到 strict 後要數秒才出現，單次取樣會誤判 |

## 給 Coding Agent 的自動測試迴圈

1. 跑 `scripts\device-test.ps1 -Scenario <情境>`（唯讀情境不需額外參數）。
2. 讀 `artifacts\run-*\summary.md`：`[FAIL]` 與 `[INCONCLUSIVE]` 都會附預期值與實際值。
3. 需要細節時讀同目錄的 `connectivity-<階段>.txt`、`networks-<階段>.json`、`logcat-<階段>.txt`。
4. 與上一個 run 目錄比對，找出「哪一步開始偏離」。
5. 修正後重跑；**同一問題最多自動修復並重測兩輪**，超過就停下回報，不要無限循環。
6. 退出碼：`0` 全 PASS、`2` 有 FAIL、`3` 無 FAIL 但有 INCONCLUSIVE、`1` 中止（看 `error.txt`）。
   **INCONCLUSIVE 不得當成 PASS 解讀**，也不該為了讓它變綠而放寬斷言；要嘛補足可判定的證據，
   要嘛回報「此問題在本裝置無法判定」。

判定紀律：VPN 是否成立看 `dumpsys connectivity` 是否出現 `extra=VPN:tw.kuies.block` 的網路，
並檢查 `VALIDATED`、DNS 宣告與底層網路；私人 DNS 只認「實際承載網際網路的底層網路」（沿
`UnderlyingNetworks` 逐層驗證，不明確就 INCONCLUSIVE）；**不以狀態列圖示判定**。
