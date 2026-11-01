# ADR-WAITER-001 — Waiter servisinde kalıcılık: JPA ve JdbcTemplate ayrımı

**Durum:** Önerildi (ekip incelemesi bekleniyor)
**Kapsam:** `backend/services/waiter-service`

## Bağlam

Waiter servisinde iki kalıcılık biçimi birlikte kullanılıyor:

- Spring Data JPA: `ServiceRequestRepository`, `OrderServeCommandRepository`, `TableCommandRepository`.
- `JdbcTemplate`: `CallLedger` ve `NotificationDeliveries`.

Auth servisi (PR #2) incelemesinde "bazı repository'ler JPA, bazıları JdbcTemplate" bulgusu verildi ve tek bir
standart önerildi. Bu karar, Waiter için ayrımın nedenini ve kuralını yazılı hale getirir.

## Karar

**Durumu olan ve iyimser kilitleme isteyen kayıtlar JPA ile** yönetilir (`@Version`, `saveAndFlush`,
`ObjectOptimisticLockingFailureException` ile yeniden deneme): çağrı, servis komutu, masa komutu.

**Eklemeli (append-only) kayıtlar ve kuyruk işlemleri JDBC ile** yazılır, çünkü SQL'in kendisi sözleşmenin
parçasıdır:

- `service_call_events`, `idempotency_records`, `notification_deliveries` satırları çağrının aynı
  transaction'ında doğrudan eklenir; entity yaşam döngüsü gerekmez.
- Bildirim kuyruğu `FOR UPDATE OF d SKIP LOCKED` ile kiralanır; birden fazla örnek aynı satırı iki kez almaz.
- `DISTINCT ON` ve `::jsonb` gibi PostgreSQL özellikleri JPQL ile ifade edilemez.

JDBC kullanılan yerlerde kurallar:

1. SQL yalnızca `infrastructure/persistence/repository` altındaki sınıflarda bulunur.
2. Durum değerleri SQL içinde string literal olarak yazılmaz; enum adı parametre olarak bağlanır
   (`NotificationDeliveryStatus.PENDING.name()`).
3. Her metot çağıranın transaction'ında çalışır; HTTP çağrısı bir veritabanı transaction'ı açıkken yapılmaz.

## Sonuçlar

- Yeni bir durum alanı eklemek enum + Flyway `CHECK` kısıtı + (varsa) ilgili sorgu parametreleri demektir;
  hiçbir SQL metninde durum adı yinelenmez.
- Tek strateji (yalnız JPA veya yalnız JDBC) seçilmedi. Yalnız JPA, kuyruk kiralamayı doğal SQL olmadan
  ifade edemez; yalnız JDBC, iyimser kilitlemeyi ve dirty-checking'i elle yeniden yazmayı gerektirir.
- Bu ayrım ileride değişirse (ör. kuyruk harici bir aracıya taşınırsa) yalnız `NotificationDeliveries` etkilenir.
