# Restofy — Gereksinim Analizi

**İnceleme tarihi:** 15 Ekim 2026  
**Proje:** `C:\Users\T_451\OneDrive\Masaüstü\qr-management-system`  
**Birincil kaynak:** `docs/gereksinimler/Restofy_Nihai_Gereksinimler.docx`, 14 Ekim 2026 nihai konsolidasyonu.

31 Ekim 2026 QR güncellemesi: QR-01–QR-04 yerel kodu ve ADR-QR-001/A uyarlaması mevcuttur; eski tarihli altyapı analizi aşağıdadır. Gerçek Auth/Order/Payment bağlantıları kapalıdır. Bu belge kaynak gereksinimleri kodla karşılaştırır. `[KARAR]` kaynakta belirlenmiş kuralı, `[ÖNERİ]` henüz uygulanmamış tasarımı, `[AÇIK]` ürün/teknik karar ihtiyacını, `[MEVCUT]` repository içinde görülen yapıyı belirtir. Aşağıdaki veri modeli ve tasarım önerileri onaylanmış veya uygulanmış özellik sayılmaz.

İlgili belgeler: [mimari](architecture.md), [veritabanı](database.md), [ER diyagramları](er-diagram.md), [klasör yapısı](folder-structure.md).

## 1. Kaynak önceliği ve inceleme sınırı

1. Nihai belgenin normatif kararları ve içindeki Kitchen Service V1 bölümü.
2. Nihai belgeye aktarılmış Restofy Servisler gereksinimleri.
3. İlk sistem gereksinimleri ve tarihsel ekler.
4. Teknik öneriler; normatif iş kuralının yerine geçmez.

Referans verilen eski Word dosyaları repository içinde ayrı dosyalar olarak bulunmadı; nihai belgedeki aktarımları incelendi. Ek A/B tarihsel izlenebilirlik içindir. Örneğin eski ekteki “yeniden başlatmada ürünü otomatik aç” ifadesi geçerli değildir. Nihai karar, kapalı ürünün durumunu ve nedenini korumaktır. Kaynakta geçen dış teknik bağlantılar bu çalışma kapsamında ayrıca doğrulanmadı; değerlendirme yerel belge ve koda dayanır.

Bu çalışma statik repository incelemesidir. Canlı veritabanı, çalışan container, dış ödeme/ağ sistemi veya testlerin güncel çalışma sonucu doğrulanmadı. Test dosyalarının varlığı testlerin geçtiği anlamına gelmez.

## 2. Ürün ve aktörler

Restofy, müşterinin masaya bağlı QR ile menüye ulaşmasını, ürünleri özelleştirerek sipariş vermesini; restoranın mutfak, servis, ödeme, değerlendirme ve raporlama süreçlerini yürütmesini hedefler.

- **Müşteri / guest:** Menü, kendi sepeti/siparişi, çağrı, ödeme ve uygun değerlendirme. Kayıtlı müşteri hesabının zorunluluğu kesinleşmemiştir; tasarım guest kullanımını esas alan öneridir.
- **WAITER:** Restoran masaları ve çağrıları, hazır sipariş teslimi, yetkili ödeme teyidi ve masa kapanışı.
- **KITCHEN:** Mutfak kuyruğu, hazırlık geçişi, ürün erişilebilirliği, iptal talebi ve manuel fire.
- **ADMIN:** Kendi restoranının ayarları, personel yönetimi, menü/masa/QR ve raporları. Başka restoran verisine erişim sağlamaz.
- **Servis kimliği:** Yalnız tanımlı servis işlemleri; kullanıcı rolüyle aynı yetki türü değildir.

Platform yöneticisinin ayrı yetkileri `docs/SECURITY.md` içinde ayrıştırılması gereken konu olarak geçer; nihai ürün belgesinde ayrıntılı platform yönetimi kapsamı yoktur. Bu nedenle ek ürün modülü olarak varsayılmamıştır.

## 3. Mevcut uygulama ve boşluk

### 3.1 Kanıtlanabilen altyapı

- `backend/pom.xml`: Java 25, Spring Boot 4.1.1 ve 13 Maven modülü.
- `backend/services/api-gateway`: Spring Cloud Gateway Server Web MVC, 12 `/api/v1` yönlendirmesi; veritabanı yok.
- `docker-compose.yml`: PostgreSQL 17 Alpine ve 13 backend servisi, servis başına ayrı DB adı, yerel host portları. Frontend Compose dışında.
- `postgres/init-databases.sh`: 12 boş servis veritabanını oluşturacak ilk kurulum betiği; iş tablolarını veya ayrı servis kullanıcılarını oluşturmaz.
- Veri kullanan servislerin ayarlarında Flyway ve `spring.jpa.hibernate.ddl-auto=validate` var. İş alanı migration SQL dosyası bulunmadı. Test SQL fixture'ları üretim şeması değildir.
- 15 üretim Java dosyası: 13 uygulama başlangıç sınıfı ve Order içindeki 2 RestClient yapılandırma sınıfı. Üretim kaynaklarında iş controller/entity veya özel `SecurityFilterChain` bulunmadı.
- Order servisindeki RestClient connect/read timeout yapılandırması mevcut. Diğer servislere uygulanmış ortak HTTP katmanı varsayılmaz.
- Frontend: Next.js 16.4.1, React 19.3.0, TypeScript 5, Tailwind CSS 4; TR/EN next-intl altyapısı. `src/app/page.tsx` yalnız `null` döndürüyor. Feature klasörleri büyük ölçüde `.gitkeep`.
- Gateway routing/downstream, RestClient ve gerçek PostgreSQL üzerinde Flyway test kodu; frontend dil/layout ve HTTP/tarayıcı test kodu mevcut. İş akışlarının uçtan uca uygulandığına dair kanıt yok.

### 3.2 Gereksinimlerin uygulama durumu

QR/masa oturumu, ürün/kategori CRUD, sepet/sipariş, mutfak, garson çağrıları, gerçek ödeme, değerlendirme, bildirim ve yönetim raporları: **NOT IMPLEMENTED — incelenen üretim kodunda iş uygulaması bulunmadı.** Auth/JWT/RBAC, tenant izolasyonu ve ödeme doğrulaması da uygulanmış kabul edilemez. Bağımlılık veya ENV adının bulunması özellik kanıtı değildir.

Altyapı bir başlangıç noktasıdır; çalışan restoran MVP'si veya production hazırlığı olarak değerlendirilmemelidir.

## 4. Fonksiyonel gereksinimler ve kabul ölçütleri

Her alt başlıktaki F kodları nihai Word belgesinin kodlarıdır. Ek kabul ölçütleri tasarım önerisidir; kaynak kapsamını sessizce değiştirmez.

### 4.1 QR ve masa oturumu

**F-QR-01:** Masaya özel QR oluşturma, görüntüleme, indirme, yenileme, etkinleştirme, pasifleştirme ve silme. QR doğrulanıp doğru masaya eşlenir.

Kabul: Pasif, iptal edilmiş veya geçersiz QR işlem oturumu açamaz; yenilenen QR'ın eskisi yeni erişim sağlayamaz. Geçmiş sipariş referanslarını bozmamak için QR silmenin mantıksal olması önerilir. Mevcut guest oturumlarına QR yenilemenin etkisi D-14'te açıktır.

**F-QR-02:** İlk tarama ana masa oturumunu açar; ilk tarama dahil her tarama bağlı guest oturumu üretir. Sipariş yokken 15 dakika işlemsizlikte sona erer; sipariş varsa ödeme/masa kapanışına kadar sürer.

Kabul: Eşzamanlı iki ilk tarama tek açık ana oturum ve ayrı guest kayıtları üretir. Süresi dolmuş oturumla sipariş verilmez. [ADR-QR-001 K6](decisions/ADR-QR-001-masa-kapanisi-ve-siparis-koordinasyonu.md) hedefinde gerçek menü/sepet hareketi yalnız ilgili guest ve main süresini yeniler; diğer guest'ler bağımsızdır. Açık sekme/polling yenilemez. Kabul edilmiş sipariş main ve sahibini korur. Gerçek servisler arası entegrasyon henüz uygulanmadı.

**F-QR-03:** Masa/QR aktifliği, tarama bazlı müşteri sayısı, doluluk ve oturum durumu gösterilir. [ADR-QR-001 K1–K3](decisions/ADR-QR-001-masa-kapanisi-ve-siparis-koordinasyonu.md) ile yeni hedef, gerçek siparişler tamamlanıp mali koşullar sağlanınca ayrıca garson/admin onayı olmadan main ve bütün aktif guest'lerin otomatik kapanmasıdır. Siparişsiz guest ödeme beklenen kişi sayılmaz. QR kodu bu karara uyarlandı; 31 Ekim K11 ile aynı Order/Payment koşullarına bağlı yetkili WAITER/ADMIN close ve durum API'leri geri eklendi. Otomatik kapanış korunur; staff isteği force veya ödeme kanıtı değildir. Gerçek Order/Payment adaptörü kapalıdır; tam servisler arası akış henüz uygulanmadı.

Kabul: Tarama sayısı gösterilir; bunun tekil insan sayısı olmadığı ayrıca açıklanır. Aynı cihazın tekrar taramasını tekilleştirme politikası açık tutulur. Aktif sipariş/bakiye varken normal kapanışın engellenmesi önerilir.

### 4.2 Menü ve ürün

**F-MN-01:** Ana/alt kategori CRUD, aktiflik, arama ve kategori filtresi; müşteriye yalnız uygun ürünler gösterilir.

Kabul: Kategori döngüsü oluşmaz; başka restoran kategorisine ürün bağlanamaz. Pasif üst kategorinin alt ürünlerinin görünürlüğü açık bir yayın politikasıyla belirlenir.

**F-MN-02:** Ad, görsel, fiyat, KDV dahil ibaresi ve içerikler; açıklama, gramaj, kalori, hazırlık süresi ve puan ortalaması desteği. Kaynakta açıklama/gramaj/kalori/hazırlık süresi opsiyoneldir.

Kabul: Negatif fiyat reddedilir; boş opsiyonel alan sahte sıfır değerle doldurulmaz. Puan yokken “henüz değerlendirme yok” durumu gösterilir.

**F-MN-03:** Malzeme ekleme/çıkarma ve ek malzeme fiyat farkı. Yayın durumu ile mutfak satışa uygunluğu ayrı tutulması önerilir.

Kabul: İzin verilmeyen çıkarma/ekleme reddedilir; fiyat farkı sunucuda hesaplanır. Mutfak kapatma nedeni/zamanı korunur; ürün kendiliğinden satışa dönmez. V1 hammadde, reçete ve stok miktarı muhasebesi içermez.

### 4.3 Sepet ve sipariş

**F-OR-01:** Ekle/çıkar/adet güncelle, seçenekler, toplam/KDV, etkin oturumda sipariş ve benzersiz sipariş numarası.

Kabul: Sunucu restoran/masa/guest sahipliği, sipariş kabul ayarı, ürün erişilebilirliği, fiyat ve seçenekleri yeniden kontrol eder. Aynı idempotency key + aynı istek aynı sonucu döndürür; farklı gövde 409 üretir. Aynı masadaki iki sipariş ayrı orderId alır. Menü sonradan değişse de sipariş snapshot'ı değişmez.

**F-OR-02:** Alındı, hazırlanıyor, hazır, servis edildi, ödeme bekliyor, ödendi, tamamlandı ve iptal evreleri görüntülenir.

Kabul: Bu ifadeler tek zorunlu doğrusal enum olarak uygulanmaz; sipariş, mutfak, teslim ve ödeme yaşam döngülerinin ayrılması önerilir. Ön ödeme seçilirse ödeme servis öncesinde de gerçekleşebilir. Ekrandaki birleşik gösterim yetkili servis kayıtlarından türetilir.

**F-OR-03:** Tam ve ürün bazlı iptal, neden/metin ve geçmiş. Müşteri neden girmediyse garson gerekçe kaydeder.

Kabul: Mutfak talebinde neden zorunludur; Order kesinleştirmeden iptal gerçekleşmez. Ödenmiş siparişin iptali otomatik iade sayılmaz. Hazır sipariş iptal yetkisi ve kısmi iptalin Kitchen V1'e yansıması açık karardır.

### 4.4 Mutfak — V1 zorunlu kapsam

**F-KT-01:** Sipariş bazlı görüntüleme, aktif/hazırlanıyor/hazır/iptal filtreleri; masa, orderId, ürün/adet, not, eklenen/çıkarılan malzeme ve zamanlar.

Kabul: Aynı masanın siparişleri birleştirilmez. “Yeni sipariş en üstte” ile “en eski bekleyen ilk” kaynak ifadeleri için öneri: yeni gelenler göstergesi üstte, çalışma kuyruğu `received_at ASC, id ASC`. Bu ekran ayrımı ürün kararı olarak teyit edilir.

**F-KT-02:** Yalnız `WAITING → PREPARING → READY`. İlk geçişte startedAt, ikinci geçişte readyAt; `KitchenOrderReady` bildirimi. İptal yalnız Order onayıyla kesinleşir.

Kabul: WAITING doğrudan READY olamaz. Aynı geçişin tekrarı yeni olay veya zaman üretmez. Kitchen READY, servis tesliminden sonra da READY kalır. Ürün bazlı hazırlık butonu yoktur. Gecikme etiketi opsiyoneldir; zaman eşiği açık karardır.

**F-KT-03:** Ürünü stokta yok/geçici kapalı yapma, neden/zaman, yetkili yeniden açma; yeniden başlatmada kayıtlı durum. Manuel fire ve tarih/ürün filtreleri; gün sonu sipariş/fire raporu.

Kabul: Kapalı ürün restart sonrası kapalı kalır. Fire; ürün, pozitif adet, neden, açıklama, zaman ve kullanıcı taşır. Nedenler: yanlış hazırlama, bozulma, düşürme/hasar, iptal sonrası israf, diğer. Gün sonu alınan/hazır/iptal/bekleyen/hazırlanıyor sayıları ve fire neden kırılımı hesaplanır. Fire girişi stok miktarını otomatik azaltmaz.

### 4.5 Garson

**F-WT-01:** Restoranın tüm masaları, dolu/boş durumları, çağrı/hesap istekleri, aktif/iptal/hazır siparişler.

**F-WT-02:** Garson/yardım/hesap çağrısı çözülene kadar izlenir; hazır sipariş teslim edildi işaretlenir.

Kabul: Okundu işareti çağrıyı çözmez. İki garsonun aynı çağrıyı sahiplenmesi/siparişi teslim etmesi version kontrolüyle çakışma üretir. Teslimin otoritesi Order, garson komutu Waiter üzerinden önerilir.

**F-WT-03:** [ADR-QR-001 K1/K11](decisions/ADR-QR-001-masa-kapanisi-ve-siparis-koordinasyonu.md) normal otomatik kapanışı korur ve yetkili garson/admin'in aynı güvenli kapanışı istemesini geri getirir. Normal istek, tamamlanmış sipariş ve Order'ın Payment'tan doğruladığı mali uygunluk koşullarını atlamaz. QR komutu/sonucu kalıcı ve sorgulanabilirdir; Waiter yalnız kesin CLOSED sonrası çağrılarını sonlandırır. Temizlendi ayrı yetkili işlemdir. QR kodu mevcuttur; gerçek Order/Payment/Waiter kimlik ve transport entegrasyonu yoktur.

Kabul: `OCCUPIED → CLEANING → AVAILABLE` önerilir; kapanış ve temizlik aynı olay sayılmaz. Kapanış devam ederken yeni sipariş kabul edilmez. Yerleşik 15 dakika ve Wi-Fi üç saat süreleri birbirinden bağımsızdır.

### 4.6 Ödeme ve değerlendirme

**F-PY-01:** [ADR K10](decisions/ADR-QR-001-masa-kapanisi-ve-siparis-koordinasyonu.md) ile nakit ve online seçenekleri Payment geliştirmesine dahildir. Nakit yalnız yetkili personelin Payment kaydıyla, online yalnız doğrulanmış sağlayıcı/işlem kanıtıyla kesinleşir; QR/Waiter client beyanı kanıt değildir. Restoranda ve online ödeme; yemek kartı, bahşiş ve bölüşme opsiyonel. Admin yöntemleri kontrol eder.

Kabul: Kapalı yöntemden yeni işlem başlatılmaz. Restoranda ödeme yalnız yetkili personel teyidiyle başarılı olur. Gerçek online sağlayıcı entegrasyonu ve sürüm sınırı D-01'e bağlıdır.

**F-PY-02:** Bekleyen/başarılı/başarısız/iptal ödeme, callback eşleştirme ve mükerrer tahsilatın engellenmesi. Demo bypass yalnız testte ve gerçek tahsilat dışında.

Kabul: İstemcinin başarı yönlendirmesi ödeme kanıtı değildir. Tekrarlanan/gecikmiş callback ikinci tahsilat doğurmaz; başarısız sonuç başarılı kaydı geri düşürmez. Belirsiz timeout sonucunda kör tekrar yerine sağlayıcı durum sorgusu yapılır. Demo tutarları gerçek ciroya eklenmez.

**F-PY-03:** Ödenmiş siparişin ürün/restoran değerlendirmesi; 1–5 yıldız ve isteğe bağlı yorum.

Kabul: Başka guest'in siparişine veya siparişte olmayan ürüne puan verilmez. Tekrar değerlendirme kısıtı önerilir. Nakit ödeme hakkı kesinleşmeden yalnız online veya tüm ödemeler için davranış olmuş gibi yazılmaz.

### 4.7 Yönetim, bildirim ve analiz

**F-AD-01:** Restoran/sipariş/ödeme aç-kapa, bilgi mesajı, masa/QR/menü/sipariş/personel görünürlüğü ve manuel kontrol.

Kabul: Admin paneli başka servis tablolarına yazmaz; sahibinin yetkili API'sini kullanır. Admin sipariş kapatma ayarı ödeme ve geçmiş görüntülemeyi yanlışlıkla engellemez. Garson login/mesai etkisi D-05'te açıktır.

**F-AD-02:** Sipariş/çağrı yaşam döngülerinde rol hedefli bildirim; okundu, aktif, çözüldü ayrımı; masa kapanınca temizleme.

Kabul: Yeni sipariş, hazır, iptal, çağrı ve ödeme olayları doğru restoran/role gider. “Temizleme” aktif listeden kaldırma/arşivleme olarak önerilir; audit geçmişi silinmez. Okundu her alıcıya, çözüm iş kaydına aittir.

**F-AD-03:** Günlük sipariş, ciro, ürün satışı, masa yoğunluğu, iptal nedenleri, değerlendirme ve ödeme türü raporları.

Kabul: Ciro doğrulanmış gerçek ödemelerden hesaplanır. Brüt sipariş toplamı, net tahsilat ve kâr ayrı kavramlardır; maliyet modeli olmadığı için kâr hesaplandığı iddia edilmez. Yerel gün sınırı restoran saat diliminden UTC aralığına çevrilir. Tarama sayısı tekil müşteri sayısı değildir.

### 4.8 Planlanan Wi-Fi

**F-TM-01 [PLAN/AÇIK]:** Sipariş üzerindeki QR doğrulamasıyla üç saat erişim ve süre sonunda kesme hedefi.

Ağ cihazı/API, cihaz kimliği, izinler, iptal başarısı ve tekrar sipariş süresi tanımsızdır. Masa QR'ı, guest oturumu ve Wi-Fi hakkı farklı kavramlardır. Çekirdek sipariş/ödeme akışı bu entegrasyona bağımlı yapılmaz. Salt timer kaydı açılması erişimin gerçekten kesildiğini kanıtlamaz.

## 5. Fonksiyonel olmayan gereksinimler

Kaynak Word'deki teknik maddeler öneridir; repository'deki `docs/SECURITY.md` uygulama sırasında ayrıca dikkate alınacak güvenlik kurallarıdır.

- **Yetki:** Sunucuda rol + restoran + kaynak sahipliği; istemci restaurantId/role göndererek yetki kazanamaz. Başka masanın guest tokenıyla okuma/yazma reddedilir.
- **Oturum:** Süreli/iptal edilebilir token, güçlü parola hash'i, admin TOTP/kurtarma kapsamı. Tarayıcı kimlik tokenlarını kalıcı JS store'a koymama; cookie seçilirse HttpOnly/Secure/SameSite ve CSRF tasarımı.
- **Tutarlılık:** Yerel transaction, idempotency, optimistic locking, outbox/inbox; bilinmeyen dağıtık sonucu açık durumla takip ve uzlaştırma.
- **API:** Mevcut `/api/v1` öneki korunur; DTO validation, sayfalama üst sınırı, güvenli hata cevabı ve correlation ID. OpenAPI bağımlılığı sözleşmenin yazıldığı anlamına gelmez.
- **Gözlemlenebilirlik:** Yapılandırılmış log/metric/trace ve health/readiness. Ham token, parola, cookie, kart verisi veya TOTP sırrı loglanmaz.
- **İşletim:** Ayrı DB kullanıcıları, yedek/geri yükleme tatbikatı, kaynak limitleri ve production network sınırları tasarlanır. Şu an ortak DB kimliği kullanılıyor.
- **Arayüz:** Mobile-first müşteri akışı, TR/EN tutarlılığı, yükleniyor/boş/hata/offline durumları ve `docs/web-rules.md` erişilebilirlik hedefleri. Özel ekranlar indekslenmez.
- **Ölçülebilir kalite [AÇIK]:** Beklenen restoran/masa/istek hacmi, p95 gecikme, bildirim gecikmesi, kullanılabilirlik, RPO/RTO ve saklama süreleri verilmemiştir; sayısal SLA icat edilmemelidir.

## 6. Açık karar kaydı

- **D-01 / Ürün + ödeme:** Demo/gerçek ödeme sürüm sınırı, sağlayıcı, ön/son ödeme ve iptal/iade/başarılı geç callback davranışı.
- **D-02 / Ürün + Kitchen + Order:** Ürün bazlı iptalin sipariş bazlı hazırlığa etkisi; kalan ürünler ve tam iptal ilişkisi; READY sipariş iptal sınırı.
- **D-03 / Ürün + Rating:** Nakit ödeyen guest puanlayabilir mi? Oy tekilliği ve değerlendirme süresi nasıl uygulanır?
- **D-04 / QR — Karar kaydı:** [ADR-QR-001](decisions/ADR-QR-001-masa-kapanisi-ve-siparis-koordinasyonu.md) bağımsız guest/main aktivitesi, anlamlı menü/sepet etkileşimi ve sipariş koruması hedefini kaydeder; önceki güvenli iptal sonrası 15 dakika kuralı korunur. Gerçek aktarım ve süre sınırı koordinasyonu uygulama işidir; karar kaydı bunların test edildiği anlamına gelmez.
- **D-05 / Ürün + Admin + Auth:** Garson login/mesai ile sipariş açılması; son garson logout davranışı ve admin OFF önceliği.
- **D-06 / Kitchen UX:** Yeni gelen üstte / en eski bekleyen önce ayrımı ve gecikme etiketi eşiği.
- **D-07 / Timer + operasyon:** Ağ cihazı/API, cihaz eşleme, izin gereksinimleri, süre yenileme ve kesme başarısızlığının telafisi.
- **D-08 / Ödeme:** Yemek kartı, bahşiş, bölüşme ve vergi/yuvarlama kapsamı; ülke bazlı kurallar bu analizde hukuken doğrulanmamıştır.
- **D-09 / Mimari + Auth:** Kullanıcı kimlik yöntemi, cookie/JWT, CSRF, TOTP ve servis kimliği sözleşmesi.
- **D-10 / Mimari:** Olay aktarımı için ilk etap outbox + iç HTTP mi, broker mı? Broker repository'de bulunmuyor.
- **D-11 / Veri + operasyon:** Saklama, anonimleştirme, yedek, RPO/RTO ve hacim hedefleri.
- **D-12 / Raporlama:** Gün sonu mutfak sayılarında gün içinde READY olan mı, gün sonunda READY kalan mı? Öneri: günün kabul kohortunda anlık durum + ayrı durum olayı sayacı.
- **D-13 / Veri + ekip:** Auth migration aralığı kaynak listede yok; mevcut aralıkların dolması halinde genişletme politikası belirlenmeli.
- **D-14 / QR + güvenlik — Karar ve gelecek kurtarma:** Normal rotate mevcut main/guest'leri kapatmaz. ADR K12'de ayrı ADMIN force-rotate/oturum sonlandırma seçildi: açık sipariş/UNKNOWN uyarısı, zorunlu gerekçe ve ikinci onay; borç/audit/uzlaştırma korunur. Force API/paneli ve kurtarma engeli henüz uygulanmadı. QR yeniden indirme için mevcut digest + şifreli payload korunur.
- **D-15 / Ürün — MVP ödeme kapsamı kararlaştırıldı:** [ADR-QR-001 K4](decisions/ADR-QR-001-masa-kapanisi-ve-siparis-koordinasyonu.md) uyarınca müşteri yalnız kendi guest siparişlerini öder; ortak hesap/başkası adına ödeme MVP dışında. Başka guest siparişlerini görme veya oturum sonrası ödeme erişimi bu kararla genişletilmedi; diğer D-15 başlıkları açık kalır.

## 7. Önerilen geliştirme sırası

1. Auth/tenant/servis sözleşmeleri, kaynak sahipliği, açık ürün kararları ve ilk Flyway tasarımı.
2. Admin restoran ayarları; QR masa/ana/guest oturumu; Menu kategori/ürün/seçenek ve kalıcı erişilebilirlik.
3. Order fiyat snapshot'ı, sepet/sipariş, idempotency; Kitchen sipariş kuyruğu/durum; Waiter çağrı/teslim; temel bildirim.
4. **Kitchen V1 tamamlanması:** Zorunlu iptal nedeni/Order onayı, manuel fire ve gün sonu raporu. Bunlar ertelenirse Kitchen V1 tamamlandı denmez.
5. Yetkili restoranda ödeme, kapanış, temel rapor ve D-03 sonrası değerlendirme. Demo yalnız izole test verisiyle.
6. D-01 sonrası gerçek online ödeme ve uzlaştırma; opsiyonel ödeme türleri ve Wi-Fi ayrı faz.

Bu sıra teslim önerisidir. Kaynağın zorunlu Kitchen V1 kapsamını yeniden tanımlamaz.

## 8. Uçtan uca doğrulama planı

1. Geçerli/pasif/yenilenmiş QR, 15 dakika işlemsizlik, aynı masaya eşzamanlı ilk tarama ve tenant/masa saldırısı.
2. Ürün fiyatı/aktifliği değişirken sepet onayı; aynı sipariş isteğinin tekrarı ve aynı anahtarla farklı gövde.
3. İki guest aynı masadan farklı sipariş; ayrı orderId ve yalnız kendi ayrıntısına erişim.
4. WAITING → READY reddi, PREPARING/READY zamanları, yinelenen hazır olayı ve garson tesliminden sonra Kitchen READY kalması.
5. Mutfak iptal talebinin Order onayına kadar kesinleşmemesi; iptal/servis/ödeme yarışları.
6. Kapalı ürünün restart sonrası kapalı kalması; fire neden/adet filtreleri ve gün sonu kırılımları.
7. Aynı/ters sırada/yanlış imzalı callback; belirsiz timeout; gerçek ve demo tahsilat ayrımı.
8. Okundu çağrının açık kalması, iki garson çözüm yarışı, masa kapanırken yeni sipariş yarışı ve temizlik sonrası müsaitlik.
9. Ödenmemiş/başkasına ait siparişe puan reddi, D-03 politikasının uygulanması.
10. Servis kesintisi, outbox yeniden deneme, olay sırası, tekrar tüketim, manuel uzlaştırma ve snapshot yeniden senkronizasyonu.

Bu senaryolar gelecek uygulamanın kabul planıdır; bu incelemede çalıştırılmış sonuçlar değildir.
