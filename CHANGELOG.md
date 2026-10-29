# Changelog

本專案遵循語意化版本。以下記錄各版本的實際變更。

## v0.6.0

**主題：與系統 Wi-Fi、無線偵錯與私人 DNS 共存**

### 修正

- 無線偵錯頁面改為顯示實體 Wi-Fi 位址：建立隧道時固定讓系統 UID（1000）留在實體網路上，
  Settings 與 system_server 不再把 VPN 當成自己的預設網路。
- 私人 DNS 設定頁不再由 KuiesBlock 單方面描述一個無法完成 strict DoT 的 VPN 網路；
  strict 模式仍維持不宣告 DNS 的共存方式。

### 已知限制

- strict 私人 DNS 下系統的加密 DNS 會繞過本機規則，查詢計數維持「無資料」。
- 私人 DNS 設定頁的「無法連線」字串未能在測試機重現，列為 INCONCLUSIVE；可重現的系統層
  失敗表徵（VPN 網路 `PrivateDnsBroken` 與「這個網路沒有網際網路連線」通知）已不再出現。
- DNS 自動備援換手與備援期間的本機攔截已實機驗證；解除故障後自動切回主要 DNS 的記錄
  未能在 adb 斷線前讀取，列為 INCONCLUSIVE。

## v0.5.1

**主題：設定操作、過濾器自動更新與背景執行保護 UI 優化**

### 新增／變更

- 「過濾器自動更新」的週期改為兩段式：點選不同週期只更新畫面暫存值，選項下方靠右出現
  「儲存並更新」；按下後才寫入設定、重新安排既有的唯一 WorkManager 週期工作，並立即觸發
  一次既有手動更新流程。
- 選回原本儲存的週期、收合摺疊區塊或關閉「自動更新過濾器」都會丟棄尚未儲存的選擇，不會暗中
  寫入設定；關閉開關後仍保留已儲存的週期。
- 設定儲存或排程重新安排失敗時顯示「無法儲存更新週期，請再試一次」，並回復原本儲存的週期，
  不讓畫面與實際生效設定不一致。清單更新失敗不影響已儲存的週期，沿用既有失敗狀態顯示，
  使用者仍可用「立即更新」重試。
- 切換廣告攔截強度（寬鬆／標準／嚴格／自訂）後，立即保存新模式並以既有內容重建本機 Filter
  Snapshot；若該模式已有可用快取，先套用再於背景檢查清單，不再需要進入「過濾器管理」按
  「立即更新」。沒有快取時顯示「正在更新過濾規則…」，下載成功後才發布新 Snapshot。
- 自訂模式勾選或取消來源後立即重新計算生效來源；缺少內容的來源以短時間 debounce 合併為一次
  下載，取消的來源立即退出 Snapshot，已下載內容保留供日後重用。HaGeZi Multi 互斥與至少一個
  來源的限制維持不變。
- 下載固定在單一下載執行緒，結果只合併回仍指向同一清單的設定；快速切換時只採用最後一次選擇，
  較晚完成的舊層級不會覆蓋新模式，也不會新增重複的週期性 Worker。
- 「廣告攔截強度」卡片在切換當下立即顯示「正在更新過濾規則…」與小型不確定進度指示器，選單
  收合時仍可見；成功後短暫顯示「過濾規則已更新」，失敗且快取可用時顯示「更新失敗，使用現有
  規則」，無可用規則時顯示「規則尚未就緒」。沒有實際更新工作時不顯示進度。
- 首頁「背景執行保護」已授權狀態移除「已允許 ✓」文字與文字型勾號，改為右側 20dp 圓形打勾
  圖示（`ic_check_circle`），沿用 Primary 色；TalkBack 描述為「已允許背景執行」。未授權狀態、
  系統授權 Intent 與 `PowerManager.isIgnoringBatteryOptimizations()` 狀態來源均未改變。
- `versionName` → `0.5.1`，`versionCode` → `16`。

### 驗證（v0.5.1）

- `:app:assembleDebug` PASS。
- `:app:assembleRelease`（含 R8 與 lintVital）PASS；release APK 以既有正式金鑰簽章，憑證
  SHA-256 為 `b6f5ccd5b3c8497e8b095db2c653c0dfe5b1c6e9b84b0f67307aad501af3235c`。
- 直接相關單元測試 PASS：暫存與已儲存週期分離、選回原值後儲存按鈕消失、儲存成功後重新排程並
  只觸發一次更新、設定或排程失敗時回滾、清單更新失敗仍保留新週期、關閉自動更新不套用暫存值、
  已授權卡片不再顯示文字狀態且保留無障礙描述；以及切換模式自動觸發下載、快取優先套用、無快取
  未就緒、下載失敗保留有效快取、快速切換時舊結果不覆蓋新模式、自訂來源 debounce 合併、無工作
  時不顯示進度、成功／失敗提示文字與提示位於摺疊內容之外。
- 使用者已確認 v0.5.1 實機操作無問題；自動化實機下載與實際攔截結果未在本輪重跑
  （INCONCLUSIVE）。

### 已知限制（v0.5.1）

- v0.5.0 與之前版本的所有已知限制繼續適用。

## v0.5.0

**主題：背景執行保護**

### 新增／變更

- 首頁「App 個別設定」與「DNS 自動備援」之間新增「背景執行保護」卡片：未授權時顯示說明、
  「尚未允許」與「允許背景執行」按鈕；取得豁免後縮為單列「已允許 ✓」，不再顯示說明或按鈕。
- 授權狀態只以 `PowerManager.isIgnoringBatteryOptimizations()` 為準，每次回到首頁（含從系統
  設定返回）都重新查詢；在 Android 設定撤銷後會恢復申請卡片，不以 SharedPreferences 記錄
  「使用者曾按下允許」。
- 只有使用者點擊按鈕才開啟系統畫面：優先使用 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
  並帶入 `package:` URI；裝置不支援時改用 `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`；
  兩者皆無法開啟時顯示簡短提示，不會閃退，也不模擬或自動點擊系統確認視窗。
- 豁免為使用者自主選擇，不是 VPN 啟動前置條件：未授權仍可啟用防護、使用本機攔截、DNS 自動
  備援、調整過濾器與 App 個別設定。
- 未新增背景服務、週期性 Worker、WakeLock、AlarmManager 或輪詢；沿用既有 VPN 前景服務。
- Manifest 新增 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，僅用於開啟系統授權流程。
- `versionName` → `0.5.0`，`versionCode` → `15`。

### 驗證（v0.5.0）

- `:app:assembleDebug` PASS。
- 直接相關單元測試 PASS：授權狀態讀取與撤銷、直接授權 Intent 與備援頁面選擇、無處理程式時
  回報失敗而不拋出例外、首頁卡片位置與兩種顯示、文案守門。
- 2026-10-20 於 SM-F7660（Android 16）以 0.5.0 完成實機驗證，使用者確認操作無問題。

### 已知限制（v0.5.0）

- Android 電池最佳化豁免不等於三星 One UI 的所有背景限制；本版未使用 Samsung 私有 API，也
  不修改其休眠 App 清單，亦不宣稱已取得三星全部背景執行權限。
- 即使取得豁免，Android 仍可能因系統資源不足、手機廠牌限制或使用者強制停止而終止 App。
- v0.4.50 與之前版本的所有已知限制繼續適用。

## v0.4.50

**主題：DNS 自動備援**

### 新增／變更

- 首頁「App 個別設定」下方新增「DNS 自動備援」卡片，預設開啟；狀態由 VPN Service 實際驗證的
  DNS 路徑驅動，防護未啟動時不顯示「主要 DNS 正常」。
- 主要 DoH 連續失敗達門檻後，先以 Cloudflare 加密 DoH 進行一次真實往返驗證，成功才把上游切換
  至備援；切換不重建 VPN Tunnel、不清除本機規則、統計或 App 排除設定。
- 備援期間以低頻率探測主要 DNS，採逐步退避（30 秒起，最長 5 分鐘）；主要 DNS 通過驗證後自動
  切回，不重新下載過濾清單。
- 關閉備援時若正在使用備援，會先驗證主要 DNS；主要不可用時沿用既有 fail-open，不會暗中繼續
  使用備援。
- fail-open 會先關閉 VPN tunnel、移除前景通知並更新失敗狀態，再停止服務；關閉備援後不會再使用
  已停用的備援上游。
- 啟動時優先驗證主要 DNS；若未能在既有啟動預算內成功，保留時間嘗試備援。兩者都失敗仍
  fail-open，不降級為明文 DNS。
- 新增設定鍵 `dns_auto_fallback_enabled`：首次安裝與舊版升級預設開啟，使用者關閉後重啟仍保持
  關閉。
- 隱私說明補充：主要 DNS 無法使用時，可能透過 Cloudflare 加密 DNS 進行解析。
- `versionName` → `0.4.50`，`versionCode` → `14`。

### 驗證（v0.4.50）

- 2026-10-19 於 SM-F7660（Android 16）以 debug 0.4.50 完成實機驗證：主要 DNS、Cloudflare
  備援切換、備援期間本機規則、主要 DNS 自動恢復、fail-open 與正常停止的資源清理均 PASS。
- 實機測試期間發現並修正 fail-open 未拆除 tunnel 的問題；修正後關閉備援與雙 DNS 失敗都會
  確實停止攔截，系統 DNS 可立即接手。

### 已知限制（v0.4.50）

- Emulator、私人 DNS 修改與「關閉備援後重啟仍保持關閉」未單獨驗證；首頁卡片視覺已於實機檢查。
- v0.4.43 與之前版本的所有已知限制繼續適用。

## v0.4.43

**主題：設定頁摺疊與安裝器修正**

### 新增／變更

- 設定頁的「主題」、「廣告攔截強度」與「過濾器自動更新」改為一致的摺疊選單：
  預設收合、整列可點、右側箭頭、收合時顯示目前設定值。
- 主題選擇後立即套用並自動收合；廣告攔截強度選擇寬鬆／標準／嚴格後自動收合；
  自訂模式保持展開，繼續顯示既有來源勾選。
- 過濾器自動更新收合時顯示「已開啟 · 每 8／24 小時」或「已關閉」；展開才顯示開關與週期。
  關閉再開啟時保留原本週期，WorkManager 排程與更新機制未改變。
- App 內更新改用 Android `PackageInstaller` Session，由系統安裝器負責確認；
  移除 `FileProvider`、一般 APK `ACTION_VIEW` Intent 與 `update_file_paths.xml`，
  避免第三方 APK 安裝器出現在選擇清單。
- 保留 SHA-256、套件名稱、版本與簽章驗證；未通過驗證的 APK 不會進入 Session。
- 保留未知來源安裝授權流程；沒有 root、Accessibility、隱藏 API 或靜默安裝。
- `versionName` → `0.4.43`，`versionCode` → `13`。

### 已知限制（v0.4.43）

- 本版沒有改變 DNS、VPN、過濾器下載與解析、WorkManager、個別 App 防護、私人 DNS 或開機自啟。
- 本輪未執行 Emulator、ADB 或實機測試；摺疊選單、主題立即生效、自訂來源、更新週期、
  PackageInstaller 確認／授權／取消／失敗、第三方安裝器是否絕跡、覆蓋安裝與資料保留全部為 `INCONCLUSIVE`。
- v0.4.42 與之前版本的所有已知限制繼續適用。

## v0.4.42

**主題：主題切換**

### 新增

- 設定頁最上方新增「主題」區塊，位於「廣告攔截強度」之前。
- 提供三個單選模式：跟隨系統（預設）、淺色、深色。
- 切換後立即套用到首頁、設定頁、App 個別設定、對話框與更新下載畫面；狀態列與導覽列圖示明暗同步調整。
- 主題選擇寫入既有私有設定，App 重新啟動後仍保留。

### 變更

- `versionName` → `0.4.42`，`versionCode` → `12`。
- 首次安裝或原本沒有主題設定的使用者預設「跟隨系統」，不修改其他偏好設定。

### 已知限制（v0.4.42）

- 本版只新增主題切換；DNS、VPN、過濾器、個別 App 防護、私人 DNS、開機自啟與 App 內更新流程均未改變。
- 三種主題的實際配色、系統切換同步、狀態列／導覽列對比、各頁與對話框一致性，以及重啟後保留，仍需實機確認。
- v0.4.41 與之前版本的所有已知限制繼續適用。

## v0.4.41

**主題：App 內下載並安裝更新**

### 新增

- 「檢查更新」發現新的正式版本後，可直接在 App 內下載 APK 並交給 Android 系統安裝器；
  不需要再自行前往 GitHub 下載頁。
- 下載階段顯示進度百分比與「取消下載」；下載失敗可重試，取消或失敗會清除未完成暫存檔。
- 下載完成後先驗證，再顯示「下載完成，準備安裝」並開啟系統安裝器。
- 未允許安裝未知來源 App 時，導向系統設定；返回且授權後繼續開啟同一份已驗證 APK。
- 使用 FileProvider 與 `REQUEST_INSTALL_PACKAGES`，只授予暫時性的 APK 讀取權限。

### 安全驗證

- 只接受 `https://github.com/kuies001/KuiesBlock/releases/download/<tag>/<name>` 的單一官方 APK；
  轉址只允許 GitHub 官方 Release CDN。
- SHA-256 優先採用 Release asset digest，沒有時使用同一 Release 的 `.sha256`；
  無法取得可信雜湊值時不允許安裝。
- 驗證 APK 套件名稱 `tw.kuies.block`、`versionName` 與 Release tag 一致、
  `versionCode` 高於目前安裝版本，且不允許降版。
- 使用 Android `GET_SIGNING_CERTIFICATES` 解析並驗證 APK 簽章，再比對憑證 SHA-256：
  `b6f5ccd5b3c8497e8b095db2c653c0dfe5b1c6e9b84b0f67307aad501af3235c`。
- Debug 簽章與正式簽章不同時顯示無法直接覆蓋的說明，不要求清除資料或解除安裝。

### 變更

- `versionName` → `0.4.41`，`versionCode` → `11`。
- 版本比較維持三段數字逐段比較，`0.4.4 < 0.4.41 < 0.4.42 < 0.5.0`。

### 已知限制（v0.4.41）

- 本版沒有改變 DNS、VPN、過濾規則、WorkManager、個別 App 防護、私人 DNS 或開機自啟。
- 更新仍由使用者手動觸發，最後由 Android 系統安裝確認；不會自動下載或靜默安裝。
- Debug 簽章版本無法直接覆蓋為正式簽章版本。
- 實機下載、取消、未知來源授權返回、系統安裝器覆蓋安裝與資料保留仍為 `INCONCLUSIVE`。
- v0.4.4 與之前版本的所有已知限制繼續適用。

## v0.4.4

**主題：首頁與設定頁 UI 精簡**

### 新增

- 首頁新增「App 個別設定」入口，並顯示目前已排除的 App 數量。
- App 個別防護頁新增「全部 App」與「已排除」分頁；已解除安裝但仍在排除清單中的項目
  會保留在「已排除」分頁，方便恢復。
- 右上角新增「更多選項」，集中「重新過濾所有 App」與確認流程。

### 變更

- 過濾器管理改為精簡版：每個生效來源只顯示一次名稱、Snapshot 的實際生效規則數
  （含千分位）、最後成功更新時間、立即更新、自動更新與 8／24 小時週期。
- 最後更新只採用真正的成功紀錄；失敗時顯示簡短原因並註明仍使用上一次成功的清單；
  尚未下載顯示「尚未下載」，沒有生效規則顯示「尚未生效」。
- 「關於」與「檢查 App 更新」合併為單一卡片，顯示「目前版本：v0.4.4」與「檢查更新」；
  保留 GitHub Stable Release 檢查、更新說明、前往下載與防重複點擊。
- 簡化設定頁文案，移除產品標語、重複版本、維護者介紹、用途長文與重複狀態標籤。
- `versionName` → `0.4.4`，`versionCode` → `10`。

### 移除

- 設定頁移除「連線診斷」區塊、執行 DNS 診斷按鈕、診斷結果與 DoH／TLS 技術資訊；
  底層 DNS 診斷與錯誤處理程式仍保留。
- 設定頁移除重複的「個別 App 防護」入口與獨立的「檢查 App 更新」卡片。

### 已知限制（v0.4.4）

- 本版只調整 UI 與必要的畫面狀態整合；DNS、VPN、過濾規則下載與解析、WorkManager 自動更新、
  個別 App 防護、VPN 排除與重建、開機自動啟動、DoH 端點與私人 DNS 均未改變。
- 連線診斷只剩底層程式，設定頁不再提供手動執行入口。
- Release APK 覆蓋安裝未在本輪實機重新驗證，維持 `INCONCLUSIVE`。
- v0.4.3 與之前版本的所有已知限制繼續適用。

## v0.4.3

**主題：個別 App 防護**

### 新增

- 設定頁新增「個別 App 防護」入口與獨立管理頁：App 搜尋、已安裝 App 清單、圖示、名稱、
  Package Name、每個 App 的防護開關、已排除數量與「全部恢復防護」確認。
- 預設所有 App 都接受 KuiesBlock 防護；可將特定 App 排除，讓它完全略過 KuiesBlock VPN。
- 新增獨立的 `AppExclusionStore`，以 Package Name 持久化，與 `FilterSettings` 分離。
- 使用 `VpnService.Builder.addDisallowedApplication`；執行中的 VPN 以 250 ms debounce 重建，
  新設定建立失敗時回退上一份已套用集合；VPN 未執行時只儲存，下次啟動才套用。
- Manifest 只查詢 `MAIN/LAUNCHER`，不使用 `QUERY_ALL_PACKAGES`；自身與已解除安裝項目安全處理。

### 變更

- `versionName` → `0.4.3`，`versionCode` → `9`。

### 已知限制（v0.4.3）

- 只列出具有 launcher entry 的 App；沒有 launcher 的 package 不會出現在清單。
- 排除是 traffic-level：被排除 App 的流量與 DNS 完全不進 VPN，但仍可能受 Android 私人 DNS
  或其他網路政策影響；這不是逐查詢的 App 歸因。
- 變更排除設定時，執行中的 VPN 會短暫重建；快速連續變更由 250 ms debounce 合併。
- 新排除集合建立失敗時會回退上一份可用集合；若仍失敗，VPN 會停止並顯示失敗。
- 平台拒絕 `establish()` 的完整回退、PackageManager 查詢失敗的 UI、快速切換的 debounce、
  真正重新開機後的保留，以及第三方 App 自身 DNS 流量，仍維持 `INCONCLUSIVE`。
- v0.4.2 與之前版本的所有已知限制繼續適用。

## v0.4.2

**主題：四種廣告攔截模式與預設過濾來源管理**

### 新增

- 廣告攔截強度新增「自訂」，與寬鬆／標準／嚴格合計四種模式。
- 自訂模式可勾選八個預設來源：HaGeZi Multi LIGHT／NORMAL／PRO++、HaGeZi TIF (Mini)、
  AdGuard DNS Filter、OISD、HaGeZi NSFW、HaGeZi Social。
- 三種 HaGeZi Multi 主清單只能擇一；其他來源可複選；至少保留一個來源。
- 來源列顯示名稱、用途、維護者與授權；清單仍由裝置下載，不打包進 APK。
- 過濾器管理顯示目前模式的有效來源、各來源狀態與實際生效規則數。

### 變更

- 寬鬆／標準／嚴格只使用各自 HaGeZi 主清單，不再疊加其他來源，避免預設模式互相影響。
- 切換模式保留既有自訂選擇與已下載內容；自動更新只處理目前模式使用的來源。
- 自訂模式切換來源時立即重建 Snapshot；清單尚未下載完成時顯示真實狀態。
- `versionName` → `0.4.2`，`versionCode` → `8`。

### 移除

- 設定頁移除自訂訂閱 URL／名稱／新增／刪除介面。
- 設定頁移除允許與封鎖清單編輯介面。

### 相容與資料保留

- 原本沒有額外自訂訂閱：保留原本的寬鬆／標準／嚴格模式。
- 原本有啟用的自訂訂閱：一次性遷移到自訂模式，保留原本主清單層級與訂閱資料。
- 舊手動允許／封鎖規則仍保存在裝置上，但不再進入 Snapshot；舊自訂訂閱只在自訂模式參與。
- 已儲存的模式不會在每次啟動被覆寫。

### 已知限制（v0.4.2）

- HaGeZi TIF 完整清單約 39 MB，超過目前的下載／解析上限，因此使用官方 TIF Mini。
- 來源由第三方維護，格式或可用性可能變動；更新失敗時保留上一次成功清單。
- 舊手動規則不再生效，且本版沒有編輯入口。
- 舊自訂訂閱只能在自訂模式參與，本版沒有 URL／名稱編輯入口。
- v0.4.1 與 v0.4.0 的既有關限制全部繼續適用：私人 DNS 指定主機名稱時本機規則無法介入；
  「防護已啟用」不等於本機規則生效；無法與其他一般 VPN 並存；App 自帶 DoH 或其他繞過系統 DNS 的
  流量不保證攔截；不宣稱完整阻擋 YouTube 廣告；只攔截 IPv4 UDP DNS；自動更新與開機自啟為盡力而為；
  「檢查 App 更新」不會自動下載或安裝 APK。

## v0.4.1

**主題：規則自動更新、開機自動恢復防護，以及設定頁的「檢查 App 更新」**

### 新增

- **過濾規則自動檢查更新**：預設**開啟**、每 **24 小時**一次，可切換為每 **8 小時**。
  只更新**已下載過**的清單，因此全新安裝在使用者選擇攔截強度之前不會下載任何清單；
  已下載的清單會自動保持最新，下載失敗時仍保留上一次成功的清單。
  關閉開關會取消週期工作，「立即更新」不受影響。排程使用**單一** WorkManager 週期工作並附加
  `NetworkType.CONNECTED` 條件，切換間隔會就地更新同一個工作，不會留下第二個；
  設定頁會顯示最近一次自動檢查的結果。
- **開機自動恢復防護**：預設**關閉**。使用者明確開啟後，重新開機時**嘗試**恢復防護。
  下列情況一律跳過：未取得 VPN 授權、使用者上次是關閉防護、其他 App 佔用 VPN 位置、
  無法判定 VPN 位置狀態、以及防護已在啟動中。過程中**不會**從背景跳出授權對話框、
  **不會**修改私人 DNS、**不會**停止其他 VPN，也**不會**重試或建立重複的 VPN 服務。
- **設定頁「檢查 App 更新」**：手動查詢 GitHub Releases 的**最新正式版本**（匿名請求，**沒有 Token**），
  以**數值**比較與安裝版本比較，明確區分「發現新版本／目前已是最新版本／目前安裝的版本較新」。
  只接受非草稿、非預先發布的版本；網路失敗、限流或解析失敗都只顯示一句短提示，不會閃退。
  發現新版本時顯示更新說明與「前往下載」，且**只會用瀏覽器開啟專案自己的 GitHub Release 頁面**
  （網址必須通過官方路徑檢查），**不會**自動下載或安裝 APK，也不會要求未知來源安裝權限。

### 變更

- `versionName` → `0.4.1`，`versionCode` → `7`。
- 設定頁新增「開機自動啟動防護」與「檢查 App 更新」兩個區塊，其中「檢查 App 更新」固定在頁面**最下方**。

### 已知限制（v0.4.1）

- **自動更新與開機自啟都是盡力而為，不保證準時或一定成功**：WorkManager 週期工作可能因 Android
  省電機制而延後；開機自動恢復在 App 曾被強制停止、或系統延後／略過開機廣播時不會執行。
- **「檢查 App 更新」只開啟 GitHub Release 頁面**，不會自動下載或安裝 APK。
- **真實行程死亡後 WorkManager 是否自行恢復執行，尚未實機驗證，維持未定論（INCONCLUSIVE）**。
- **v0.4.0 已揭露的限制全部繼續適用**，未因新增功能而放寬：
  僅攔截 IPv4 UDP DNS（DNS-over-TCP 與 IPv6 的 DNS 不攔截）；App 自帶 DoH 或繞過系統 DNS 的流量
  無法保證被擋下；私人 DNS 設為「指定主機名稱」時本機規則無法介入；**「防護已啟用」不等於本機規則
  正在生效**；無法與其他一般 Android VPN 同時運作；**不宣稱能完整阻擋 YouTube 廣告**；
  不顯示廣告攔截次數。

### 測試

- 單元測試共 **588 個**（67 個測試類別），0 失敗、1 略過（略過者為
  `FilterScaleTest.aRealUpstreamListParsesAndAnswersInBoundedTime`，需要環境變數
  `KUIESBLOCK_FILTER_SAMPLE` 才會執行）。
- 新增涵蓋：自動更新的排程政策與工作唯一性、可更新來源的選擇規則、`304` 不覆寫既有清單、
  更新失敗時保留最後成功清單、開機政策每一道閘門（含 VPN 位置狀態未知）、
  App 更新檢查的版本數值比較、官方網址政策與 API 失敗分類。

### 驗證方式

- `:app:testDebugUnitTest`、`:app:assembleDebug`、`:app:assembleRelease`（含 R8 與 lintVital）成功。
- Debug 與 Release APK 皆讀到 `applicationId tw.kuies.block`、`versionName 0.4.1`、`versionCode 7`。
- Release APK 以既有正式簽章簽署，憑證 SHA-256 與 v0.4.0 相同
  （`b6f5ccd5b3c8497e8b095db2c653c0dfe5b1c6e9b84b0f67307aad501af3235c`）。
- Release APK 的 manifest 與 DEX 確認不含逐 App 實驗的除錯元件。
- 同一台實機驗收：設定頁最下方顯示「檢查 App 更新／目前版本 v0.4.1」，
  點擊後因 GitHub 最新正式版為 v0.4.0，正確顯示「目前安裝的版本較新」。

## v0.4.0

**主題：本機過濾引擎、三級攔截強度、規則來源管理、自訂訂閱、手動允許／封鎖，以及首次使用教學**

### 新增

- **本機過濾引擎**：DNS 查詢先由裝置上的規則判斷，命中封鎖規則時直接以 `NXDOMAIN` 回應，未命中的查詢才走 DoH。
  規則不隨 App 安裝散布，一律由裝置端下載；每次發布快照時才重新組裝生效規則。
- **三級廣告攔截強度**：寬鬆／標準（推薦）／嚴格，預設標準。原本的「關閉」選項移除，改由首頁開關負責啟用與停用；
  舊安裝若曾選擇「關閉」，升級後遷移為「標準」，且**不會因此自動開啟 VPN**。
- **過濾器管理與手動更新**：顯示目前清單（HaGeZi Multi NORMAL）的維護者、授權與「目前生效規則 N 條」，
  並提供「立即更新」。規則數一律取自實際發布的快照，不採用訂閱來源自稱的數字。
- **自訂 HTTPS DNS 訂閱**：可新增自己的公開清單來源（最多 10 個），支援新增、停用、重新啟用、更新與刪除；
  來源識別碼取自網址的 SHA-256 前綴，使用者輸入的名稱不會進入檔名。
- **手動允許／封鎖網域**：允許清單與封鎖清單兩個頁籤，可選「僅此網域」或「含所有子網域」，並可逐列移除。
  手動規則一律優先於訂閱規則，且不需要下載。
- **規則更新失敗時保留最後成功清單**：下載失敗時介面明確顯示「仍使用上一次成功的清單」，
  不會因為更新失敗而清空已生效的規則。
- **首次使用教學**：兩頁導覽，說明 VPN 授權與防護開啟；第一頁可在不啟動隧道的前提下完成授權。
- **更清楚的防護生效狀態與操作回饋**：過濾狀態文字會區分「啟動中」與「尚未生效」，
  新增手動規則後會在輸入欄下方直接顯示確認訊息。

### 修正

- 強度設定的狀態列改讀實際防護狀態，不再於防護關閉時顯示「本機規則生效中」。
- 新增手動規則後不再需要捲動才能看到結果：確認訊息與規則列會同時可見，鍵盤也會自動收起。

### 介面

- 設定頁新增「廣告攔截強度」與「過濾器管理」兩個區塊（含自訂訂閱與手動規則）。
- 允許清單固定顯示「允許清單只會略過 KuiesBlock 本機封鎖，上游 DNS 服務仍可能阻擋該網域。」
- 通知文字不再寫出特定的 DoH 主機名稱，改以一般敘述說明未攔截的查詢會經加密連線送往預設 DNS 解析服務。

### 測試

- 單元測試共 **482 個**（52 個測試類別），0 失敗、1 略過（略過者需要環境變數 `KUIESBLOCK_FILTER_SAMPLE` 才會執行）。
- 新增涵蓋：規則解析與比對、訂閱下載與失敗時保留、快照發布與優先序、允許／封鎖規則驗證、
  過濾狀態文字對應、首次導覽閘門與呈現、防護狀態與過濾狀態的區分。

### 變更

- `versionName` → `0.4.0`，`versionCode` → `6`。

### 已知限制（v0.4.0）

- 僅攔截 IPv4 UDP DNS；DNS-over-TCP 不支援，IPv6 的 DNS 不攔截。
- 部分 App 自建的 DNS（自帶 DoH 的瀏覽器）可能繞過攔截；繞過系統 DNS 的流量無法保證被擋下。
- 私人 DNS 設為「指定主機名稱」時，查詢由系統解析器直接送往該主機，**本機規則無法介入**；
  設為「自動」時也可能略過攔截。此時 KuiesBlock 只維持連線正常，不會宣稱正在攔截。
- **「防護已啟用」不等於本機規則正在生效**：清單尚未下載完成，或系統私人 DNS 接管解析時，連線正常但規則沒有作用。
- **無法與其他一般 Android VPN 同時運作**（Android 一次只允許一條 VPN）；偵測到其他 VPN 時僅顯示提示，不會自行停用它。
- **不宣稱能完整阻擋 YouTube 廣告**。
- 不顯示廣告攔截次數：無法在不捏造數據的前提下準確統計。
- v0.4.0 的新功能已在同一台實機逐項驗收；其他 Android 裝置與版本尚未逐一驗證。

### 驗證方式

- `:app:assembleDebug`、`:app:assembleRelease`（含 R8 與 lintVital）成功，Release 以既有正式簽章簽署。
- `:app:testDebugUnitTest`：482 個測試、0 失敗、1 略過。

## v0.3.0

**主題：極簡首頁與設定頁、Android 私人 DNS 共存、Wi-Fi／行動網路交接修復，並完成首次真機驗收**

### 新增

- **首頁全新極簡化**：只保留品牌名稱與標語、設定入口、盾牌、防護狀態標題與一顆啟動／停用按鈕。
  不再顯示 DNS 主機、DoH 端點、傳輸方式或任何計數；只有狀態是 `ERROR` 時才多一段簡短原因與下一步。
- **首頁新增「使用 KuiesBlock 的好處」**：兩個資訊列（阻擋廣告、輕量・永久免費），文案只描述可能效益，
  不保證省電、不使用未經實測的百分比，也不宣稱完全沒有廣告。
- **私人 DNS 共存（E2）**：私人 DNS 設為「指定主機名稱」（strict）時，VPN 刻意**不宣告虛擬 DNS**，
  只維持隧道運作、不宣稱正在攔截；改為「關閉」或「自動」時才宣告虛擬 DNS 並實際攔截。
- **私人 DNS 變更即時反應**：App 執行中偵測到私人 DNS 設定改變時重新評估並調整宣告。
- **真機測試框架**：`scripts\device-test.ps1` 以 adb 驅動真機，逐項驗證 VPN 是否真的成立、私人 DNS 實際
  生效於哪個底層網路、Wi-Fi／行動網路交接，並把證據留在 `artifacts\`（不回報、不上傳）。

### 修正

- **Wi-Fi／行動網路交接後 VPN 底層網路不會更新（根因）**：原本由「最後回報的網路」勝出，因此行動網路
  換到 Wi-Fi 後 VPN 可能仍停在舊的行動網路 netId。改為以純政策排序候選網路（系統預設 > 已驗證 >
  本機傳輸 > 非計量），沿用現用網路直到出現更好的選擇，並以 tunnel generation 過濾過期事件；
  只有真正改變時才重新宣告並重建 DoH 傳輸。
- **啟動可能長期卡在「正在啟用」**：加入硬性時限，逾時即回報失敗，不再無限等待。
- **明確的停止路徑**：支援 `ACTION_STOP`（含前景通知的「停用防護」），停止時清除待執行的探測任務，
  回到「防護已停用」。
- **儲存的 DNS 傳輸可能與實際實作不一致**：舊安裝可能留下非 DoH 的傳輸設定，改為一律正規化為
  已實作的 DoH，避免設定與行為分岔。
- **殘留的 debug 覆寫會蓋掉私人 DNS 政策**：清除後以實際設定為準。

### 介面

- 設定頁精簡：移除做不到的傳輸協定選項，只保留隱私說明、連線診斷與版本資訊。
- 防護按鈕的標籤與顏色由同一個狀態對應產生（不以計時器或動畫模擬），並符合 Material 3 語意色角色；
  深淺色主題下的文字對比皆達 WCAG AA。
- 深淺色主題、圓角卡片與薄荷綠點綴維持一致；小螢幕與系統字體放大時自動換行、不破版。

### 測試

- 單元測試共 **239 個**（30 個測試類別），本輪實際執行 0 失敗、0 錯誤、0 略過（v0.2.2 記錄為 136 個）。
- 新增涵蓋：首頁不得出現任何 DNS／DoH／計數字樣的來源層守門、好處區塊文案守門（禁止百分比與絕對宣稱）、
  私人 DNS 共存與宣告政策、底層網路選擇政策、VPN 停止生命週期、防護按鈕色調對應。
- 真機驗收（Samsung Android 裝置 / Android 16）：防護啟停與連續啟停、私人 DNS 三模式切換與還原、
  E2／BASELINE 宣告、Wi-Fi ↔ 行動網路交接、首頁與設定頁 UI，全部通過。

### 變更

- `versionName` → `0.3.0`，`versionCode` → `5`。

### 已知限制（v0.3.0）

- 僅攔截 IPv4 UDP DNS；DNS-over-TCP 不支援，IPv6 的 DNS 不攔截。
- 部分 App 自建的 DNS（自帶 DoH 的瀏覽器）可能繞過攔截。
- 私人 DNS 設為「自動」或指定主機名稱時，可能改用系統的加密 DNS 而略過攔截；此時 KuiesBlock 只維持
  連線正常，不會宣稱正在攔截。
- 不顯示廣告攔截次數：AdGuard Home 不會回報哪一筆被攔截，無法在不捏造數據的前提下準確統計。
- **只在上述一台實機完成驗收**，其他 Android 裝置與版本尚未逐一驗證。

## v0.2.2

**主題：實機崩潰修正、VPN 停止機制強化、私人 DNS 相容性分析及防護狀態 UI 升級**

### 修正

- **主線程 Socket 關閉引發 NetworkOnMainThreadException 崩潰（BUG 1 根因）**：
  - 崩潰反編譯堆疊分析：`ConscryptEngineSocket$SSLOutputStream.writeInternal` <- `ConscryptEngineSocket.close` <- `okhttp3.internal._UtilJvmKt.closeQuietly` (`i61.c`) <- `OkHttpDohTransport.close` (`jd0.close`)。
  - 當底層網路切換（`onUnderlyingNetworkChanged`）或服務銷毀（`onDestroy`）時，於主線程呼叫 `transport?.close()` 會觸發 OkHttp 連線池 `connectionPool.evictAll()`，Conscrypt 於關閉 TLS Socket 時在主線程向網路寫入 TLS `close_notify` Alert，引發 `android.os.NetworkOnMainThreadException` 致命崩潰。
  - 修復方式：
    1. `OkHttpDohTransport.close()` 內建主線程檢測與安全非同步隔離，若在主 Looper 觸發則透過專用背景線程執行 `connectionPool.evictAll()`。
    2. `KuiesVpnService` 於網路重建與生命週期銷毀（`teardown`）時，全面將舊 Transport 關閉與線程清理卸載至背景，避免阻塞主線程或引發網路例外。
- **VPN 啟用後無法停用與狀態卡死（BUG 2 根因）**：
  - 既有實作僅依賴 `context.stopService()`，且 `onStartCommand` 回傳 `START_STICKY` 且缺乏明確的 `ACTION_STOP` 處理；若在 `STARTING` 探測重試（`verifyDnsRoundTrip` 延遲任務）期間停止，主線程未移除 pending callbacks，導致定時器觸發與狀態競態。
  - 修復方式：
    1. 實作明確的停止 Intent（`ACTION_STOP = "tw.kuies.block.action.VPN_STOP"`）與前台通知的「停用防護」Action 按鈕。
    2. 停止時立即清除 `mainHandler` 上的所有待執行任務（中斷 DNS 探測重試），設置 `running = false` 與 `isRunning = false`。
    3. `teardown()` 確保 TUN 介面關閉、Worker 線程池 `shutdownNow()`、Socket 於背景關閉、前台通知移除（`stopForeground(STOP_FOREGROUND_REMOVE)`）。
    4. `onStartCommand` 改為回傳 `START_NOT_STICKY`，防止服務被系統意外重啟。
    5. UI 按鈕在 `STARTING`、`ACTIVE`、`ERROR` 均支援立即停用，並同步重設狀態為 `AUTHORIZED_IDLE`（防護已停用）。

### 私人 DNS 相容性（BUG 3 分析與引導）

- **Android 私人 DNS 與 KuiesBlock 的互動**：
  - Android 啟用私人 DNS（指定主機名稱）時，系統解析器會透過 DoT（TCP 853）對外連線。KuiesBlock 僅攔截虛擬 DNS 位址（`10.111.222.2/32`），若系統或應用程式產生 TCP 查詢或探測，KuiesBlock 的 UDP-only 解析器會記錄 `NOT_UDP` 並安全丟棄。
  - 當系統或 App 產生 IPv6 查詢或探測時，`Ipv4Parser` 記錄 `NOT_IPV4` 並安全丟棄。
  - `::/0 unreachable` 為 Android 檢測 VPN 網路介面是否具備 IPv6 預設路由時的正常回報；KuiesBlock 僅接管指定 IPv4 /32 DNS 路由，底層實體網路（Wi-Fi 或行動網路）的 IPv6 與一般連線完全正常不受影響。
  - AdGuard Home 阻擋廣告時（回傳 `NXDOMAIN` 或 `0.0.0.0`），KuiesBlock 解析為合法的 DNS 回應（`isBlocked=true` 為 AdGuard Home 特性），不會誤判為網路異常或觸發 fail-open。
  - 首頁新增「已啟用私人 DNS（指定主機名稱）」引導卡片，友善提醒系統 DoT 可能優先處理部分查詢，並提供調整建議。

### 介面

- **防護狀態視覺層級更新（完整適配淺色與深色模式）**：
  - **未啟用**（`NOT_AUTHORIZED` / `AUTHORIZED_IDLE`）：灰階停用盾牌（`ic_shield_off`，`surfaceVariant` 底色），狀態標題「防護已停用」，主按鈕「啟用防護」。
  - **啟動中**（`STARTING`）：柔和橘色盾牌（`secondaryContainer` 底色），帶有旋轉進度指示器（`CircularProgressIndicator`），狀態標題「正在啟用防護」，主按鈕「停用防護」。
  - **已啟用**（`ACTIVE`）：品牌青綠色打勾盾牌（`ic_shield`，`primaryContainer` 底色），狀態標題「防護已啟用」，主按鈕「停用防護」。
  - **異常**（`ERROR`）：柔和紅色警告盾牌（`ic_shield_alert`，`errorContainer` 底色），狀態標題「防護異常」，次要文字清楚標示具體錯誤原因，主按鈕提供「重試啟用」或若殘留運行提供「停用防護」。

### 測試

- 單元測試由 127 個增加至 **136 個**（原有 127 個全數維持通過，新增 9 個測試）。
- 新增涵蓋：
  - `OkHttpDohTransport.close()` 冪等性與安全性測試。
  - `KuiesVpnService.ACTION_STOP` 明確停止 Intent 與前台通知 Action 驗證。
  - `STARTING` 狀態取消即時過渡測試。
  - `ACTIVE` 狀態停止過渡測試。
  - `ERROR` 狀態停止與重試過渡測試。
  - 重複停止與重複啟動冪等性測試。
  - 私人 DNS 指定模式（`isStrictHostname`）辨識測試。
  - 狀態標題（「防護已停用」、「正在啟用防護」、「防護已啟用」、「防護異常」）規範測試。

### 變更

- `versionName` → `0.2.2`，`versionCode` → `4`。

---

## v0.2.1

**主題：修正私人 DNS 誤判為連線異常，並停止在介面顯示自架 DNS 伺服器 IP**

### 修正

- **Android 私人 DNS 狀態誤判（根因）**：DNS 診斷原本以**系統解析器**（`InetAddress.getAllByName`，探測時的
  `InetSocketAddress(host, port)` 亦然）解析 DoH 主機名稱。但這正是 Android「私人 DNS → 指定主機名稱」會**接管**
  的解析路徑，而 KuiesBlock 自己的 DoH 路徑從不使用系統解析器（它以受保護的 UDP 直接詢問底層網路的 DNS）。
  因此當私人 DNS 設為指定主機名稱、而其 DoT 通道在該網路無法建立時，診斷會得到 `UnknownHostException`，
  並把「Android 的私人 DNS 路徑失效」誤報成「KuiesBlock 連線異常」。
  - 診斷改為使用與 DoH client 相同的路徑：`UnderlyingNetworkFinder` 取得底層網路 DNS → `PlainDnsBootstrap`
    解析 → 以解析出的位址連線，但仍設定 SNI 與 `endpointIdentificationAlgorithm = "HTTPS"`，
    **TLS 憑證與主機名稱驗證維持不變、不停用**。
  - 私人 DNS 設定**只會增加「可能繞過」的提示**，不會讓可用的連線變成失敗；真正的失敗也**不會**被顯示成正常。
- 私人 DNS 狀態改為同時讀取 `private_dns_mode` 與 `private_dns_specifier`；**僅在嚴格指定主機名稱模式**才保留
  hostname，且只用於比對「是否與 KuiesBlock 相同」，介面不顯示該主機名稱。

### 安全性與隱私

- **移除介面上的「解析位址」欄位**，診斷報告模型不再包含任何 IPv4／IPv6 位址（結構上不可能外洩）。
- 移除 TLS 憑證主體欄位。
- 探測失敗改為分類列舉（`DnsProbeFailure`），介面只顯示中文化的簡短原因，
  **不再直接顯示原始 Exception 名稱或訊息**。
- Logcat 不記錄 DNS 伺服器 IP、主機位址或查詢內容。

### 介面

- DNS 狀態改為三種情境並以顏色區分：連線正常（品牌綠）、DNS 正常但可能繞過攔截（柔和橘）、
  連線異常（錯誤紅）；私人 DNS 警告與連線錯誤因此不再混淆。
- 首頁只顯示狀態標題與一行短提示，完整說明只在設定頁出現，避免同一段長文字重複。
- 技術敘述改為一般使用者可理解的繁體中文；未新增多餘元件或動畫。

### 測試

- 單元測試由 108 個增加至 **127 個**（維持原有 108 個全數通過，新增 19 個）。
- 新增涵蓋：私人 DNS 關閉／自動／指定主機名稱／指定相同主機 搭配 DoH 正常、
  私人 DNS 啟用但 DoH 真失敗、`UnknownHostException` 不得被視為正常、TLS 失敗維持失敗、
  介面文字不含 IP 與原始例外、私人 DNS 非嚴格模式不得誤用殘留的 specifier，
  以及診斷改用注入解析器後的行為。

### 變更

- `versionName` → `0.2.1`，`versionCode` → `3`。

### 已知限制（與 v0.2.0 相同）

- 僅攔截 IPv4 UDP DNS；DNS-over-TCP 不支援；IPv6 的 DNS 不攔截。
- 部分 App 自建 DNS 與 Android 私人 DNS（自動／指定主機名稱）可能繞過攔截。
- 廣告攔截次數無法準確得知，固定顯示「尚無資料」。
- **尚未完成實機驗證**（見 README 的「目前狀態」）。

---

## v0.2.0

**主題：DNS-only VPN 核心 —— 真正透過本機 VPN 將系統 DNS 查詢加密送往自架 DoH 解析服務**

### 新增

- **DNS-only VPN Tunnel**
  - `VpnService` 建立 TUN 介面（`10.111.222.1/32`）與虛擬 DNS（`10.111.222.2`），**只加入一條 `10.111.222.2/32` 路由**。
  - `VpnRoutePlanPolicy` 在套用前擋下任何 catch-all（`/0`）或非 `/32` 的 DNS 路由；IPv6 完全不設定。
  - 使用攔截專用執行緒讀取 TUN（阻塞模式，不做輪詢），並以有界工作池（8 執行緒、佇列 256）處理查詢。
- **DNS 封包處理**
  - IPv4 標頭／UDP 封包解析與邊界檢查，拒絕 fragment、長度不符與 checksum 錯誤的封包。
  - DNS 訊息解析：壓縮名稱安全讀取（拒絕前向指標與迴圈）、EDNS(0) 大小掃描、回應結構驗證、Transaction ID 比對。
  - 回應封包重建並重新計算 IP 標頭與 UDP checksum（含 pseudo-header）。
- **DoH Client**
  - 基於 OkHttp，使用標準 `application/dns-message` wire format 與 POST。
  - 驗證 HTTP 狀態碼、Content-Type、DNS 形狀與 Transaction ID；回應大小上限 64 KB。
  - 連線池與逾時設定；TLS 憑證驗證維持預設、不停用。
- **上游連線與 bootstrap**
  - 所有上游 socket 使用 `VpnService.protect()`，避免連線循環。
  - `PlainDnsBootstrap` 以底層網路自身的 DNS 解析 DoH 主機名稱，避免遞迴依賴自身 VPN DNS；並以來源、Transaction ID、回應位元比對拒絕偽造回覆。
- **失敗處理（fail-open）**
  - DoH 連續失敗達 5 次即自動拆除 VPN、恢復正常網路，並顯示「防護已停止」通知與原因。
  - 個別查詢失敗回覆 SERVFAIL，一律計為「失敗」而非「攔截」。
  - 永不將加密 DNS 靜默降級為明文。
- **UI**
  - 首頁新增「今日 DNS 查詢次數」（真實計數）、「今日廣告攔截次數」（無法準確得知時顯示「尚無資料」）、與實際隧道狀態一致的 DNS 連線狀態。
  - 設定頁新增 DoH 端點、Android 私人 DNS 狀態與繞過警告、攔截範圍與統計說明。
  - 只有 TUN 建立成功且 DoH 實際往返成功時，才會顯示「防護已啟用」。
- **測試**
  - 單元測試由 24 個增加至 **108 個**（18 個測試類別），涵蓋路由計畫、封包解析／重建、checksum、DoH 成功／逾時／TLS 失敗、狀態機與「無假攔截統計」。
  - 新增 androidTest 驗證 `BIND_VPN_SERVICE`、前景服務型別與所需權限宣告。

### 變更

- `DnsConfig` 預設傳輸改為 **DoH**（伺服器已確認支援）。
- `ProtectionState` 新增 `STARTING`／`ACTIVE`／`ERROR`，且只有 `ACTIVE` 代表正在攔截。
- `BlockStats` 改為真實計數模型（查詢數與失敗數為實測，攔截數保持未知）。
- 新增 `ACCESS_NETWORK_STATE` 權限以偵測底層網路與網路切換。

### 已知限制（v0.2.0）

- 僅攔截 IPv4 UDP DNS；DNS-over-TCP 不支援；IPv6 的 DNS 不攔截。
- 部分 App 自建 DNS 與 Android 私人 DNS（自動／指定主機名稱）可能繞過攔截。
- 廣告攔截次數無法準確得知，顯示「尚無資料」。
- **尚未完成實機驗證**（見 README 的「目前狀態」）。

### 驗證方式

- `:app:compileDebugKotlin`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest`、`:app:assembleRelease`（含 R8 與 lintVital）全部成功。
- `:app:testDebugUnitTest`：108 個測試、0 失敗、0 錯誤。

---

## v0.1.0

**主題：專案初始化 —— 安全的本機 VPN 基礎架構與授權流程**

### 新增

- 專案骨架：Kotlin + Jetpack Compose + Material 3，`minSdk 28`、`targetSdk 37`，套件 `tw.kuies.block`。
- 品牌主題（主色 `#2F7D73`、背景 `#F7F5EF`、輔色 `#E7A56A`）與深色模式；首頁、設定頁與自繪向量圖示。
- `KuiesVpnService`：正確宣告 `BIND_VPN_SERVICE` 與前景服務型別、實作 `VpnService.prepare()` 授權流程、
  處理權限撤銷與 Service 生命週期、預留 TUN／DNS 封包與 `protect()` 介面。
  - **刻意不呼叫 `establish()`**，因此不建立任何路由、不會中斷使用者網路。
- DNS 設定與診斷模組：集中管理端點與協定、診斷主機解析與 TLS 狀態、拒絕明文降級。
- 誠實狀態設計：未完成攔截前不顯示「防護已啟用」，統計未取得前顯示「尚無資料」。
- 單元測試 24 個（狀態機、DNS 設定、診斷政策、統計無資料）。
