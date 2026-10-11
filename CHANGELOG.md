# Changelog

本專案遵循語意化版本。以下記錄各版本的實際變更。

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
