# Kitchen Service — KITCHEN-02 Sipariş Hazırlama Süreci (Faz 2)

Durum: **Faz 2 çalışma notu.** Dış servislerle ilgili sözleşmeler *öneridir*; Order, Auth, Notification veya Gateway tarafında uygulanmış sayılmaz. KITCHEN-01 sözleşmesi: [kitchen-01-contract.md](kitchen-01-contract.md).

## 1. Başlangıç durumu — kodla doğrulanmış (2.1.1)

| Konu | Bulgu |
|---|---|
| Faz 1 | Commit `3ea09c4`; Kitchen testleri **67 test, 0 hata** (Java 25.0.4.1, `./mvnw clean test`) |
| Migration | `V81__create_kitchen_core.sql`: `kitchen_orders` zaten `started_at`, `ready_at`, `cancelled_at`, `version` ve `ck_kitchen_orders_timestamps` CHECK'ini (`ready_at >= started_at >= received_at`) içerir; `kitchen_status_history` `from_status`, `to_status`, `actor_user_id`, `source_event_id`, `occurred_at`, `reason` içerir. Durum ve zaman alanları için **yeni migration gerekmedi**. Komut kimliği için **`V82`** gerekti (bkz. §2.4) |
| Entity | `KitchenOrder` yalnız `receive(...)` fabrikasına sahipti; `@Version` var. `KitchenStatusHistory` yalnız `initial(...)` fabrikasına sahipti |
| Kalemler | `kitchen_order_items` üzerinde hazırlık durumu **yoktur** ve eklenmeyecek (V1 sipariş bazlı) |
| Gateway | `Path=/api/v1/kitchen,/api/v1/kitchen/**` — yöntem kısıtı ve prefix kırpma yok; PATCH yolları Gateway'de değişiklik gerektirmez |
| Güvenlik | Production'da kimlik üreten bileşen yoktur → tüm iş istekleri 401 (fail closed); yalnız `/actuator/health` anonim. CSRF Spring varsayılanında |

## 2. Tasarım kararları

1. **Durum makinesi:** yalnız `WAITING → PREPARING → READY`. `WAITING → READY`, `READY → *`, `PREPARING → WAITING`, `CANCELLED → *` yasak. `READY` Kitchen için terminaldir ve garson servisi sonrasında da değişmez. Kalem bazlı durum yoktur.
2. **Yazma yetkisi (onaylı karar):** hazırlık komutları yalnız **`KITCHEN`** rolüne açıktır. **`ADMIN` yalnız okur; her yazma isteği 403 alır.** Kimliksiz istek 401. Restoran ve kullanıcı **yalnız doğrulanmış principal'dan** alınır; istekteki `restaurantId`, `X-Role`, `X-Restaurant-Id`, `actorId`, `startedAt`, `status` değerleri etkisizdir (istek gövdesi okunmaz). Başka restoranın siparişi 404'tür (varlığı sızmaz).
3. **Eşzamanlılık:** komut siparişi `SELECT ... FOR UPDATE` (5 sn sınırlı) ile okur; durum + zaman damgası + `version` + history **tek yerel transaction**'dadır. `@Version` ikinci güvenlik ağıdır. Dış HTTP çağrısı kilit altında yapılmaz.
4. **Tekrar komut sözleşmesi (onaylı karar):** *sırf hedef duruma ulaşılmış olması 200 için yeterli değildir.* Her hazırlık komutu zorunlu bir **`Idempotency-Key`** (UUID) header'ı taşır; bu komut kimliği, komutun yaptığı geçişin history satırıyla birlikte saklanır (`kitchen_status_history.command_id`). Tekrar **ancak şu hepsi doğruysa** `200`'dür ve hiçbir şey değişmez (zaman, `version`, history, olay): aynı komut kimliği + aynı kullanıcı + aynı sipariş + aynı hedef geçiş ve sipariş hâlâ o hedef durumdadır. Aksi hâlde:
   - Sipariş hedef durumda ama komut farklı (başka kimlik veya başka operatör): **409** (`currentStatus` ile).
   - Aynı komut kimliği başka siparişte, başka kullanıcıyla veya başka geçişte kullanılmış: **409**.
   - Aynı komut, ama sipariş bu arada ilerlemiş (ör. `READY`): **409** ve gerçek durum bildirilir; bayat başarı dönülmez.
   - `READY`/`CANCELLED` siparişe başlatma: her zaman **409**.
   - Header yok veya UUID değil: **400**.
   Komut kimliği restoran içinde benzersizdir; farklı restoranlar aynı kimliği kullanabilir. **Migration `V82`:** `command_id` sütunu, `(restaurant_id, command_id)` kısmi benzersiz indeksi ve `(restaurant_id, kitchen_order_id, to_status)` benzersiz indeksi (her durum bir siparişe en fazla bir kez girilir — çift geçişe karşı veritabanı güvencesi). Mevcut satırlar için sütun boştur (sistem kayıtları komut kimliği taşımaz).
5. **Zaman damgaları:** sunucu saatinden (`Clock`) bir kez yazılır; istemci veremez. `received_at <= started_at <= ready_at` DB CHECK'i ile de korunur; saat kayması `startedAt`'ın `receivedAt`'ten önceye düşmesine izin vermez.
6. **API yolları (onaylı karar):** `PATCH /api/v1/kitchen/orders/{orderId}/start-preparation` ve `PATCH /api/v1/kitchen/orders/{orderId}/mark-ready`. Eski adlar (`…/preparation/start`, `…/preparation/ready`, `…/ready`) ikinci bir API olarak **oluşturulmadı**. Gateway'de değişiklik gerekmez.
7. **Yanıt:** başarıda mevcut sipariş detay yanıtı (`KitchenOrderDetailResponse`); `startedAt/readyAt` boşken açık `null` (Faz 1 kararı).
8. **Kapsam dışı (Faz 2'de yapılmadı):** iptal mekanizması (Faz 4), ürün açma/kapama ve fire (Faz 3), ürün bazlı hazırlık, D-02 kararı, canlı Order/Notification teslimi, broker.

## 3. Başlatma API'si — `PATCH /api/v1/kitchen/orders/{orderId}/start-preparation` (task 2.1)

- **Erişim:** korumalı; yalnız **`KITCHEN`**. `ADMIN` ve diğer roller 403, kimliksiz 401. Kitchen altındaki **her yazma yöntemi** (POST/PUT/PATCH/DELETE) yalnız KITCHEN'a açıktır; ADMIN yalnız GET/HEAD yapar.
- **İstek:** gövde yoktur (varsa okunmaz). Zorunlu header: `Idempotency-Key: <uuid>`.
- **CSRF:** Spring varsayılanı korunur; testleri geçirmek için kapatılmadı. Kimlik doğrulama yöntemi (D-09) kesinleşince istemci tarafı yeniden değerlendirilecektir.
- **Başarı (200):** güncel sipariş detayı (GET detay ile aynı gövde): `PREPARING`, `startedAt` dolu, `readyAt` açık `null`.

| Durum | Ne zaman |
|---|---|
| 200 | `WAITING → PREPARING` uygulandı **veya** doğrulanmış tekrar (aynı komut kimliği + kullanıcı + sipariş, sipariş hâlâ `PREPARING`) |
| 400 | `orderId` UUID değil; `Idempotency-Key` yok veya UUID değil |
| 401 / 403 | kimliksiz / `KITCHEN` olmayan rol (ADMIN dahil) |
| 404 | sipariş yok **veya başka restoranın siparişi** (ayırt edilemez) |
| 409 | `PREPARING` ama farklı komut; `READY`/`CANCELLED`; komut kimliği başka bir komuta ait; sipariş bu arada ilerlemiş; eşzamanlı değişiklik/kilit zaman aşımı |

Örnek istek: `PATCH /api/v1/kitchen/orders/6f1c…/start-preparation` + `Idempotency-Key: 0b9d2f71-3a55-4a0e-8f51-2c5e8e3d7a22`.
Örnek 409: `{"status":409,"title":"Conflict","detail":"Order is already PREPARING and this command is not a repeat of the one that did it","currentStatus":"PREPARING"}`.

**Atomiklik:** durum, `started_at`, `version` ve `kitchen_status_history` (`WAITING → PREPARING`, aktör, komut kimliği, zaman) tek transaction'dadır; history yazımı başarısız olursa hepsi geri alınır (testle kanıtlı). Paralel istekler sıraya girer: aynı komutun paralel tekrarları hepsi 200 (tek geçiş uygulanır); farklı komutlardan yalnız biri 200, kalanı 409 alır.

## 4. Hazır yapma API'si — `PATCH /api/v1/kitchen/orders/{orderId}/mark-ready` (task 2.2)

- **Erişim ve istek:** başlatma ile aynı kurallar (§3): yalnız **`KITCHEN`** (ADMIN dahil diğer roller 403, kimliksiz 401), gövde yok, zorunlu `Idempotency-Key` (UUID), restoran/kullanıcı doğrulanmış principal'dan, zaman sunucu saatinden. CSRF kapatılmadı.
- **Geçiş:** yalnız `PREPARING → READY`. `WAITING → READY` için **hiçbir yol yoktur** (409, değişiklik yok). `READY` Kitchen için terminaldir; garsonun siparişi servis etmesi bu durumu değiştirmez ve Kitchen'a böyle bir komut yoktur.
- **Başarı (200):** güncel sipariş detayı (GET detay ile aynı gövde, yeni DTO yok): `status = READY`, `startedAt` **değişmez**, `readyAt` dolu, kalemler/seçenekler/notlar aynen.
- **Zaman:** `readyAt` sunucu saatinden bir kez yazılır ve `readyAt >= startedAt >= receivedAt` her zaman korunur (saat kayması `startedAt`'tan önceye düşürmez; DB CHECK'i de korur).
- **Tekrar (onaylı karar):** başlatmadaki kuralın aynısı: yalnız **aynı komut kimliği + aynı kullanıcı + aynı sipariş + `READY` hedefi** ve sipariş hâlâ `READY` ise **200**, hiçbir şey değişmez (zaman, `version`, history). Başka komut kimliği veya başka operatör: **409**. *Başlatma* komutunun kimliği `mark-ready` için kullanılamaz (başka geçişe ait: 409).

| Durum | Ne zaman |
|---|---|
| 200 | `PREPARING → READY` uygulandı **veya** doğrulanmış tekrar (sipariş hâlâ `READY`) |
| 400 | `orderId` UUID değil; `Idempotency-Key` yok veya UUID değil |
| 401 / 403 | kimliksiz / `KITCHEN` olmayan rol (ADMIN dahil) |
| 404 | sipariş yok **veya başka restoranın siparişi** |
| 409 | `WAITING` (henüz başlamadı); `READY` ama farklı komut; `CANCELLED`; komut kimliği başka bir komuta ait; eşzamanlı değişiklik/kilit zaman aşımı |

**Atomiklik:** durum, `ready_at`, `version` ve `kitchen_status_history` (`PREPARING → READY`, aktör, komut kimliği, zaman) tek transaction'dadır. `(restaurant_id, kitchen_order_id, to_status)` benzersiz indeksi (V82) ikinci bir `READY` girişini veritabanı düzeyinde engeller. *Hazır olay/outbox kaydı Task 2.4'te aynı transaction'a eklenecektir; bu task'ta olay üretilmez.*

**Listeler:** `READY` sipariş `status=READY` filtresinde görünür, `WAITING`/`PREPARING`/`ACTIVE` listelerinden çıkar; FIFO sırası ve `newArrival=false` (Faz 1 sözleşmesi) korunur.

**Zaman hassasiyeti:** `startedAt` ve `readyAt` **mikrosaniyeye** kesilerek yazılır (PostgreSQL `timestamptz` hassasiyeti). Böylece komutun döndürdüğü zaman ile sonraki GET'in gösterdiği zaman birebir aynıdır (kesilmeseydi Windows'ta nanosaniyeli yanıt, mikrosaniyeli okumadan farklı olabilirdi).

## 5. Geçiş matrisi ve hata sözleşmesi (task 2.3)

### 5.1 Geçiş matrisi (her komut YENİ bir `Idempotency-Key` ile; `KitchenTransitionMatrixTests` ile birebir test edilir)

| Kaynak durum | `start-preparation` | `mark-ready` |
|---|---|---|
| `WAITING` | **200** → `PREPARING`, `startedAt` yazılır | **409** `ORDER_STATE_CONFLICT`, değişiklik yok |
| `PREPARING` | **409** (zaten hazırlanıyor, bu komut onu yapan değil), değişiklik yok | **200** → `READY`, `readyAt` yazılır |
| `READY` | **409**, değişiklik yok | **409**, değişiklik yok |
| `CANCELLED` | **409**, değişiklik yok | **409**, değişiklik yok |

Reddedilen her komutta `status`, `version`, `started_at`, `ready_at`, `cancelled_at` ve history satır sayısı **değişmez** (test eder). `WAITING/PREPARING → CANCELLED` iptal akışı Faz 4'tür ve bu fazda uygulanmaz; `CANCELLED` kaynakları test verisiyle sınanır.

### 5.2 Tekrar (idempotency) — ne garanti edilir, ne edilmez

Tekrar **200** döner ve hiçbir şeyi değiştirmez ancak şu hepsi doğruysa: aynı `Idempotency-Key`, aynı kullanıcı, aynı sipariş, aynı geçiş **ve sipariş hâlâ o hedef durumda**.

**Sınır (bilinçli karar):** idempotency burada *tarihsel sonuç tekrarı garantisi vermez*. Başarılı bir komut, sipariş sonradan başka bir duruma geçtikten sonra aynı anahtarla tekrar gönderilirse **409** döner (`ORDER_STATE_CONFLICT`, `currentStatus` gerçek durumu söyler), çünkü Kitchen o komutun eski başarılı yanıtını saklamaz ve bayat bir "başarılı" döndürmek siparişin bugünkü gerçeğini gizlerdi. Örnek: `start-preparation` başarılı oldu, sipariş `READY` oldu; ağ zaman aşımı yüzünden istemci eski start'ı tekrar gönderirse 409 ve `currentStatus=READY` alır. İstemci bunu "komut kaybolmadı, sipariş ilerledi" diye yorumlamalı ve siparişi (GET) okumalıdır.

### 5.3 Hata kodları

Her 409/503 yanıtı RFC 9457 `ProblemDetail` içinde makine tarafından okunur `code` ve `retryable` alanı taşır.

| HTTP | `code` | Ne zaman | `retryable` | İstemci ne yapmalı |
|---|---|---|---|---|
| 400 | — | `orderId`/`Idempotency-Key` UUID değil, header yok, geçersiz parametre | — | isteği düzelt |
| 401 / 403 | — | kimliksiz / yetkisiz rol (ADMIN dahil yazmada) | — | kimlik / yetki |
| 404 | — | sipariş yok **veya başka restoranın** | — | — |
| 409 | `ORDER_STATE_CONFLICT` | geçiş mevcut durumdan yapılamaz veya sipariş bu komutun hedefinde ama komut farklı | `false` | `currentStatus`'a göre siparişi oku; **aynı komutu tekrarlamak işe yaramaz** |
| 409 | `COMMAND_ID_CONFLICT` | anahtar başka sipariş/kullanıcı/geçiş için kullanılmış | `false` | yeni anahtar üret |
| 409 | `CONCURRENT_MODIFICATION` | iyimser kilit (`version`) yarışı: sipariş bu istek sırasında değişti | `true` | siparişi yeniden oku, sonra karar ver |
| **503** | `ORDER_BUSY` | sipariş satır kilidi 5 sn içinde alınamadı (geçici yoğunluk) | `true` | **aynı `Idempotency-Key` ile güvenle tekrar dene**, `Retry-After: 2` sn bekle |

**Kilit zaman aşımı (`503 ORDER_BUSY`) neden 409 değil:** 409, istemcinin çözmesi gereken kalıcı bir durum çakışmasıdır; kilit zaman aşımı ise geçici bir sunucu yoğunluğudur ve hiçbir şeyin kesin olarak başarısız olduğunu söylemez. Bu istek siparişi değiştirmemiştir, ama başka bir istek (hatta aynı komutun kendisi) başarılı olmuş olabilir. Bu yüzden istemci "başarısız" varsaymamalı; aynı anahtarla tekrar gönderebilir (başarılı komut ise doğrulanmış tekrar olarak 200 döner) ya da siparişi okuyabilir. Sunucu **otomatik ve sınırsız yeniden deneme yapmaz**; kilit beklemesi 5 sn ile sınırlıdır ve ölçülmüştür. *Not: önceki sürümde bu durum 409 idi; 409 yerine 503'e geçiş onayınıza sunulmuş bir karardır.*

### 5.4 `Idempotency-Key` biçimi ve kötü istek kuralları

- Başlık **tam olarak bir tane** ve **kanonik UUID** (`8-4-4-4-12` onaltılı; büyük/küçük harf aynı komuttur) olmalıdır. Birden çok `Idempotency-Key` başlığı, virgülle ayrılmış liste, `1-1-1-1-1` gibi gevşek biçimler, boşluklu/aşırı uzun/UUID olmayan değerler **400** döner ve hiçbir şey değişmez; değer yanıtta yansıtılmaz. (Belirsiz bir komut kimliği "ilkini seçerek" çözülmez.)
- Komut yollarında `GET/POST/PUT/DELETE` (KITCHEN için) **405**, ADMIN için her yazma yöntemi **403**; yol hileleri (`;`, `//`, `..`, büyük harf, sondaki `/`) komutu çalıştırmaz.
- Aynı `orderId` iki farklı restoranda iki bağımsız siparişdir; birinin komutu diğerini etkilemez.
- Sahte `X-Role`, `X-Restaurant-Id`, `X-User-Id`, `X-HTTP-Method-Override` başlıkları etkisizdir.

## 6. "Hazır" olayı ve Order'a bildirim sözleşmesi (task 2.4)

### 6.1 Sipariş bazlı tamamlama

V1'de "tüm ürünler hazır" ifadesi yalnızca **`KitchenOrder.status == READY`** demektir. Kalem bazlı `ready` alanı, kalem endpoint'i veya tamamlama sayacı **yoktur ve eklenmeyecektir**. Siparişin kendi iş durumunu (Order tarafında "hazır" görünümü) nasıl güncelleyeceği **Order sahibinin kararıdır**; Kitchen Order'ın veritabanına yazmaz ve Order kodu üretmez.

### 6.2 Olay: `KitchenOrderReady` (öneri, `schemaVersion` 1 — Order ekibiyle teyit edilecek)

Her `PREPARING → READY` geçişi, **aynı transaction'da** tam bir olay üretir (sipariş başına en fazla bir; tekrar komutu yenisini üretmez).

```json
{
  "eventId": "uuid (sabit; yeniden gönderimde değişmez)",
  "eventType": "KitchenOrderReady",
  "schemaVersion": 1,
  "occurredAt": "2026-10-17T15:04:05.123456Z",
  "restaurantId": "uuid",
  "orderId": "uuid (Order servisinin sipariş kimliği)",
  "aggregateVersion": 3,
  "correlationId": "string|null",
  "causationId": "uuid (READY komutunun Idempotency-Key değeri)",
  "payload": {
    "preparationStatus": "READY",
    "startedAt": "2026-10-17T15:00:00.000000Z",
    "readyAt": "2026-10-17T15:04:05.123456Z"
  }
}
```

- **Asgari veri:** kullanıcı kimliği, masa, ürün, not, fiyat veya kişisel veri **taşınmaz**; yalnız Order'ın zaten bildiği `orderId` ve hazırlık zamanları. Sır yoktur.
- `aggregateVersion` = geçişten sonraki Kitchen sipariş `version` değeri; tüketici eski olayın yeni durumu ezmesini buna bakarak engelleyebilir. `occurredAt` = `readyAt` (mikrosaniye).
- **Teslim garantisi: en az bir kez.** `eventId` sabittir; **tüketici (Order) olayı `eventId` ile idempotent işlemelidir**. "Tam bir kez" garantisi verilmez.
- `KitchenPreparationStarted` yalnızca **mimari öneridir**; Order'ın bir "hazırlanıyor" projeksiyonuna ihtiyacı olup olmadığı belli olmadığından **üretilmez ve kaydedilmez**.

### 6.3 Statüler — neyin ne kadar doğrulandığı

| Konu | Durum |
|---|---|
| Olay sözleşmesi (§6.2) | **CONTRACT READY** — Order ekibinin onayı bekliyor |
| Olayın yerel, dayanıklı kaydı (READY ile aynı transaction) | **LOCAL EVENT PERSISTED** — uygulanır ve testlidir |
| `OrderEventSink` portu + teslimat durum makinesi | **PORT READY** — sahte hedefle testli |
| Order'a gerçek iletim (zamanlayıcı, HTTP/broker adaptörü, hedef adres, servis kimliği) | **BLOCKED / DELIVERY NOT VERIFIED** — D-10 ve Order sözleşmesi gelmeden kurulmaz |

### 6.4 Order ekibiyle teyit edilecekler
1. Taşıma: iç HTTP + outbox mu, broker mu (D-10). 2. Hedef endpoint/yol/yöntem ve servisler arası kimlik doğrulama. 3. `KitchenOrderReady` alanları ve `eventId` ile idempotent tüketim. 4. Order'ın READY bildirimini sipariş durumuna nasıl yansıttığı (Kitchen'dan bağımsız). 5. Sıra boşluğunda veya kayıp olayda Order'ın Kitchen'dan güncel durumu nasıl alacağı (örn. salt okunur servis çağrısı; bugün yoktur).

### 6.5 Yerel olay kaydı (V83) — uygulanan teslim garantisinin tam sınırı

- **Tablolar (`V83__create_outbox.sql`, Kitchen aralığı):** `outbox_events` (olay, sabit `id` = `eventId`) ve `outbox_deliveries` (hedef başına teslim durumu). `UNIQUE(restaurant_id, aggregate_type, aggregate_id, event_type)`: bir sipariş için en fazla **bir** `KitchenOrderReady`; tekrar veya yarışan komut ikinci olay **üretemez** (veritabanı güvencesi). `UNIQUE(restaurant_id, event_id, destination)`, tenant uyumlu bileşik FK, `DELIVERED ⇔ delivered_at` ve `PENDING ⇒ next_attempt_at` CHECK'leri.
- **Atomiklik:** olay ve teslimat satırı, READY geçişiyle **aynı transaction'da** yazılır (`Propagation.MANDATORY`). Olay yazılamazsa durum, `readyAt`, `version`, history, olay ve teslimat **birlikte geri alınır** (testle kanıtlı); komut kimliği yeniden kullanılabilir kalır. Tekrar komutu (200) ve reddedilen komutlar (409/404/401) **hiçbir olay yazmaz**.
- **Teslim garantisi:** *bu sürümde kayıt dayanıklıdır, iletim yoktur.* Olay yerel olarak kalıcıdır (`PENDING`); Order'a hiçbir şey gönderilmez.
- **Yeniden başlatma:** olay ve teslimat PostgreSQL'dedir; uygulama yeniden başlatıldığında kaybolmaz.

### 6.6 `OrderEventSink` portu ve teslimat davranışı (sahte hedefle testli)

`OrderEventSink` (port) için **üretim uygulaması yoktur**; `OutboxDeliveryService` kimse tarafından zamanlanmaz veya çağrılmaz. Port sözleşmesi ve kurallar testlerle sabittir:

| Durum | Sonuç |
|---|---|
| Onay (acknowledgement) | `DELIVERED`, kalıcı ve nihai; sonraki denemeler hedefi **çağırmaz** |
| Zaman aşımı, bağlantı hatası, 5xx, istisna, boş/belirsiz sonuç | `RETRYABLE_FAILURE`: **başarı sayılmaz**; aynı `eventId` ile yeniden denenir. Gecikme 5, 10, 20, 40, 80, 160, 320 sn (en çok 1 saat) |
| Kalıcı ret | `FAILED` hemen; yeniden deneme yok |
| 8 deneme tükendi | `FAILED` (`MAX_ATTEMPTS_EXCEEDED`), **görünür**; elle uzlaştırma gerekir |
| Eşzamanlı işçiler | Satır kilidiyle alınan 60 sn'lik **kira**: aynı olay aynı anda iki kez gönderilmez; işçi çökerse kira dolunca yeniden alınır |
| Dış çağrı sırasında | Transaction ve satır kilidi **açık değildir** |
| Hata kodu | Yalnız kısa, temizlenmiş kod saklanır (adres, token, ileti saklanmaz) |

**K2-A15:** Order ulaşılamasa da (zaman aşımı/istisna) Kitchen siparişi `READY` kalır; teslimat `PENDING` ve yeniden deneme zamanı görünür; olay, history ve sipariş değişmez. Teslimat sorunu "hazır"ı geri alamaz.

**Uzlaştırma planı (uygulanmadı):** `FAILED` teslimatlar sorgulanabilir (`outbox_deliveries.status = 'FAILED'`); Order ile bir zaman damgası/sürüm karşılaştırması ve gerekirse salt okunur Kitchen durum sorgusu ekibin kararıdır (§6.4). Tüketici `eventId` ile idempotent olmak zorundadır.
