# Restofy — ER Diyagramları

**Tarih:** 15 Ekim 2026. Kaynak: nihai gereksinim Word belgesi ve repository.  
**Durum:** Önerilen hedef veri modeli; mevcut üretim şeması değildir. Üretim entity/migration bulunmadığı için diyagramlar reverse engineering çıktısı olarak sunulmaz.

Alanların ayrıntısı, NULL kuralları, türler, tenant kısıtları ve indeksler [database.md](database.md) içinde; iş kuralları [requirements.md](requirements.md), servis akışları [architecture.md](architecture.md) içindedir.

## 1. Okuma kuralları

- Mermaid `erDiagram` kullanılmıştır. GitHub veya Mermaid destekli Markdown görüntüleyiciyle çizilebilir; düz metin editörü kodu gösterir.
- `||` tam bir, `o|` sıfır/bir, `o{` sıfır/çok, `|{` bir/çok anlamındadır.
- `PK` fiziksel birincil anahtar, `FK` yalnız aynı servis DB'sindeki fiziksel ilişkidir. Şemalardaki `UK` tek kolon tekilliğini özetler; bileşik/kısmi tekillikler database.md'de açıklanır.
- `REF` etiketli ilişki başka servis kimliğidir: SQL FK veya veritabanları arası JOIN değildir. İlk genel şemadaki bütün ilişkiler kavramsaldır.
- Okunabilirlik için created_at/updated_at/version ve bazı snapshot alanları her kutuda gösterilmemiştir. Bu dosya tüm DDL'nin yerine geçmez.
- İş tablolarındaki restaurant_id, sahip Admin dışındaki DB'lerde mantıksal tenant referansıdır. Yerel parent/child bağlantıları ayrıca tenant bileşik FK ile korunmalıdır.

## 2. Servisler arası kavramsal model

**Bu bölümdeki çizgilerin hiçbiri servisler arası fiziksel FK değildir.** Kutulardaki önek servis sahibini gösterir; aşağıdaki yerel şemalarda gerçek önerilen tablo adları kullanılır.

```mermaid
erDiagram
    ADMIN_RESTAURANTS ||..o{ AUTH_STAFF_MEMBERSHIPS : "REF restoran"
    ADMIN_RESTAURANTS ||..o{ QR_DINING_TABLES : "REF restoran"
    ADMIN_RESTAURANTS ||..o{ MENU_PRODUCTS : "REF restoran"
    QR_DINING_TABLES ||..o{ QR_TABLE_SESSIONS : "yerel"
    QR_TABLE_SESSIONS ||..o{ QR_GUEST_SESSIONS : "yerel"
    QR_GUEST_SESSIONS ||..o{ ORDER_ORDERS : "REF guest"
    QR_TABLE_SESSIONS ||..o{ ORDER_ORDERS : "REF masa oturumu"
    ORDER_ORDERS ||..|{ ORDER_ORDER_ITEMS : "yerel"
    MENU_PRODUCTS ||..o{ ORDER_ORDER_ITEMS : "REF urun ve snapshot"
    ORDER_ORDERS ||..o| KITCHEN_KITCHEN_ORDERS : "REF eventual projection"
    ORDER_ORDERS ||..o{ PAYMENT_PAYMENT_ATTEMPTS : "REF odeme denemeleri"
    ORDER_ORDERS ||..o| PAYMENT_PAYMENT_SETTLEMENTS : "REF V1 tam tahsilat"
    QR_GUEST_SESSIONS ||..o{ WAITER_SERVICE_REQUESTS : "REF cagri"
    ORDER_ORDERS ||..o{ RATING_RATING_ELIGIBILITIES : "REF uygunluk"
    PAYMENT_PAYMENT_SETTLEMENTS o|..o{ RATING_RATING_ELIGIBILITIES : "REF politikaya bagli"
    ORDER_ORDERS ||..o{ TIMER_WIFI_GRANTS : "REF planlanan"
    MENU_PRODUCTS ||..o{ KITCHEN_WASTE_RECORDS : "REF manuel fire"
    WAITER_SERVICE_REQUESTS o|..o{ NOTIFICATION_NOTIFICATIONS : "REF bildirim"
    ORDER_ORDERS o|..o{ NOTIFICATION_NOTIFICATIONS : "REF bildirim"
```

Order → Kitchen sıfır/bir gösterilir: olay henüz ulaşmamış olabilir; aynı orderId için ikinci mutfak kaydı oluşamaz. Ödeme denemesi çok, V1 başarılı gerçek settlement en çok birdir. Bölüşme/iade dahil değildir. Wi-Fi için çokluk ürün politikası belirlenmeden tek cihaza sınır koymamak amacıyla kavramsaldır.

## 3. Admin DB

```mermaid
erDiagram
    restaurants ||--o| restaurant_settings : "ayar"
    restaurants ||--o{ daily_metrics : "turetilmis rapor"

    restaurants {
        uuid id PK
        varchar name
        varchar slug UK
        varchar timezone
        char currency
        varchar status
    }
    restaurant_settings {
        uuid id PK
        uuid restaurant_id FK,UK
        boolean system_enabled
        boolean ordering_enabled
        boolean payment_enabled
        boolean allow_on_site
        boolean allow_online
        boolean allow_meal_card
        text customer_message
    }
    daily_metrics {
        uuid id PK
        uuid restaurant_id FK
        date business_date
        varchar metric_key
        varchar dimension_key
        char currency
        bigint count_value
        numeric amount_value
        timestamptz as_of
    }
```

daily_metrics yeniden üretilebilir rapor projeksiyonudur. Boyut ve para birimi tekilliği database.md'de tanımlıdır; operasyonel iş gerçeği değildir.

## 4. Auth DB

```mermaid
erDiagram
    staff_users ||--o{ staff_memberships : "restoran ve rol"
    staff_users ||--o{ auth_sessions : "oturum"
    staff_users ||--o| staff_mfa : "TOTP"
    staff_users ||--o{ mfa_recovery_codes : "tek kullanim"
    auth_sessions o|--o{ auth_sessions : "rotasyon gecmisi"

    staff_users {
        uuid id PK
        varchar email_normalized UK
        text password_hash
        varchar status
    }
    staff_memberships {
        uuid id PK
        uuid restaurant_id "REF Admin"
        uuid staff_user_id FK
        varchar role
        boolean is_active
    }
    auth_sessions {
        uuid id PK
        uuid staff_user_id FK
        uuid restaurant_id "REF Admin"
        text token_digest UK
        uuid rotated_from_id FK
        timestamptz expires_at
        timestamptz revoked_at
    }
    staff_mfa {
        uuid id PK
        uuid staff_user_id FK,UK
        text encrypted_totp_secret
        timestamptz enabled_at
        bigint last_accepted_counter
    }
    mfa_recovery_codes {
        uuid id PK
        uuid staff_user_id FK
        text code_digest UK
        timestamptz used_at
    }
```

Personel global kimlik, üyelik restoran bazlıdır. staff_users üzerinde restaurant_id olmaması restoran sınırlarının kaldırıldığı anlamına gelmez. Auth yöntemi ve TOTP sözleşmesi henüz uygulanmamıştır.

## 5. QR DB

```mermaid
erDiagram
    dining_tables ||--o{ qr_codes : "QR gecmisi"
    dining_tables ||--o{ table_sessions : "oturum gecmisi"
    table_sessions ||--o{ guest_sessions : "ilk tarama dahil"
    qr_codes ||--o{ qr_scan_events : "tarama"
    table_sessions ||--o{ qr_scan_events : "basarili esleme"
    guest_sessions ||--o| qr_scan_events : "guest acilisi"
    table_sessions ||--o{ session_transition_operations : "yerel lifecycle islemi"
    session_transition_operations ||--o| session_source_confirmations : "degismez kaynak teyidi"

    dining_tables {
        uuid id PK
        uuid restaurant_id "REF Admin"
        varchar table_number
        integer capacity
        varchar operational_status
        boolean is_active
    }
    qr_codes {
        uuid id PK
        uuid restaurant_id
        uuid table_id FK
        text token_digest UK
        text encrypted_payload
        varchar status
        timestamptz issued_at
        timestamptz expires_at
        timestamptz revoked_at
    }
    table_sessions {
        uuid id PK
        uuid restaurant_id
        uuid table_id FK
        varchar status
        timestamptz opened_at
        timestamptz last_activity_at
        timestamptz idle_expires_at
        timestamptz closing_at
        timestamptz closed_at
    }
    guest_sessions {
        uuid id PK
        uuid restaurant_id
        uuid table_session_id FK
        text token_digest UK
        varchar status
        timestamptz joined_at
        timestamptz expires_at
        timestamptz ended_at
    }
    qr_scan_events {
        uuid id PK
        uuid restaurant_id
        uuid qr_code_id FK
        uuid table_session_id FK
        uuid guest_session_id FK
        timestamptz scanned_at
        varchar outcome
    }
    session_transition_operations {
        uuid id PK
        uuid restaurant_id
        uuid table_session_id FK
        text request_json
        varchar request_digest
        varchar stage
        text result_json
        timestamptz requested_at
        timestamptz last_attempt_at
        integer attempts
        bigint version
    }
    session_source_confirmations {
        uuid operation_id PK,FK
        uuid restaurant_id
        text confirmation_json
        timestamptz received_at
    }
```

Tarihsel olarak bir masanın çok oturumu olabilir; aynı anda OPEN/CLOSING en fazla birdir ve kısmi unique indeksle korunur. ADR-QR-001/A/K13 uyarınca QR sipariş/finans projeksiyonu tutmaz. V29/V30 yerel lifecycle komut/sonucunu, V121 kaynak ACK/sapma makbuzunu tutar; bir işlemde en fazla bir kaynak teyidi vardır. Geçmiş ve teyitler değişmezdir. Gerçek Order/Payment ağ adaptörleri henüz uygulanmadı; diyagram bunları tamamlanmış saymaz.

## 6. Menu DB

```mermaid
erDiagram
    categories o|--o{ categories : "ust ve alt"
    categories ||--o{ products : "urun"
    products ||--o{ product_options : "icerik ve ek secenek"
    products ||--o| product_availability : "guncel satis durumu"
    products ||--o{ product_availability_history : "erisim gecmisi"

    categories {
        uuid id PK
        uuid restaurant_id
        uuid parent_id FK
        varchar name
        integer sort_order
        boolean is_active
    }
    products {
        uuid id PK
        uuid restaurant_id
        uuid category_id FK
        varchar name
        text image_url
        text description
        numeric base_price
        char currency
        numeric vat_rate
        boolean price_includes_vat
        numeric weight_grams
        integer calories
        integer estimated_prep_minutes
        boolean is_published
    }
    product_options {
        uuid id PK
        uuid restaurant_id
        uuid product_id FK
        varchar name
        varchar option_type
        numeric price_delta
        integer min_quantity
        integer max_quantity
        boolean is_active
    }
    product_availability {
        uuid id PK
        uuid restaurant_id
        uuid product_id FK,UK
        varchar status
        varchar reason_code
        text reason_text
        timestamptz changed_at
        uuid changed_by_user_id "REF Auth"
    }
    product_availability_history {
        uuid id PK
        uuid restaurant_id
        uuid product_id FK
        varchar from_status
        varchar to_status
        varchar reason_code
        text reason_text
        uuid changed_by_user_id "REF Auth"
        timestamptz changed_at
        uuid command_id
    }
```

Satışa sunulan ürün için availability kaydı oluşturulması application transaction'ında zorunlu önerilir. Modelde 0/1, henüz satışa açılmamış ürün taslağına izin verir; availability bulunmaması satış izni sayılmaz. Kitchen kapatma komutunun yazarı Menu'dür. Otomatik restart açılışı yoktur. product_options hammadde stok tablosu değildir.

## 7. Order DB

```mermaid
erDiagram
    carts ||--o{ cart_items : "sepet satiri"
    orders ||--|{ order_items : "kesinlesmis satir"
    order_items ||--o{ order_item_options : "secenek snapshot"
    orders ||--o{ cancellation_requests : "iptal talebi"
    cancellation_requests ||--o{ cancellation_items : "iptal kapsami"
    order_items ||--o{ cancellation_items : "iptal miktari"
    orders ||--o{ order_status_history : "gecis gecmisi"

    carts {
        uuid id PK
        uuid restaurant_id
        uuid table_session_id "REF QR"
        uuid guest_session_id "REF QR"
        varchar status
    }
    cart_items {
        uuid id PK
        uuid restaurant_id
        uuid cart_id FK
        uuid product_id "REF Menu"
        integer quantity
        jsonb selections
        text note
    }
    orders {
        uuid id PK
        uuid restaurant_id
        varchar order_number
        uuid table_session_id "REF QR"
        uuid guest_session_id "REF QR"
        uuid table_id "REF QR"
        uuid admission_id "REF QR"
        varchar status
        varchar service_status
        char currency
        numeric original_gross_total
        numeric cancelled_gross_total
        numeric payable_total
        timestamptz accepted_at
        timestamptz served_at
    }
    order_items {
        uuid id PK
        uuid restaurant_id
        uuid order_id FK
        uuid product_id "REF Menu"
        varchar product_name_snapshot
        integer quantity
        integer cancelled_quantity
        numeric unit_gross_price
        numeric unit_net_price
        numeric vat_rate_snapshot
        numeric vat_amount
        numeric line_gross_total
    }
    order_item_options {
        uuid id PK
        uuid restaurant_id
        uuid order_item_id FK
        uuid source_option_id "REF Menu"
        varchar name_snapshot
        varchar action
        integer quantity
        numeric unit_price_delta_snapshot
    }
    cancellation_requests {
        uuid id PK
        uuid restaurant_id
        uuid order_id FK
        varchar source
        uuid requested_by_user_id "REF Auth"
        uuid guest_session_id "REF QR"
        varchar reason_code
        text reason_text
        varchar status
        timestamptz requested_at
        timestamptz decided_at
    }
    cancellation_items {
        uuid id PK
        uuid restaurant_id
        uuid cancellation_request_id FK
        uuid order_item_id FK
        integer quantity
        numeric gross_amount_snapshot
        uuid related_product_id "REF Menu"
    }
    order_status_history {
        uuid id PK
        uuid restaurant_id
        uuid order_id FK
        varchar state_axis
        varchar from_status
        varchar to_status
        timestamptz occurred_at
        uuid event_id UK
    }
```

İptal satırı talebin siparişiyle aynı order'a ait olmalıdır; yalnız iki bağımsız FK bunu garanti etmez. Ek bileşik anahtar/transaction validation gerekir. Tam iptal tüm kalan miktarları kapsar. Kısmi iptalin Kitchen V1'e yansıması açık karardır. Sepet kesinleşmiş siparişin yerine geçmez; menü fiyat güncellemesi snapshot'ı değiştirmez.

## 8. Kitchen DB

```mermaid
erDiagram
    kitchen_orders ||--|{ kitchen_order_items : "salt okunur urun snapshot"
    kitchen_orders ||--o{ kitchen_status_history : "siparis bazli gecis"

    kitchen_orders {
        uuid id PK
        uuid restaurant_id
        uuid order_id "REF Order"
        uuid table_session_id "REF QR"
        varchar table_number_snapshot
        varchar order_number_snapshot
        varchar status
        timestamptz received_at
        timestamptz started_at
        timestamptz ready_at
        timestamptz cancelled_at
        text cancellation_reason
        bigint source_order_version
    }
    kitchen_order_items {
        uuid id PK
        uuid restaurant_id
        uuid kitchen_order_id FK
        uuid order_item_id "REF Order"
        uuid product_id "REF Menu"
        varchar product_name_snapshot
        integer quantity
        integer cancelled_quantity
        jsonb options_snapshot
        text note_snapshot
    }
    kitchen_status_history {
        uuid id PK
        uuid restaurant_id
        uuid kitchen_order_id FK
        varchar from_status
        varchar to_status
        uuid actor_user_id "REF Auth"
        uuid source_event_id
        timestamptz occurred_at
        text reason
    }
    waste_records {
        uuid id PK
        uuid restaurant_id
        uuid product_id "REF Menu"
        varchar product_name_snapshot
        integer quantity
        varchar reason_code
        text description
        timestamptz recorded_at
        uuid recorded_by_user_id "REF Auth"
        uuid order_id "REF Order opsiyonel"
    }
```

Fire her zaman siparişe bağlı değildir; waste_records bu nedenle yerel kitchen_orders FK'si taşımaz. kitchen_order_items üzerinde hazırlık durumu yoktur. V1 WAITING → PREPARING → READY; iptal Order onayıyla. Ürün kapatma gerçeği Menu DB'sindedir, ikinci availability tablosu Kitchen'da oluşturulmaz.

## 9. Waiter DB

```mermaid
erDiagram
    service_requests {
        uuid id PK
        uuid restaurant_id
        uuid table_session_id "REF QR"
        uuid guest_session_id "REF QR"
        varchar request_type
        text message
        varchar status
        uuid claimed_by_user_id "REF Auth"
        timestamptz claimed_at
        uuid resolved_by_user_id "REF Auth"
        timestamptz resolved_at
        bigint version
    }
```

Tek yerel iş tablosu başlangıç için yeterlidir; masa ve sipariş burada kopya otorite olarak tutulmaz. Teslim Order, ana oturum kapanışı QR işlemidir. Garson ekranı gerekli projeksiyonları API/olaydan alır.

## 10. Payment DB

```mermaid
erDiagram
    payment_attempts ||--o| payment_settlements : "dogrulanmis REAL tahsilat"
    payment_attempts o|--o{ payment_webhook_receipts : "callback gecmisi"

    payment_attempts {
        uuid id PK
        uuid restaurant_id
        uuid order_id "REF Order"
        uuid guest_session_id "REF QR"
        varchar method
        varchar mode
        varchar provider
        varchar provider_payment_id
        varchar provider_idempotency_key
        numeric amount
        char currency
        varchar status
        uuid confirmed_by_user_id "REF Auth"
        timestamptz started_at
        timestamptz succeeded_at
    }
    payment_settlements {
        uuid id PK
        uuid restaurant_id
        uuid payment_attempt_id FK,UK
        uuid order_id "REF Order"
        numeric amount
        char currency
        timestamptz confirmed_at
        varchar evidence_reference
    }
    payment_webhook_receipts {
        uuid id PK
        uuid restaurant_id
        uuid payment_attempt_id FK
        varchar provider
        varchar provider_event_id
        text payload_digest
        timestamptz received_at
        varchar verification_status
        varchar processing_status
        timestamptz processed_at
    }
```

`UNIQUE(restaurant_id,order_id)` settlement için V1 tam ödeme kısıtıdır. Bölüşme/iade modeli değildir. Callback imza/merchant eşlemesiyle tenant belirlenir; belirsiz/gecikmiş sonuç kaybolmaz. DEMO başarı gerçek settlement veya ciro oluşturmaz.

## 11. Rating DB

```mermaid
erDiagram
    rating_eligibilities ||--o| restaurant_reviews : "restoran oyu"
    rating_eligibilities ||--o{ product_reviews : "siparis urun oyu"

    rating_eligibilities {
        uuid id PK
        uuid restaurant_id
        uuid order_id "REF Order"
        uuid guest_session_id "REF QR"
        uuid payment_settlement_id "REF Payment"
        varchar payment_method
        varchar policy_version
        varchar status
        timestamptz eligible_at
        timestamptz expires_at
    }
    restaurant_reviews {
        uuid id PK
        uuid restaurant_id
        uuid eligibility_id FK,UK
        smallint stars
        text comment
        timestamptz submitted_at
    }
    product_reviews {
        uuid id PK
        uuid restaurant_id
        uuid eligibility_id FK
        uuid order_item_id "REF Order"
        uuid product_id "REF Menu"
        smallint stars
        text comment
        timestamptz submitted_at
    }
    product_rating_summaries {
        uuid id PK
        uuid restaurant_id
        uuid product_id "REF Menu"
        bigint rating_count
        bigint rating_sum
        timestamptz updated_at
    }
```

Rating summary product_id üzerinden mantıksal olarak gruplanır; product_reviews tablosuna fiziksel FK değildir. Yıldız 1..5; guest/sipariş/ürün sahipliği ayrıca doğrulanır. Nakit değerlendirme ve kapanmış guest sonrası sınırlı erişim politikaları açıktır.

## 12. Notification DB

```mermaid
erDiagram
    notifications ||--o{ notification_recipients : "alici bazli okundu"

    notifications {
        uuid id PK
        uuid restaurant_id
        uuid source_event_id
        varchar type
        uuid table_session_id "REF QR"
        uuid order_id "REF Order"
        uuid service_request_id "REF Waiter"
        varchar target_role
        jsonb payload
        varchar status
        timestamptz resolved_at
        timestamptz archived_at
    }
    notification_recipients {
        uuid id PK
        uuid restaurant_id
        uuid notification_id FK
        uuid staff_user_id "REF Auth"
        varchar delivery_status
        timestamptz delivered_at
        timestamptz read_at
    }
```

Bildirim okuma alıcı bazlıdır; iş çağrısının çözümü Waiter olayıyla gelir. Masa kapanışında arşivleme geçmişi silmez.

## 13. Mail DB ve planlanan Timer DB

Bu iki kutu grubu ayrı veritabanlarıdır; aralarında fiziksel ilişki yoktur.

```mermaid
erDiagram
    mail_jobs ||--o{ mail_delivery_attempts : "gonderim denemesi"

    mail_jobs {
        uuid id PK
        uuid restaurant_id "REF Admin opsiyonel"
        uuid source_event_id UK
        text recipient_reference
        varchar template_key
        jsonb template_data
        varchar status
        timestamptz next_attempt_at
        timestamptz expires_at
    }
    mail_delivery_attempts {
        uuid id PK
        uuid mail_job_id FK
        integer attempt_number
        timestamptz attempted_at
        varchar provider_message_id
        varchar status
        varchar error_code
    }
    wifi_grants {
        uuid id PK
        uuid restaurant_id
        uuid order_id "REF Order"
        uuid table_session_id "REF QR"
        text device_reference
        varchar controller_reference
        varchar status
        timestamptz started_at
        timestamptz expires_at
        timestamptz revoked_at
        varchar last_error_code
    }
```

Wi-Fi timer planlanmıştır; controller entegrasyonu tanımsızdır. Üç saat sonunda expires_at geçmesi erişimin gerçekten kesildiğini kanıtlamaz. Mail template verisi sır içeriyorsa kısa süreli/şifreli tutulmalı ve loglanmamalıdır.

## 14. Teknik tablolar — ihtiyaç duyan her servis DB'sinde

```mermaid
erDiagram
    outbox_events ||--o{ outbox_deliveries : "hedef bazli teslim"

    idempotency_records {
        uuid id PK
        uuid restaurant_id
        varchar actor_scope
        varchar operation
        varchar idempotency_key
        text request_digest
        varchar status
        uuid resource_id
        jsonb safe_response
        timestamptz expires_at
    }
    outbox_events {
        uuid id PK
        uuid restaurant_id
        varchar aggregate_type
        uuid aggregate_id
        bigint aggregate_version
        varchar event_type
        integer schema_version
        jsonb payload
        timestamptz occurred_at
    }
    outbox_deliveries {
        uuid id PK
        uuid event_id FK
        varchar destination
        varchar status
        integer attempt_count
        timestamptz next_attempt_at
        timestamptz delivered_at
    }
    inbox_events {
        uuid id PK
        varchar consumer_name
        uuid event_id
        uuid aggregate_id
        bigint aggregate_version
        timestamptz processed_at
    }
    audit_logs {
        uuid id PK
        uuid restaurant_id
        varchar actor_type
        uuid actor_id
        varchar action
        varchar entity_type
        uuid entity_id
        timestamptz occurred_at
        varchar correlation_id
        jsonb safe_change
    }
```

Inbox.event_id üreticinin başka DB'deki outbox'una fiziksel FK değildir. Tekillik `(consumer_name,event_id)` ile uygulanır. Outbox teslimi `(event_id,destination)` bazındadır. Idempotency anahtarı tenant + actor + operation scope'uyla tekildir. Flyway history bu teknik modelin dışında Flyway tarafından yönetilir.

## 15. Modelin garanti etmesi gereken kurallar

1. Bir masada aynı anda tek OPEN/CLOSING ana oturum; ilk dahil tüm taramalar guest'e bağlıdır.
2. Guest, oturum ve yerel çocuk satırları aynı tenant/ebeveyn zincirine aittir.
3. Aynı orderId tek Kitchen kaydı; item başına hazırlık durumu yoktur.
4. Kitchen iptal talebi Order kesin kararıyla ayrıdır; READY teslim sonrası değişmez.
5. Availability tek otoritede kalıcıdır; restart otomatik açmaz.
6. Sipariş ürün/fiyat/vergi/seçenek snapshot'ı geçmişi korur; iptal orijinal miktarı geçmez.
7. V1 tek gerçek settlement/order; tekrar callback veya belirsiz timeout çift tahsilat üretmez.
8. Çağrı okundu/çözüldü farklıdır; bildirim alıcı bazlı okunur.
9. 15 dakika idle, ödeme/masa kapanışı ve üç saat Wi-Fi ayrı yaşam döngüleridir.
10. Çizgiler tek başına transaction, authorization, yarış kontrolü veya olay teslim garantisi sağlamaz. Kısıtlar ve servis protokolü birlikte uygulanmalıdır.

Bu diyagramlar Markdown kaynak düzeyinde hazırlanmıştır; Mermaid görsel render doğrulaması bu incelemede yapılmamıştır.
