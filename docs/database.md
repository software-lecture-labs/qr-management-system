# Restofy — Veritabanı Analizi ve Önerilen Şema

**Tarih:** 15 Ekim 2026. Birincil kaynak: `Restofy_Nihai_Gereksinimler.docx`.  
**Durum:** Mevcut DB altyapısının analizi ve henüz uygulanmamış mantıksal/fiziksel tasarım önerisi. Bu belge çalıştırılabilir migration değildir; canlı DB incelenmemiştir.

İlgili belgeler: [ER diyagramları](er-diagram.md), [mimari ve dağıtık akışlar](architecture.md), [gereksinimler](requirements.md), [klasörler](folder-structure.md).

## 1. Mevcut durum

`docker-compose.yml`, PostgreSQL `17-alpine` üzerinde 12 servis DB'si tanımlar. `postgres/init-databases.sh` isimleri ENV'den alır ve eksik DB'leri oluşturur. API Gateway DB kullanmaz. Veri servisleri JPA `ddl-auto=validate` ve Flyway `classpath:db/migration` kullanacak biçimde ayarlanmıştır.

İncelenen üretim kaynaklarında domain entity veya iş migration SQL'i yoktur. Menu test fixture'larındaki `migration_probe`/`hibernate_probe` tabloları iş modeli değildir. Flyway'in runtime'da history tablosu oluşturup oluşturmadığı canlı DB'den doğrulanmadı. Buradaki tabloların hiçbiri var olan üretim tablosu olarak sunulmaz.

Servis/DB adı sözleşmesi:

- Auth → `DB_AUTH_NAME`; Admin → `DB_ADMIN_NAME`; QR → `DB_QR_NAME`.
- Menu → `DB_MENU_NAME`; Order → `DB_ORDER_NAME`; Kitchen → `DB_KITCHEN_NAME`.
- Waiter → `DB_WAITER_NAME`; Payment → `DB_PAYMENT_NAME`; Rating → `DB_RATING_NAME`.
- Notification → `DB_NOTIFICATION_NAME`; Mail → `DB_MAIL_NAME`; Timer → `DB_TIMER_NAME`.

Gerçek DB adları ENV değerleridir; bu belgede sabit ad kabul edilmez. Compose bütün veri servislerine ortak DB kimliği geçirir. Ayrı DB'ler ayrı kullanıcı ve izinlerin yerine geçmez; servis başına sınırlandırılmış runtime/migration rolleri önerilir.

## 2. Ortak modelleme sözleşmesi

- **PK:** Aksi belirtilmedikçe `id uuid NOT NULL PRIMARY KEY`, uygulamada üretilir. UUID türü/versiyonu ekip kararıdır; yeni extension varsayılmaz.
- **Tenant:** İş tablolarında `restaurant_id uuid NOT NULL`. Başka DB'deki restoran kimliği mantıksal referanstır. Global auth kimliği ve genel teknik teslimat kaydı istisnası açıkça belirtilir.
- **Yerel FK:** Aynı DB içindedir. Tenant taşıyan ebeveynde `UNIQUE(restaurant_id,id)` ve çocukta bileşik `(restaurant_id,parent_id)` FK önerilir. UUID tekilliği tenant karışmasını tek başına engellemez.
- **Mantıksal REF:** Başka servisin DB'sine FK/JOIN kurulmaz. UUID, yetkili API/olay + snapshot ile doğrulanır. Aşağıda REF bu anlama gelir.
- **Zaman:** `timestamptz`; API UTC anı, rapor restoran saat dilimiyle. `date` yalnız rapor iş günü gibi takvim kavramları için.
- **Para:** `numeric(19,4)` + `currency char(3)` önerisi; gösterim/tahsilat ölçeği ve vergi yuvarlaması D-08'de kesinleşir. `float/double` kullanılmaz. Oran `numeric(7,4)` ve `0..100`.
- **Adet:** V1 ürün/fire adetleri pozitif integer. Gramaj `numeric(12,3)` olabilir; miktar stoğu izlenmez.
- **Durum:** Kontrollü `varchar` + CHECK veya eşdeğer uygulama/DB kısıtı. Native enum zorunlu değildir. `version bigint NOT NULL DEFAULT 0` eşzamanlı güncelleme için.
- **İzleme:** İş kayıtlarına `created_at`, değişebilir kayıtlara `updated_at`, kritik geçişlere aktör REF'i eklenir. Bu ortak alanlar aşağıda her tabloda tekrarlanmamıştır.
- **Silme:** Personel, ürün, QR ve masa geçmişe referans veriliyorsa arşivlenir; finansal/iptal/geçmiş kayıtları fiziksel silinmez. Saklama/anonimleştirme takvimi D-11'dir.
- **JSONB:** Sürümlü snapshot, seçenek ve sağlayıcıya özgü hassas olmayan metadata için; FK, durum, tutar ve sorgulanan kimlik alanlarının yerine kullanılmaz.
- **Sırlar:** Parola/token/TOTP/ödeme hassas verisi düz metin veya log içinde tutulmaz. PAN/CVV saklanmaz. QR/guest/auth tokenları için digest; yeniden gösterilecek sır gerekiyorsa ayrı şifreli saklama ve erişim politikası gerekir.

Alan listelerinde `?` NULL olabilir demektir; diğer alanlar NOT NULL önerilir. Koşullu alanların zorunluluğu CHECK veya use-case validation ile uygulanır. REF'ler harici, FK'ler yereldir.

## 3. Admin DB — restoran ve ayarlar

### `restaurants`

`id`, `name varchar(200)`, `slug varchar(120)`, `timezone varchar(80)`, `currency char(3)`, `status varchar(20)`, `version`.

Slug normalize edilip benzersiz olmalı. Saat dilimi IANA adı olarak doğrulanır. Restoran kendi tenant köküdür; ayrıca restaurant_id taşımaz. Fiziksel silme geçmiş servis kayıtlarını koparmamalıdır.

### `restaurant_settings`

`id`, `restaurant_id FK restaurants`, `system_enabled boolean`, `ordering_enabled boolean`, `payment_enabled boolean`, `allow_on_site boolean`, `allow_online boolean`, `allow_meal_card boolean`, `customer_message text?`, `version`.

`UNIQUE(restaurant_id)`. Mesai/login ile sipariş açılma kuralı D-05'tir; bu tablo tek başına o davranışı çözmez. Opsiyonel ödeme alanları destek ilanı değildir; varsayılan kapalı ve ürün kararıyla açılır.

### `daily_metrics` — türetilmiş, öneri

`id`, `restaurant_id FK restaurants`, `business_date date`, `metric_key varchar(80)`, `dimension_key varchar(200)`, `currency char(3)?`, `count_value bigint?`, `amount_value numeric(19,4)?`, `as_of timestamptz`, `source_version bigint?`.

`UNIQUE(restaurant_id,business_date,metric_key,dimension_key)`; parasal metriklerde para birimi dimension_key içine de dahil edilir. Kaynak servis olayları inbox ile bir kez uygulanır. Kaynak gerçek yerine geçmeyen, yeniden üretilebilir projeksiyondur; farklı metrikler kör toplanmaz.

## 4. Auth DB — personel ve kimlik

### `staff_users`

`id`, `email_normalized varchar(254)`, `password_hash text`, `status varchar(20)`, `password_changed_at timestamptz?`, `version`.

`UNIQUE(email_normalized)`. Global personel kimliği önerildiği için restaurant_id yoktur; restoran yetkisi üyelikle verilir. Restoran başına ayrı e-posta hesap modeli istenirse sözleşme değiştirilmelidir. Parola hash algoritması/parametreleri Auth kararıdır; test şifresi üretim verisi değildir.

### `staff_memberships`

`id`, `restaurant_id REF Admin`, `staff_user_id FK staff_users`, `role varchar(20)`, `is_active boolean`, `version`.

`UNIQUE(restaurant_id,staff_user_id,role)`; önerilen roller ADMIN/WAITER/KITCHEN. Bir personel birden fazla restoran/role sahip olabilir; aktif restoran bağlamı her istekte doğrulanır.

### `auth_sessions`

`id`, `staff_user_id FK`, `restaurant_id REF`, `token_digest text`, `expires_at timestamptz`, `revoked_at timestamptz?`, `rotated_from_id uuid? FK auth_sessions`, `last_used_at timestamptz?`.

`UNIQUE(token_digest)`. Sunucu oturumu veya refresh-token ailesi tasarımına göre uyarlanır; JWT/cookie seçilmiş sayılmaz. Rotasyon/replay algılama ve revoke Auth'ta olmalıdır.

### `staff_mfa` / `mfa_recovery_codes`

`staff_mfa`: `id`, `staff_user_id FK UNIQUE`, `encrypted_totp_secret text`, `enabled_at timestamptz?`, `last_accepted_counter bigint?`, `version`.

`mfa_recovery_codes`: `id`, `staff_user_id FK`, `code_digest text UNIQUE`, `used_at timestamptz?`.

TOTP sırrı geri çözülebilir şifreleme, recovery code tek kullanımlık digest gerektirir. Admin TOTP/kurtarma kapsamını taşımak için önerilmiştir; mevcut uygulama değildir. Encryption anahtarı DB'de aynı kayıtla tutulmaz.

## 5. QR DB — masa ve oturum

### `dining_tables`

`id`, `restaurant_id REF`, `table_number varchar(40)`, `display_name varchar(120)?`, `capacity integer?`, `operational_status varchar(20)`, `is_active boolean`, `version`.

`UNIQUE(restaurant_id,table_number)`, capacity varsa > 0. Durum AVAILABLE/OCCUPIED/CLEANING/DISABLED uygulanmıştır. Aktiflik ve doluluk aynı alan değildir.

### `qr_codes`

`id`, `restaurant_id`, `table_id FK dining_tables`, `token_digest varchar(64) UNIQUE`, `encrypted_payload text NOT NULL`, `status varchar(20)`, `issued_at timestamptz`, `expires_at timestamptz?`, `revoked_at timestamptz?`, `version`.

Masa başına tek aktif QR, `(restaurant_id,table_id) WHERE status='ACTIVE'` benzersiz kısmi indeksle uygulanmıştır. Digest doğrulama; şifreli payload aynı QR'ın yeniden görüntülenmesi içindir. Sıradan rotate credential'ı değiştirir, mevcut guest/main'leri kapatmaz; K12 yönetici kurtarması ayrı ve henüz uygulanmamış akıştır. Masanın aktifliği ayrıca kontrol edilir.

### `table_sessions`

`id`, `restaurant_id`, `table_id FK`, `status varchar(20)`, `opened_at timestamptz`, `last_activity_at timestamptz`, `idle_expires_at timestamptz?`, `closing_at timestamptz?`, `closed_at timestamptz?`, `closed_by_user_id uuid? REF Auth`, `version`.

`UNIQUE(restaurant_id,table_id) WHERE status IN ('OPEN','CLOSING')`. Main kapanışı/expiry bütün aktif guest'lere yayılır; IDLE_GUESTS yalnız seçilen guest'leri bitirir. ADR-QR-001/A uyarınca QR sipariş/finans projeksiyonu tutmaz; Order'ın kalıcı iznini kullanır. Süre ve kaynak teyidi davranışı ADR K6/K13'tedir; gerçek Order adaptörü henüz kapalıdır.

### `guest_sessions`

`id`, `restaurant_id`, `table_session_id FK`, `token_digest text UNIQUE`, `status varchar(20)`, `joined_at timestamptz`, `last_activity_at timestamptz`, `expires_at timestamptz?`, `ended_at timestamptz?`, `version`.

İlk tarama dahil her kabul edilen tarama guest açar. Guest kaydı kişi kimliği değildir. Guest erişimi kendi siparişleriyle sınırlı önerilir. Ana/guest eşleşmesi bileşik tenant FK ile korunur.

### `qr_scan_events`

`id`, `restaurant_id`, `table_id`, `qr_code_id FK`, `table_session_id uuid FK`, `guest_session_id uuid FK`, `scanned_at timestamptz`, `outcome varchar(20)`.

Yalnız kabul edilen taramalar kaydedilir (`ACCEPTED`); aynı transaction'da guest/main bağlantısı tenant ve masa bileşik FK'leriyle korunur. Ham token/IP/device fingerprint alanı yoktur.

### `session_transition_operations` — QR'ın yerel lifecycle işlemi

V29/V30: `id`, `restaurant_id`, `table_session_id FK`, `request_json text`, `request_digest varchar(64)`, `stage`, `result_json text?`, `requested_at`, `last_attempt_at?`, `attempts`, `version`. Aynı tenant/main için en fazla bir REQUESTED/APPLIED/ABORTING işlem. İstek ve yazıldıktan sonra yerel makbuz değişmez; geçmiş silinmez. Staff aktörü/gerekçesi komuta bağlıdır. Bu kayıt Order sipariş kabulü veya Payment defteri değildir.

### `session_source_confirmations` — K13 kaynak teyidi

V121: `operation_id PK/FK session_transition_operations`, `restaurant_id`, `confirmation_json text`, `received_at`. İşlem başına en fazla bir değişmez kaynak ACK'i; QR'ın yerel APPLIED sonucu ile tenant/main/digest/etki eşleşmesi insert trigger'ıyla da korunur. scopeDrift/driftBlockers içeren teyit kaydı ve QR COMPLETED geçişi aynı transaction'dadır. Sipariş/finans kalemleri veya credential saklanmaz. Eski `order_admissions` ve ayrıntılı iş/finans projeksiyonu seçilen A modelinde yoktur.

31 Ekim branch-only CREATE birleştirmesi ve yerel QR reseti açık kullanıcı onayıyla yapıldı; mevcut kısıtlar korunarak V23–V25 ara dosyaları kaldırıldı. Yeni zincir V21,V22,V26–V30,V121; ek sürüm sahipliği için [migration kararı](decisions/migrations.md) ve geçiş sınırı için [ADR §6](decisions/ADR-QR-001-masa-kapanisi-ve-siparis-koordinasyonu.md#6-migration-ve-merge-öncesi-koşullar) geçerlidir. Bu, paylaşılmış migration geçmişini yeniden yazma genel izni değildir.

## 6. Menu DB — katalog ve satışa uygunluk

### `categories`

`id`, `restaurant_id REF`, `parent_id uuid? FK categories`, `name varchar(150)`, `sort_order integer`, `is_active boolean`, `version`.

Tenant uyumlu parent FK; kendi kendine parent ve kategori döngüsü engellenir. Çok seviyeli hiyerarşi mümkün olsa da UI ana/alt kategoriyi esas alır. Parent pasiflik yayılımı açık yayın politikasıdır.

### `products`

`id`, `restaurant_id`, `category_id FK`, `name varchar(200)`, `image_url text`, `description text?`, `base_price numeric(19,4)`, `currency char(3)`, `vat_rate numeric(7,4)`, `price_includes_vat boolean`, `weight_grams numeric(12,3)?`, `calories integer?`, `estimated_prep_minutes integer?`, `is_published boolean`, `version`.

Fiyat/gramaj/kalori/süre negatif olamaz; opsiyonel alanlar NULL olabilir. Image URL kontrollü storage kaynağıdır. Ürün ortalama puanı Rating projeksiyonundan gelir; elle değiştirilebilir ürün gerçeği değildir. KDV dahil ibaresi fiyatın gerçek vergi modeline uymalıdır.

### `product_options`

`id`, `restaurant_id`, `product_id FK`, `name varchar(150)`, `option_type varchar(20)`, `price_delta numeric(19,4)`, `min_quantity integer`, `max_quantity integer`, `is_active boolean`, `version`.

Tür INCLUDED/ADD önerisi: INCLUDED çıkarılabilir temel malzeme, ADD eklenebilir malzeme. Çıkarma yetkisi min_quantity=0 ile modellenebilir; zorunlu içerik çıkarılamaz. V1'de INCLUDED fiyat farkı 0, ADD farkı >=0; `0 <= min_quantity <= max_quantity`. Sipariş seçimi ADD/REMOVE eylemini snapshot'lar. Genel hammadde deposu değildir.

### `product_availability`

`id`, `restaurant_id`, `product_id FK UNIQUE`, `status varchar(30)`, `reason_code varchar(40)?`, `reason_text text?`, `changed_at timestamptz`, `changed_by_user_id uuid REF Auth`, `version`.

AVAILABLE/OUT_OF_STOCK/TEMPORARILY_CLOSED. Kapalı durumlarda neden zorunlu; her değişiklik geçmişe yazılır. Menu bu kaydın tek yazarıdır; Kitchen yetkili API komutu yollar. Yeniden başlatmada AVAILABLE yapılmaz.

### `product_availability_history`

`id`, `restaurant_id`, `product_id FK`, `from_status varchar(30)`, `to_status varchar(30)`, `reason_code varchar(40)?`, `reason_text text?`, `changed_by_user_id uuid REF`, `changed_at timestamptz`, `command_id uuid`.

`UNIQUE(restaurant_id,command_id)` tekrar kapatma komutunu tekilleştirir. Ürün yayın pasifliğiyle mutfak kapanışı farklı kavramlardır. Geçerli müşteri görünürlüğü kategori/yayın + availability koşullarından türetilir.

## 7. Order DB — sepet, sipariş ve iptal

### `carts` / `cart_items`

`carts`: `id`, `restaurant_id`, `table_session_id uuid REF QR`, `guest_session_id uuid REF QR`, `status varchar(20)`, `version`.

`cart_items`: `id`, `restaurant_id`, `cart_id FK`, `product_id uuid REF Menu`, `quantity integer`, `selections jsonb`, `note text?`.

Bir guest için tek ACTIVE sepet kısmi benzersiz indeks önerilir. Miktar >0 ve üst sınır doğrulanır. İstemcide gösterilen fiyat teklif niteliğindedir; checkout'ta sunucu yeniden hesaplar. Kalıcı sepet kaynakta zorunlu değildir; bu model çok sekmeli tutarlılık için öneridir.

### `orders`

`id`, `restaurant_id`, `order_number varchar(40)`, `table_session_id uuid REF QR`, `guest_session_id uuid REF QR`, `table_id uuid REF QR`, `admission_id uuid REF QR`, `status varchar(30)`, `service_status varchar(20)`, `currency char(3)`, `original_gross_total numeric(19,4)`, `cancelled_gross_total numeric(19,4)`, `payable_total numeric(19,4)`, `accepted_at timestamptz`, `served_at timestamptz?`, `completed_at timestamptz?`, `version`.

`UNIQUE(restaurant_id,order_number)`; id küresel sipariş kimliği, order_number kullanıcı gösterimidir. Durum ACCEPTED/PARTIALLY_CANCELLED/CANCELLED/COMPLETED; service_status PENDING/SERVED önerisi. Mutfak hazırlığı ve ödeme durumu bu enum'a katılmaz; gerekirse ayrı yeniden üretilebilir projeksiyonlarda tutulur.

Temel V1 toplamı: `payable_total = original_gross_total - cancelled_gross_total`, `0 <= cancelled_gross_total <= original_gross_total`. Bahşiş/indirim/servis ücreti dahil değildir; eklenirse model ve vergi hesabı yeniden tanımlanır. Sipariş toplamı yeni fiyatlarla tekrar hesaplanmaz.

### `order_items`

`id`, `restaurant_id`, `order_id FK`, `product_id uuid REF Menu`, `product_name_snapshot varchar(200)`, `quantity integer`, `cancelled_quantity integer`, `unit_gross_price numeric(19,4)`, `unit_net_price numeric(19,4)`, `vat_rate_snapshot numeric(7,4)`, `vat_amount numeric(19,4)`, `line_gross_total numeric(19,4)`, `note text?`.

`quantity>0`, `0<=cancelled_quantity<=quantity`. Tutarlar seçilmiş seçeneklerle kesinleşmiş değerlerdir; unit_gross_price seçenek farklarını içerir, aşağıdaki option fiyatları ikinci kez toplama eklenmez. Sonradan katalog değişse de snapshot değişmez.

### `order_item_options`

`id`, `restaurant_id`, `order_item_id FK`, `source_option_id uuid REF Menu`, `name_snapshot varchar(150)`, `action varchar(10)`, `quantity integer`, `unit_price_delta_snapshot numeric(19,4)`.

Action ADD/REMOVE; miktar >0. Kaynak ürünün izinli seçeneği kontrol edilir. Tarihsel seçenek satırı katalogdaki fiziksel silmeye bağımlı değildir.

### `cancellation_requests` / `cancellation_items`

`cancellation_requests`: `id`, `restaurant_id`, `order_id FK`, `source varchar(20)`, `requested_by_user_id uuid? REF Auth`, `guest_session_id uuid? REF QR`, `reason_code varchar(40)?`, `reason_text text?`, `status varchar(20)`, `requested_at timestamptz`, `decided_at timestamptz?`, `decided_by_user_id uuid? REF`, `decision_note text?`, `version`.

`cancellation_items`: `id`, `restaurant_id`, `cancellation_request_id FK`, `order_item_id FK`, `quantity integer`, `gross_amount_snapshot numeric(19,4)`, `related_product_id uuid? REF Menu`.

REQUESTED/APPROVED/REJECTED. Tam iptal bütün kalan miktarları, kısmi iptal seçilen miktarları kapsar. İlgili item aynı order'a ait olmalı; composite constraint veya transaction validation gerekir. Kitchen kaynağında neden zorunlu: OUT_OF_STOCK/CUSTOMER_REQUEST/KITCHEN_ERROR/OTHER. Müşteri nedeni boş bırakabilir; kesin karar öncesi garson gerekçe tamamlar. Onaylı toplam iptal miktarı orijinal miktarı geçemez. Kısmi iptalin Kitchen davranışı D-02'dir.

### `order_status_history`

`id`, `restaurant_id`, `order_id FK`, `state_axis varchar(30)`, `from_status varchar(30)?`, `to_status varchar(30)`, `actor_user_id uuid? REF`, `actor_guest_id uuid? REF`, `reason text?`, `occurred_at timestamptz`, `event_id uuid`.

`UNIQUE(event_id)` önerilir. İş/teslim/projeksiyon değişimleri ayrıştırılır. Mutfak asıl durum geçmişi Kitchen'dadır. Onaylı iptal ve finansal geçmiş korunur.

## 8. Kitchen DB — hazırlık ve fire

### `kitchen_orders`

`id`, `restaurant_id`, `order_id uuid REF Order`, `table_session_id uuid REF QR`, `table_number_snapshot varchar(40)`, `order_number_snapshot varchar(40)`, `status varchar(20)`, `received_at timestamptz`, `started_at timestamptz?`, `ready_at timestamptz?`, `cancelled_at timestamptz?`, `cancellation_reason text?`, `source_order_version bigint`, `version`.

`UNIQUE(restaurant_id,order_id)`. WAITING/PREPARING/READY/CANCELLED. PREPARING için started_at, READY için started_at ve ready_at gerekir; `ready_at >= started_at >= received_at`. İptal eski started_at bilgisini silmez. READY servis sonrası değişmez.

### `kitchen_order_items`

`id`, `restaurant_id`, `kitchen_order_id FK`, `order_item_id uuid REF Order`, `product_id uuid REF Menu`, `product_name_snapshot varchar(200)`, `quantity integer`, `cancelled_quantity integer`, `options_snapshot jsonb`, `note_snapshot text?`.

`UNIQUE(restaurant_id,order_item_id)`. **Ürün hazırlık durumu/startedAt/readyAt içermez**; V1 sipariş bazlıdır. cancelled_quantity alanının işletim davranışı D-02 sonrası açılır. Order snapshot'ı salt okunur; mutfak fiyat veya ürün seçeneği değiştiremez.

### `kitchen_status_history`

`id`, `restaurant_id`, `kitchen_order_id FK`, `from_status varchar(20)?`, `to_status varchar(20)`, `actor_user_id uuid? REF`, `source_event_id uuid?`, `occurred_at timestamptz`, `reason text?`.

Geçiş + outbox aynı transaction'da. Yalnız Order kesinleştirme olayı CANCELLED yapar. Kullanıcı komutu farklı idempotency key ile gelse bile beklenen version/geçiş kontrolü çift hazır bildirimi üretmemelidir.

### `waste_records`

`id`, `restaurant_id`, `product_id uuid REF Menu`, `product_name_snapshot varchar(200)`, `quantity integer`, `reason_code varchar(40)`, `description text?`, `recorded_at timestamptz`, `recorded_by_user_id uuid REF Auth`, `order_id uuid? REF Order`.

Miktar >0. Nedenler WRONG_PREPARATION/SPOILAGE/DAMAGE/CANCELLATION_WASTE/OTHER. V1 manuel adet kaydı; otomatik malzeme düşümü veya maliyet muhasebesi değildir. Düzeltme gerekiyorsa audit izli süreç tasarlanır, geçmiş sessizce değiştirilmez.

Gün sonu raporu başlangıçta bu tablolardan hesaplanabilir; ayrı mutfak rapor tablosu zorunlu değildir. D-12 sayım anlamını belirler; alınan/hazır/iptal değerleri farklı zaman bazlarında kör toplanmaz.

## 9. Waiter DB — çağrı

### `service_requests`

`id`, `restaurant_id`, `table_session_id uuid REF QR`, `guest_session_id uuid REF QR`, `request_type varchar(20)`, `message text?`, `status varchar(20)`, `claimed_by_user_id uuid? REF Auth`, `claimed_at timestamptz?`, `resolved_by_user_id uuid? REF`, `resolved_at timestamptz?`, `version`.

Tür WAITER/HELP/BILL; durum OPEN/CLAIMED/RESOLVED/CANCELLED. Claim/resolve version kontrolüyle yapılır. Okundu alanı burada çağrıyı çözmek yerine Notification alıcı kaydındadır. Aynı guest/istek türüne aktif çağrı tekilliği önerisi ürün kararıyla netleşir; masa bazında farklı guest çağrılarını istemeden birleştirmemeli.

Teslim ve masa kapanışı Waiter DB'sinde ikinci otorite oluşturmaz; Order/QR komutlarıdır. Operasyon görünümü gerekiyorsa yeniden üretilebilir projection eklenir.

## 10. Payment DB — ödeme

### `payment_attempts`

`id`, `restaurant_id`, `order_id uuid REF Order`, `guest_session_id uuid? REF QR`, `method varchar(30)`, `mode varchar(10)`, `provider varchar(80)?`, `provider_payment_id varchar(200)?`, `provider_idempotency_key varchar(150)?`, `amount numeric(19,4)`, `currency char(3)`, `status varchar(20)`, `confirmed_by_user_id uuid? REF Auth`, `started_at timestamptz`, `succeeded_at timestamptz?`, `failure_code varchar(80)?`, `version`.

Method ON_SITE/ONLINE; MEAL_CARD ancak kapsam onaylanırsa. Mode REAL/DEMO. Status PENDING/SUCCEEDED/FAILED/CANCELLED/UNKNOWN. `amount>0`; sıfır bakiye tahsilat girişimi değildir. `(provider,provider_payment_id)` NULL olmayanlarda benzersiz. V1 bölüşme yokken `(restaurant_id,order_id) WHERE status IN ('PENDING','UNKNOWN')` tek aktif girişim önerilir; yöntem değişimi önce önceki sonucu kesinleştirir.

### `payment_settlements`

`id`, `restaurant_id`, `payment_attempt_id FK UNIQUE`, `order_id uuid REF Order`, `amount numeric(19,4)`, `currency char(3)`, `confirmed_at timestamptz`, `evidence_reference varchar(200)`.

V1 tam ödeme için `UNIQUE(restaurant_id,order_id)`; yalnız doğrulanmış **REAL** başarı settlement yaratır. Attempt ile aynı order/tenant/currency/tutar şartı yerel FK/transaction validation ile korunur. Bölüşme veya iade eklenirse bu tekillik değişir; migration ve muhasebe modeli gerekir. Demo başarı settlement oluşturmaz.

### `payment_webhook_receipts`

`id`, `restaurant_id`, `payment_attempt_id uuid? FK`, `provider varchar(80)`, `provider_event_id varchar(200)`, `payload_digest text`, `received_at timestamptz`, `verification_status varchar(20)`, `processing_status varchar(20)`, `processed_at timestamptz?`, `error_code varchar(80)?`.

`UNIQUE(provider,provider_event_id)`. Tenant callback body'den kör alınmaz; doğrulanmış merchant/provider eşlemesiyle bulunur. Tenant belirlenemeyen şüpheli istek bu iş tablosuna tenant uydurularak yazılmaz; hassas olmayan güvenlik kaydıyla ayrılır. İmza doğrulaması + receipt + iş değişimi/inbox ilişkisiyle tekrar işleme engellenir. Ham ödeme payload'ını sınırsız saklama önerilmez.

Refund, bahşiş ve kişi/ürün bazlı paylaştırma tabloları bu V1 modelinde yoktur. D-01/D-08 sonrası kapsamlanır. Ödenmiş iptal ve geç başarı için uzlaştırma görünürlüğü gerekir; siparişi silmek ödeme yükümlülüğünü ortadan kaldırmaz.

## 11. Rating DB — uygunluk ve değerlendirme

### `rating_eligibilities`

`id`, `restaurant_id`, `order_id uuid REF Order`, `guest_session_id uuid REF QR`, `payment_settlement_id uuid? REF Payment`, `payment_method varchar(30)`, `eligible_at timestamptz`, `expires_at timestamptz?`, `policy_version varchar(40)`, `status varchar(20)`.

`UNIQUE(restaurant_id,order_id,guest_session_id)`. Uygunluk yalnız doğrulanmış kaynaktan; D-03'e göre politika. Sırf istemci “ödedim” dedi diye uygunluk yaratılmaz. Guest erişimi masa kapanınca sona eriyorsa ödeme sonrası puan için sınırlı ayrı capability gerekir; süre ve taşıma yöntemi ürün/güvenlik kararıdır.

### `restaurant_reviews` / `product_reviews`

`restaurant_reviews`: `id`, `restaurant_id`, `eligibility_id FK UNIQUE`, `stars smallint`, `comment text?`, `submitted_at timestamptz`.

`product_reviews`: `id`, `restaurant_id`, `eligibility_id FK`, `order_item_id uuid REF Order`, `product_id uuid REF Menu`, `stars smallint`, `comment text?`, `submitted_at timestamptz`.

Stars 1..5; `UNIQUE(eligibility_id,order_item_id)`. Ürünün gerçekten uygun sipariş item'ı olduğu servis/snapshot ile doğrulanır. Aynı ürünün bir siparişte birden fazla item'ı için oy tekilliği ayrıca karardır; bu öneride item başınadır.

### `product_rating_summaries` — projeksiyon

`id`, `restaurant_id`, `product_id uuid REF Menu`, `rating_count bigint`, `rating_sum bigint`, `updated_at timestamptz`.

`UNIQUE(restaurant_id,product_id)`; ortalama count>0 ise sum/count, yoksa NULL. Summary değerlendirme commit'iyle veya idempotent olayla güncellenir; ortalamaların ortalaması alınmaz.

## 12. Notification, Mail ve Timer DB'leri

### Notification: `notifications`

`id`, `restaurant_id`, `source_event_id uuid`, `type varchar(60)`, `table_session_id uuid? REF QR`, `order_id uuid? REF Order`, `service_request_id uuid? REF Waiter`, `target_role varchar(20)?`, `payload jsonb`, `status varchar(20)`, `resolved_at timestamptz?`, `archived_at timestamptz?`.

`UNIQUE(source_event_id,type)`; ACTIVE/RESOLVED/ARCHIVED. Payload hassas veri içermeyen sürümlü gösterim bilgisidir. Çağrı çözümü Waiter olayından gelir; bildirimi okumak çözüm değildir.

### Notification: `notification_recipients`

`id`, `restaurant_id`, `notification_id FK`, `staff_user_id uuid REF Auth`, `delivered_at timestamptz?`, `read_at timestamptz?`, `delivery_status varchar(20)`.

`UNIQUE(notification_id,staff_user_id)`. Rol hedefli bildirim alıcıları yetkili üyelik/mesai politikasından çözümlenir; farklı restoran personeli eklenmez. Masa kapanışında aktif liste arşivlenir, geçmiş fiziksel silinmez.

### Mail: `mail_jobs` / `mail_delivery_attempts`

`mail_jobs`: `id`, `restaurant_id uuid? REF`, `source_event_id uuid UNIQUE`, `recipient_reference text`, `template_key varchar(80)`, `template_data jsonb`, `status varchar(20)`, `next_attempt_at timestamptz?`, `expires_at timestamptz?`.

`mail_delivery_attempts`: `id`, `mail_job_id FK`, `attempt_number integer`, `attempted_at timestamptz`, `provider_message_id varchar(200)?`, `status varchar(20)`, `error_code varchar(80)?`.

`UNIQUE(mail_job_id,attempt_number)`. Global hesap kurtarmada restaurant_id NULL olabilir; genel DB/table erişimi public değildir. Alıcı bilgisi minimum tutulur/korunur. Sıfırlama linki içeriyorsa kısa süre, şifreli saklama ve gönderim sonrası payload temizleme gerekir; ham token loglanmaz. Mail başarısı kullanıcının işlem yaptığını kanıtlamaz.

### Timer: `wifi_grants` — plan, çekirdek V1 dışında

`id`, `restaurant_id`, `order_id uuid REF Order`, `table_session_id uuid REF QR`, `device_reference text`, `controller_reference varchar(200)?`, `status varchar(30)`, `started_at timestamptz`, `expires_at timestamptz`, `revoked_at timestamptz?`, `last_error_code varchar(80)?`, `version`.

PENDING_ACTIVATION/ACTIVE/REVOKE_PENDING/REVOKED/FAILED önerisi. İlk süre üç saat hedefidir; tekrar sipariş uzatma ve cihaz tekilliği D-07'dir. expires_at geçmesi controller'ın bağlantıyı kestiğini kanıtlamaz; gerçek kesme ACK'i ayrıca izlenir. Ham ağ kimliği gereksizse saklanmaz. Table session timeout'u bu tabloyla yönetilmez.

## 13. Servis başına teknik tablolar — öneri

Bu tablolar merkezi tek bir DB'de tutulmaz; ihtiyaç duyan servisin kendi DB'sindedir. İş alanı tablolarının yerine geçmezler.

- **`idempotency_records`:** `id`, `restaurant_id`, `actor_scope`, `operation`, `idempotency_key`, `request_digest`, `status`, `resource_id?`, `response_status?`, `safe_response? jsonb`, `expires_at`; `UNIQUE(restaurant_id,actor_scope,operation,idempotency_key)`. Aynı key/farklı gövde çatışmadır; PENDING tekrar yeni işlem başlatmaz. Hassas yanıt/token saklanmaz.
- **`outbox_events`:** `id` (=eventId), `restaurant_id?`, `aggregate_type`, `aggregate_id`, `aggregate_version`, `event_type`, `schema_version`, `payload jsonb`, `occurred_at`. İş verisiyle aynı transaction.
- **`outbox_deliveries`:** `id`, `event_id FK outbox_events`, `destination`, `status`, `attempt_count`, `next_attempt_at?`, `delivered_at?`, `last_error_code?`; `UNIQUE(event_id,destination)`. Çok hedefli teslimde her hedef bağımsız izlenir.
- **`inbox_events`:** `id`, `consumer_name`, `event_id uuid`, `aggregate_id?`, `aggregate_version?`, `processed_at`; `UNIQUE(consumer_name,event_id)`. İş etkisiyle aynı transaction; yalnız “alındı” kaydı iş başarı garantisi değildir.
- **`audit_logs`:** `id`, `restaurant_id?`, `actor_type`, `actor_id?`, `action`, `entity_type`, `entity_id?`, `occurred_at`, `correlation_id`, `safe_change jsonb?`. Parola, QR/guest token, kart, TOTP sırrı taşımaz. Global auth/teknik olaylarda tenant NULL olabilir; erişim kapsamı ayrıca sınırlandırılır.
- **`flyway_schema_history`:** Flyway yönetir; elle iş tablosu gibi değiştirilmez. Her servis DB'sinde bağımsızdır.

## 14. Kritik indeksler ve kısıtlar

1. QR: Tek OPEN/CLOSING masa oturumu; token digest tekilliği; `(restaurant_id,table_session_id,status)` guest araması; `(restaurant_id,table_session_id,scanned_at)` tarama.
2. Menu: `(restaurant_id,parent_id,sort_order)` kategori; `(restaurant_id,category_id,is_published)` ürün; product_availability ürün tekilliği. Arama indeksi gerçek sorgu/hacim ölçülmeden seçilmez.
3. Order: `(restaurant_id,table_session_id,accepted_at DESC)`, `(restaurant_id,guest_session_id,accepted_at DESC)`, `(restaurant_id,status,accepted_at)`; tenant+order_number tekilliği.
4. Kitchen: `(restaurant_id,status,received_at,id)`; WAITING/PREPARING için kısmi indeks değerlendirilebilir. Aynı order_id tekilliği, geçiş version kontrolü.
5. Fire: `(restaurant_id,recorded_at)` ve `(restaurant_id,product_id,recorded_at)`.
6. Waiter: `(restaurant_id,status,created_at)` ve `(restaurant_id,table_session_id,status)`.
7. Payment: Tek aktif girişim, provider/event tekilliği ve V1 tek settlement/order; `(restaurant_id,status,started_at)` uzlaştırma.
8. Rating: Eligibility ve ürün/item oy tekilliği; yıldız CHECK 1..5.
9. Teknik: Teslim kuyruğunda `(status,next_attempt_at)`; idempotency/inbox tekillikleri; tenant+occurred_at audit erişimi.

FK child kolonları için sorgu ihtiyacına göre indeks eklenir. İndeksler migration'da tanımlanır; yalnız application check'e bırakılan tekillikler eşzamanlılıkta yeterli değildir. Transaction içinde birden fazla satır değişiyorsa tutarlı kilit sırası gerekir.

## 15. Örnek PostgreSQL kısıtları

Aşağıdaki parça yalnız tasarım örneğidir; tablolar tanımlı varsayılır ve doğrudan migration olarak çalıştırılmamalıdır.

```sql
-- QR DB
CREATE UNIQUE INDEX uq_live_table_session
ON table_sessions (restaurant_id, table_id)
WHERE status IN ('OPEN', 'CLOSING');

-- Kitchen DB
ALTER TABLE kitchen_orders
ADD CONSTRAINT ck_kitchen_status
CHECK (status IN ('WAITING', 'PREPARING', 'READY', 'CANCELLED'));

ALTER TABLE kitchen_orders
ADD CONSTRAINT ck_kitchen_timestamps
CHECK (
  (status <> 'PREPARING' OR started_at IS NOT NULL)
  AND (status <> 'READY' OR (started_at IS NOT NULL AND ready_at IS NOT NULL))
  AND (started_at IS NULL OR started_at >= received_at)
  AND (ready_at IS NULL OR (started_at IS NOT NULL AND ready_at >= started_at))
);

-- Payment DB: bölüşmeli ödeme içermeyen V1 önerisi
CREATE UNIQUE INDEX uq_unresolved_payment
ON payment_attempts (restaurant_id, order_id)
WHERE status IN ('PENDING', 'UNKNOWN');
```

CHECK geçerli durum geçişini tek başına denetlemez. Örneğin mutfak WAITING → READY atlaması application geçiş komutu + beklenen version koşuluyla reddedilir. Tutarlar, iptal toplamı ve settlement kaynak eşleşmesi de transaction bütünlüğü gerektirir.

## 16. Migration stratejisi

Her servis `src/main/resources/db/migration/V<version>__<description>.sql` altında bağımsız Flyway geçmişi tutar. `postgres/init-databases.sh` yalnız ilk boş volume kurulumunda DB oluşturur; tablo migration aracı değildir.

`docs/decisions/migrations.md` mevcut ekip aralıkları: Mail V11–20; QR V21–30; Payment V31–40; Order V41–50; Notification V51–60; Menu V61–70; Timer V71–80; Kitchen V81–90; Rating V91–100; Waiter V101–110; Admin V111–120. **Auth aralığı bu listede bulunmuyor; V1–10 diye varsayılmamalıdır.**

Aralıklar tek bir ortak DB migration zinciri anlamına gelmez; her DB bağımsızdır. Aralık dolduğunda genişletme politikası belirlenir. Uygulanmış dosya değiştirilmez, yeni migration eklenir. `clean`, `repair`, `baseline`, volume silme veya veri reseti bu analiz kapsamında yapılmaz. JPA şema üretimi yerine Flyway + validate yaklaşımı korunur.

## 17. Rapor, saklama ve doğrulama

Günlük tahsilat gerçek payment_settlements toplamıdır; demo ve başarısız attempt dahil değildir. Sipariş satışı, iptal miktarı ve tahsilat ayrı rapor ölçüleridir. Farklı para birimleri toplanmaz. Günlük aralık yerel `[00:00, ertesi gün 00:00)` değerlerinden UTC'ye çevrilir; `DATE(timestamp)` sunucu saat dilimine kör bağlanmaz.

Kitchen gün sonu raporu iş günü kabul edilen sipariş kohortunun durumları ve ayrı geçiş sayılarıyla açıklanmalıdır; D-12 bekler. Fire neden/adet toplamı kayıt zamanı üzerinden hesaplanır. Tarama sayısı masa yoğunluğu göstergesi olabilir, tekil insan sayımı değildir.

Saklama süreleri, yedek/geri yükleme hedefi, kişisel veri silme/anonimleştirme ve ödeme kanıtı muhafazası D-11'de belirlenmelidir. Burada yasal süre veya uyum iddiası verilmez.

Uygulama sonrası şema kabulü: tenant bileşik FK'leri, tek açık masa oturumu, duplicate sipariş/callback, toplam ve vergi yuvarlama, iptal üst sınırı, mutfak geçişleri, restart sonrası availability, outbox/inbox tekrarları, migration checksum ve geri yükleme gerçek PostgreSQL üzerinde doğrulanmalıdır. Bu incelemede migration veya uygulama testi çalıştırılmamıştır.
