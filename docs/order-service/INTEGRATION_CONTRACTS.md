# Order Service — sözleşmeler ve kararlar

> **Durum:** Bu belge Order Service'in **koddaki** durumunu özetler. HTTP sözleşmesinin tek yetkili kaynağı koddan üretilen OpenAPI belgesidir (`SWAGGER_ENABLED=true` iken `GET /v3/api-docs`; varsayılan kapalı). Burada anlatılan bir şeyin uygulanmış olması, başka servislerle uçtan uca doğrulandığı anlamına gelmez.
>
> **Etiketler:** **UYGULANDI** (kodda var ve Order testleriyle doğrulandı), **UYGULANMADI** (yok), **DOĞRULANMADI** (kodda var ama gerçek servisle denenmedi).
>
> Ortak belgelerde (`architecture.md`, `SECURITY.md`, `database.md`, `requirements.md`) Order'a özel ayrıntı tutulmaz; yalnızca sınırlar orada, ayrıntı bu belgededir.

## 1. Sorumluluk sınırı

| Order Service'in sahibi olduğu | Sahibi olmadığı |
|---|---|
| Sepetler, siparişler, fiyat/KDV snapshot'ları | Masalar, QR kimlik bilgileri, masa/guest oturumları (QR) |
| İptal kararları ve iptal geçmişi | Ödeme, tahsilat, iade (Payment) |
| Siparişin hazırlık ve ödeme **durumu kaydı** (ödeme durumunu yalnız Payment'ın doğrulanmış olayı değiştirir) | Hazırlık süreci (Kitchen) |
| Masa oturumu kapanış **uygunluğu** ve kalıcı kapanış işlemi (ADR-QR-001) | Masayı fiilen kapatmak (QR) |

Başka servisin veritabanına erişim, ortak JPA entity veya fiziksel FK yoktur. `restaurant_id`, `table_*`, `guest_*` değerleri mantıksal referanstır. Tüm parasal tutarlar `bigint` minor birimdir, fiyatlar KDV dahil brüttür, KDV/net satır başına `ROUND_HALF_UP` ile ayrıştırılır (D-08'in yalnız saklama biçimi kısmı).

## 2. Kimlik ve yetki — geçici sözleşme (D-09)

Order üç kimlik türünü, her biri kendi portu üzerinden doğrular. İstemci başlıkları (`X-Role`, `X-User-Id`, `X-Restaurant-Id`) hiçbir türde yetki kaynağı değildir; çerez okunmaz, CSRF kapalıdır çünkü tarayıcının otomatik eklediği bir kimlik bilgisi kullanılmaz.

| Tür | Başlık | Doğrulayan port | Not |
|---|---|---|---|
| Misafir | `Authorization: Bearer <opak jeton>` | `QrSessionPort` | Restoran, masa, guest ve oturum durumu QR'dan gelir |
| Personel | `Authorization: Staff <opak kimlik>` | `AuthContextPort` | Roller `ADMIN`, `WAITER`, `KITCHEN`; aktif restoran doğrulanmış kimlikten alınır |
| Servis | `Authorization: Service <opak kimlik>` | `ServiceIdentityPort` | `KITCHEN`, `PAYMENT`, `QR`; yalnız `/api/v1/internal/**` |

- **DOĞRULANMADI / UYGULANMADI:** Gerçek QR, Auth ve servis kimliği adaptörleri yoktur. Mock adaptörler yalnız `dev` ve `test` profilinde yüklenir; diğer profillerde üç doğrulayıcı da **fail-closed** (503) çalışır.
- **`dev` profili kimlik doğrulama yapmaz.** Sabit yer tutucu misafir, personel (ADMIN dahil) ve servis kimlikleri, API Gateway üzerinden gelen isteklerde de kabul edilir. Bu yüzden `dev` profili yalnızca yerel makinede kullanılmalı, paylaşılan veya erişilebilir bir sunucuda çalıştırılmamalıdır. Servis `dev` profiliyle açıldığında günlüğe tek ve belirgin bir uyarı bloğu yazılır (kimlik bilgilerini yazmaz, `dev` dışında üretilmez).
- **Açık karar:** Personel kimliğinin taşınma biçimi (`Staff` başlığı mı, Auth'un `HttpOnly` çerezi mi) Auth sahibiyle netleşecek. Çerez seçilirse Order'ın CSRF/Origin korumasını da üstlenmesi gerekir.
- Hedef servis kimliği yöntemi (kısa ömürlü imzalı token) SECURITY.md'de tarif edilmiştir ve **UYGULANMADI**.

## 3. Uç noktalar

Gateway yalnızca `/api/v1/orders` ve `/api/v1/carts` yollarını yönlendirir. `/api/v1/internal/**` ve `/api/v1/admin/**` Gateway'den yönlendirilmez; ağ erişim kontrolü ayrıca doğrulanmalıdır (D-02, **DOĞRULANMADI**).

### 3.1 Misafir ve personel (UYGULANDI)

| Yöntem ve yol | Kim | Not |
|---|---|---|
| `GET /api/v1/carts/current` | misafir | Boş sepet döner, sepet oluşturmaz |
| `POST /api/v1/carts/current/items` | misafir | İsteğe bağlı `Idempotency-Key`; aynı seçim tek satıra birleşir |
| `PATCH /api/v1/carts/current/items/{id}` | misafir | Miktarı ayarlar |
| `DELETE /api/v1/carts/current/items/{id}` ve `/items` | misafir | Satır sil / sepeti temizle |
| `POST /api/v1/orders` | misafir | `Idempotency-Key` **zorunlu**; sepeti siparişe çevirir |
| `GET /api/v1/orders`, `/{id}`, `/{id}/history` | misafir (kendi), personel | Filtre: `tableSessionId`, `tableId`, `view`, `paymentStatus`; KITCHEN indirgenmiş görünüm görür (tutar ve ödeme yok) |
| `PATCH /api/v1/orders/{id}/fulfillment-status` | personel | Bir adım ileri |
| `POST /api/v1/orders/{id}/cancellations` | siparişin sahibi misafir, `WAITER`, `ADMIN` | `Idempotency-Key` zorunlu |
| `GET /api/v1/orders/{id}/cancellations` | sahibi misafir, personel | |
| `PATCH /api/v1/orders/{id}/cancellations/{cid}/reason` | `WAITER`, `ADMIN` | Eksik nedeni bir kez ekler |
| `GET /api/v1/admin/order-cancellations` | `ADMIN` | Restoran kimlikten alınır, istek parametresinden değil |

Başka restoranın veya başka misafirin kaynağı **404** döner (varlık sızdırılmaz); rol yetmiyorsa 403, kimlik yoksa 401.

### 3.2 Servisten servise (UYGULANDI, DOĞRULANMADI)

| Yöntem ve yol | Yalnız | Amaç |
|---|---|---|
| `POST /api/v1/internal/order-events/kitchen` | KITCHEN | `WAITING/PREPARING/READY` durumu |
| `POST /api/v1/internal/order-events/kitchen-cancellation-request` | KITCHEN | Kitchen'ın kendi iptal talebi (neden zorunlu) |
| `POST /api/v1/internal/order-events/kitchen-cancellation` | KITCHEN | Bekleyen iptal talebine onay/ret |
| `POST /api/v1/internal/order-events/payment` | PAYMENT | Tek ödeme durumu değiştirme yolu |
| `POST /api/v1/internal/table-sessions/{id}/closure-preparations` | QR | Kapanış veya boşta-süre bitişi için izin ve tutuk (`purpose` SETTLED veya IDLE, bkz. 5) |
| `GET  /api/v1/internal/table-sessions/{id}/closure-preparations/{operationId}?restaurantId=` | QR | İşlemin durumu ve izin yenileme/geri çekme (bilinmeyen işlem `NOT_STARTED`) |
| `POST /api/v1/internal/table-sessions/{id}/closure-preparations/{operationId}/confirmation` | QR | `CLOSED` (isteğe bağlı makbuzla) / `NOT_CLOSED` (abort) |
| `GET  /api/v1/internal/table-sessions/{id}/order-state?restaurantId=` | QR | Siparişlerin boşta-süre kuralları için anlamı, salt okunur |

Her olayda `eventId` tekilleştirme sağlar: aynı kimlik tekrar gelirse işlem iki kez yapılmaz (`duplicate=true`), aynı kimlik farklı gövdeyle gelirse `409`. Başka restoranın siparişi `404`.

## 4. İş kuralları (UYGULANDI)

**Hazırlık durumu.** `RECEIVED → PREPARING → READY → SERVED → COMPLETED`; adım atlanmaz, geri dönülmez. `PREPARING/READY` için yetkili Kitchen servisidir; personelin bunları `PATCH` ile vermesi yalnız `dev`/`test` profilinde açıktır. `SERVED/COMPLETED` için `WAITER` veya `ADMIN`. `COMPLETED` ödeme gerektirmez (operasyonel ve finansal tamamlanma ayrıdır). `CANCELLED` yalnız iptal kullanım senaryosuyla gelir.

**Ödeme durumu** (hazırlıktan bağımsız ikinci eksen): `NOT_REQUESTED → PENDING → PAID | FAILED | CANCELLED`, `FAILED → PENDING`; `PAID` ve `CANCELLED` sonludur. Yalnız Payment'ın doğrulanmış olayı değiştirir; istemcideki başarı sayfası kanıt sayılmaz. `PENDING`/`PAID` olayı siparişin güncel borcunu ve para birimini taşımalıdır (`409 PAYMENT_MISMATCH`).

**Tahsilat güvenliği.** Her tahsilat `payment_captures` tablosunda `captureId` ile veritabanı kısıtlarıyla tekil tutulur: silinmez, yeniden yazılmaz, bir sipariş en çok bir tahsilatla kapanır. İkinci farklı tahsilat, başka siparişte kullanılmış `captureId`, tutarı uyuşmayan veya iptal edilmiş siparişe gelen `PAID` uzlaştırma vakası olur. `PENDING`'inden önce gelen `PAID` doğrulanmadan uygulanmaz, kalıcı bekler.

**Sipariş oluşturma.** Fiyat, KDV ve toplam sunucuda hesaplanır, istemcinin gönderdiği tutara güvenilmez. Müşterinin onayladığı toplam ve fiyat parmak izi uyuşmazsa sipariş verilmez (`409 PRICE_CHANGED`); değişmiş fiyat ancak parmak izi + `acknowledgePriceChanges` ile kabul edilir. Sipariş anında QR oturumu yeniden sorulur. Aynı `Idempotency-Key` aynı siparişi döner, farklı gövdeyle `409`. Bir sepet en çok bir kez siparişe çevrilir.

**İptal.** Tam veya kısmi (satır ve adet). `RECEIVED` siparişte talep aynı işlemde kesinleşir; `PREPARING` siparişte yalnız güvenilir Kitchen onayıyla (onay yoksa talep açık kalır, hiçbir zaman otomatik onay veya zaman aşımı yoktur); `READY` ve sonrası iptal edilemez (D-02); ödenmiş siparişi müşteri iptal edemez. İptal **iade yapmaz**: gereken iade (`refund_required_minor`) kaydedilir, gerçekleşen iade yalnız Payment'ın doğrulamasıyla yazılır. Neden isteğe bağlı kısıtlı katalog ve en çok 300 karakter düz metindir. Yazar türü `GUEST`, `STAFF` veya `SERVICE`'tir; servisin bildirdiği personel (`reported_staff_id`) yalnız beyandır, hiçbir yetki kararında kullanılmaz.

**Eşzamanlılık.** Aynı sipariş üzerindeki durum, ödeme ve iptal işlemleri sipariş satırı kilidiyle serileşir. Aynı masa oturumunda checkout ile kapanış sıralaması için bkz. 5.

**Saklama.** Idempotency kayıtları: sepet 24 saat, checkout ve iptal 72 saat. Teslim edilmiş outbox 30 gün, inbox 90 gün. Finansal kayıtlar (`payment_captures`, `payment_reconciliation_cases`) hiçbir temizlikte silinmez.

## 5. ADR-QR-001 — masa oturumu yaşam döngüsü (Order tarafı)

QR masa ve oturumların, Order siparişlerin sahibidir. QR'da ayrıntılı sipariş/finans kopyası tutulmaz; Order bütün masa oturumunun siparişlerinden karar üretir. Yanıtlar QR'ın kendi tiplerine göre biçimlendirilmiştir (QR PR #11: `OrderSessionAccess`, `SessionTransition.Authority`, `OrderSessionState`). **Bu sözleşme Order'ın taslağıdır; QR servisi henüz benimsemedi ve gerçek bir QR adaptörü yoktur.**

**İzin (authority) — UYGULANDI.** Hazırlama ve sorgu yanıtı QR'ın "kaynak izni"dir: `sourceStatus` (`NOT_STARTED`, `UNKNOWN`, `BLOCKED`, `AUTHORISED`), `effect` (`NONE`, `SETTLED_CLOSE`, `IDLE_MAIN`, `IDLE_GUESTS`), `sourceRevision` (en az 1, yalnızca artar), `verifiedAt`, `validUntil` (`verifiedAt` + 1 dakika), `complete` (her zaman `true`: kapsamın tamamına bakılır), `checkoutFenced` ve `idleGuestIds`. `BLOCKED`, `UNKNOWN` ve `NOT_STARTED` yanıtları da güncel ve tamdır, çünkü QR yalnızca güncel bir yanıta göre hareket eder (ve abort eder). `purpose` ve `commandDigest` isteğin parçasıdır.

**İzin yenileme ve geri çekme.** Aynı işlemi `GET` ile sormak siparişleri yeniden kontrol eder: hâlâ geçerliyse izin yenilenir (yeni `verifiedAt`/`validUntil`); QR yerelde bir şey uygulamadan önce siparişler değiştiyse izin geri çekilir (`BLOCKED` veya `UNKNOWN`, `effect=NONE`, `blockers`). Geri çekilen işlemin tutuğu, QR `NOT_CLOSED` ile abort edene kadar sürer; zaman aşımı abort sayılmaz. Order'ın "hayır" deme hakkı QR yerelde uygulayana kadar sürer, uyguladıktan sonra biter (aşağıda `CLOSED`). Order'ın hiç görmediği bir işlem `NOT_STARTED` (200) döner, QR bunu "hazırla" diye okur.

**Amaç `SETTLED`: kapanış uygunluğu (K2, K3, K7) — UYGULANDI.** Oturumun tüm siparişlerine, hangi misafir verdiyse bakılır. Hiç siparişi olmayan misafir kapsama girmez; hiç siparişi olmayan oturum asla uygun değildir. Sonuç:

| Sonuç | Anlamı |
|---|---|
| `ELIGIBLE` | Her sipariş `COMPLETED` ve ödenmiş (borç 0), veya `CANCELLED` ve iade bekleyen yok |
| `NOT_ELIGIBLE` | Kesin engel var: `NO_ORDERS`, `ORDER_NOT_COMPLETED`, `PAYMENT_NOT_SETTLED`, `CANCELLATION_PENDING`, `REFUND_PENDING`, `SCOPE_CHANGED` (izin verildikten sonra siparişler değişti) |
| `UNKNOWN` | Para belirsiz, uygun sayılmaz: `PAYMENT_IN_PROGRESS`, `PAYMENT_VERIFICATION_PENDING`, `RECONCILIATION_REQUIRED` |

Kesin engel belirsizlikten önce gelir. Ödeme durumu, Payment'ın doğrulanmış olaylarıyla Order'da kayıtlı hâlidir; Payment'a doğrudan sorgu **UYGULANMADI**.

**Amaç `IDLE`: boşta-süre bitişi (K6) — UYGULANDI.** İstek ayrıca `requestedAt`, `mainIdleExpiresAt` ve `guests` (her biri `guestSessionId` ve `idleExpiresAt`) taşır; eksikse `422`. Order şunu kararlaştırır: hiçbir sipariş oturumu korumuyorsa ve ana süre bittiyse tüm oturum sona erebilir (`IDLE_MAIN`); aksi hâlde yalnızca süresi bitmiş **ve** siparişiyle korunmayan misafirler sona erebilir (`IDLE_GUESTS`, `idleGuestIds`). Hiçbiri yoksa `BLOCKED` (`SESSION_PROTECTED_BY_ORDERS` veya `NOTHING_TO_EXPIRE`). Yerleşik bir `SETTLED` oturum boşta-süreyle bitirilmez, kapatılır. `IDLE_GUESTS` tutuğu yalnız adı geçen misafirlerin sipariş vermesini engeller; tamamlanmış bir `IDLE_GUESTS` işlemi oturumu bitirmez, sonrasında yeni işlem hazırlanabilir.

**Sipariş durumu (order-state) — UYGULANDI.** `GET /api/v1/internal/table-sessions/{id}/order-state?restaurantId=` salt okunur, hiçbir şeye izin vermez. `knowledge`: `NO_ORDERS` (hiç sipariş yok veya hepsi güvenle iptal), `OPEN_WORK` (bitmemiş, ödenmemiş veya parası belirsiz sipariş var), `SETTLED`; eksik veri hiçbir zaman "sipariş yok" diye bildirilmez. `protectedGuests` güvenle iptal edilmemiş siparişi olan misafirlerdir; `cancellationReleases` tüm siparişleri güvenle iptal edilmiş misafiri iptal anıyla eşler; `mainCancellationAt` yalnız oturumdaki her sipariş güvenle iptal edildiyse ana oturumun serbest kaldığı andır. "Güvenle iptal" = iptal edilmiş, kararı verilmiş, iade bekleyen yok, ödeme yolda değil, doğrulanmamış veya uzlaştırılmamış bildirim yok; belirsiz para kimseyi serbest bırakmaz. `revision`, `verifiedAt` ve `validUntil` izinle aynı anlamdadır.

**Sıralama (K8) — UYGULANDI.** Checkout ve hazırlık, masa oturumu başına PostgreSQL advisory lock ile sıralanır (checkout paylaşımlı, hazırlık özel kilit; çok örnekli çalışmada da geçerli). Tutuk önce kurulduysa checkout hiçbir kayıt yazmadan `409 TABLE_SESSION_CLOSING` döner; checkout önce girdiyse kapsamda yer alır.

**Kalıcı işlem (K9) — UYGULANDI.** `table_session_closures` (V48): yalnız izin verilen işlem saklanır (`PREPARED → COMPLETED | RELEASED`). Aynı `operationId` ve aynı `commandDigest` aynı işlemi döner (izin yenilenir); aynı `operationId` başka bir komutla (digest, amaç, oturum, restoran) `409 IDEMPOTENCY_CONFLICT`; oturumda başka canlı işlem varsa `409 CLOSURE_IN_PROGRESS`. Hazırlanmış işlem zamanla kendiliğinden bırakılmaz.

**Tam komut doğrulaması.** QR'ın adaptörü yanıtın kendi komutuna ait olduğunu doğrulamadan ACK/abort sonucu üretmez (QR entegrasyon rehberi §6). Bu yüzden her yanıt `commandDigest` taşır (kayıtlı işlemde saklanan digest). `.../confirmation` isteğinde `commandDigest` **zorunludur** (eksikse `422`); `GET .../closure-preparations/{operationId}` için isteğe bağlıdır. Verilen digest kayıtlı digest ile aynı olmalıdır, değilse `409 IDEMPOTENCY_CONFLICT` ve hiçbir şey değişmez. Order'ın hiç görmediği işlemde (`NOT_STARTED`) yanıt, soruda verilmişse o digest'i geri yansıtır; bu yüzden onayda digest zorunludur: hiç hazırlanmamış işlemin abort'una verilen "abort edilecek bir şey yok" yanıtı da QR'ın kendi komutuna bağlanabilir. Digest biçimi 64 küçük harfli hex'tir, aksi `422`.

**Personel (K11) kaynaklı kapanış.** QR'ın garson/admin komutu aynı `SETTLED` protokolüdür; fark yalnız QR'ın komut digest'indedir (kaynak ve gerçek aktör digest'e bağlanır). Order digest'i anlamlandırmaz, yalnız saklar ve eşler. Personel isteği ödeme/iş koşullarını atlamaz; uygunluk kuralı değişmedi. Nakit/online (K10) ayrımı Payment'tadır: Order yine yalnız Payment'ın doğrulanmış olaylarıyla kendi kayıtlarında ödenmiş saydığını kullanır.

**Abort (`NOT_CLOSED`).** Tutuğu bırakır, yeni siparişler yeniden kabul edilir. Order'ın hiçbir şey tutmadığı bir işlem için (hiç hazırlanmamış veya izin verilmemiş) güvenli bir `200` döner ("abort edilecek bir şey yok"), böylece QR `BLOCKED` sonrasında abort'u kesin olarak tamamlayabilir. Aynı onayı tekrar göndermek güvenlidir; ters yöndeki onay `409 INVALID_STATUS_TRANSITION`.

**Makbuz.** `CLOSED` onayı isteğe bağlı olarak QR'ın yerel sonucunu taşır: `effect`, `sessionVersion`, `tableVersion`, `completedAt`, `expiredGuests`. `effect` izin verilenle aynı olmalıdır (aksi hâlde `409 CLOSURE_RECEIPT_MISMATCH` ve işlem `PREPARED` kalır); makbuz saklanır.

**`CLOSED` her zaman kabul edilir (kapsam sapması).** QR değişikliği yerelde uyguladıktan sonra `CLOSED` gönderir ve uyguladığını kendiliğinden geri almaz; "hazırım" diyen taraf son kararı reddedemez. Bu yüzden Order, `PREPARED` bir işlemde `CLOSED` onayını **reddetmez**: işlemi `COMPLETED` yapar. Siparişler izin anındaki hâlinden artık farklıysa (gerçekçi neden: hazırlıktan sonra Payment'tan gelen, reddedilemeyen bir para bildirimi, örneğin ikinci bir tahsilat) bu fark ayrıca kaydedilir: V49 `table_session_closure_drifts`, işlem başına en çok bir satır, yalnızca eklenir, aynı işlemde yazılır; log'a `WARN` düşer. Yanıt `scopeDrift=true` ve o andaki `driftBlockers` ile döner; aynı onayı veya `GET` sorgusunu tekrarlamak aynı yanıtı verir ve ikinci kayıt yazmaz. Hiçbir şey reddedilmediği için iki servis aynı durumda kalır; sapmayı insanların uzlaştırması gerekir. **UYGULANMADI:** sapma kayıtlarını gösteren ekran, rapor veya olay (şimdilik yalnızca kayıt ve log).

**Misafir aktivitesi (K6) — Order'ın payı UYGULANDI.** Başarılı her sepet değişikliği (ekle, miktar, sil, temizle) aynı işlemde QR hedefli `CartActivityRecorded` outbox olayı yazar. Okuma, idempotent tekrar, değişmeyen miktar, boş sepeti temizleme ve reddedilen istek olay üretmez. Olay ürün, fiyat, not veya jeton taşımaz. QR'ın bu olayı alıp süreyi yenilemesi QR'ın işidir; dispatcher olmadığı için olay şimdilik hiçbir yere teslim edilmez.

**UYGULANMADI:** K5'in gerçek QR çağrısı (jeton doğrulama adaptörü), K4'ün Payment tarafı, Order'dan Payment'a doğrulama sorgusu.

## 6. Olaylar (outbox) — UYGULANDI, tüketici yok

Olaylar iş değişikliğiyle aynı işlemde `outbox_events` ve hedef başına `outbox_deliveries` olarak yazılır. **Dispatcher yoktur**: olaylar `PENDING` kalır ve hiçbir servise teslim edilmiş sayılmaz (D-10 açık). Tüketiciler `eventId` ile tekilleştirmelidir. Olay türleri: `OrderPlaced`, `OrderFulfillmentStatusChanged`, `OrderPaymentStatusChanged`, `OrderPaymentReconciliationRequired`, `OrderCancellationRequested`, `OrderCancelled`, `OrderItemsCancelled`, `OrderCancellationRejected`, `OrderAmountAdjusted`, `CartActivityRecorded`.

## 7. Veritabanı

Flyway aralığı V41–V50, kullanılan V41–V49 (V50 boş). Migration'lar ALTER ve veri taşıma içermeyecek biçimde birleştirilmiştir; hiçbir paylaşılan veritabanı eski zinciri uygulamamıştı.

| Sürüm | İçerik |
|---|---|
| V41 | Sepetler (aktif sepet tekilliği, sona ermiş sepet yeniden aktifleşmez) |
| V42 | Siparişler, kalemler, seçenekler, numara sayacı; snapshot değişmezliği (trigger) |
| V43 | Idempotency kayıtları, outbox |
| V44 | Durum geçmişi (yalnız eklenir) |
| V45 | Inbox; hazırlık ve ödeme geçiş koruması (trigger) |
| V46 | Tahsilatlar ve uzlaştırma vakaları |
| V47 | İptaller, kalemleri ve nedenleri; `cancelled_quantity` tutarlılığı |
| V48 | Masa oturumu işlemleri: amaç, etki, komut özeti, tutuk, makbuz (kapanış ve boşta-süre) |
| V49 | Kapanış kapsam sapması kayıtları (yalnızca eklenir) |

`ddl-auto=validate`; şemayı yalnız Flyway değiştirir. Uygulanmış bir migration paylaşıldıktan sonra yeniden yazılmaz.

## 8. Hata sözleşmesi

`ProblemDetail` benzeri tek gövde: `code`, `message`, isteğe bağlı `details`; yığın izi, SQL, jeton veya kişisel veri içermez. Başlıca kodlar: `401 UNAUTHENTICATED`, `403 ACCESS_DENIED`, `404 ORDER_NOT_FOUND / CLOSURE_NOT_FOUND`, `409 PRICE_CHANGED / TABLE_SESSION_NOT_ACTIVE / TABLE_SESSION_CLOSING / IDEMPOTENCY_CONFLICT / INVALID_STATUS_TRANSITION / PAYMENT_MISMATCH / CANCELLATION_NOT_ALLOWED / CLOSURE_IN_PROGRESS`, `422 VALIDATION_ERROR`, `503 DEPENDENCY_UNAVAILABLE`. Tam liste `ErrorCode` içindedir.

## 9. Açık işler

- Gerçek Auth, QR, Kitchen, Payment entegrasyonu ve uçtan uca doğrulama (**DOĞRULANMADI**, testler mock kimliklerle çalışır).
- Personel kimliği taşıma biçimi (Auth ile).
- Order → Payment doğrulama sorgusu ve outbox dispatcher.
- Yeni internal uç noktalar ve QR servis kimliği Order'ın taslağıdır; QR ve Payment sahiplerinin onayı gerekir (SECURITY.md). Yanıtlar QR PR #11'deki tiplere göre biçimlendirildi ama QR'ın gerçek adaptörü henüz yok: alan adları ve HTTP biçimi adaptör yazılırken QR ile birlikte kesinleşecek.
- Sapma kayıtlarını (V49) kimin okuyacağı: şimdilik yalnızca kayıt ve log.
- Çoklu Order örneği altında eşzamanlılık testi (kilitler PostgreSQL'dedir ama testler tek JVM içidir).
