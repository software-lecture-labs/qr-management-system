# QR Service — Entegrasyon Rehberi

**Güncelleme:** 31 Ekim 2026. **Kapsam:** QR-01–QR-04 ve [ADR-QR-001/A](../decisions/ADR-QR-001-masa-kapanisi-ve-siparis-koordinasyonu.md) doğrultusunda yenilenen QR kodu.

QR masa, QR kimliği ve main/guest oturumlarının sahibidir. Sipariş kabulü/iş durumu Order'da, mali doğruluk Payment'ta kalır. QR ayrıntılı sipariş/ödeme defteri tutmaz. Bu rehber Java sınırlarını açıklar; çalışan karşı-servis endpoint'i, broker veya gerçek servisler arası entegrasyon ilan etmez. Servisler QR domain/JPA sınıflarını import etmez veya QR DB'sine bağlanmaz.

## 1. Teslim kapsamı ve gerçek durum

| Alan | Uygulanan QR davranışı |
| --- | --- |
| QR-01 | Tenant kapsamında masa CRUD, aktiflik/doluluk ayrımı, filtre/sayfalama ve optimistic version |
| QR-02 | Rastgele QR kimliği, digest + AES-GCM şifreli içerik, rotate/aktiflik/silme, metadata, PNG görüntüleme/indirme |
| QR-03 | QR doğrulama, tek açık main, her kabul edilen taramada ayrı guest/token/event, tutarlı yerel istatistik |
| QR-04 / A | Bağımsız guest/main idle; Order kaynaklı koruma; kalıcı otomatik ve staff isteğiyle normal kapanış/idle; kayıp yanıt uzlaştırması; ayrı yetkili temizlendi |

**IMPLEMENTED:** QR tarafındaki A koordinatörü, kısa yerel transaction'lar, değişmez işlem/sonuç kaydı, bounded retry işi, yeni V29/V30 ve PostgreSQL testleri. Eski `OrderAdmissionService`, `OrderBusinessOutcomeService` ve sipariş/finans tabloları kaldırıldı. Personel kapanış komutu 31 Ekim K11 kararıyla güvenli Order protokolüne bağlı biçimde geri eklendi.

**NOT IMPLEMENTED:** Gerçek Auth üyelik/rol ve servis kimliği adaptörleri; Order → Payment sorgusu ve gerçek QR/Order transport'u; frontend aktivitesi/guest taşıması, rate limit ve downstream bildirimleri; K12 yönetici force-rotate/kurtarma API'si ve paneli. Order PR #13 fd4e7a9'da gate, order-state, izin yenileme/geri çekme ve K13 sapma kaydı vardır; QR yerel sonuç işlemesi buna uyarlandı, gerçek bağlantı/E2E yoktur.

**NOT VERIFIED:** Gerçek Auth → Gateway → QR → Order → Payment başarı/yarış E2E, çoklu gerçek Order instance gate'i, gerçek ödeme ve mobil kamera. Testlerde PostgreSQL/Flyway gerçektir; Order otoritesi test double'ıdır.

Health dışındaki HTTP iş API'leri **denyAll** kalır. Auth'ı başlatmak veya rol/restoran header'ı göndermek erişimi açmaz. `OrderSessionAccess.inspect` varsayılan `UNKNOWN`; diğer kaynak metotları unavailable döner. Dolayısıyla çalışan job'lar henüz gerçek siparişsizliği/ödemeyi doğrulayıp masa kapatamaz.

Normatif kaynaklar: [güvenlik](../SECURITY.md), [gereksinimler](../requirements.md), [mimari](../architecture.md), [veri modeli](../database.md), [ER](../er-diagram.md), [yerleşim](../folder-structure.md), [migration aralıkları](../decisions/migrations.md), [web kuralları](../web-rules.md). Eski analizlerin tarihli tespitleri güncel kod yerine geçmez.

## 2. Gerçek HTTP envanteri

Önek **`/api/v1/qr`**; aşağıdaki yollar buna göredir. Gateway'de bu önek için Web MVC route mevcut, path korunur. Route varlığı authentication/authorization veya iş akışının açık olduğu anlamına gelmez. Personel izin matrisi Auth ekibiyle kesinleşecek; “yönetim” burada mevcut kapalı `TableManagementAccess` sınırını ifade eder. Hiçbir iş endpoint'i şu anda public değildir.

| Method | Yol | Tasarlanan kapsam / istek | Uygulanan başarı sözleşmesi |
|---|---|---|---|
| POST | `/tables` | Yönetim; CreateTableRequest | 201 TableResponse + Location |
| PUT | `/tables/{id}` | Yönetim; UpdateTableRequest | 200 TableResponse |
| PATCH | `/tables/{id}/active` | Yönetim; ChangeTableActiveRequest | 200 TableResponse |
| GET | `/tables/{id}` | Yönetim; tenant kapsamlama | 200 TableResponse |
| GET | `/tables` | Yönetim; filtre/page/sort query | 200 TablePageResponse |
| POST | `/tables/{tableId}/qr-codes` | Yönetim; body yok | 201 CreatedQrCodeResponse |
| POST | `/tables/{tableId}/qr-codes/{qrId}/rotate` | Yönetim; QrCodeVersionRequest | 201 CreatedQrCodeResponse |
| PATCH | `/tables/{tableId}/qr-codes/{qrId}/active` | Yönetim; ChangeQrCodeActiveRequest | 200 QrCodeResponse |
| DELETE | `/tables/{tableId}/qr-codes/{qrId}?version=…` | Yönetim; version query zorunlu | 204, body yok |
| GET | `/tables/{tableId}/qr-codes` | Yönetim; page/size | 200 QrCodePageResponse |
| GET | `/tables/{tableId}/qr-codes/{qrId}` | Yönetim; metadata | 200 QrCodeResponse |
| GET | `/tables/{tableId}/qr-codes/{qrId}/image` | Yönetim; görsel | 200 image/png, inline |
| GET | `/tables/{tableId}/qr-codes/{qrId}/download` | Yönetim; aynı görsel | 200 image/png, attachment |
| POST | `/validate` | İncelenecek müşteri scan erişimi; ValidateQrCodeRequest | 200 ValidatedQrCodeResponse; oturum açmaz |
| POST | `/table-sessions/open` | İncelenecek müşteri scan erişimi; ValidateQrCodeRequest | Her kabulde 201 OpenedGuestSessionResponse |
| POST | `/guest-sessions/activity` | Aynı guest/masa bağlamı; GuestActivityRequest | 200 GuestActivityResponse |
| GET | `/tables/{id}/session-statistics` | Yönetim | 200 TableSessionStatisticsResponse |
| POST | `/table-sessions/{sessionId}/close` | Doğrulanmış WAITER/ADMIN; StaffCloseRequest + Idempotency-Key | 200 CLOSED / 202 CLOSING, StaffClosureResponse |
| GET | `/table-sessions/{sessionId}/close-commands/{commandId}?tableId=…` | Aynı tenant staff; salt okuma | 200 StaffClosureResponse |
| POST | `/table-sessions/{sessionId}/cleaned` | Aynı restoran doğrulanmış WAITER/ADMIN; SessionLifecycleRequest | 200 SessionLifecycleResponse |

**Bu önek altında 20 iş method/path'i vardır.** Personel normal close ve close-commands status metotları geri eklendi. Ayrıca Gateway dışı iki internal staff-delegasyon metodu aşağıda belirtilir; toplam 22 iş method/path'i vardır. Otomatik kapanış public HTTP komutu değildir. `/actuator/health` önek dışında public 200'dür, ayrıntıları açmaz. Swagger/OpenAPI dev ayarı olsa bile HTTP yetkisi otomatik açılmaz; anonim `/v3/api-docs` isteğinin 401 dönmesi güvenlik testinde doğrulanır. Yukarıdaki 200/201/202/204 uygulanan controller sözleşmesidir; gerçek kimlikli HTTP başarı E2E'si değildir.

Bütün iş JSON yanıtları `Cache-Control: no-store`. PNG 512×512; `Content-Disposition` adı `qr-{qrId}.png`, nosniff, noindex/nofollow/noarchive ve no-referrer header'ları mevcut. Metadata liste/detayı digest, şifreli payload veya ham token döndürmez. INACTIVE QR/masa görseli basılabilir; bu, tarama yetkisi sağlamaz. REVOKED görseli 409 olur.

### Ağ adresleri ve istemci kurulumu

`.env.example` içindeki örnek topoloji aşağıdadır; gerçek ortam portları ENV ile değişebilir:

| İstemci | Base adres | Kullanım |
| --- | --- | --- |
| Tarayıcı / host uygulaması | `http://localhost:5050/api/v1/qr` | Gateway üzerinden dış istek; üretimde onaylanmış HTTPS edge adresi kullanılır |
| Aynı Compose ağındaki servis | `QR_SERVICE_URL=http://qr-service:8080` | Base URI'ye `/api/v1/qr/...` yolu eklenir; servis kimliği ayrıca gerekir |
| Yerel tanılama | `http://127.0.0.1:5056` | QR host portu; doğrudan erişim QR yetki kontrolünü atlamaz |

Docker içindeki `localhost`, QR container'ını değil çağıran container'ı gösterir. Servis URL'sinin ENV'de bulunması gerçek bir HTTP istemcisi veya servis güveni kurulduğu anlamına gelmez. İlgili servisin kendi infrastructure/client katmanında Spring `RestClient` ve sürümlü DTO'lar kullanılmalı; başka servisin JPA/domain sınıfları paylaşılmamalıdır.

Mevcut `REST_CLIENT_CONNECT_TIMEOUT_MS` / `REST_CLIENT_READ_TIMEOUT_MS` sözleşmesinden yararlanın; örnekte 3000/5000 ms vardır. Bunların bütün QR/karşı-servis çağrılarına otomatik uygulandığını varsaymayın; mevcut istemci yapılandırması ve testleri kontrol edilmelidir. HTTP timeout'ı işlem başarısızlığına dair kesin kanıt değildir. Özellikle create/rotate/open/lifecycle komutlarına genel otomatik POST retry eklemeyin; işlem türünün uzlaştırma ve tekrar güvenliği kuralını uygulayın.

Guest doğrulama ve Order lifecycle Java sınırları için gerçek transport hâlâ yoktur. Waiter kapanışı için `/api/v1/internal/tables/{tableId}/sessions/{sessionId}/close` POST ve `/close-commands/{commandId}?restaurantId=…` GET controller'ları eklendi. İkisi de denyAll arkasındadır; WaiterLifecycleAccess gerçek servis kimliği + aktif personel delegasyonu olmadan olumlu sonuç vermez. İncelenen Waiter'ın `/internal/v1` taslak prefix'i client tarafında canonical `/api/v1/internal` yoluna geçirilmelidir; prefix ve servis güveni karşı servis sahibiyle teyit edilmeden gerçek entegrasyon tamamlanmış sayılmaz.

## 3. Request ve response şekilleri

### Validation

| DTO / query | Alanlar ve gerçek sınırlar |
|---|---|
| CreateTableRequest | `tableNumber`: zorunlu, blank olamaz, en fazla 40; `displayName`: opsiyonel/en fazla 120; `capacity`: opsiyonel/pozitif integer |
| UpdateTableRequest | Aynı alanlar + zorunlu nonnegative `version`; **PUT tam değiştirmedir**, eksik displayName/capacity temizlenir |
| ChangeTableActiveRequest / ChangeQrCodeActiveRequest | Zorunlu boolean `isActive`, zorunlu nonnegative `version` |
| QrCodeVersionRequest | Zorunlu nonnegative `version`; rotate body'de, delete query'de |
| ValidateQrCodeRequest | Yalnız `qrToken`: 43 URL-safe karakter `[A-Za-z0-9_-]` |
| GuestActivityRequest | Aynı biçimde `guestToken` + zorunlu UUID restaurantId/tableSessionId/guestSessionId; bu ID'ler doğrulanacak beklentidir, yetki değildir |
| StaffCloseRequest | commandId, restaurantId, tableId, tableSessionId, nonnegative expectedVersion, requestedByUserId, requestedAt zorunlu; reason opsiyonel düz string ≤300, kontrol karakteri yok. Idempotency-Key UUID'si commandId ile eşleşmeli; path/body hedefi aynı olmalı |
| SessionLifecycleRequest | `version`: zorunlu/nonnegative; cleaned için tableVersion |
| GET tables | isActive yalnız `true/false`; operationalStatus enum; page 0..10000, size 1..100; varsayılan 0/20; sort `tableNumber/createdAt/updatedAt`, direction `asc/desc` |
| GET QR list | page 0..10000, size 1..100, varsayılan 0/20; sıralama issuedAt DESC, id DESC |

İş alanında numara trim edilir; restoran+numara unique'dir. Case-insensitive numara politikası eklenmedi. Unknown JSON alanları uygulama ayarıyla reddedilir; DTO'ya `role`, `paymentSucceeded`, beklenmeyen restaurantId veya operationalStatus eklemek yetki kazandırmaz. UUID/enum/body parse ve Jakarta validation hataları kontrollü 400'dür.

### Örnekler (temsili değerler, gerçek sır/veri değildir)

Masa oluştur:

```json
{"tableNumber":"12","displayName":"Bahçe 12","capacity":4}
```

TableResponse:

```json
{"id":"00000000-0000-0000-0000-000000000012","restaurantId":"00000000-0000-0000-0000-000000000001","tableNumber":"12","displayName":"Bahçe 12","capacity":4,"operationalStatus":"AVAILABLE","isActive":true,"version":0,"createdAt":"2026-10-20T00:00:00Z","updatedAt":"2026-10-20T00:00:00Z"}
```

PUT: `{"tableNumber":"12","version":0}` displayName/capacity'yi NULL yapar. PATCH active: `{"isActive":false,"version":0}`. Rotate ve lifecycle: `{"version":0}`; delete: `?version=0`.

Scan body: `{"qrToken":"<gerçek-43-karakter-token>"}`. Activity body:

```json
{"guestToken":"<gerçek-43-karakter-token>","restaurantId":"00000000-0000-0000-0000-000000000001","tableSessionId":"00000000-0000-0000-0000-000000000002","guestSessionId":"00000000-0000-0000-0000-000000000003"}
```

`<…>` yer tutucuları literal gönderilmez; biçim doğrulamasından geçmez. Ham QR/guest tokenını loga, analytics'e veya kalıcı JS store'a koyma. Müşteri sayfasının guest taşıma/yeniden yükleme sözleşmesi henüz onaylanıp uygulanmadı.

OpenedGuestSessionResponse şekli:

```json
{"tableSession":{"id":"00000000-0000-0000-0000-000000000002","restaurantId":"00000000-0000-0000-0000-000000000001","tableId":"00000000-0000-0000-0000-000000000012","status":"OPEN","version":0,"openedAt":"2026-10-20T00:00:00Z","lastActivityAt":"2026-10-20T00:00:00Z","idleExpiresAt":"2026-10-20T00:15:00Z","orderProtected":false},"guestSession":{"id":"00000000-0000-0000-0000-000000000003","restaurantId":"00000000-0000-0000-0000-000000000001","tableSessionId":"00000000-0000-0000-0000-000000000002","status":"ACTIVE","version":0,"joinedAt":"2026-10-20T00:00:00Z","lastActivityAt":"2026-10-20T00:00:00Z","expiresAt":"2026-10-20T00:15:00Z","orderProtected":false},"guestToken":"<yalnız-ilk-yanıtta-dönen-token>"}
```

Temizlendi yanıtı aynı metadata biçimini kullanır. Otomatik kapanışta `closedByUserId` NULL'dır; sahte personel üretilmez. Aşağıdaki kapanmış oturum için cleaned isteğinde güncel **7** tableVersion kullanılır:

```json
{"sessionId":"00000000-0000-0000-0000-000000000002","tableId":"00000000-0000-0000-0000-000000000012","status":"CLOSED","version":4,"closingAt":"2026-10-20T00:20:00Z","closedAt":"2026-10-20T00:20:00Z","closedByUserId":null,"tableStatus":"CLEANING","tableVersion":7}
```

Diğer response alanları (Java record isimleri gerçek DTO'lardır):

| DTO | Alanlar |
|---|---|
| TablePageResponse | **content**, page, size, totalElements, totalPages |
| QrCodePageResponse | **items**, page, size, totalElements, totalPages (masa listesinden farklı anahtar) |
| CreatedQrCodeResponse | id, restaurantId, tableId, status, version, issuedAt, expiresAt, **qrToken** |
| QrCodeResponse | id, restaurantId, tableId, status, version, issuedAt, revokedAt, expiresAt |
| ValidatedQrCodeResponse | qrId, restaurantId, tableId, tableNumber, displayName, checkedAt; doğrulama snapshot'ı, yetki değil |
| GuestActivityResponse | restaurantId, tableSessionId, guestSessionId, mainVersion, guestVersion, lastActivityAt, mainIdleExpiresAt, guestExpiresAt, mainOrderProtected, guestOrderProtected |
| TableSessionStatisticsResponse | restaurantId, tableId, liveSessionId, liveSessionStatus, activeGuestCount, sessionAcceptedScanCount, tableAcceptedScanCount, checkedAt |

Ham guest tokenı yalnız yeni guest yanıtında döner; DB'de SHA-256 digest bulunur. QR ise digest + AES-GCM şifreli içerikle korunur; aynı QR ileride görsel olarak tekrar alınabilir. QR metadata token kurtarma API'si değildir. Kaybolan QR create/rotate yanıtından sonra önce metadata okunur; kör yeni rotate yapılmaz.

### Hata sözleşmesi

ProblemDetail `status/detail/title` temel sözleşmesi; iç SQL/token/stack trace açılmaz. 400 validation/parse; 401 doğrulanmış kimlik yok; 403 yetki/CSRF; 404 yok/yabancı tenant/uygunsuz QR veya guest; 409 duplicate/stale version/uygunsuz lifecycle/FK veya kapanış koşulu; 503 yapılandırılmamış QR görsel adresi veya eksik gerçek kapanış kanıtı. Genel beklenmeyen hata güvenli 500'dür. Şu an canlı business yazmaları CSRF'siz 403, protected GET'ler anonim 401; varsayılan ADMIN/WAITER rolü de izin sağlamaz. 429 bu serviste uygulanmış kabul edilmez; erişim açılmadan rate limit tasarlanmalıdır.

## 4. Durumlar, süreler ve veri sahipliği

QR'ın yerel zinciri: `dining_tables → qr_codes/table_sessions → guest_sessions/qr_scan_events/session_transition_operations`. Tenant kapsamlı yerel FK'ler vardır; Order/Payment/Auth DB'sine FK veya JOIN yoktur.

| Eksen | Güncel davranış |
| --- | --- |
| Masa | İlk main ile AVAILABLE → OCCUPIED; normal otomatik kapanışla CLEANING; ayrı yetkili temizlendi ile AVAILABLE. Güvenli siparişsiz idle doğrudan AVAILABLE |
| QR | ACTIVE/INACTIVE/REVOKED; masa başına tek ACTIVE. Rotate eski kimliği revoke eder. Referanslı silme 409 |
| Main | OPEN → CLOSING, kaynakla koordinasyon, sonra CLOSED/EXPIRED; kesin kaynak abort'uyla OPEN. Aynı masa için tek OPEN/CLOSING |
| Guest | Her kabul edilen scan ayrı ACTIVE guest. Normal kapanış ENDED, idle EXPIRED. Kimlik/scan sayısı tekil insan sayısı değildir |
| Yerel operasyon | REQUESTED → APPLIED → COMPLETED veya REQUESTED → ABORTING → ABORTED. Sonuç ve komut değişmez; ödeme durumları saklanmaz |

Siparişsiz guest kendi son gerçek aktivitesinden 15 dakika kullanılır; main herhangi bir geçerli scan/aktiviteyle yenilenir. Bir telefon diğer guest'in süresini yenilemez. Okuma, token doğrulama, polling ve arka plan sekmesi aktivite sayılmaz. Order'ın başarılı anlamlı sepet değişikliği ve frontend'in gerçek menü etkileşimi entegrasyonda ayrı bildirilir; bağlantıları henüz yoktur.

Order'ın güvenilir kapsamı kabul edilmiş sipariş sahibi guest'leri ve main'i korur. UNKNOWN veya süresi geçmiş kaynak sonucu fiziksel expiry izni değildir. Süresi dolmuş credential'ın kullanımı ile DB satırlarının job tarafından sonlandırılması farklıdır.

Güvenli son tam iptalde ilgili guest, bütün siparişler güvenli iptalse main için `max(lastActivityAt, doğrulanmışİptalAt)+15 dakika` kullanılır. Gecikmiş mesaj alındığı andan yeni süre başlatmaz; terminal guest canlanmaz. Payment belirsizliği veya bekleyen iade güvenli iptal değildir.

Normal kapanış için bütün gerçek siparişler tamamlanmış ve mali açıdan uygun olmalıdır. Ödenmemiş COMPLETED veya ödenmiş ama hazırlanan sipariş kapanışı engeller. Hiç sipariş vermemiş guest için ödeme aranmaz; kapanışta o da main ile sonlanır. Otomatik kapanışın yanında aynı koşullara bağlı doğrulanmış WAITER/ADMIN isteği de desteklenir. Personel paid/force beyanı yetki veya mali kanıt değildir; temizlik ayrı işlemdir.

## 5. Bağlanacak kapalı güven sınırları

| Sınır | Mevcut davranış | Gerçek entegrasyon yükümlülüğü |
| --- | --- | --- |
| TableManagementAccess | Authentication exception | Personel kimliği, güncel tenant üyeliği ve yönetim izni |
| SessionLifecycleAccess | Authentication exception | Normal close/status ve temizlendi için doğrulanmış aynı-restoran WAITER/ADMIN/userId |
| WaiterLifecycleAccess | Authentication exception | Doğrulanmış Waiter servis kimliği VE aktif aynı-restoran staff delegasyonu; body/header beyanı değil |
| GuestSessionValidationService | Yerel token digest'i + main/guest/masa/süre doğrulaması | Servis kimliği doğrulanmış Order taşıması; tokenı güvenli taşıma |
| OrderSessionAccess.inspect | UNKNOWN | Bütün tenant/main kapsamı, korunan guest'ler, güvenli iptal anları ve tazelik |
| prepare/status/acknowledge/abort | Unavailable | Aynı kalıcı komuta bağlı, doğrulanmış Order/Payment kaynak sonucu |

Sabit tenant, test mock'u veya `permitAll` gerçek entegrasyon değildir. Client restaurantId/role beyanı yetki sayılmaz. Guest credential'ı başka guest veya personel erişimi vermez. Ham QR/guest tokenları log/olay/analytics'e konmaz; tarayıcı localStorage/sessionStorage/IndexedDB'de tutulmaz. Servis güveni ve güvenli tarayıcı taşıması ayrıca uygulanıp doğrulanmalıdır.

## 6. Order/Payment ile A sözleşmesi

**Bunlar Java sözleşmeleridir; yeni iç HTTP endpoint'leri açılmadı.** Order kendi infrastructure/client katmanında sürümlü wire DTO ve `RestClient` adaptörü geliştirmelidir. QR'ın sınıfları ortak domain kütüphanesine dönüştürülmez. Ağ endpoint yolu, servis credential'ı ve izin matrisi ayrı entegrasyon PR'ında kesinleştirilir.

### Guest bağlamı

`GuestSessionValidationService.validate(guestToken)` → restaurantId, tableId, tableSessionId, guestSessionId, checkedAt.

QR token digest'inden gerçek bağlamı çözer; aktif masa/OCCUPIED, OPEN main, ACTIVE guest ve süre/Order koruması birlikte kontrol edilir. Token + beklenen ID overload'u da vardır; beyan edilen ID'ler yalnız eşleşme beklentisidir. CLOSING/terminal main veya başka guest/tenant reddedilir. Doğrulama aktivite yazmaz ve sonraki checkout commit'i için kalıcı izin değildir. Order'ın kendi kapanış gate kontrolü yine zorunludur.

### Okuma sonucu — OrderSessionState

`inspect(restaurantId, tableSessionId)` şu minimal bilgiyi sağlar:

| Alan | Anlam |
| --- | --- |
| restaurantId / sessionId | Yetkili kapsam, istekle birebir eşleşmeli |
| knowledge | UNKNOWN, NO_ORDERS, OPEN_WORK, SETTLED; Order'ın bütün kapsam değerlendirmesi |
| revision / verifiedAt / validUntil | Pozitif kaynak sürümü, gelecekte olmayan doğrulama, henüz dolmamış geçerlilik |
| protectedGuests | Kabul edilmiş işi/koruması süren gerçek guest kimlikleri |
| cancellationReleases / mainCancellationAt | Güvenli son iptalden sonraki sabit süre başlangıçları |

Bu sonuç QR'da per-order/finans projeksiyonu olarak saklanmaz. Kaynak erişilemiyorsa veya kapsam/tazelik bilinmiyorsa UNKNOWN kullanılır. NO_ORDERS okuması tek başına expiry izni; SETTLED okuması tek başına kapanış izni değildir.

### Kalıcı kapanış/idle protokolü

Kaynak: [SessionTransition](../../backend/services/qr-service/src/main/java/com/restofy/qr/domain/SessionTransition.java), [koordinatör](../../backend/services/qr-service/src/main/java/com/restofy/qr/application/service/TableSessionClosureService.java), [QR transaction'ları](../../backend/services/qr-service/src/main/java/com/restofy/qr/application/service/SessionTransitionTransactions.java).

1. QR kısa yerel transaction'da masa/main kilidini alır, süreleri kontrol eder, main'i CLOSING yapar ve REQUESTED kaydını yazar. `Command`: değişmez operationId, Purpose SETTLED/IDLE, requestedAt ve snapshot (tenant/main/table kimlikleri, main/table version, zamanlar, aktif guest version/süreleri).
2. QR transaction'ı bittikten sonra Order'a **aynı komutla** `prepare(command)` çağrılır. Order restaurant/main bazında kendi checkout commit'iyle aynı kalıcı gate'i kullanır; bütün siparişler, devam eden kabuller ve Payment belirsizliklerini değerlendirir.
3. `Authority` tam komutu, SourceStatus, Effect, pozitif sourceRevision, verifiedAt/validUntil, complete, checkoutFenced ve gerekiyorsa idleGuestIds içerir. AUTHORISED yalnız kalıcı checkout engeli ve tam güncel mali kapsam sağlanmışsa verilir. Sadece PAID, boolean, JVM kilidi, sayfalı/active sipariş listesi yeterli değildir.
4. QR yeni kısa transaction'da tam komut, kapsam, tazelik, fence ve güncel yerel snapshot'ı tekrar doğrular. SETTLED_CLOSE main + tüm aktif guest + CLEANING; IDLE_MAIN due main + guest expiry + AVAILABLE; IDLE_GUESTS yalnız kaynakça uygun ve yerelde süresi dolmuş guest'ler + main'in yenilenmeden OPEN olmasıdır.
5. Yerel sonuç ile APPLIED aynı transaction'da kalıcılaşır. `LocalResult` operationId/kapsam, etki, main/table durum ve version, completedAt, expiredGuests içerir. Sonradan değiştirilemez.
6. Transaction dışında `acknowledge(command, localResult)` yapılır. `Confirmation` kimlik/digest/amaç/etki, tamlık, pozitif kaynak sürümü ve doğrulama zamanı denetlenir. Doğrulanmış COMPLETED teyidi V121 `session_source_confirmations` içine kaydedilir ve QR işlemi aynı transaction'da COMPLETED olur. scopeDrift varsa da yerel sonuç korunur; IDLE_GUESTS bütün main'i kapatan bir işlem değildir.
7. Güncel/tam BLOCKED veya UNKNOWN + NONE sonucu ya da yerel snapshot değişikliği QR'ı ABORTING'e geçirir. `abort(command)` aynı komut için doğrulanmış RELEASED veya digest'i eşleşen NOT_STARTED döndürünce OPEN/ABORTED olur. Timeout, null, eksik veya yanlış kapsamlı yanıt kendiliğinden yeniden açmaz.

Order `status(command)` ile aynı işlem sonucunu döndürür. Kayıp prepare yanıtından sonra yeni işlem ID'si yaratılmaz. Yalnız yetkili, güncel ve tam NOT_STARTED yanıtı aynı komutun prepare edilmesini sağlar. Ağda bilinmeyen/eksik/stale yanıt REQUESTED/CLOSING'i korur; tam ve güncel UNKNOWN + NONE ise NOT_CLOSED abort yoludur. Kalıcı gate izin süresi doldu diye kalkmaz. QR süresi geçmiş izni aynı işlemle bir kez status üzerinden yeniler; kilit beklerken süre dolarsa uygulamayı erteleyip REQUESTED bırakır. İşlem başına sıkı yenileme döngüsü yoktur.

Kaynak status BLOCKED/UNKNOWN/NOT_STARTED için de komut, kapsam ve tazelik doğrulanır. ACK/abort artık boolean değildir. `Confirmation` operationId, restaurantId, sessionId, purpose, commandDigest, kaynak state/effect/revision/verifiedAt/complete ve scopeDrift/driftBlockers taşır. NOT_STARTED abort'unun purpose alanı null olabilir; diğer sonuçlarda amaç eşleşmelidir. Gerçek wire adaptörü her status/confirmation'a commandDigest göndermeli, bilinmeyen ek JSON alanlarını tolere etmeli ve yanlış kimlik/digest'ten olumlu sonuç üretmemelidir. HTTP IDEMPOTENCY_CONFLICT ve hatalı makbuz operasyonel uzlaştırmadır; kör POST retry edilmez. Bu ağ adaptörü henüz uygulanmadı.

### K13 — Kaynak kapsam sapması

Doğru PREPARED işleme ait CLOSED teyidinde Order artık CLOSURE_SCOPE_CHANGED ile reddetmez; COMPLETED + scopeDrift/driftBlockers döndürür ve kendi V49 kaydını tutar. QR teyidi yerelde değişmez saklar; oturumu yeniden açmaz, tahsilat/iade başlatmaz. Staff/Waiter yanıtında CLOSED korunur; bilinen sapma mevcut blockers alanında yalnız sabit RECONCILIATION_REQUIRED olarak görünür. Ayrıntılı kaynak kodları yerel teyit kaydındadır. ACK kaybolduğunda bilgi henüz bilinmeyebilir; APPLIED işlem aynı kimlikle uzlaştırılır. Yönetici ekranı/raporu ve Order sapma olayı bu görevde yoktur.

### Transaction ve retry sınırı

Dış koordinatörler caller transaction'ını `NEVER` ile reddeder. Order okuma/prepare/status/ACK/abort çağrıları hiçbir QR DB transaction'ında çalışmaz. QR yazımı kısa transaction'larda aynı parent kilit sırası ve optimistic version ile yürür. `openLocked/validateLocked` yalnız mevcut QR transaction'ında ve önceden alınmış kaynak bilgisiyle iç kompozisyon içindir; ağdan çağrılmaz.

`SessionClosureRetryJob` 60 saniyede önce REQUESTED/APPLIED/ABORTING işlemleri, sonra OPEN main adaylarını en fazla 100'er kayıtla keyset tarar. Bir kayıt hatası diğerlerini durdurmaz. `SessionIdleExpiryJob` aynı aralık/batch yaklaşımıyla idle adaylarını değerlendirir ve aynı gate protokolünü kullanır. Bunlar broker/outbox dispatcher veya ödeme retry mekanizması değildir.


### Staff/Waiter normal kapanış ve durum sözleşmesi — K11

Normal kapanış otomatik de, doğrulanmış staff isteğiyle de aynı Order izni üzerinden yürür. Personel komutu ödeme onayı veya force yetkisi değildir; gerçek Order/Payment adaptörü yokken kaynak UNKNOWN kalır.

POST gövdesi (temsili kimlikler, gerçek token içermez):

```json
{
  "commandId": "00000000-0000-0000-0000-000000000010",
  "restaurantId": "00000000-0000-0000-0000-000000000001",
  "tableId": "00000000-0000-0000-0000-000000000012",
  "tableSessionId": "00000000-0000-0000-0000-000000000002",
  "expectedVersion": 3,
  "requestedByUserId": "00000000-0000-0000-0000-000000000004",
  "reason": "Müşteri hesabının kapanmasını istedi",
  "requestedAt": "2026-10-31T10:00:00Z"
}
```

- Header: `Idempotency-Key: 00000000-0000-0000-0000-000000000010`. Client restaurantId/userId yalnız doğrulanacak beklentidir. Beklenen version ana oturumun sürümüdür. Aynı kabul edilmiş komutun farklı hedef, origin, aktör, version, gerekçe veya zamanla tekrarı 409 COMMAND_CONFLICT'tir.
- 200 CLOSED: `commandId,state,tableId,tableSessionId,closedAt,guestSessions{total,ended},table,rejectionCode,blockers`. total == ended olmak zorundadır; ENDED ve daha önce EXPIRED guest'ler terminal sayılır. table alanı `id,restaurantId,tableNumber,displayName,capacity,operationalStatus,active,version,currentSession` taşır.
- 202 CLOSING: kalıcı istek kabul edildi fakat sonuç kesinleşmedi. Gelen GET yalnız durumu okur, retry yapmaz.
- GET 200 REJECTED: kaynak kesin abort doğrulandıktan sonra terminal ret. POST aynı reddi 409 CLOSURE_BLOCKED olarak döndürür. Bilinmeyen commandId GET 404; uyumsuz tenant 403, hedef/versiyon/komut çakışmaları güvenli 404/409'dur.
- Order okuması baştan UNKNOWN ise mutasyon başlamadan 503 olabilir. Prepare yanıtı kalıcı niyetten sonra kaybolursa bilinen CLOSING/yerel CLOSED döner. ACK kaybı yerel kapanışı geri almaz; K9 uzlaştırması devam eder.
- Aynı POST kapatma anındaki makbuzu döndürür; yeni ziyareti etkilemez. GET'in table alanı güncel QR görünümüdür; sonradan temizlenen veya yeni oturum açılmış masa farklı görünebilir. Eski kapanış kendiliğinden geri alınmaz.
- Origin STAFF ve WAITER_SERVICE ayrı doğrulanır. Waiter için servis kimliği tek başına staff yetkisi sağlamaz; active personel delegasyonu da gerekir. Varsayılan adapter'lar kapalıdır. Gerçek servis/cookie/CSRF modeli ayrıca bağlanacak; keyfî Authorization veya X-User/X-Role header'ı olumlu yetki üretmez.
- Waiter, QR kesin CLOSED ve bütün guest'lerin sonlandığını bildirmeden çağrılarını iptal etmemelidir. QR'dan kendiliğinden başlayan kapanışın Waiter'a dayanıklı bildirimi henüz yoktur.

### Nakit/online ve yönetici kurtarması — K10/K12

Payment nakit ve online seçenekleriyle geliştirilecek. Nakit yalnız yetkili personelin Payment kaydıyla, online yalnız güvenilir sağlayıcı kanıtıyla doğrulanır. QR bunların tutar/borç/tahsilat defterini tutmaz; Order tam kapsamı doğrular.

Admin panelinden açık sipariş veya kaynak kesintisi uyarısı sonrası ayrı “zorla QR yenile ve oturumu sonlandır” işlemi **gelecek geliştirme** olarak ADR'ye kaydedildi. Güncel ADMIN yetkisi, zorunlu gerekçe ve ikinci açık onay; değişmez audit ve borç/ödeme/iptal geçmişinin korunması gerekir. Operasyonel kapanış “ödendi” demek değildir. Kaynak belirsizse zorunlu uzlaştırma ve yeni ziyaret/kabul engeli planlanır.

**Bu görevde force API, admin paneli veya kurtarma kayıt altyapısı uygulanmadı.** Normal rotate mevcut main/guest'leri kapatmaz. Normal close gövdesi `force`, `paid` veya rol beyanlarını reddeder. Ayrıntılı akış ve sınırlar [ADR K12](../decisions/ADR-QR-001-masa-kapanisi-ve-siparis-koordinasyonu.md#k12--yönetici-uyarıyla-gerekçeyle-ve-ayrı-onayla-zorla-qr-yenileyebilecek-gelecek-geliştirme) içindedir.

## 7. Karşı servislerin yapacağı işler

| Servis | Gereken |
| --- | --- |
| Order / Hamza | K5 gerçek QR token adaptörü; başarılı sepet aktivitesi; main bazlı kalıcı checkout gate; prepare/status/ACK/abort; tam kapsam/korunan guest/güvenli iptal sonucu; Payment'ın yetkili sorgusu |
| Payment / Alper | Order'a mali kapsam, sürüm/tazelik ve belirsizlik/iade sonuçları; müşterinin yalnız kendi siparişini ödemesi; kesin olmayan ödeme/iadenin clear sayılmaması |
| Auth / QR | Personel kimliği/tenant üyeliği, cleaned/yönetim izinleri ve gerçek servis güveni; kademeli API açılışı |
| Menu / Frontend | Güvenli public URL/fragment ve guest taşıması; gerçek menü aktivitesi; background polling'in aktiviteye dönüşmemesi |
| Waiter | K11 normal close + read-only status request/response alanları QR'da mevcut; canonical prefix ve gerçek servis/staff delegasyonuna client'ı uyarlamak; CLOSED sonrası çağrılarını bitirmek. Internal cleaned/okuma ve otomatik kapanış bildirimi ayrıca geliştirilecek |
| Notification / diğer tüketiciler | Kapanış/temizlik olayları ve dayanıklı teslim ayrıca geliştirilir; mevcut QR retry işi bildirim teslimi sağlamaz |

Order PR #13'ün incelenen `fd4e7a9` sürümünde V48 kalıcı işlem/gate, prepare/status/confirmation, order-state, izin yenileme/idle ve V49 değişmez sapma kaydı vardır. K13, önceki CLOSED uyuşmazlığını çözer. Gerçek Payment sorgusu, QR adaptörü, outbox dispatcher ve sapma ekranı/raporu/olayı yoktur. V41–V49 kullanılır; V50 boştur. Waiter PR #14 `5dcb64b` salt okunur incelendi. Uzak servis kodları değiştirilmedi; gerçek servisler arası E2E çalıştırılmadı.

## 8. Bağlama ve ortak doğrulama sırası

1. Gerçek Auth personel/servis güvenini küçük PR'larla kurun; önce ortak rol/tenant matrisi ve negatif testler.
2. Mevcut Order gate ve sürümlü wire sözleşmesini gerçek Payment sorgusuyla tamamlayın. İncelenen Order zinciri V41–V49 kullanıyor; sonraki numara için güncel geçmiş ve ekip aralığı esas alınır.
3. QR'ın kapalı `OrderSessionAccess` sınırını gerçek adaptörle değiştirin; read authority ile mutation permit'i karıştırmayın.
4. Gerçek token doğrulama ve foreground aktivite taşımasını kurun; müşteri taramaları açılmadan rate limit'i ekleyin.
5. Checkout-kapanış yarışları, kayıp ACK/restart, Order/Payment kesintisi, tenant/guest sahipliği ve SQL rollback için ortak E2E çalıştırın.
6. Gerçek kimlikli HTTP ve mobil akış doğrulandıktan sonra endpoint gruplarını açın. Tek bir `permitAll` değişikliği entegrasyon değildir.

## 9. ENV, migration ve yerel işletim

- Yeni uygulama ENV'si veya bağımlılık eklenmedi. `QR_PAYLOAD_ENCRYPTION_KEY` mevcut Base64 32-byte AES anahtarıdır; kaybolursa eski QR içerikleri çözülemez. Gerçek değer Git/log dışında, `.env.example` boş tutulur. [Anahtar rehberi](qr-key-uretimi.md).
- `QR_PUBLIC_ENTRY_URL` boşsa görsel 503. HTTPS, userinfo/query/fragment yok; loopback HTTP yalnız dev/test. Payload `entryUrl#token=<qrToken>`; fragment sunucu isteğine gitmez. Gerçek müşteri sayfası/üretim URL'si henüz bağlı değildir.
- Önceki staff düzenlemesinde (9c7ac86) migration/ENV değiştirilmedi veya DB sıfırlanmadı. İsteğe bağlı initiatedBy bilgisi mevcut değişmez komut JSON'una eklenir; eski otomatik komutun digest'i ve eksik-field JSON'u okunabilir kalır. Staff kullanıcı kimliği mevcut closed_by_user_id alanında da tutulur. Sonraki K13 şema birleştirmesi aşağıdadır.
- Branch-only CREATE birleştirmesi kullanıcı tarafından 31 Ekim'de açıkça istendi: V21/V22/V26/V28'de nihai kısıtlar birleştirildi, V23–V25 ara dosyaları kaldırıldı. Mevcut zincir V21, V22, V26–V30 ve V121'dir. V29 yerel komut/sonuç, V30 değişmez geçmiş, V121 değişmez kaynak teyidi/sapma makbuzudur. Sipariş/finans projeksiyonu eklenmedi. QR SQL migration'larında ALTER TABLE adımı kalmadı. Bu genel production migration politikası değildir.
- V29/V30 değişimi bu branch için merge öncesi açık kullanıcı izniyle yapıldı. Eski checksum'lı **yalnız yerel QR DB** yine açık izinle sıfırlandı; geçmiş QR verileri taşınmadı. Diğer servis DB'leri ve ortak PostgreSQL/Sonar volume'leri korunmuştur. Bu istisna paylaşılan ortama veya başka migration'a genellenmez; ekipte eski QR geçmişi olanlar kendi onaylı geçişini planlamalıdır.
- Ek QR aralığı V121–V130 kullanıcı onayıyla [sürüm dağılımına](../decisions/migrations.md) kaydedildi. V121 kullanılır, V122–V130 boştur. Başka servisin aralığı kullanılmaz. K13 için yalnız yerel restofy_qr DB'si sıfırlanıp yeni zincir/JPA validate/health 200 doğrulandı; önceki mevcut tablo sütun/kısıt/indeks katalogları korundu, diğer DB kimlikleri değişmedi. QR verileri taşınmadı; ortak volume silinmedi.
- JPA `ddl-auto=validate` korunur; otomatik repair/clean yoktur. Yeni dağıtım/paylaşılan geçmiş sonrası migration değişimi yeni sürümle yapılır.

## 10. Auth entegrasyonuna başlarken

Auth ekibinin [PR #2](https://github.com/software-lecture-labs/Restofy/pull/2) teslimi referanstır; `dev` birleştirildikten sonra **gerçek güncel Auth kodu ve sözleşmesi yeniden okunmalıdır**. Auth'ın kendi testlerinin geçmesi QR yetkilendirmesinin çalıştığını kanıtlamaz.

Plan, mevcut QR PR'ının dev merge'inden sonra Auth'ı küçük PR'larla bağlamaktır. Endpoint başına zorunlu tek PR yerine ortak yetki/iş akışı bazında gruplama reviewer'a sorulacaktır; ilk öneri masa listeleme/detay ve `TableManagementAccess` doğrulamasıdır. Guest güvenliği ve hız sınırı tamamlanana kadar müşteri tarama endpoint'leri kapalı kalır. Bu, onaylanmış yeni bir erişim matrisi veya tamamlanmış Auth entegrasyonu değildir.

1. Auth'ın personel oturumu ve JWT/cookie sözleşmesini QR/Gateway sınırlarıyla karşılaştırın. İmza algoritması, issuer, audience, süre, token türü ve auth/MFA durumu kaynakta tanımlandığı biçimde doğrulanmalı. Auth'ın private signing key'i QR'a kopyalanmamalı; QR payload AES anahtarı personel JWT anahtarı değildir. Anahtar dağıtımı/yenilemesi ve servis kimliği için yeni güven tasarımı ayrıca onaylanmalıdır.
2. `TableManagementAccess` ve personelde kalacak temizlik/yönetim işlemleri için güncel restoran üyeliği, işlem izni ve kullanıcı kimliği üreten gerçek adaptörleri geliştirin. Otomatik kapanışın Order kaynağını gerçek servis kimliğiyle doğrulayın; kapalı adaptörü `permitAll` veya sahte kullanıcıyla aşmayın. Yönetim/temizlendi izin matrisi ayrıca korunur. Sahte `X-Role`/`X-Restaurant-Id` ve varsayılan Spring kullanıcısı yetki kaynağı değildir.
3. Personel oturumu ile anonim guest yetkisini ayrı tutun. Guest tokenı yalnız eşleşen restoran/main/guest kapsamında geçerlidir; başka guest'in siparişlerini veya personel API'lerini açmaz. Tokenın tarayıcıda taşınması, reload ve kayıp token davranışı ayrıca kararlaştırılmalı; localStorage/sessionStorage/IndexedDB kullanılmamalı.
4. Cookie kullanımı için CSRF, izinli Origin/CORS, logout/revoke ve üyelik değişikliğinin etkisini birlikte uygulayıp test edin. Curl/Postman veya servis çağrısı başarısız diye CSRF kapatılmamalı. Personel cookie'si ile servis kimliği aynı şey değildir.
5. QR tarama/activity ve görsel üretimi için rate limit/kaynak sınırlarını belirleyin. Birden fazla instance'ta tutarlılık, limit kapasitesi dolduğunda masum kullanıcıların engellenmemesi ve güvenilir proxy üzerinden istemci IP'si çözümü doğrulanmalı. İstemcinin gönderdiği `X-Forwarded-For` doğrudan güvenilir kabul edilmemeli.
6. Önce testte ve onaylı entegrasyon ortamında gerçek Auth ile tenant/rol/nesne sahipliği ve CSRF negatif senaryolarını doğrulayın. Ancak bundan sonra endpoint bazlı erişim kurallarını açın; bütün iş API'lerini topluca `permitAll` yapmak entegrasyon değildir.

Mevcut kapalı adaptörleri kaldırıp sabit olumlu sonuç döndürmek güvenlik kontrolünü tamamlamaz. Yerine gerçek adaptör ve sözleşme testleri gelmelidir. Unit/entegrasyon testlerindeki kontrollü mock'lar korunur; gerçek E2E bunlara eklenir. Bu rehber Auth veya servis kimliği için yeni bir üretim sözleşmesini onaylamaz.

## 11. Bakım kararları ve gözlemlenebilirlik

### Beklenmeyen hata kaydı

`ApiExceptionHandler` beklenmeyen hata için `QR_UNEXPECTED_ERROR` olay kodunu ve exception sınıfını ERROR düzeyinde kaydeder. Exception mesajı, cause, stack trace, request URL/body/header veya token loga aktarılmaz; kullanıcıya güvenli 500 ProblemDetail döner. Bu minimal kayıt dağıtık tracing veya kapsamlı audit altyapısının yerini tutmaz. Entegrasyonda correlation/trace tasarımı ayrıca değerlendirilmelidir.

### İstatistik okumasının transaction kararı

`TableSessionStatisticsService.get` salt okunur PostgreSQL `REPEATABLE_READ` transaction'ı kullanır. Yerel main/guest ve tarama sayaçları tek MVCC snapshot'ından okunur. Order okuma bilgisi transaction dışında alınır; uzak kaynakla atomik snapshot iddiası yoktur. Caller transaction'ı varsa uzak I/O yapılmaz ve koruma konusunda konservatif yerel görünüm kullanılır; sırf istatistik için `PESSIMISTIC_WRITE` alınmaz. Devam eden tarama, activity veya kapanış yazma transaction'ının bitmesini beklemek gerekmez.

İlk okuma sırasında commit edilmemiş değişiklikler görülmez; sorgular arasında yeni commit gelse bile aynı yanıt eski snapshot'tan tutarlı kalır. Sonraki istek yeni commit'i görür. Bu yanıt güncel bir mutasyon izni değildir; Order/kapanış/guest yazma akışlarının kilitleri ve güncel doğrulamaları korunur.

`REQUIRES_NEW`, çağıranın varsa transaction'ını askıya alır; daha zayıf izolasyona sessizce katılmayı önler. Bunun maliyeti iç içe çağrıda ayrı bağlantı gerektirmesidir. Normal HTTP okuması bağımsız transaction'dır; yazma transaction'ı içinden istatistik çağırmayın. Yük/bağlantı havuzu kapasitesi production öncesi ölçülmelidir; bu teslim sayısal performans garantisi vermez.

### Sabitler ve otomatik backend kontrolü

Idle ve kapanış retry işleri 60 saniye aralıkla, 100 kayıt sınırı ve keyset sayfalama ile çalışır. URL üst sınırı `MAX_ENTRY_URL_LENGTH` ile adlandırıldı. Masa ve QR listeleri ortak `PageLimits` doğrulamasını kullanır. Liste/URL sınırları değişmedi; yeni uygulama ENV'si eklenmedi.

[Backend verify workflow'u](../../.github/workflows/backend-verify.yml), backend/kalite scripti/workflow değişen push'larda, `dev` hedefli PR'larda ve manuel tetiklemede Java 25 ile mevcut `pre-push backend dev` kapısını çalıştırır. Bu kapı `mvn clean verify`, her serviste gerçekten çalışmış ve atlanmamış JUnit testleri ve servis başına en az %80 satır coverage kontrolünü içerir. Node.js ve Docker/Testcontainers gerekir; gerçek uygulama ENV'si veya Sonar tokenı kullanılmaz. Workflow yalnız okuma iznine sahiptir; deploy/commit/push yapmaz. Bu dosyanın varlığı çalışmasının başarılı olduğu anlamına gelmez; ilk GitHub çalışması ayrıca kontrol edilmelidir. Branch protection veya zorunlu status check ayarı bu teslimde değiştirilmez.

## 12. Kabul ölçütleri ve kanıt sınırı

QR testleri tenant izolasyonu, kapalı API'ler, ayrı guest süreleri/koruması, güvenli iptal zamanı, otomatik tüm-guest kapanışı, CLOSING sırasında kullanım reddi, stale/eksik kaynak kanıtı, değişen snapshot, SQL rollback, paralel aynı işlem, kayıp prepare/ACK ve değişmez audit kayıtlarını kapsar. Test kaynak adaptörleri çağrıldığında açık QR transaction'ı olmadığını ayrıca doğrular.

Yerel PostgreSQL testlerinin geçmesi gerçek Order gate'ini veya Payment doğruluğunu kanıtlamaz. Ortak kabul planı [ADR §8](../decisions/ADR-QR-001-masa-kapanisi-ve-siparis-koordinasyonu.md#8-ortak-kabul-testleri) içindedir. Backend pre-push ve bağımsız Sonar gerçek sonuçları görev raporunda tutulur; bu rehbere geçmiş sayılarla yeni çalışma başarılıymış gibi kanıt eklenmez. Frontend bu görevde çalıştırılmaz.

Entegrasyonun tamamlanması için gerçek servis kimliği/tenant kontrolleri, Order gate + Payment kapsamı, token/aktivite transport'u, tarama rate limit'i, kesinti/yarış E2E ve onaylı kademeli HTTP açılışı birlikte doğrulanmalıdır. QR'ın yerel A uyarlamasının tamamlanması bütün bu servis işlerinin tamamlandığı anlamına gelmez.
