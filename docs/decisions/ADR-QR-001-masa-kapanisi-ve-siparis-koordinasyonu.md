# ADR-QR-001 — Masa kapanışı, sipariş kabulü ve finansal güvenlik sınırı

- **Durum:** A modeli korunur. K10–K12'ye K13 kapsam sapması kararı eklendi; QR'ın kaynak teyidi, izin yenileme ve abort işlemesi buna uyarlandı. Gerçek HTTP/kimlik/Payment bağlantısı ve ortak E2E tamamlanmadı. K12 yönetici kurtarması gelecek geliştirmedir.
- **Oluşturulma:** 29 Ekim 2026. **İlk karar:** 30 Ekim 2026. **Son güncelleme:** 31 Ekim 2026.
- **Sahipler ve teyit:** QR/Payment — Alper; Order — Hamza (`HmzT270`). Hamza A/K1–K9 ortak kararını ve Order çalışmalarını [kendi PR yorumunda](https://github.com/software-lecture-labs/Restofy/pull/13#issuecomment-6535867981) teyit etti. Waiter — Umut: kod sözleşmesi incelendi; yeni K11/K12 için Umut'un ve etkilenen Order tarafının ayrıca yazılı teyidi henüz alınmadı.
- **Kaynak:** [İlk QR review](https://github.com/software-lecture-labs/Restofy/pull/11#pullrequestreview-5695894632), [31 Ekim QR review — Order/Waiter/yönetici yolu](https://github.com/software-lecture-labs/Restofy/pull/11#pullrequestreview-5721966492), [31 Ekim Order review — CLOSED teyidi](https://github.com/software-lecture-labs/Restofy/pull/13#pullrequestreview-5722290865).
- **İncelenen kod:** Order PR #13, [fd4e7a9](https://github.com/software-lecture-labs/Restofy/commit/fd4e7a95b523f14b1854da60def2d1893d4b4700); Waiter PR #14, [5dcb64b](https://github.com/software-lecture-labs/Restofy/commit/5dcb64b8209d8266bf68f5e6565131ba0af16a31). Salt okunur incelendi; dallar çekilmedi ve bu servislerin kodu değiştirilmedi. K13, Hamza'nın kullanıcı tarafından paylaşılan 31 Ekim görev belgesi ve Alper'in QR uyarlamasını yapma açık talebiyle kaydedildi; PR #11'de kişisel teyit/yorum ayrı iştir.

Bu belge kararları ve bunları uygulayacak işleri kaydeder. Kararın kabulü kodun değiştiği, migration'ın uygulandığı, yeni API açıldığı veya testlerin geçtiği anlamına gelmez. Mevcut kod ile seçilen hedef aşağıda ayrı gösterilir. Önceki `docs/ADR/` konumundan repository'nin `docs/decisions/` düzenine taşınmıştır.

## 1. Bağlam ve mevcut uygulama

[Mimari](../architecture.md) uyarınca sipariş kabulü ve iş durumunun sahibi Order, mali doğruluğun sahibi Payment, masa/guest oturumları ve masa kapanışının sahibi QR'dır. Servisler kendi veritabanlarına sahiptir; servisler arasında DB erişimi, fiziksel FK veya ortak JPA entity paylaşımı yapılmaz.

Karar sonrası güncel QR kodu ile uzak Order incelemesinin durumu:

- **QR:** Eski admission ve ayrıntılı iş/finans projeksiyonu kaldırıldı. [V29](../../backend/services/qr-service/src/main/resources/db/migration/V29__create_session_transition_operations.sql) yalnız QR'ın kalıcı lifecycle komut/sonucunu, [V30](../../backend/services/qr-service/src/main/resources/db/migration/V30__guard_session_transition_history.sql) değişmez geçmiş ve stage geçişlerini kurar. QR sipariş/Payment defteri tutmaz.
- **QR kapanışı:** [Otomatik koordinatör](../../backend/services/qr-service/src/main/java/com/restofy/qr/application/service/TableSessionClosureService.java) korunur. [StaffClosureService](../../backend/services/qr-service/src/main/java/com/restofy/qr/application/service/StaffClosureService.java) ile doğrulanmış WAITER/ADMIN'in aynı güvenli kapanışı istemesi ve sonucunu sorgulaması geri eklendi. Gerçek aktör/gerekçe değişmez komutta tutulur; main/guest/masa yerelde atomiktir. Staff veya Waiter isteği Order/Payment doğrulamasını atlayamaz. HTTP sınırları kapalıdır.
- **QR idle/guest:** [OrderSessionAccess](../../backend/services/qr-service/src/main/java/com/restofy/qr/application/port/OrderSessionAccess.java) varsayılan UNKNOWN/unavailable döndürür. Geçici Order okuması koruma/iptal zamanı içindir; fiziksel expiry kalıcı gate izni gerektirir. Bağımsız guest süreleri ve tokenla gerçek bağlam çözümü uygulanmıştır; gerçek ağ/aktivite aktarımı yoktur.
- **Order checkout:** İncelenen yeni PR #13'te checkout ve kapanış hazırlığı restoran/main kapsamında PostgreSQL shared/exclusive advisory lock ile sıralanıyor; V48 PREPARED/COMPLETED kaydı sonraki checkout'ı 409 ile engelliyor. Bu, önceki 7acc43f incelemesinden sonra eklenen gerçek Order kodudur; gerçek QR adaptörü ve çoklu gerçek servis E2E henüz doğrulanmadı.
- **Order kimlik ve ödeme:** Token çözümü için QrSessionPort; prepare/status/confirmation ve order-state internal API'leri var. Guest/main koruması ve güvenli iptal zamanı kaynağı eklendi. Payment'ın olaylarından Order'da tutulan durum kullanılıyor; gerçek Order → Payment sorgusu ve gerçek QR/servis kimliği adaptörleri uygulanmamış.
- **Order durum/teslimat:** COMPLETED ödeme durumundan bağımsız. V48 kalıcı işlem, izin süresi/yenileme, SETTLED/IDLE ve V49 değişmez kapsam sapması kaydı mevcut. CLOSED kapsam sapması sorunu K13 ile çözüldü. CartActivityRecorded outbox yazılıyor fakat dispatcher yok; sapma ekranı/raporu/olayı yok. Order kodu uzaktan incelendi; Order testleri bu QR çalışmasında çalıştırılmadı.
- **Payment:** İncelenen yerel çalışma ağacında başlangıç altyapısı vardır; gerçek sorgu, tahsilat ve uzlaştırma entegrasyonu bu incelemede doğrulanmadı. Bu belge bunları uygulanmış kabul etmez.

## 2. Mimari seçim ve alternatifler

**A seçildi:** QR masa, ana/guest oturumları ve aktivite sürelerine odaklanır. Order, masa oturumunun bütün siparişlerini ve Payment'tan doğruladığı mali durumu değerlendirerek QR'a güvenilir kapanış uygunluğu sağlar. QR'daki ayrıntılı sipariş/finans kopyası sadeleştirilir.

| Seçenek | Sonuç | Gerekçe / maliyet |
| --- | --- | --- |
| A — Order'dan yetkili uygunluk ve kapanış koordinasyonu | Seçildi | QR'daki veri tekrarı ve sipariş/finans sorumluluğu azalır. Kalıcı koordinasyon ve servis kesintisinde bekleme gerekir. |
| B — QR'da olaylarla güncellenen küçük engelleyici iş projeksiyonu | Ana model olarak seçilmedi | Sürekli projeksiyon, eksik/sırasız olay ve yeniden eşitleme yükü getirir. |
| C — QR rezervasyonu ve ayrıntılı sipariş/finans projeksiyonu | Seçilmedi | Mevcut QR kodundan yararlanır; ancak Order'ı QR kabul protokolüne bağlar ve QR'da ayrıntılı kopya gerektirir. |

A, tek bir GET veya tekrar kullanılabilen `canClose=true` cevabına indirgenmez. Checkout ile kapanışın sıralanması, tam kaynak kapsamı ve kalıcı sonucu öğrenme K8/K9'un zorunlu parçalarıdır. QR'da minimal oturum/işlem kimliği ve sürüm tutmak ikinci bir sipariş/finans otoritesi kurmak anlamına gelmez.

## 3. Kararlar — K1–K13

### K1 — Otomatik kapanış ve yetkili personel isteği birlikte desteklenecek

Masa oturumunun tamamlanma ve mali koşulları sağlandığında main ve bağlı aktif guest'ler otomatik kapanabilir. 31 Ekim ek kararıyla aynı restoranın doğrulanmış WAITER/ADMIN kullanıcısı da normal kapanış isteyebilir (K11). Otomatik kapanış için ayrıca personel onayı şart değildir; personel isteği de mali/iş koşullarını atlamaz. Kalıcı QR kimliğini değiştirmek bu normal kapanış işleminin parçası değildir.

### K2 — Siparişlerin tamamlanması ve ödeme birlikte aranacak

Ödeme tamam olsa bile hazırlığı/teslimi devam eden sipariş varsa masa açık kalır. Sipariş tamam ama ödeme eksikse yine kapanmaz. Fulfillment'ın sahibi Order, mali doğruluğun sahibi Payment olarak kalır; QR birleşik uygunluğu Order'dan alır. Bu karar, ödeme başlatma API'sine ayrıca `COMPLETED` ön koşulu ekleyen bir kural değildir; bu çalışmada ödeme zamanına yeni bir yasak uygulanmaz.

### K3 — Siparişsiz guest'ten ödeme beklenmeyecek

QR okutmak borç doğurmaz. Örneğin üç guest'ten ikisi sipariş verdiyse kapanış bu iki kişinin gerçek sipariş/ödeme yükümlülüklerine bakar. Koşullar tamamlanınca siparişsiz üçüncü guest de main ve diğer aktif guest'lerle birlikte kapanır. Guest sayısı kadar ödeme kaydı aranmaz. Hiç sipariş verilmemiş masada normal 15 dakika idle kuralı sürer; boş kapsam anında "ödendi" sayılıp kapanmaz.

### K4 — MVP'de müşteri yalnız kendi siparişlerini ödeyecek

Müşterinin ödeme yetkisi kendi doğrulanmış guest oturumuna bağlı siparişleriyle sınırlıdır. Ortak masa hesabı ve başkası adına ödeme MVP kapsamı dışındadır. Aynı masada olmak diğer guest'in siparişine yetki vermez. Kişinin birden fazla kendi siparişi bulunabilir; bu karar ödeme başına sipariş sayısını belirlemez. Masa çapındaki servis uygunluk kontrolü müşteri erişimini genişletmez.

### K5 — Order tokenı QR'a doğrulatıp bağlamı alacak

Kimliği doğrulanmış Order servisi guest tokenıyla QR'a başvurur. QR tokenın gerçek kaydından `restaurantId`, `tableId`, `tableSessionId`, `guestSessionId` bilgilerini çözüp güncel kullanılabilirliği doğrular. Masa aktifliği, main/guest durumu, süreler ve sipariş koruması birlikte değerlendirilir; ham `ACTIVE` veya istemci kimlik beyanı yeterli değildir.

Doğrulama süreyi yenilemez ve tek başına sipariş kabul izni değildir. Geçersiz token ile QR erişilemiyor durumu ayrılır; belirsizlikte erişim açılmaz. Ham token loga/olaya yazılmaz. QR'ın token-only ve token + beklenen kimlik Java guard'ları vardır; gerçek iç HTTP doğrulama ve taşıma adaptörü henüz yoktur.

### K6 — Gerçek menü/sepet etkileşimi 15 dakikayı yenileyecek

Siparişsiz oturumda gerçek menüye giriş, kategori/ürün gezinmesi ve anlamlı sepet işlemleri son aktiviteyi yeniler. Başarılı sepet mutasyonlarını Order, gerçek menü etkileşimlerini frontend QR'a bildirir. Aktivite ilgili guest ve main'i yeniler; diğer guest'lerin bireysel süresini uzatmaz.

İşlem yapılmazsa son aktiviteden itibaren 15 dakika sonunda normal idle kuralları uygulanır. Sayfadan çıkışta anında kapanma veya tarayıcının çıkış bildirimi zorunluluğu yoktur. Açık ekran, arka plan sekmesi ve otomatik polling/heartbeat aktivite değildir. Gecikmiş bildirim kapanmış/süresi dolmuş oturumu yeniden açmaz.

Kabul edilmiş sipariş main'i ve sipariş sahibini korur. Böyle bir masa salt 15 dakika işlem yok diye kapanmaz. Bu koruma Order'ın geçici, güvenilir guest/main kapsamına bağlandı; gerçek adaptör henüz yoktur ve yerel kayıt yokluğu sipariş yokluğu sayılmayacaktır.

### K7 — Order, Payment bilgisini doğrulayıp QR'a uygunluk sağlayacak

Sipariş, guest ve masa oturumunu bilen Order, Payment'tan yetkili mali durumu sorgular; bütün masa kapsamıyla birleştirip QR'a uygunluk/engel sonucu sağlar. QR normal kapanış için Payment'ı ayrıca sorgulamaz ve sipariş/finans kararının ikinci sahibi olmaz. V29/V30 ve bağlı QR kodu bu hedefe uyarlandı: yalnız QR lifecycle komutu ve yerel sonuç saklanır; Order/Payment transport'u kapalıdır.

Kaynak kapsamı bütün guest'lerin gerçek siparişlerini, devam eden kabulleri ve mali belirsizlikleri kapsar. Müşteri/personel için varsayılan `active` sipariş listesi bu kapsam değildir; ödenmemiş `COMPLETED` sipariş de kapanışı engeller. Tek başına `PAID`, sıfır bakiye, `CANCELLED` veya boş sayfa bütün koşulların sağlandığını kanıtlamaz.

### K8 — Yeni checkout ve kapanış hazırlığı Order'da kalıcı sıralanacak

Order, restoran + `tableSessionId` kapsamında yeni sipariş kabulünü ve kapanış hazırlığını aynı kalıcı koordinasyonla sıralar:

1. Sipariş önce kalıcı kabul edilmişse kapanış hesabına katılır; koşullar sağlanmıyorsa masa açık kalır.
2. Kapanış hazırlığı önce kesinleşmişse yeni checkout sipariş kaydı üretmeden `409` döner.

Eski QR doğrulama cevabı, ayrı kilitler veya yalnız bir JVM'deki mutex bu engeli aşamaz. Checkout commit'i aynı koordinasyonu denetlemelidir. Ağ yanıtının sırası işlem sırası sayılmaz; hem güvenli kapanış hem sonradan kabul edilen yeni sipariş başarılı görünemez. QR'ın guest açma/doğrulama ve idle yolları da kapanış hazırlığına uygun davranmalıdır. Servisler arası transaction atomikliği iddia edilmez.

### K9 — Kayıp yanıt/kesinti aynı kalıcı işlem kimliğiyle çözülecek

Kapanış kalıcı, tenant ve masa oturumuna bağlı tek bir işlem kimliğiyle izlenir. Aynı işlem ikinci bir kapanış veya ödeme üretmez; farklı içerikle aynı kimliği kullanmak kabul edilmez. Süreç yeniden başlasa da aynı işlemin sonucu sorgulanıp güvenli biçimde devam edilir.

Sonuç bilinmiyorsa oturum kapanmış varsayılmaz. Başlamış kapanış da timeout yüzünden kendiliğinden iptal edilerek yeni siparişe açılmaz. QR/Order kalıcı sonuç ve teyidi uzlaştırır; sadece ağ hatası bir işlemin başarısızlığını kanıtlamaz. Kesin olarak uygun olmayan sonuçla belirsiz sonuç ayrılır; yeniden açma ancak uzlaştırılmış işlem durumuna göre yapılır.

Tekrar mekanizması yalnız kapanış koordinasyonunu tamamlar; müşteriden tekrar ödeme istemez, yeni tahsilat başlatmaz. Yeniden denemeler aralıklı ve kaynak sınırlarıyla yürütülür; süresiz sıkı döngü kurulmaz. Çelişkili kaynak kayıtları sessizce çözülmez, operasyonel uzlaştırma gerektirir. Mevcut genel POST çağrılarına kör otomatik retry eklenmez.


### K10 — Payment nakit ve online yöntemleri destekleyecek

Payment Service geliştirmesinde nakit ve online seçenekleri bulunacak. Nakit için aynı restoranın yetkili personelinin Payment'ta kaydedilmiş teyidi; online için doğrulanmış sağlayıcı/işlem kanıtı esas alınır. QR veya Waiter'ın gönderdiği bir `paid=true`, serbest metin ya da başarı ekranı ödeme kanıtı değildir. Yöntem farkı borcu, guest sahipliğini, tekrar güvenliğini ve Order'ın tam oturum doğrulamasını kaldırmaz.

Bu bir ürün/entegrasyon kararıdır; bu görevde Payment API'si, sağlayıcı veya nakit tahsilat kaydı uygulanmadı. “Nakit aldım” gerekçesiyle QR doğrudan ödeme durumu değiştirmez.

### K11 — Garson/admin normal kapanış komutu geri eklendi

Kapanış özelliği Payment entegrasyonu sonrasına bırakılmak istenmişti; review sonrasında normal personel komutu şimdiden QR'a geri eklendi. Doğrulanmış WAITER/ADMIN kendi restoranında belirli table/main, görülen main version ve kalıcı commandId ile istekte bulunur. İstemci restaurantId veya requestedByUserId beyanı yetki değildir. Waiter yolu ayrıca doğrulanmış servis kimliği ve personel delegasyonu gerektirir.

Staff komutu otomatik akışla aynı Order prepare/status/ACK/abort protokolünü kullanır. Açık/ödenmemiş/belirsiz iş zorla kapatılamaz. Order entegrasyonu yokken olumlu cevap uydurulmaz. Nakit ve online akışın farkı Payment'tadır; QR'ın kapanış koşulu ikisi için de aynıdır.

- Başlangıç isteği `Idempotency-Key == commandId` taşır. Aynı kimlik + farklı aktör, hedef, version, gerekçe veya istek zamanı 409 olur.
- Gerçek aktör, normalize opsiyonel gerekçe, kaynak STAFF/WAITER_SERVICE ve hedef değişmez komut JSON'una bağlanır. Kullanıcı kimliği staff kapanışında mevcut closed_by_user_id alanına da yazılır. Yeni migration veya finans tablosu eklenmedi; eski otomatik komut/digest biçimi okunabilir kalır.
- 200 CLOSED yalnız yerel main ve bütün aktif guest'ler bitmişse döner; 202 CLOSING sonuç henüz kesinleşmemiş demektir. Yerel kapanış tamamlanıp Order ACK'i kaybolduysa sonuç CLOSED, iç uzlaştırma APPLIED kalabilir.
- Sonuç GET'i salt okumadır; komutu yeniden çalıştırmaz veya Order çağırmaz. Aynı POST eski kapanışın makbuzunu döndürür; yeni ziyareti kapatmaz.
- Waiter kendi çağrılarını ancak QR'ın kesin CLOSED sonucundan sonra kapatır. Otomatik kapanışların Waiter'a dayanıklı bildirimle aktarımı ayrı entegrasyon işidir.

**Uygulanan yollar (HTTP hâlâ denyAll):**

| Method | Yol | Yetki sınırı |
| --- | --- | --- |
| POST | `/api/v1/qr/table-sessions/{sessionId}/close` | Doğrulanmış WAITER/ADMIN |
| GET | `/api/v1/qr/table-sessions/{sessionId}/close-commands/{commandId}?tableId=...` | Aynı restoranın doğrulanmış staff'ı |
| POST | `/api/v1/internal/tables/{tableId}/sessions/{sessionId}/close` | Waiter servis kimliği + aktif staff delegasyonu |
| GET | `/api/v1/internal/tables/{tableId}/sessions/{sessionId}/close-commands/{commandId}?restaurantId=...` | Aynı doğrulanmış servis/delegasyon kapsamı |

İncelenen Waiter PR #14 ile komut/sonuç alanları, 200/202 anlamları, tüm guest'lerin sonlanma kanıtı ve commandId eşleşmesi hizalandı. Waiter'ın mevcut `/internal/v1` taslak prefix'i bu projenin `/api/v1` standardına uymuyor; kendi client'ında yukarıdaki canonical yola geçirilmesi ve gerçek servis/personel doğrulamasının bağlanması gerekiyor. Taslak yolu doğrudan public alias olarak açmadık. Waiter tablo/guest okuma ve cleaned/status taslak endpoint'lerinin tamamının bu görevde uygulandığı iddia edilmez.

### K12 — Yönetici uyarıyla, gerekçeyle ve ayrı onayla zorla QR yenileyebilecek (GELECEK GELİŞTİRME)

Kullanıcı 31 Ekim'de şu ürün davranışını açıkça seçti: yönetici QR'ı yenilerken masada açık sipariş olup olmadığı/sonlanıp sonlanmadığı gösterilir; anlık kesinti veya problem varsa yönetici zorla yenileyip hedef oturumu sonlandırabilir. Bu, K11'deki normal garson kapanışından ve mevcut sıradan QR rotate işleminden ayrı bir kurtarma eylemidir.

**Planlanan akış:**

1. Aynı restoranın doğrulanmış ADMIN kullanıcısı gerçek table/main ve görülen sürümleri seçer. QR, Order/Payment kapsamını kontrol etmeye çalışır; açık sipariş veya doğrulanamayan durum açık uyarı olarak gösterilir. Kesinti “sipariş yok” veya “ödendi” kabul edilmez.
2. Yöneticiye etkilenen oturumun kapanacağı, eski guest erişimlerinin biteceği ve mali kayıtların korunacağı açıklanır. Zorla işlem ayrı seçim, zorunlu gerekçe ve ikinci açık onay gerektirir; yalnız popup'ı görmek backend yetkisi değildir.
3. Ayrı kalıcı kurtarma commandId'si, gerçek admin kimliği, tenant/main/table, gerekçe, kaynak durumunun bilinirliği, zaman ve önceki işlemlerle ilişki audit'e yazılır. QR kendi transaction'ında eski QR'ı geçersizleştirme, yeni credential üretme ve hedef main/aktif guest sonlandırmasını birlikte yapacak şekilde geliştirilir. Eski mali/operasyonel geçmiş silinmez.
4. Kapanış borcu silmez, siparişi iptal/COMPLETED yapmaz, ödemeyi PAID göstermez ve otomatik iade/tahsilat başlatmaz. Mali durum ile operasyonel zorla sonlandırma ayrı kalır; çözülemeyen yükümlülükler zorunlu uzlaştırma kaydıyla izlenir.
5. Uzak Order/Payment erişilemiyorsa iki DB atomikliği veya uzaktaki uçuştaki checkout'ın durduğu garanti edilemez. Geç gelen kabul/ödeme sonucu korunup aynı kurtarma işlemiyle uzlaştırılır. Masa yeni ziyarete hemen açılmaz: CLEANING/kurtarma engeli korunur; uzlaştırma/gate doğrulanmadan temizlendi ile AVAILABLE yapma ve yeni sipariş kabulü engellenmelidir.
6. Kaynak geri geldiğinde aynı işlem kimliğiyle kesin durum sorgulanır; eski kabul edilmiş komut/sonuç yeniden yazılmaz, kapanmış eski oturum kendiliğinden açılmaz ve yeni müşterinin oturumuna müdahale edilmez.

**NOT IMPLEMENTED:** Admin paneli, force API, kalıcı kurtarma/uzlaştırma kaydı ve ona ait masa kullanım engeli bu PR'da kodlanmadı. Bunlar yeni güvenlik/servis sözleşmesi ve gerekirse yeni QR migration aralığıyla ayrı küçük geliştirme işidir. Mevcut `/rotate` yalnız credential'ı değiştirir; mevcut guest/main'leri kendiliğinden kapatmaz. K12 varmış gibi `force` alanı normal staff/rotate isteğine gönderilemez; normal close sözleşmesi bu alanları reddeder.

### K13 — Yerel uygulamadan sonra kapsam sapması kapanışı geri almayacak

Hamza'nın görev belgesi ve Order `fd4e7a9` uygulamasındaki ikinci çözüm kabul edildi. Bu, ana mimari A'dan B'ye geçiş değildir. QR yerel değişikliği uyguladıktan sonra aynı işlem kimliği, digest ve izin verilen etkiye ait CLOSED teyidini gönderir; yerel sonucu kendiliğinden geri almaz. Order PREPARED işlemi COMPLETED yapar. Hazırlıktan sonra kaynak kapsamı değişmişse Order V49'a değişmez sapma kaydı yazar ve `scopeDrift/driftBlockers` döndürür; aynı ACK/GET aynı sapmayı döndürür. QR oturumu kapalı sayar, ödeme tekrarlamaz ve sapmayı operasyonel uzlaştırma olarak izler.

- “CLOSED kabul edilir”, yanlış kimlik/digest/makbuz veya RELEASED işlemden dönüşün kabul edildiği anlamına gelmez. Kimlik, tenant, main, digest, amaç, etki ve kalıcı COMPLETED sonucu doğrulanmalıdır.
- QR'ın ACK/abort portları boolean yerine `Confirmation` döndürür. Başarılı ACK ve kaynak sapması, V121 `session_source_confirmations` kaydına yazılır ve yerel işlem aynı transaction'da COMPLETED olur. Kayıt değişmezdir; QR'ın önceki yerel makbuzu değiştirilmez. Bu, sipariş veya mali kayıt kopyası değildir.
- Staff/Waiter CLOSED yanıtının mevcut `blockers` alanında bilinen sapma için sabit `RECONCILIATION_REQUIRED` kodu döner. Masa CLOSED/CLEANING kalır; gerçek Order kodları tenant kapsamlı yerel kaynak teyidinde korunur. Yeni HTTP alanı veya UI bu görevde eklenmedi. ACK kaybında sapma henüz bilinmeyebilir; APPLIED kaydı aynı işlemle uzlaştırılır.
- Yerelde uygulamadan önce izin yalnız validUntil'e kadar kullanılabilir. Süresi geçmiş izin aynı operationId ile status üzerinden yenilenir; çağrı başına yenileme sınırlıdır. QR kilidini beklerken süre dolarsa yazma/abort yapılmaz, REQUESTED kalır ve sonraki deneme güncel izin alır.
- Güncel, tam ve doğru komuta ait BLOCKED/UNKNOWN + NONE, NOT_CLOSED ile abort edilir. Ağ kesintisi, null, eksik, stale veya yanlış kapsamlı yanıt bu kanıt değildir. QR yalnız doğrulanmış RELEASED veya digest'i geri yansıtan NOT_STARTED ile yeniden açılır. NOT_STARTED yanıtındaki purpose null olabilir; gerçek adaptör bunu yalnız bu duruma özel eşler. IDLE_GUESTS teyidi bütün main'in kapandığı anlamına gelmez.
- Gerçek HTTP adaptörü; bilinmeyen ek JSON alanlarını tolere etme, her status/confirmation'da commandDigest gönderme, HTTP conflict'lerini kör retry etmeden uzlaştırma ve servis kimliğini doğrulama sorumluluklarını henüz uygulamadı. Portların varsayılanı kapalıdır. Buradaki QR iş mantığı testleri gerçek Order/Payment E2E değildir.

## 4. Korunan önceki kurallar

- Güvenli biçimde tamamen iptal edilen son sipariş sonrası ilgili guest için, bütün siparişler güvenli iptal edildiyse main için doğrulanmış iptal anından yeni 15 dakika kuralı korunur. Ödeme belirsizliği veya gerekli ama doğrulanmamış iade varken bu koruma otomatik kaldırılmaz; iptal talebi gerçekleşmiş iade sayılmaz. Bu ADR yeni bir otomatik iade politikası getirmez.
- Main, açık guest'ler ve masa durumu QR'ın tek yerel transaction'ında sonlandırılır. Siparişli normal kapanışta mevcut temizlik ayrımı korunur: `OCCUPIED → CLEANING`; ayrı yetkili temizlendi işlemi `AVAILABLE` yapar. Siparişsiz idle mevcut davranışına göre masayı serbest bırakır.
- Geçmiş sipariş, ödeme ve kapanış kayıtları silinmez. Normal otomatik kapanış için sahte personel UUID'si üretilmez; audit, gerçek servis/işlem kimliğini yansıtmalıdır. QR audit'i session_transition_operations içindeki kalıcı komut ve yerel sonuçtur; otomatik kapanışta closed_by_user_id NULL kalır.
- Gerçek Auth/servis kimliği, tenant/guest sahipliği ve müşteri tarama rate limit'i tamamlanmadan mevcut kapalı API'ler açılmaz. Yeni public endpoint, ENV, broker veya ortak domain kütüphanesi bu ADR ile eklenmiş değildir.

## 5. Hedef akış ve sözleşme yükümlülükleri

1. QR geçerli taramada main/guest açar. Order K5 ile gerçek guest bağlamını doğrular; ödeme yetkisi K4'le sınırlıdır.
2. Kullanıcı aktivitesi K6'yla işlenir. Siparişsiz idle, güvenilir Order kapsamıyla ve süre sınırındaki yeni checkout'la koordine edilerek sonlandırılır.
3. Order sipariş ve Payment durumundaki değişiklikleri kapanış değerlendirmesini tetikleyecek güvenilir bir mekanizmaya bağlar. Bildirim tek başına kapanış kanıtı değildir; QR'da ayrıntılı finans kopyası gerektirmez.
4. Kapanış için kalıcı işlem kimliği oluşturulur ve süreç K8 üzerinden Order'ın checkout'larıyla sıralanır. Order bütün oturum kapsamını ve Payment'ın yetkili mali bilgisini doğrular. Uzak sorgular uzun DB kilidi altında tutulmaz; kalıcı koordinasyon korunur ve sonuç ilgili sürümlerle bağlanır.
5. Sonuç uygun değilse kapanış yapılmaz; bilinmiyorsa K9 uzlaştırması sürer. Güvenilir uygunluk sonucu yalnız ilgili işlem, restoran, main ve kaynak kapsamı için geçerlidir; eski genel bir boolean yeniden kullanılamaz. Yeni ödeme denemesi, iptal veya değişen kaynak sürümü göz ardı edilemez.
6. QR güncel main/masa sürümlerini ve işlem durumunu kısa yerel transaction'da tekrar denetleyip main ve aktif guest'leri bitirir, masayı `CLEANING` yapar. Doğrulanmış normal kapanışta personel onayı aranmaz.
7. QR'ın kalıcı sonucu Order tarafıyla aynı kimlik üzerinden teyit/uzlaştırılır. Sonlandırma yanıtı kaybolursa kaynak sonuç sorgulanır; tahsilat veya kapanış yan etkisi tekrarlanmaz.

**İç sözleşmelerin uygulama yükümlülükleri:** Guest doğrulama, guest/main sipariş koruması ve sipariş yokluğu, kapanış hazırlığı/uygunluğu, sonuç sorgusu ve teyit; bütününde doğrulanmış servis kimliği, tenant kapsamı, sürümlenmiş DTO, tekrar güvenliği, kaynak tamlığı/tazeliği ve güvenli hata yanıtı gerekir. Kesin HTTP yolları/DTO adları henüz uygulanmadı; çalışan endpoint gibi belgelenmez. Aktarımın restart/kayıp yanıt güvencesi kalıcı iş kayıtlarıyla sağlanmalıdır. Order outbox'ının yalnız yazıcı olması teslimat garantisi vermez; gereken işleyici/gönderici uygulama kapsamına alınır. Broker seçimi D-10'u bu ADR genel olarak kapatmaz.


### 5.1 CLOSED teyidi uyuşmazlığı — K13 ile karar verildi

Önceki e01fd03 sürümünde `CLOSURE_SCOPE_CHANGED`, QR kapanmışken Order'ı PREPARED bırakabiliyordu. Yeni fd4e7a9 sürümünde hazırlığı kabul edilmiş işlemin doğru CLOSED teyidi tamamlanır; kaynak sapması V49'da ayrı uzlaştırma kaydıdır. Hamza'nın yazılı görev belgesi, Order kodu ve Alper'in uygulama talebi doğrultusunda ikinci yaklaşım K13 olarak kaydedildi. Eski hata artık hedef sözleşmenin parçası değildir.

QR kaynak teyidi/sapma kaydı ve izin/abort işlemesi K13'e uyarlandı. Ortak ağ DTO eşlemesi, gerçek servis güveni, Payment sorgusu ve E2E hâlâ uygulanacak/doğrulanacak işlerdir. Kararın kaydedilmesi bu bağlantıların tamamlandığı anlamına gelmez. Order'ın [güncel sözleşmesi](https://github.com/software-lecture-labs/Restofy/blob/fd4e7a95b523f14b1854da60def2d1893d4b4700/docs/order-service/INTEGRATION_CONTRACTS.md#5-adr-qr-001--masa-oturumu-yaşam-döngüsü-order-tarafı) esas alınır.

### 5.2 Review sorularına yanıt ve açık teyitler

| Review konusu | Kaydedilen yanıt / gerçek sınır |
| --- | --- |
| Order onayı | Hamza'nın PR #13 kendi yorumu A/K1–K9'u teyit ediyor. PR #11'de istenen doğrudan sahiplik teyidi yine kendi hesabından verilmelidir. |
| Waiter etkisi | Normal garson kapanışı K11 ile geri eklendi; komut/sonuç semantiği PR #14'e göre test ediliyor. Umut'un kararı görüp teyit etmesi, canonical prefix ve gerçek servis/personel güveni değişiklikleri kendi PR'ında yapılmalı. |
| Yönetici müdahalesi | K12, açık sipariş/UNKNOWN uyarısı + ayrı onay/gerekçe + audit ile gelecekte zorla yenileme/oturum sonlandırma kararını kaydeder. Normal nakit teyidi Payment'tadır. Kurtarma API'si/paneli henüz yoktur. |
| CLOSED sonrası kapsam sapması | K13 kararı ve iki servisin yerel kodu mevcut; gerçek ağ bağlantısı/E2E bekliyor. Hamza'nın görev belgesi kaydedildi; PR #11 kişisel teyidi ayrı. |

## 6. Migration ve merge öncesi koşullar

Reviewer, V29/V30'un yalnız bu branch'te olduğunu ve paylaşılan ortamda uygulanmadığını belirtti. Bu çalışma ekipteki bütün veritabanlarının `flyway_schema_history` kayıtlarını doğrulamadı. Önceki "V29/V30 zaten uygulanmış, değiştirilemez" ifadesi bu ayrımı yapmadığı için düzeltildi.

- Ölçüt yalnız production değildir. Paylaşılan/güvenilen Flyway geçmişine girmiş migration sessizce yeniden yazılmaz. Dev merge'i SQL'i kendi başına çalıştırmaz; ekip QR'ı başlattığında ortak şema yükümlülüğü oluşabilir.
- Önceki A uyarlamasında eski ayrıntılı şema kaldırılıp V29/V30 yalnız QR lifecycle komut/sonucuna dönüştürüldü. 31 Ekim K13 çalışmasında kullanıcı ayrıca branch'e ait QR ALTER adımlarını CREATE tanımlarında birleştirmeyi ve yerel QR verisini sıfırlamayı açıkça istedi. V21/V22/V26/V28 buna göre düzenlendi; V23/V24/V25 ara dosyaları kaldırıldı. Bu numaralar tarihsel görev sürümleri olarak yeniden kullanılmayacak. Kısıtlar korunur; diğer servis migration'ları değiştirilmez.
- Yerel QR DB'de eski V29/V30 checksum'ları bulundu. Kullanıcı yerel veri resetini açıkça onayladı; yalnız doğrulanmış QR veritabanı sıfırlanıp yeni imajla migration uygulandı. Eski QR verileri taşınmadı; diğer servis DB'leri ve PostgreSQL/Sonar volume'leri silinmedi. Ekipte eski QR geçmişi varsa kendi açık onaylı geçişi gerekir. Bu istisna başka migration/paylaşılan geçmiş için izin değildir.
- K13 CREATE birleştirmesinden sonra 31 Ekim'de yalnız restofy-dev projesinin restofy_qr DB'si yeniden kuruldu. V21,V22,V26–V30,V121 ve JPA validate ile health 200 doğrulandı. Önceki mevcut tablo sütun/kısıt/indeks katalogları yeni kurulumla aynı çıktı; kaynak teyidi tablosu ayrıca eklendi. Diğer DB kimlikleri değişmedi. compose down -v veya ortak volume silme kullanılmadı; yerel QR iş verisi sıfırlandı ve taşınmadı.
- [Sürüm dağılımı](migrations.md): QR V21–V30 + kullanıcı tarafından onaylanan ek V121–V130. V121 yalnız kaynak teyidi/sapma makbuzu CREATE şemasıdır; V122–V130 boş. Order V41–V49 kullanır; V50 boş. Payment V31–V40 ve Notification V51–V60'a dokunulmaz. Bu branch-only birleştirme, paylaşılan/verisi korunacak Flyway geçmişini sessizce değiştirme izni değildir; eski geçmiş bulunan ortamlar ayrı açık geçiş onayı gerektirir.
- PR #11'in A'ya uyarlanmış kod/migration ve ilgili testleriyle review edilmesi gerekir. Bu belge tek başına "merge hazır" sonucu değildir. QR-04 uzun sürerse QR-01–QR-03'ü gerçekten bağımsızlaştırıp test ederek ayrı PR'a ayırmak alternatif olarak kalır; böyle bir ayırma yapılmış değildir.

## 7. Servis bazında uygulama ve doğrulama planı

| Sorumlu | Yapılacak iş | Bu karar çalışmasındaki durum |
| --- | --- | --- |
| QR / Alper | A koordinasyonu; K11 staff API'leri; K13 kaynak teyidi/sapma kaydı ve izin/abort işlemesi; K12 kurtarma tasarımı | IMPLEMENTED — QR yerel iş mantığı ve CREATE şeması; gerçek HTTP/Auth/servis adaptörleri ve K12 force API/paneli NOT IMPLEMENTED |
| Order / Hamza | K8/K9 kalıcı koordinasyon, guest/idle kaynağı, K13 teyit/sapma; gerçek Payment ve QR bağlantısı | PARTIALLY IMPLEMENTED — fd4e7a9'da V48/V49, order-state, izin/idle ve K13 mevcut; gerçek Payment sorgusu, QR adaptörü, outbox dispatcher ve sapma ekranı/raporu/olayı yok |
| Payment / Alper | Nakit + online; yetkili teyit/sağlayıcı kanıtı, K4 sahiplik, Order'a güncel mali kapsam ve uzlaştırma | PLANNED — ürün kararı kaydedildi; gerçek Payment uygulaması/E2E bu görevde yapılmadı |
| Frontend / ilgili geliştirici | Foreground aktivite, guest taşıması, garson komutu; yönetici uyarı/ikinci onay ve kurtarma görünümü | NOT IMPLEMENTED — bu görev frontend geliştirmesi veya testi içermez |
| Ortak / servis sahipleri | Gerçek kaynak/tenant/checkout yarışı ve Payment kapsamı E2E; gerçek servis kimliği | NOT VERIFIED — QR testlerinde Order otoritesi kontrollü; gerçek servisler arası E2E çalıştırılmadı |
| Dokümantasyon / Alper | Kararlar, yeni konum, mimari/gereksinim ve güncel entegrasyon rehberi | IMPLEMENTED — GitHub işlemleri geliştiriciye bırakıldı |

Uygulama PR'ları ortak sözleşme ve koordinasyon, QR sadeleştirme/geçiş, Payment ve gerçek Auth/servis bağlantısı gibi incelenebilir parçalara ayrılır. Bir parçanın tamamlanması bütün entegrasyonun bittiği anlamına gelmez. HTTP güvenlik mekanizması, kesin DTO/endpoint'ler, retry limitleri ve veri geçiş ayrıntıları uygulama işlerinde somutlaştırılır; sayı veya SLA uydurulmaz.

## 8. Ortak kabul testleri

| Senaryo | Beklenen sonuç |
| --- | --- |
| Sipariş yok, işlem yok / 14. dakikada gerçek etkileşim | İlk durumda 15 dakika idle; ikinci durumda ilgili guest/main yenilenir |
| Yalnız açık sekme/polling; iki telefon | Yapay yenileme olmaz; birinin hareketi diğer guest'i yenilemez |
| QR kesintisi, yanlış servis kimliği, sahte tenant/guest ID | Erişim açılmaz; geçersizlik ve bağımlılık kesintisi ayrılır |
| K5 doğrulama ve gecikmiş aktivite | Doğrulama süreyi yenilemez; terminal oturum diriltilmez |
| Kabul edilmiş sipariş ve 15 dakika hareketsizlik | Main ve sipariş sahibi korunur; siparişsiz guest kendi idle kuralına tabidir |
| Ödeme tamam/sipariş tamam değil; sipariş tamam/ödeme eksik | İkisinde de normal kapanış olmaz |
| İki sipariş sahibi ve bir siparişsiz guest | Gerçek yükümlülükler bitince tüm aktif guest/main kapanır; üçüncü ödeme aranmaz |
| Kendi siparişi / aynı masadaki başkası / başka restoran | K4 sahipliği doğrulanır; yetkisiz sipariş/ödeme erişimi reddedilir |
| Yeni checkout önce / kapanış hazırlığı önce | Önceki sipariş kapsamda; önce kapanışsa yeni checkout 409; çoklu instance'ta aynı sonuç |
| Uygulamadan önce izin geri çekilir / uygulamadan sonra kapsam sapar | İlk durumda doğrulanmış abort; ikinci durumda K13 COMPLETED + değişmez sapma kaydı, QR geri açılmaz |
| Yetkili staff / sahte tenant-user / eski version / aynı commandId farklı içerik | Normal izinli kapanış; sahte kapsam reddi; 409 ve değişmez audit |
| Staff prepare/ACK yanıtı kayıp / GET durum sorgusu | 202 veya kesin yerel CLOSED; GET salt okuma, aynı işlem uzlaştırılır |
| Nakit teyidi yok / istemci paid=true / online callback belirsiz | Normal kapanışa kanıt sayılmaz |
| Gelecek admin force kurtarması | Uyarı + ADMIN + gerekçe + ayrı onay; borçlar korunur, yeni ziyaret uzlaştırmaya kadar engellenir; henüz test edilmiş özellik değil |
| Kayıp ACK, QR/Order kesintisi, süreç yeniden başlatma | Aynı kalıcı kimlikle sonuç öğrenilir; çift kapanış, yeni tahsilat veya kör yeniden açma olmaz |
| Güvenli tam iptal / ödeme belirsizliği / iade bekliyor | Önceki güvenli iptal süre kuralı korunur; belirsiz durumda otomatik serbest bırakma olmaz |
| SQL hatası ve kapanış sonrası temizlendi | Main/guest/masa birlikte rollback/commit; temizlik ayrı; eski işlem yeni main'e dokunmaz |
| V29/V30 geçişi ve JPA validate | Onaylı geçmiş/veri planı ve PostgreSQL testleriyle doğrulanır; reset/repair ile hata gizlenmez |

Bu tablo ortak E2E test planıdır. QR'ın yerel davranışları PostgreSQL 17, kontrollü Order yanıtı ve gerçek Flyway ile test edilir; bu testler gerçek Order gate'i veya Payment mali doğruluğu kanıtı değildir. Backend pre-push ve Sonar sonuçları görev raporuna gerçek koşular tamamlandığında kaydedilir; frontend bu görevde çalıştırılmaz.

## 9. Kararların son özeti ve teyit durumu

| Karar | Kabul edilen davranış |
| --- | --- |
| K1 | Otomatik kapanış korunur; aynı güvenli koşullarla yetkili personel de isteyebilir |
| K2 | Gerçek siparişler tamamlanmış ve mali koşullar sağlanmış olacak |
| K3 | Siparişsiz guest ödeme beklenen kişi sayılmayacak; main ile kapanacak |
| K4 | MVP'de müşteri yalnız kendi guest siparişlerini ödeyecek |
| K5 | Order tokenı QR'a doğrulatıp gerçek oturum bağlamını alacak |
| K6 | Gerçek menü/sepet aktivitesi 15 dakikayı yenileyecek; polling yenilemeyecek |
| K7 | A seçildi: Order Payment'ı doğrulayıp QR'a uygunluk sağlayacak; QR sadeleşecek |
| K8 | Checkout ve kapanış hazırlığını Order kalıcı sıralayacak; önce kapanışsa yeni checkout 409 |
| K9 | Kesinti/kayıp yanıt aynı kalıcı kimlikle uzlaştırılır; CLOSED sonrası kapsam sapması K13'e göre kaydedilir |
| K10 | Nakit ve online ödeme Payment yetkili kanıtıyla, QR'da paid beyanı olmadan |
| K11 | Yetkili normal garson/admin komutu ve salt okunur durum API'si; Order/Payment koşulları korunur |
| K12 | Gelecekte ADMIN uyarı + gerekçe + ikinci onayla force-rotate/oturum sonlandırabilir; borç/audit/uzlaştırma korunur |
| K13 | Doğru CLOSED teyidi tamamlanır; kapsam sapması değişmez kayda alınır, QR geri açılmaz; uygulama öncesi izin geri çekilirse doğrulanmış abort gerekir |

**Teyit ve uygulama sınırı:** A/K1–K9 için Hamza'nın PR #13'te kendi teyidi vardır; K13 için yazılı görev belgesi ve fd4e7a9 kodu incelendi, Alper QR uyarlamasını açıkça istedi. PR #11'de istenen doğrudan kişisel teyit ve K13 yorumunu geliştiriciler kendi hesaplarından ekler. Umut'un K11/K12 eklerini onayladığı, K12'nin çalıştığı veya gerçek ağ entegrasyonunun tamamlandığı iddia edilmez. Commit/push, PR/comment, review ve merge kullanıcı tarafından yapılır.
