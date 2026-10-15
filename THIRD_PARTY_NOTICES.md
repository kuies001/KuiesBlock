# 第三方素材與授權標註（Third-Party Notices）

本文件列出 KuiesBlock 內含的第三方素材、來源、授權與修改情況。
本專案自身的授權見根目錄 [`LICENSE`](LICENSE)（Apache License 2.0）。

---

## 1. 圖示（Vector Drawable）

### 1.1 來源與授權

- **素材名稱：** Google Material Design Icons
- **原始專案：** <https://github.com/google/material-design-icons>
- **授權名稱：** Apache License, Version 2.0
- **上游授權檔案：** <https://github.com/google/material-design-icons/blob/master/LICENSE>

上游對授權的原始聲明（引自該專案 `README.md` 的 “License” 一節，2026-10-15 讀取，原文照引）：

> We have made these icons available for you to incorporate into your products under the
> [Apache License Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt). Feel free to remix
> and re-share these icons and documentation in your products.
> We'd love attribution in your app's *about* screen, but it's not required.

補充事實（皆於 2026-10-15 查證）：

- 上游 repository 根目錄的內容為 `.github, .gitignore, LICENSE, README.md, android, font, ios, png, src, symbols, update, variablefont`，**沒有 `NOTICE` 或 `COPYING` 檔案**，因此 Apache-2.0 第 4(d) 條沒有額外的 NOTICE 內容必須保留。
- 上游的 Apache-2.0 條文**未填寫著作權行**（附錄仍是 `Copyright [yyyy] [name of copyright owner]` 範本）。因此上面那段 README 聲明是本文件唯一能引用的「原始著作權／歸屬聲明」，本文件不會憑空編造一行不存在的著作權宣告。

### 1.2 本專案如何核對這些圖示

方法（可重現）：

1. 取出每個 drawable 的 `android:pathData`。
2. 以 HTTPS 取得上游同一圖示的官方 SVG（`filled` 與 `outlined` 兩種變體都取），抽出其 `d` 屬性。
3. 兩者轉成正規化形式比較：
   - **canonical 完全相等**：指令一律大寫、數值以不受地區設定影響的格式重寫，因此 `c-.82` 與 `C-0.82` 視為相同、而真正的幾何差異不會被忽略。
   - **數值序列的最長共同子序列（LCS）比例**：只看座標數值、不看指令字母，因此「相對／絕對寫法」不影響結果；現行上游把部分短曲線改寫成 arc（多了半徑與旗標數值），會使比例下降，因此比例偏低不等於不同圖示。

**核對所用的上游檔案來自第三方重新打包**，這一點必須說清楚：

- 套件：`@material-design-icons/svg@0.14.15`（套件詮釋資料：<https://registry.npmjs.org/@material-design-icons/svg>）
- 實際取檔網址：`https://cdn.jsdelivr.net/npm/@material-design-icons/svg@0.14.15/<filled|outlined>/<圖示名稱>.svg`
- 維護者：**marella**（repository：<https://github.com/marella/material-design-icons>），套件自身宣告的授權欄位是 `Apache-2.0`。
- 它**不是 Google 官方發布的套件**，而是 Google Material Design Icons 的第三方重新打包。使用它是因為核對當下 `raw.githubusercontent.com` 對本機限流，無法穩定取得 Google 官方 repo 的原始檔案。
- 因此：**圖示的原始來源與授權以 1.1 節的 Google repository 為準**；1.3 節的比對結果只能證明「本專案的圖示與該套件所打包的同一份 Google 圖示一致（或為其修改版）」。

### 1.3 逐一核對結果

| 本專案檔案 | 對應上游圖示 | 比對結果 | 修改情況 |
| --- | --- | --- | --- |
| `ic_arrow_back.xml` | `arrow_back` | **完全相同**（canonical 完全相等；17 個數值同序） | 未修改 |
| `ic_block.xml` | `block` | 同一圖示（`filled/block`；shared-geometry 81.2%，數值個數同為 64） | 未修改（差異僅為路徑寫法） |
| `ic_check_circle.xml` | `check_circle` | 同一圖示（`filled/check_circle`；94.1%，數值個數同為 34） | 未修改 |
| `ic_dns.xml` | **`language`**（地球／語言圖示） | 同一圖示（`filled/language`；62.5%，上游把經線改寫為 arc）。**注意：本專案檔名為 `ic_dns`，內容其實是 Google 的 `language` 圖示，不是 `dns` 圖示。** | 未修改 |
| `ic_info.xml` | `info`（**outlined** 變體） | 同一圖示（`outlined/info`；88%，數值個數同為 50；對 `filled/info` 僅 44%） | 未修改 |
| `ic_refresh.xml` | `refresh` | 同一圖示（77.4%，數值個數同為 53） | 未修改 |
| `ic_settings.xml` | `settings` | 同一圖示，但**與現行上游版本並非逐位元相同**：本專案用的是較早版本，開頭座標與指令序列一一對應，現行上游已把短轉角曲線改寫為 arc（47.7%；本專案 174 個數值 vs 上游 107 個） | 未修改（沿用較早版本的同一圖示） |
| `ic_warning.xml` | `warning` | 同一圖示（78.9%，數值個數同為 19） | 未修改 |
| `ic_shield.xml` | `verified_user` | 盾牌與內部勾勾的幾何與上游 `verified_user` 相同（88.2%） | **已修改**：新增 `android:fillType="evenOdd"`，使內部勾勾以鏤空方式呈現 |
| `ic_shield_alert.xml` | `verified_user` | 盾牌外型取自 `verified_user`；內部標記不同（63.3%） | **已修改**：內部勾勾替換為驚嘆號 |
| `ic_shield_off.xml` | `verified_user` | 盾牌外型取自 `verified_user`；內部標記不同（76%） | **已修改**：內部勾勾替換為橫線 |

### 1.4 本專案自繪（非第三方素材）

| 檔案 | 內容 | 判定依據 |
| --- | --- | --- |
| `ic_launcher_background.xml` | 108×108 方塊 + 圓形 | 基本幾何圖形，不對應任何上游圖示路徑 |
| `ic_launcher_foreground.xml`、`ic_launcher_monochrome.xml` | 108 viewport 的盾牌 | 與 `verified_user` 不成比例：均勻縮放 4.5 倍會得到 `M54,4.5L13.5,22.5…`，本專案是 `M54,26.5L31.5,36.5…`，因此是自行繪製的盾牌，非上游路徑 |

### 1.5 Apache-2.0 的遵循方式

- **保留著作權、專利、商標與歸屬聲明**：即 1.1 節引用之上游聲明與本文件。
- **標明已修改的檔案**：見 1.3 節「修改情況」欄（`ic_shield.xml`、`ic_shield_alert.xml`、`ic_shield_off.xml`）。
- **保留 NOTICE 內容**：上游沒有 NOTICE 檔案（1.1 節），故無內容需保留。
- **附上授權全文**：見根目錄 [`LICENSE`](LICENSE)，與 <https://www.apache.org/licenses/LICENSE-2.0.txt> 逐位元相同（SHA-256 `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30`），本文件不重複貼上以免兩份文字分歧。

---

## 2. 應用程式內含的第三方函式庫

清單取自 Gradle 的 `releaseRuntimeClasspath`（107 個 `group:artifact`），授權讀自**各成品自己的 POM**（2026-10-15）：

| 授權（依成品 POM 宣告） | 數量 | 代表模組 |
| --- | --- | --- |
| The Apache Software License, Version 2.0 | 104 | `androidx.compose.material3:material3`、`androidx.compose.ui:ui`、`androidx.lifecycle:lifecycle-runtime*`、`androidx.activity:activity-compose`、`androidx.core:core-ktx`、`com.squareup.okhttp3:okhttp`、`com.squareup.okio:okio`、`org.jetbrains.kotlinx:kotlinx-coroutines-core` |
| The Apache License, Version 2.0 | 2 | `org.jetbrains.kotlin:kotlin-stdlib`、`org.jspecify:jspecify` |
| **成品 POM 未宣告授權** | 1 | `com.google.guava:listenablefuture`（POM 的 `name` 為 “Guava ListenableFuture only”、無 `<licenses>` 區塊、無 project url；jar 為 3,149 bytes 的佔位成品） |

- 上述 106 個模組皆為 Apache-2.0，與本專案授權相同。
- `com.google.guava:listenablefuture` 是**尚未由成品自身宣告授權**的項目，列為待確認事項（不以推測填補）。
- **測試專用**：JUnit 4（Eclipse Public License 1.0）等只在測試組態，**不在** `releaseRuntimeClasspath`、不隨 APK 散布。
- 本應用程式**沒有內嵌任何字型檔**（`app/src/main/res` 下沒有 `.ttf`/`.otf`/`.woff`）。

---

## 3. 維護方式

- 新增或替換任何第三方素材時，請在本文件補上：素材名稱、來源連結、授權、是否修改。
- 只依「風格看起來像某家」不足以判定來源；請比對實際檔案內容並留下可重現的方法。
