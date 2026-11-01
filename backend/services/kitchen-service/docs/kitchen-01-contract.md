# Kitchen Service — KITCHEN-01 Tasarım ve Sözleşme Taslağı

Durum: **Faz 1 / KITCHEN-01 çalışma notu.** Burada yazan sözleşmeler *önerilen ve ekipçe teyit edilecek* sözleşmelerdir; Order, Auth veya Gateway tarafında uygulanmış sayılmaz.

## 1. Doğrulanmış repo gerçekleri (1.1.1)

| Konu | Bulgu |
|---|---|
| Paket kökü / katmanlar | `com.restofy.kitchen` — `api`, `application`, `domain`, `infrastructure` (iskelet, iş kodu yok) |
| Gateway | `Path=/api/v1/kitchen,/api/v1/kitchen/**`, prefix **kırpılmaz**; servis yolları `/api/v1/kitchen/...` olmalıdır |
| Migration aralığı | Kitchen **V81–V90** (`docs/decisions/migrations.md`); serviste önceden migration yoktu, ilk dosya `V81__create_kitchen_core.sql` |
| JPA / Flyway | `ddl-auto=validate`, `spring.flyway.locations=classpath:db/migration` |
| Test altyapısı | Testcontainers `jdbc:tc:postgresql:17-alpine` (`backend/test-support/application-test.properties`), gerçek PostgreSQL |
| Güvenlik | `spring-boot-starter-security` var, özel `SecurityFilterChain` **yoktu** (varsayılan generated-password) |
| Auth sözleşmesi | **Yok** (D-09 açık). Servisler arası kimlik sözleşmesi yok |
| Order sözleşmesi | **Yok**. Taşıma (transport) biçimi belirsiz (D-10 açık) |
| Derleme ortamı | Makinede JDK 25 yok (17, 24). Doğrulama `-Djava.version=24` komut satırı override'ı ile yapılır; dosyalar değişmez |

## 2. Tasarım kararları

1. **Veri sahipliği:** Kitchen yalnız kendi DB'sini kullanır; `order_id`, `table_session_id`, `order_item_id`, `product_id` mantıksal REF'tir (cross-DB FK yok).
2. **Tenant:** her tabloda `restaurant_id`; yerel parent/child bağı `(restaurant_id, parent_id)` bileşik FK. Tüm sorgular `restaurant_id` ile sınırlanır; `restaurant_id` **yalnız doğrulanmış principal'dan** alınır, istek gövdesi/header/query'den alınmaz.
3. **Kimlik (fail closed):** Auth sözleşmesi olmadığından production'da kimlik doğrulayan bir mekanizma **yoktur**; filtre zinciri `anyRequest` için kimlik doğrulaması ister ve kimlik üreten bileşen bulunmadığından tüm iş istekleri 401 döner. Testler yalnız test kaynağındaki fixture ile `KitchenAuthentication` üretir. `permitAll`, `X-Role`/`X-Restaurant-Id` header güveni **yoktur**.
4. **Rol:** `/api/v1/kitchen/**` → `KITCHEN` veya `ADMIN`. **Karar:** ADMIN ve KITCHEN yalnız kendi restoranlarının mutfak siparişlerini **okuyabilir**; hazırlama ve diğer yazma yetkileri ayrıca değerlendirilecektir (Faz 1'de yazma endpoint'i yoktur).
5. **Sipariş kabulü:** HTTP endpoint **açılmaz** (taşıma kararı yok, iç kabul endpoint'i dışarı açılamaz). Kabul, `AcceptedOrderIngestService` application use-case'i ve sürümlü `OrderAcceptedMessage` DTO'sudur; test fixture ile doğrulanır.
6. **Idempotency:** `UNIQUE(restaurant_id, order_id)` + `UNIQUE(source_event_id)`. Eşzamanlı çift teslimde kaybeden transaction unique ihlali alır, sonuç `DUPLICATE` olarak sınıflanır.
7. **Sürüm (version) kuralı:** gelen `aggregateVersion` < saklanan → `STALE_IGNORED` (eskisi yeniyi ezmez); `==` → `DUPLICATE`; `>` → mevcut kayıt **değiştirilmez**, `NEWER_VERSION_NOT_APPLIED` döner (geçici ve güvenli davranış; kapsam genişletilmedi). Kabul sonrası sipariş güncellemesinin (kısmi iptal vb.) Kitchen'a yansıması D-02'ye bağlıdır; tek taraflı uygulanmadı. **İzlenebilirlik:** işlenemeyen yeni version `WARN` log (restoran, sipariş, kayıtlı/gelen version, olay ve correlation kimliği; sipariş içeriği yok) ve `kitchen.order.ingest{outcome=...}` sayacı ile görünür kalır; geçersiz mesajlar `REJECTED_INVALID` olarak sayılır. Yeni migration veya tablo gerekmedi, ileride senkronizasyon eklenmesini engelleyen bir şey yoktur.
8. **Faz 1 dışı:** hazırlık geçişi, stok, fire/rapor, iptal kararı, Notification/Order'a olay (outbox/inbox tabloları açılmadı).
9. **Yeni ENV / bağımlılık / parent değişikliği:** yok.

## 3. Sipariş kabul sözleşmesi — `OrderAcceptedMessage` (öneri, schemaVersion 1)

```json
{
  "eventId": "uuid",
  "schemaVersion": 1,
  "occurredAt": "2026-10-17T15:04:05Z",
  "correlationId": "string(<=64, [A-Za-z0-9._-])",
  "restaurantId": "uuid",
  "orderId": "uuid",
  "aggregateVersion": 1,
  "orderNumber": "string(<=40)",
  "tableSessionId": "uuid",
  "tableNumber": "string(<=40)",
  "items": [
    {
      "orderItemId": "uuid",
      "productId": "uuid",
      "productName": "string(<=200)",
      "quantity": 1,
      "note": "string(<=1000)|null",
      "options": [
        { "action": "ADD|REMOVE", "optionId": "uuid", "name": "string(<=150)", "quantity": 1, "note": "string(<=500)|null" }
      ]
    }
  ]
}
```

Fiyat/vergi alanı **bilinçli olarak yoktur**; Kitchen fiyat tutmaz veya hesaplamaz. **`received_at` / `receivedAt` = Kitchen servisinin siparişi kaydettiği an** (Order'ın kabul zamanı değildir ve onunla karıştırılmamalıdır; kuyruk sırası bu alana dayanır). Sipariş-geneli (order seviyesi) not alanı mevcut sözleşmede yoktur ve eklenmemiştir; yalnız kalem notu vardır. Bilinmeyen alan (strict parse) politikası taşıma biçimi belli olunca 1.4.2 kapsamında ele alınır; bugün yalnız yazılı DTO alanları saklanır.

## 4. Mutfak kuyruğu API'si — `GET /api/v1/kitchen/orders` (task 1.2)

- **Erişim:** korumalı; `KITCHEN` veya `ADMIN` rolü. Restoran bağlamı **yalnız doğrulanmış principal'dan** gelir; `restaurantId` query/header değeri yok sayılır. Kimlik doğrulayan bileşen henüz yok → production'da 401 (fail closed).
- **Gateway:** `/api/v1/kitchen/**` kırpılmadan iletilir; yol servis içinde de aynıdır.

| Parametre | Varsayılan | Kural |
|---|---|---|
| `status` | parametre **hiç gönderilmezse** `WAITING` | `WAITING`, `PREPARING`, `READY`, `CANCELLED`, `ACTIVE` (= `WAITING` + `PREPARING`). **Gönderilmiş ama boş/boşluk** (`?status=`) ve geçersiz/küçük harf değerler **400** |
| `receivedFrom` | yok (sınırsız) | ISO-8601 an, **dahil** (`2026-10-17T00:00:00Z`). Boş/boşluk/geçersiz → 400 |
| `receivedTo` | yok (sınırsız) | ISO-8601 an, **hariç**. `receivedFrom` < `receivedTo` olmalı, aksi 400. Boş/boşluk/geçersiz → 400 |
| `page` | `0` | tam sayı ≥ 0. Boş/boşluk, negatif, ondalık, taşan veya sayı olmayan değer → 400 |
| `size` | `20` | tam sayı 1–100. Boş/boşluk ve aralık dışı/geçersiz değer → 400 |

**Tutarlı kural (tüm isteğe bağlı parametreler):** parametre **hiç gönderilmezse** varsayılan uygulanır; **gönderilmiş ama boş, yalnız boşluk, geçersiz veya aynı anahtar birden çok kez** gönderilmişse **400** döner. Hata mesajı gönderilen değeri yansıtmaz. Doğrulama kimlik/rol kontrolünden sonra çalışır (kimliksiz istek 401, yetkisiz rol 403).

Sıralama sabittir ve istemci değiştiremez: **`received_at ASC, id ASC`** (en eski bekleyen önce; eşit zamanda `id` ile deterministik).

Örnek yanıt:

```json
{
  "content": [
    {
      "orderId": "6f1c0a52-8d0e-4c0e-9a41-1d7a4b2f9e10",
      "orderNumber": "A-100",
      "tableNumber": "12",
      "tableSessionId": "0b9d2f71-3a55-4a0e-8f51-2c5e8e3d7a22",
      "status": "WAITING",
      "receivedAt": "2026-10-17T15:04:05.123Z",
      "newArrival": true,
      "itemCount": 2
    }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1
}
```

Hata yanıtları RFC 9457 `ProblemDetail` biçimindedir; 401/403 güvenlik katmanından gövdesiz gelir.

### Sıralama ve yeni sipariş göstergesi (D-06 kararı: FIFO)

- **Karar:** ana hazırlık kuyruğunda **en eski bekleyen sipariş ilk sıradadır**: `received_at ASC, id ASC`. Eski siparişlerin gecikmemesi önceliklidir; yeni siparişleri üstte göstermek için bu sıra **değiştirilmez**.
- **Yeni sipariş göstergesi:** her satırdaki `newArrival` (`status = WAITING`, yani hazırlığa başlanmamış) ve `status=WAITING` yanıtındaki `totalElements` (rozet sayısı). Gösterge yalnız görsel işarettir, sıralamayı etkilemez. "Son görülenden sonra gelenler" için istemci `receivedFrom` kullanabilir.
- **Gecikme etiketi uygulanmadı;** süre eşiği daha sonra belirlenecektir.
- Faz 1'de durum geçişi ve komut endpoint'i yoktur; `PREPARING/READY/CANCELLED` kayıtları Faz 2 ve sonrasında oluşacaktır, listeler bu durumları şimdiden filtreler.
- Faz 1'de durum geçişi ve komut endpoint'i yoktur; `PREPARING/READY/CANCELLED` kayıtları Faz 2 ve sonrasında oluşacaktır, listeler bu durumları şimdiden filtreler.

## 5. Sipariş detayı — `GET /api/v1/kitchen/orders/{orderId}` (task 1.3)

- **Erişim:** korumalı; `KITCHEN` veya `ADMIN`. Restoran bağlamı doğrulanmış principal'dan gelir. `{orderId}` **Order servisinin** sipariş kimliğidir; masa oturumu, Kitchen iç satır kimliği, kalem veya ürün kimliği ile arama yapılamaz (404).
- **Salt okunur:** `POST/PUT/PATCH/DELETE` bu yolda 405 döner (CSRF'siz istek zaten reddedilir). Masa bilgisi Order'ın kabul anında gönderdiği **snapshot**'tır; güncel QR verisi değildir ve Kitchen QR DB'sine sorgu yapmaz. Sonradan gelen farklı sürüm/eski sürüm kayıt snapshot'ını değiştirmez.
- **Notlar:** mevcut sözleşme yalnız **sipariş kalemi notunu** (`items[].note`) ve malzeme notunu (`items[].options[].note`) taşır; ikisi de eksiksiz gösterilir. Sipariş seviyesinde ayrı bir not alanı yoktur ve eklenmemiştir.
- **Boş değerler açık `null`'dır:** hazırlığa başlanmamışsa `"startedAt": null`, hazır değilse `"readyAt": null`, notu olmayan kalemde `"note": null` gönderilir (alan hiç çıkarılmaz; `@JsonInclude(ALWAYS)` ile global Jackson ayarından bağımsızdır). Seçeneği olmayan kalem `"options": []` döner.
- **`receivedAt`:** Kitchen servisinin siparişi kaydettiği andır; Order'ın kabul zamanı değildir.
- **Yanıta konmayanlar:** restoran kimliği, Kitchen iç `id`, `version`, olay kimlikleri, kaynak sipariş sürümü.

Başarılı yanıt (200):

```json
{
  "orderId": "6f1c0a52-8d0e-4c0e-9a41-1d7a4b2f9e10",
  "orderNumber": "A-100",
  "tableNumber": "12",
  "tableSessionId": "0b9d2f71-3a55-4a0e-8f51-2c5e8e3d7a22",
  "status": "WAITING",
  "receivedAt": "2026-10-17T15:04:05.123Z",
  "startedAt": null,
  "readyAt": null,
  "items": [
    { "orderItemId": "…", "productId": "…", "productName": "Burger", "quantity": 2, "note": "az pişmiş" },
    { "orderItemId": "…", "productId": "…", "productName": "Ayran", "quantity": 1, "note": null }
  ]
}
```

Hatalar (RFC 9457 `ProblemDetail`, iç ayrıntı yok):

| Durum | Ne zaman |
|---|---|
| 400 | `orderId` UUID değil |
| 401 | kimlik doğrulanmamış (bugün production'da her istek: kimlik doğrulayan bileşen yok) |
| 403 | doğrulanmış ama `KITCHEN`/`ADMIN` değil |
| 404 | sipariş yok **veya başka restoranın siparişi** (ikisi ayırt edilemez) |

## 6. Malzeme seçenekleri — `options_snapshot` (task 1.4)

Her kalemin `options_snapshot` jsonb sütunu sürümlüdür (veritabanında `jsonb_typeof = 'object'` CHECK'i var):

```json
{
  "schemaVersion": 1,
  "options": [
    { "action": "REMOVE", "optionId": "uuid", "name": "Soğan", "quantity": 1, "note": null },
    { "action": "ADD",    "optionId": "uuid", "name": "Peynir", "quantity": 2, "note": "çift kaşar" }
  ]
}
```

- `action`: yalnız `ADD` (temel ürüne eklenen) veya `REMOVE` (çıkarılan). Başka değer reddedilir.
- Sıra Order'ın gönderdiği sıradır ve korunur. Seçeneği olmayan kalem `"options": []` döner.
- **Fiyat yok:** snapshot ve API fiyat/vergi taşımaz; Kitchen hiçbir tutar hesaplamaz.
- Saklama kayıpsızdır: tırnak, ters bölü, Unicode/emoji, satır sonu ve HTML benzeri metin aynen saklanır ve JSON olarak serileştirilir (kullanıcı metni HTML olarak işlenmez; istemci metin olarak göstermelidir).
- Okuma sırasında bilinmeyen `schemaVersion` tahmin edilmez, hata verir (500 yerine ilerleyen sürümlerde migration/uyarlama gerekir).

Doğrulama sınırları (aşılırsa mesaj tamamen reddedilir, kısmi kayıt yoktur):

| Alan | Sınır |
|---|---|
| kalem sayısı | 1–200 |
| kalem `quantity` | 1–999 |
| kalem `note` | ≤ 1000 karakter |
| kalem başına seçenek | ≤ 50; aynı `optionId` iki kez olamaz (ADD+REMOVE çelişkisi dahil) |
| seçenek `name` / `note` / `quantity` | ≤ 150 / ≤ 500 / 1–99 |

**Ham payload politikası** (`OrderAcceptedMessageReader`, taşıma biçiminden bağımsız): bilinmeyen alan (özellikle `price`, `unitPrice`, `priceDelta`, `totalPrice` gibi tutar alanları), tekrar eden anahtar, `"2"` veya `2.5` gibi tür dönüşümü, geçersiz `action`, 256 Ki karakterden büyük gövde reddedilir. Hata gövdesi payload içeriğini veya alan adını yansıtmaz.

**Salt okunur:** kalem/seçenek/not değiştiren hiçbir endpoint yoktur; `KITCHEN` ve `ADMIN` için `POST/PUT/PATCH/DELETE` denemeleri 404/405 döner ve veritabanındaki snapshot değişmez. Varlıklar `updatable=false` işaretlidir.

## 7. Test kapsamı ve doğrulama sınırları (dürüst durum)

- **Gerçek veritabanı:** testler Testcontainers ile PostgreSQL 17 üzerinde çalışır (H2 yok); Flyway V81 ve Hibernate `validate` bu testlerle doğrulanır.
- **Test verisi:** `PREPARING`, `READY` ve `CANCELLED` kayıtları yalnız test kodundaki `KitchenOrderSeeder` ile doğrudan SQL olarak yazılır; üretim migration'ına veri eklenmez. Bu durumlara geçiren **gerçek iş akışı Faz 1'de uygulanmamıştır** (Faz 2 kapsamı).
- **Restart testi:** uygulama context'i kapatılıp aynı veritabanına yeni context bağlanır ve liste aynen gelir. Bu bir **uygulama** yeniden başlatma testidir; PostgreSQL container'ının yeniden başlatılması test edilmemiştir (Faz 1 için zorunlu değil).
- **Java sürümü:** hedef sürüm Java 25'tir ve `pom.xml` değiştirilmemiştir. Bu makinede JDK 25 yoktur; testler komut satırında `-Djava.version=24` ile çalıştırılmıştır. **Java 25 ile doğrulama: NOT VERIFIED.**
- **Pre-push:** çalıştırılmadı (`mvn` ve Java 25 Maven gerektirir) — **NOT RUN**. Betik değiştirilmedi.
- **`/actuator/health`:** pre-push kontrolü kimliksiz HTTP 200 ve `status: UP` bekler; Spring Security'nin varsayılan zinciri (tüm iş servislerinde aynı) bu yolu kimlik doğrulamasına bağlar, bu serviste de 401 döner. Güvenlik istisnası eklenmedi; açık teknik konu olarak ekip kararı bekler.
- **CSRF:** Spring varsayılanı korunur (Faz 1 yalnız GET). State-changing endpoint'ler ve Auth yöntemi (D-09) netleşince yeniden değerlendirilecektir.

## 8. Teknik borçlar ve gerçek entegrasyon için gerekenler (KITCHEN-01 sonu)

Bu bölüm onaylanmış bir geliştirme değil, sonraki entegrasyon aşaması için kayıttır. Hiçbiri KITCHEN-01'de uygulanmamıştır.

**Yeni sipariş version'ı uzlaştırması (D-02 bekliyor).** `NEWER_VERSION_NOT_APPLIED` kayıt değiştirmez; yalnız `WARN` log ve `kitchen.order.ingest` sayacı bırakır — sessizce uygulanmış sayılmaz. Gerçek uzlaştırma için D-02 kararı (kısmi iptal ve READY iptal sınırı), Order'dan sürümlü snapshot alma yolu ve Order tarafında `OrderCancelled`/`OrderItemCancelled` olay sözleşmesi gerekir. Bugünkü model (UNIQUE sipariş, `source_order_version`, geçmiş tablosu) buna engel değildir.

**Order entegrasyonu — Order ekibiyle teyit edilecek sözleşmeler:**
- "Sipariş kabul edildi" teslimi: taşıma biçimi (iç HTTP + outbox/inbox veya broker, D-10), endpoint yolu/yöntemi, servis kimliği; bu belgedeki `OrderAcceptedMessage` (schemaVersion 1) alanları.
- İptal kararı olayı ve Kitchen'dan Order'a iptal talebi (neden zorunlu) — KITCHEN-03 kapsamı.
- Hazırlık olayları (`KitchenPreparationStarted`, `KitchenOrderReady`) — KITCHEN-02 kapsamı.

**Auth entegrasyonu — Auth ekibiyle teyit edilecek sözleşmeler:** kullanıcı kimlik doğrulama yöntemi (cookie/JWT, D-09), doğrulanmış principal'ın taşıdığı alanlar (`userId`, `restaurantId`, rol `KITCHEN`/`ADMIN`), restoran üyeliği değişince yetki iptali, servisler arası (servis kimliği) doğrulama. Bugün `KitchenAuthentication` üreten production bileşeni yoktur; tüm iş istekleri 401'dir.

**CSRF.** Spring varsayılanı korunur. Durum değiştiren (POST/PATCH) endpoint'ler geldiğinde Auth yöntemi ve servisler arası kimlik kararıyla uyumlu CSRF yaklaşımı ayrıca değerlendirilecektir; testleri geçirmek için kapatılmayacaktır.

**Diğer borçlar:** gecikme etiketi eşiği belirlenmedi (varsayımsal değer yok); `docs/folder-structure.md` ve `docs/web-rules.md` içindeki eski `.agents/SECURITY.md` referansı; `/actuator/health` ve pre-push uyumu ortak altyapı konusudur (servis güvenlik yapılandırması değiştirilmedi).

**Java 25 ortamı kurulduğunda çalıştırılacak doğrulama komutları** (PowerShell, repo kökünden; bu makinede çalıştırılmamıştır — NOT VERIFIED):

```powershell
java -version                       # 25 olmalı
cd backend\services\kitchen-service
.\mvnw -B clean verify              # -Djava.version override'ı OLMADAN
cd ..\..\..
.\pre-push\pre-push.cmd kitchen-service        # yalnız Kitchen (kullanıcı çalıştırır)
.\pre-push\pre-push.cmd full dev               # tam kalite kapısı (kullanıcı çalıştırır)
```
