# ADR-QR-002 — QR müşteri oturumu, cookie ve hız sınırı güvenliği

- **Durum:** Alper tarafından HEDEF KARARLAR kabul edildi; cookie/CSRF/rate limit uygulaması yapılmadı. Açık teknik ve ürün ayrıntıları aşağıda işaretlidir.
- **Tarih:** 1 Kasım 2026.
- **Hazırlayan ve QR karar sahibi:** Alper — QR / Payment sorumlusu.
- **Etkilenen taraflar:** QR, frontend ve guest kimliğini kullanacak Order/Waiter/Payment geliştiricileri. Diğer servis sahiplerinin taşıma sözleşmesine onay verdiği varsayılmaz.
- **İlgili personel çalışması:** [ADR-QR-AUTH-001](ADR-QR-AUTH-001-qr-auth-entegrasyonu.md).

Bu kayıt, Alper'in müşteri güvenliğini QR'da tutma, browser tokenını JSON yerine HttpOnly cookie ile taşıma ve QR hız sınırlarını servis içinde yönetme kararını belgeler. Uygulanmış özellik veya açılmış API değildir. Kod, ENV, migration, güvenlik filtreleri ve entegrasyon rehberi bu belgeyle değiştirilmemiştir.

## 1. Mevcut durum

| Kaynak | İncelenen davranış |
| --- | --- |
| [GuestTokenCodec](../../backend/services/qr-service/src/main/java/com/restofy/qr/infrastructure/security/GuestTokenCodec.java) | 32 byte SecureRandom ile Base64URL guest tokenı üretir; biçim kontrolü ve domain ayrımıyla SHA-256 digest hesaplar. |
| [Oturum açma controller'ı](../../backend/services/qr-service/src/main/java/com/restofy/qr/api/controller/TableSessionController.java) / [yanıt DTO'su](../../backend/services/qr-service/src/main/java/com/restofy/qr/api/dto/response/OpenedGuestSessionResponse.java) | Her kabul edilen tarama ayrı guest oluşturur; `guestToken` ilk JSON yanıtında döner. Yanıt no-store kullanır. |
| [GuestActivityRequest](../../backend/services/qr-service/src/main/java/com/restofy/qr/api/dto/request/GuestActivityRequest.java) | Mevcut istek gövdesi token ve beklenen restoran/main/guest kimliklerini içerir. Cookie modeline göre henüz uyarlanmamıştır. |
| [GuestSessionValidationService](../../backend/services/qr-service/src/main/java/com/restofy/qr/application/service/GuestSessionValidationService.java) | Tokenı gerçek guest/main bağlamıyla, güncel kullanılabilirlik ve Order kaynak sınırıyla doğrular. Doğrulama aktivite yazmaz. |
| [HTTP güvenliği](../../backend/services/qr-service/src/main/java/com/restofy/qr/infrastructure/security/TableSecurityConfiguration.java) | Sağlık dışında iş endpoint'leri denyAll arkasındadır; gerçek browser guest cookie/CSRF/rate limit entegrasyonu yoktur. |

Rastgele token ve sunucudaki hash saklama mevcut temeldir. Yeni karar bu temeli koruyarak browser taşımasını değiştirmektir. QR credential'ı ile guest tokenı farklı amaçlara sahiptir; biri diğerinin yerine geçmez.

## 2. Alper tarafından kabul edilen hedef kararlar

### K1 — Anonim müşteri kimliği QR sorumluluğunda kalacak

QR okutularak açılan guest için Auth personel hesabı/JWT'si üretilmeyecek. Auth'a müşteri giriş/doğrulama çağrısı zorunlu kılınmayacak. Guest, personel rolü veya başka guest'in sipariş/ödeme yetkisini kazanmaz.

Bu karar QR'ın bütün işlevlerinin diğer servislerden bağımsız olduğu anlamına gelmez: sipariş koruması, güvenli iptal ve kapanış için [ADR-QR-001](ADR-QR-001-masa-kapanisi-ve-siparis-koordinasyonu.md) kapsamındaki Order/Payment sınırları korunur.

### K2 — Güçlü token ve hash saklama korunacak

QR mevcut güçlü, rastgele guest tokenını üretmeye devam edecek; veritabanında ham token yerine digest saklanacak. Token, kimliği ve kapsamı doğrulanması gereken bir erişim kanıtıdır. Rastgelelik tokenın çalınmasını veya tekrar kullanılmasını kendiliğinden engellemez.

### K3 — Browser'a ham token JSON yerine Set-Cookie ile taşınacak

Browser oturum açma yanıtındaki ham `guestToken` kaldırılacak; sunucu tokenı `Set-Cookie` ile verecek. İş gereği gerekli, hassas olmayan oturum bilgileri JSON'da kalabilir; bu kimlik alanları tek başına yetki kazandırmayacak.

Browser aktivite isteğinin tokenı gövdeden alma davranışı da cookie modeline uyarlanacak. Eski JSON/gövde token yolunu varsayılan bir fallback olarak açık bırakmak kararlaştırılmış değildir. Browser dışındaki iç servis sözleşmesi ayrı tasarlanacak; bu karar Java iş servislerinin ham tokenı güvenli iç bağlamda almasını kendiliğinden yasaklamaz.

### K4 — Cookie güvenlik özellikleri açıkça tanımlanacak

Guest credential cookie'si HttpOnly, production HTTPS üzerinde Secure ve uygun SameSite ile kullanılacak. Cookie gerekli en dar Path/host kapsamıyla sınırlanacak; Auth personel/refresh/MFA/CSRF cookie'leriyle çakışmayacak. Token localStorage, sessionStorage, IndexedDB veya kalıcı JS erişilebilir store'a taşınmayacak.

**Açık ayrıntılar:** Cookie adı, SameSite değeri, Path, Domain verilmemesi/host kapsamı, yaşam süresi ve yenileme biçimi henüz seçilmedi. Yerel HTTP/HTTPS davranışı ayrıca tanımlanacak; production güvenliği development kolaylığı için gevşetilmeyecek. Yeni ENV gerekiyorsa uygulama öncesi açık onay alınacak.

### K5 — Yetki her kullanımda sunucuda doğrulanacak

Cookie bulunması yeterli değildir. QR tokenın gerçek kaydından restoran/masa/main/guest bağını, aktiflikleri, süreleri, kapanış ve sipariş korumasını doğrulayacak. İstemcinin gönderdiği kimlikler yetki kaynağı olmayacak. Başka tenant/guest nesnesine erişim reddedilecek.

Cookie süresi ile sunucu oturumu ayrı kavramlardır. Sunucu kapatılmış veya kullanılamaz oturumu cookie kalsa da reddeder. Cookie ömrünü sabit 15 dakikaya bağlayıp sipariş nedeniyle korunmuş guest'in credential'ını kaybetmek hedef davranış değildir; süre/yenileme politikası bu korumayla birlikte tasarlanacak.

Kapanış sonrası cookie, aynı isim/Path/Domain özellikleriyle temizlenecek şekilde tasarlanır. Ancak bütün telefonlara anında cookie silme yanıtı ulaştırılamaz; güvenlik sunucu tarafındaki geçersizliğe dayanır. Yeni credential üretme, kayıp token ve yeniden tarama politikası uygulama öncesi netleştirilecek.

### K6 — Cookie ile yazma işlemleri CSRF korumalı olacak

Guest aktivitesi ve diğer cookie'li durum değişiklikleri için sunucuda CSRF doğrulaması ve uygun Origin politikası uygulanacak. HttpOnly tokenın JavaScript tarafından okunmasını sınırlar; CSRF'yi veya XSS üzerinden yapılabilecek bütün işlemleri tek başına engellemez. SameSite ve CORS tek başına CSRF doğrulamasının yerine konmayacak.

Yeni guest oluşturan giriş/tarama akışı da başlangıç CSRF/Origin ve kötüye kullanım tasarımına dahil edilecek. Kullanılacak token/header/cookie adı, başlangıç kanıtının alınması ve oturum değişimindeki yenileme henüz seçilmedi. CSRF kanıtının taşınması guest credential'ını JavaScript'e açmayı gerektirmemeli; personel ve guest bağlamları karışmamalı.

### K7 — Hız sınırlarını QR yönetecek

QR tarama/doğrulama, guest oluşturma, aktivite ve kendi personel işlemlerinin limitlerini Auth'a limit kontrol çağrısı yapmadan uygulayacak. Personel endpoint'inin JWT/güncel oturum doğrulaması bundan ayrı kalır. Diğer servisler için aynı sahiplik Alper'in önerisidir; ortak ekip kararı varsayılmaz.

- Tarama için istemci adresi ve doğrulanmış QR/masa kapsamı; aktivite için doğrulanmış guest; personel işlemleri için kullanıcı/restoran kapsamı değerlendirilecek.
- Her kabul edilen tarama yeni guest oluşturduğu için guest oluşturma hızı ve aktif guest kapasitesi ayrıca sınırlandırılacak. Tek başına IP, ortak restoran Wi-Fi'sinde yeterli veya adil değildir.
- Geçersiz girdinin DB/kripto maliyetinden önce ucuz boyut/biçim ve kötüye kullanım kontrolleri değerlendirilecek. İstemcinin serbest restoran/guest beyanı doğrulanmış limit anahtarı sayılmayacak.
- Çoklu QR instance'ında toplam limit tutarlılığı sağlanacak. QR'ın kendi PostgreSQL'i bir seçenek; sayaç depolaması henüz seçilmedi. Auth/başka servis DB'si kullanılmayacak.
- Yalnız güvenilir Gateway/proxy zincirinden gelen istemci adresi kullanılacak. Güvenilir zincir ve forwarding header temizleme ayarı ayrıca ortaklaştırılacak; doğrudan `X-Forwarded-For` kabul edilmeyecek.
- Sayısal limitler, kapasite/eviction davranışı, sayaç deposu kesintisi ve 429/Retry-After sözleşmesi açık. Depo kesintisi sessizce sınırsız erişime dönüşmeyecek; ayrıntılı hata/erişilebilirlik politikası ayrıca seçilecek.

### K8 — Token sızıntısı ve önbellekleme engellenecek

Ham guest tokenı URL/query, log, hata yanıtı, analitik veya olay payload'ına yazılmayacak. Token oluşturan/taşıyan yanıtlar no-store olacak; mevcut oturum açma/aktivite no-store davranışı korunacak. Paylaşımlı proxy/CDN cache başka müşterinin cookie veya oturum verisini sunmamalı.

QR linkinin kopyalanabilmesi fiziksel masada bulunmayı kanıtlamaz. Cookie'ye geçiş bu riski çözmüş sayılmaz; fiziksel varlık için yeni Wi-Fi/konum zorunluluğu bu belgeyle eklenmez.

## 3. Uygulamadan önce çözülecek taşıma ve ürün soruları

| Konu | Yanıtlanacak ayrıntı / etkilenen taraf |
| --- | --- |
| Order/Waiter/Payment'a guest taşıması | JSON token kaldırılınca frontend tokenı okuyup header'a koyamaz. Hangi browser yollarının cookie alacağı, ilgili servisin credential'ı nasıl alıp QR'a doğrulatacağı ve servis kimliği/delegasyonu taraflarla kararlaştırılmalı. Çalışan adapter/BFF varmış gibi davranılmayacak. |
| Farklı masa ve çoklu sekme | Aynı browser kapsamındaki sekmeler cookie'yi paylaşır. Başka QR okutulduğunda mevcut oturum, hesap erişimi ve cookie'nin üzerine yazılması davranışı seçilmeli; aynı anda çoklu masa desteği varsayılmayacak. |
| Tekrarlı tarama ve kayıp yanıt | Mevcut her tarama ayrı guest davranışıyla cookie yenilenmesi, reload ve yanıt kaybında tekrar açma/guest çoğalması birlikte değerlendirilmeli. Otomatik guest birleştirme kararı verilmedi. |
| Credential/cookie ömrü | Siparişli koruma, bağımsız guest idle süresi, browser kapanışı ve kayıp credential davranışıyla uyumlu politika seçilmeli. Geçersiz oturum yenilemeyle diriltilmemeli. |
| İstemci geçişi | JSON yanıtı ve activity gövdesi değişince mevcut frontend ve servis istemcilerinin etkisi incelenmeli; sürümleme/geçiş ve dağıtım sırası tanımlanmalı. |
| Cookie ve CSRF ayrıntıları | İsim, Path/host, SameSite, ömür, bootstrap/yenileme/temizleme ve yerel geliştirme davranışı somutlaştırılmalı. |
| Limit ve proxy ayrıntıları | Sayısal bütçe, sayaç tutarlılığı, kapasite/kesinti ve güvenilir istemci adresi ilgili geliştiricilerle belirlenmeli. |

Bu açık sorular çözülmeden taşıma/güvenlik sözleşmesi tamamlanmış veya bütün müşteri API'leri açılabilir sayılmaz. Aktif guest kapasitesini aşan istekleri reddetmek, mevcut guest'leri rastgele silme yetkisi vermez.

## 4. Korunan iş kuralları ve kapsam sınırı

- Gerçek menü/sepet etkileşimi ilgili guest ve main süresini yeniler; başka guest'i yenilemez. Açık sekme/polling ve salt doğrulama aktivite değildir.
- Kapanmış veya süresi dolup kullanılamaz olmuş guest, gecikmiş bildirimle yeniden açılmaz. Sipariş koruması ve güvenli son iptal sonrası 15 dakika kuralları ADR-QR-001'e göre korunur.
- Guest tokenı sipariş kabulü, Payment tahsilat kanıtı veya personel/servis kimliği yerine geçmez. Order checkout/kapanış koordinasyonu ve guest sahipliği kendi yetkili sınırlarında korunur.
- Yeni public endpoint, tüm API'leri permitAll açma, Auth/Order/Waiter/Payment kod değişikliği, yeni broker veya ortak domain kütüphanesi bu çalışmanın kapsamında değildir.
- QR personel RBAC matrisi ve Auth güncel oturum yöntemi, ADR-QR-AUTH-001 kapsamında ayrıca sonuçlandırılacak.

## 5. Kabul ölçütleri — henüz çalıştırılmadı

| Senaryo | Beklenen davranış |
| --- | --- |
| Yeni guest açma | JSON ve loglarda ham token yok; yalnız uygun Set-Cookie; DB'de digest; yanıt no-store |
| Başka restoran/main/guest veya sahte kimlik alanları | Cookie olsa bile yetkisiz erişim reddi |
| Geçersiz, belirsiz/çift cookie, kapalı veya kullanılamaz guest | Güvenli reddetme; terminal oturumu yenilememe |
| CSRF eksik/yanlış veya yasak Origin | Cookie varlığına rağmen durum değişikliğinin reddi |
| Başlangıç oturum açma istismarı | Kararlaştırılmış başlangıç koruması ve guest oluşturma limitleri uygulanır |
| 15 dakika idle / siparişle korunan guest | Sunucu süre/koruma kuralları korunur; cookie ömrü sipariş korumasını yanlışlıkla kesmez |
| Polling, gecikmiş aktivite, iki telefon | Yapay yenileme/diriltme olmaz; diğer telefonun süresi uzamaz |
| Çoklu sekme/başka masa/yanıt kaybı | Seçilecek açık politikaya uygun; sessizce yanlış guest/masa üzerinden işlem yapılmaz |
| Çoklu QR instance, ortak Wi-Fi, sahte forwarded header | Tutarlı/adil limit ve yalnız güvenilir proxy adresi |
| Sayaç kapasitesi veya deposu kesintisi | Seçilecek kontrollü hata davranışı; sessiz sınırsız erişim yok |
| Order/Waiter/Payment browser akışı | Güvenli taşıma ve gerçek QR doğrulaması; başka guest'in yetkisini kullanamama |
| Kapanış sonrası cookie ve token tekrarı | Cookie temizleme mümkünse uygulanır; eski token sunucuda reddedilir |

## 6. Uygulama ve doğrulama durumu

| Parça | Durum |
| --- | --- |
| Rastgele guest tokenı, digest saklama ve yerel guest doğrulama temeli | MEVCUT — kaynak incelendi; bu çalışmada değiştirilmedi |
| JSON'dan Set-Cookie'ye geçiş, activity uyarlaması, cookie yaşam döngüsü | UYGULANMADI — kabul edilen hedef |
| Guest CSRF/Origin ve QR'a özel hız sınırları | UYGULANMADI — uygulama ayrıntıları açık |
| Gerçek browser ve servisler arası guest taşıma | DOĞRULANMADI — taraflar arası sözleşme ve E2E bekliyor |
| Bu belgedeki kabul testleri, pre-push ve Sonar | ÇALIŞTIRILMADI — yalnız dokümantasyon değişikliği |

Uygulama küçük PR'lara ayrılacak. Cookie geçişi, CSRF, limitler ve gerekli gerçek doğrulamalar tamamlanıp test edilmeden müşteri endpoint'leri açılmayacak. ENV/migration ihtiyacı uygulanacak tasarımdan sonra belirlenir; bu belgeyle bunlarda değişiklik yapılmadı. Entegrasyon rehberi istendiği üzere bu aşamada güncellenmedi. Commit/push, PR/yorum, review ve merge geliştiriciye aittir.

Kaynaklar: [SECURITY](../SECURITY.md) §3–5, §8, §11–12; [Auth limiter ADR'si](ADR-AUTH-002-rate-limit-ve-kilitler.md); [Spring Security CSRF rehberi](https://docs.spring.io/spring-security/reference/features/exploits/csrf.html).
