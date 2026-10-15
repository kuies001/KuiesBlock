# Changelog

本專案遵循語意化版本。以下記錄各版本的實際變更。

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
  - Android 啟用私人 DNS（指定主機名稱 `dns.kuies.tw`）時，系統解析器會透過 DoT（TCP 853）對外連線。KuiesBlock 僅攔截虛擬 DNS 位址（`10.111.222.2/32`），若系統或應用程式產生 TCP 查詢或探測，KuiesBlock 的 UDP-only 解析器會記錄 `NOT_UDP` 並安全丟棄。
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
  因此當私人 DNS 設為 `dns.kuies.tw` 而其 DoT 通道在該網路無法建立時，診斷會得到 `UnknownHostException`，
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

**主題：DNS-only VPN 核心 —— 真正透過本機 VPN 將系統 DNS 查詢加密送往 dns.kuies.tw**

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
