# KuiesBlock

> 輕量攔截廣告，不再被打擾

KuiesBlock 是一款 Android 輕量廣告攔截器。它使用 Android 內建的 `VpnService` 建立一個**只攔截 DNS** 的本機 VPN，
把系統的 DNS 查詢以加密的 **DNS over HTTPS（DoH）** 送往自架的 AdGuard Home，由伺服器端統一維護廣告與追蹤網域規則。

- 語言：Kotlin
- UI：Jetpack Compose + Material 3
- 最低版本：Android 9（API 28）
- 目標／編譯版本：API 37
- 套件名稱：`tw.kuies.block`
- 版本：**v0.3.0**
- 發布方式：GitHub 直接提供 APK（不上架 Google Play）
- 免費、無廣告、無訂閱、無帳號、無追蹤 SDK

## 運作原理

1. 使用者按下「啟用防護」，App 透過 `VpnService.prepare()` 取得 VPN 授權。
2. 服務建立 TUN 介面（`10.111.222.1/32`），並指定虛擬 DNS `10.111.222.2`，**只加入一條 `10.111.222.2/32` 路由**
   （私人 DNS 設為「指定主機名稱」時刻意不宣告虛擬 DNS，見下方「已知限制」）。
   - 不加入 `0.0.0.0/0`、不加入 `::/0`，因此 HTTPS、影音、遊戲等一般流量完全不進入 VPN、不受影響。
3. 從 TUN 讀取 IPv4 / UDP DNS 查詢，解析後以 DoH POST 送往 `https://dns.kuies.tw/dns-query`。
4. 驗證回應（HTTP 狀態、`application/dns-message`、DNS Transaction ID、封包結構），重建 IPv4 + UDP 封包並寫回 TUN。
5. 當 DoH 連續失敗達門檻時會 **fail-open**：自動停用 VPN，讓系統 DNS 回復正常運作，並明確告知防護已停止。

為避免循環，送往上游的 socket 全部經過 `VpnService.protect()`，且 DoH 主機名稱的解析使用底層網路自身的 DNS
（避免透過自己的 VPN DNS 造成遞迴）。

## 隱私

- 不建立帳號、不收集個人資訊、不上傳瀏覽紀錄。
- 只在本機保存「每日查詢次數與失敗次數」等彙總數字，**不保存任何網域或瀏覽歷史**。
- 設定與統計已排除於 Android 備份與裝置移轉之外。
- 不以明文 DNS 回應用戶查詢，也不會把加密 DNS 靜默降級為明文。

## 目前狀態

**v0.3.0 已在一台實機完成驗收**（Samsung SM-F7660 / Android 16，以 USB + adb 自動化執行）：

- 防護啟用、停用、連續啟停，以及停止後 90 秒內不被背景回呼重新啟動 — 通過
- 私人 DNS「關閉／自動／指定主機名稱」三種模式的切換、實際承載網路判定與還原 — 通過
- strict 私人 DNS 下 VPN 不宣告虛擬 DNS（E2）、BASELINE 模式宣告虛擬 DNS — 通過
- Wi-Fi → 行動網路 → Wi-Fi 交接後，VPN 的底層網路跟隨系統預設網路更新（390 → 373 → 393）— 通過
- 首頁、設定頁與防護狀態 UI，含 320dp 小螢幕與系統字體放大 1.3 倍 — 通過

本機建置與單元測試：

- `:app:compileDebugKotlin`、`:app:assembleDebug`、`:app:assembleRelease`（含 R8 與 lintVital）皆成功
- `:app:testDebugUnitTest`：239 個測試、0 失敗

**只有上述一台裝置完成驗收。** 其他 Android 版本與機型尚未逐一驗證；私人 DNS「自動」模式是否真的
使用 DoT 無法由平台觀測，該子項維持無法判定（不視為通過）。

## 已知限制

- **僅攔截 IPv4 的 UDP DNS**。
- **不支援 DNS-over-TCP**：送往虛擬 DNS 的 TCP 連線不會被轉送，因此回應過大時該次查詢會失敗而非解析成功。
- **不攔截 IPv6 的 DNS**（IPv6 完全不進入 VPN，可正常上網但不受過濾）。
- **部分 App 自建的 DNS（例如自帶 DoH 的瀏覽器）可能繞過攔截**。
- Android 的「私人 DNS」若設為「自動」或指定主機名稱，可能改用系統的加密 DNS 而略過攔截；此時 KuiesBlock
  仍會保持連線正常，但**不會宣稱正在攔截**（介面上就不會顯示成受保護）。
- **不顯示廣告攔截次數**：AdGuard Home 不會回報哪一筆被攔截，無法在不捏造數據的前提下準確統計，因此介面完全不顯示攔截數字。

## 建置

```bash
# 需要 JDK 17 以上與 Android SDK（platform 37、build-tools 36.0.0）
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

若 `java` 不在 PATH，請先設定 `JAVA_HOME` 指向 JDK（例如 Android Studio 內建的 JBR）。
版本資訊請以 `gradle/libs.versions.toml` 為準。

## 專案結構

```
app/src/main/java/tw/kuies/block/
├── core/    共用狀態、保護狀態機
├── data/    設定與每日統計
├── dns/     DNS 設定、診斷、訊息與封包編解碼
├── doh/     DoH client、bootstrap 解析
├── vpn/     VpnService、路由計畫、通知
└── ui/      Compose 畫面與主題
```

## 變更紀錄

請見 [CHANGELOG.md](CHANGELOG.md)。
