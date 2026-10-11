# Changelog

本專案遵循語意化版本。以下記錄各版本的實際變更。

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
