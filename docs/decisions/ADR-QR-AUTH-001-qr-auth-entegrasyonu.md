# ADR-QR-AUTH-001 — QR ve Auth entegrasyonu

- **Durum:** ÖNERİ / ORTAK KARAR BEKLİYOR. QR'a ait kapsam kararları aşağıda ayrıca belirtilmiştir; Auth sözleşmesi kabul edilmiş değildir.
- **Tarih:** 1 Kasım 2026.
- **Hazırlayan:** Alper — QR / Payment sorumlusu.
- **Değerlendirmesi beklenen:** Kerem — Auth sorumlusu; ilgili Gateway geliştiricisi ve reviewer.
- **Kapsam:** Personel JWT doğrulaması, güncel oturum kontrolü, servis güveni, personel cookie/CSRF uyumu ve restoran kapsamı.

Bu belge Alper'in önerilerini ve Kerem'e yönelttiği soruları kaydeder. Kerem'in çözüm seçtiği, işi üstlendiği veya reviewer'ın onay verdiği varsayılmaz. Belgenin eklenmesi kod, ENV, migration veya endpoint erişim kuralı değişikliği değildir. Entegrasyon rehberi bu aşamada güncellenmemiştir.

## 1. Bağlam ve incelenen mevcut durum

1 Kasım 2026 tarihli yerel kaynak incelemesi:

| Kaynak | Mevcut davranış |
| --- | --- |
| [StaffJwtTokens](../../backend/services/auth-service/src/main/java/com/restofy/auth/infrastructure/security/StaffJwtTokens.java) | Auth RS256 personel JWT'si üretir. Access audience `restofy-staff-api`; claim'ler arasında `sub`, `sid`, `jti`, `role`, `restaurant_id`, `token_type`, `auth_state` ve zaman alanları bulunur. MFA bekleyen token ayrı tür/audience kullanır. |
| [AuthCookies](../../backend/services/auth-service/src/main/java/com/restofy/auth/infrastructure/security/AuthCookies.java) | Access cookie `restofy_access`, Path `/api/v1`, HttpOnly; refresh ve MFA cookie'leri Path `/api/v1/auth`. SameSite Lax; Secure yapılandırmadan gelir. |
| [StaffSessionService](../../backend/services/auth-service/src/main/java/com/restofy/auth/application/service/StaffSessionService.java) | Auth imzaya ek olarak aktif kullanıcıyı, oturum-kullanıcı bağını, güncel rolü, auth durumunu, iptal/idle/mutlak süreyi denetler. `authenticate` ve `activity` ayrı işlemlerdir. |
| [AuthCsrfTokens](../../backend/services/auth-service/src/main/java/com/restofy/auth/infrastructure/security/AuthCsrfTokens.java) | CSRF kanıtı refresh veya MFA cookie'sine, bunlar yoksa anonim bağlama bağlanır. QR yoluna refresh cookie'si gönderilmediğinden Auth mekanizması doğrudan kopyalanamaz. |
| [AuthSettings](../../backend/services/auth-service/src/main/java/com/restofy/auth/infrastructure/config/AuthSettings.java) | JWT restoran kapsamı mevcut tek `AUTH_RESTAURANT_ID` yapılandırmasına dayanır. Dinamik çoklu restoran üyeliği tamamlanmış kabul edilmez. |
| [QR güvenlik sınırı](../../backend/services/qr-service/src/main/java/com/restofy/qr/infrastructure/security/TableSecurityConfiguration.java) | Sağlık erişimi dışında HTTP iş API'leri denyAll arkasındadır. Gerçek personel JWT/oturum adaptörü henüz yoktur. |

Mevcut Auth controller'larında QR servis kimliğiyle kullanılacak ayrı bir iç oturum doğrulama veya JWKS endpoint'i bulunmadı. Browser `/api/v1/auth/session` endpoint'i mevcut olsa da bu, burada kararlaştırılmış bir servis sözleşmesi değildir. Auth'un private signing key'i QR'a kopyalanmaz; QR Auth veritabanını sorgulamaz veya Auth JPA entity'lerini paylaşmaz.

[ADR-AUTH-001](ADR-AUTH-001-mail-gonderimi.md), parola sıfırlama e-postasının Auth → Mail teslimini ele alır. [ADR-AUTH-002](ADR-AUTH-002-rate-limit-ve-kilitler.md), Auth hız sınırlarını ve kilitleri ele alır. İkisi de aşağıdaki güncel personel oturumu/QR CSRF sözleşmesini sonuçlandırmaz; bu belge onların kararlarını değiştirmez.

## 2. Alper'in kapsam kararları ve önerdiği yaklaşım

- **QR tarafında kararlaştırıldı:** Anonim müşteri/guest kimliğinin sahibi QR'dır. Müşteri oturumu açmak için Auth personel hesabı veya personel JWT'si oluşturulmayacak. Ayrıntılar [ADR-QR-002](ADR-QR-002-security.md) içindedir.
- **QR tarafında kararlaştırıldı:** QR, kendi endpoint'lerinin hız sınırlarını Auth'a çağrı gerektirmeden uygular ve yönetir. Bu, korunan personel endpoint'inin kimlik doğrulama ihtiyacını kaldırmaz.
- **Diğer servislere öneri:** Her servis kendi işlem limitlerinin sahibi olsun. Bu öneri diğer servis sahipleri adına alınmış ortak karar değildir; Gateway'deki ek genel korumaları dışlamaz.
- **Auth için Alper'in önerisi:** JWT doğrulamasına ek olarak güncel oturum kontrolü kullanılsın. Böylece Auth'un iptal, aktiflik, güncel rol ve idle kuralları QR'da da korunabilsin. Çözüm yöntemi Q1–Q9 ile Kerem'e sorulmaktadır; henüz seçilmedi.
- **Yetki sınırı:** Auth güncel personel kimliğini/rolünü sağlar; QR kendi işlemi için rol, restoran ve hedef masa/oturum sahipliğini doğrular. Servis kimliği, personel JWT'si ve guest tokenı birbirinin yerine geçmez.

## 3. Kerem ile yanıtlanacak sorular

### Q1 — QR, Auth'a hangi kanıtla başvuracak?

**Soru:** QR'ın elindeki personel access JWT'si ve oturum kimliği hangi sürümlü istekle kontrol edilecek? Auth tokenı yeniden mi doğrulayacak; hangi alanlar dönecek?

**Alper'in önerisi:** QR önce JWT'nin biçimini, imzasını ve zorunlu claim'lerini doğrulasın. Güncel oturum kontrolü yalnız kimliği doğrulanmış iç servis üzerinden yapılsın; istemcinin serbest `userId`, `sid`, rol veya restoran beyanı yeterli sayılmasın. QR refresh token istemesin ve Auth DB'sine erişmesin.

**Kerem'den beklenen:** Uygulanabilir kontrol yöntemi, minimum kanıt/yanıt alanları, endpoint gerekiyorsa yolu ve istek/yanıt sözleşmesi. Ham JWT/cookie loga yazılmayacak. **Yanıt / ortak karar: Bekliyor.**

### Q2 — Auth, QR servisinin kimliğini nasıl doğrulayacak?

**Soru:** İç doğrulama isteğinin gerçekten QR'dan geldiği nasıl kanıtlanacak? Yetki hangi doğrulama işlemleriyle sınırlanacak; kimlik bilgisi nasıl yenilenecek?

**Alper'in önerisi:** İç ağda olmak veya `X-Service-Name` gibi bir header göndermek yeterli olmasın. Servis kimliği ile doğrulanan personel oturumu ayrı denetlensin. Sabit credential koda gömülmesin; Auth private key'i veya CSRF sırrı paylaşılmasın.

**Kerem'den beklenen:** Servis güveni ve kimlik bilgisinin dağıtım/iptal/yenileme modeli. Yeni ENV veya sır gerekiyorsa uygulama öncesi ayrıca açık onay alınacak. **Yanıt / ortak karar: Bekliyor.**

### Q3 — Hangi oturum, kullanıcı, rol ve restoran bilgileri doğrulanacak?

**Soru:** Güncel kullanıcı aktifliği, oturum sahipliği, revocation, idle/mutlak süre, MFA/auth durumu, rol ve restoran kapsamı hangi alanlarla doğrulanacak?

**Alper'in önerisi:** Sonuç kontrol edilen `sub`/`sid` ve restoran kapsamına açıkça bağlı olsun; başka oturumun sonucu yeniden kullanılmasın. QR yalnız access + AUTHENTICATED personel durumunu kabul etsin. MFA_PENDING, refresh ve guest tokenları personel erişimi açmasın. Auth'tan kimlik doğrulanması QR kaynak yetkilendirmesinin yerine geçmesin.

**Kerem'den beklenen:** Yetkili alanlar, tutarlılık/güncellik güvencesi ve rol değişikliği davranışı. **Yanıt / ortak karar: Bekliyor.**

### Q4 — Geçersiz oturumla Auth kesintisi nasıl ayrılacak?

**Soru:** İptal/geçersizlik, yetersiz personel yetkisi, timeout ve servis kimliği/yapılandırma hatası nasıl ayrılacak?

**Alper'in önerisi:** Kesin geçersiz personel oturumu 401; geçerli kimlikle yasak işlem 403; doğrulama bağımlılığı kullanılamıyorsa erişim açmadan uygun 503 üretilecek şekilde eşleme tanımlansın. Auth'un QR servis credential'ını reddetmesi, personelin oturumunun geçersizliğini kanıtlamaz; upstream statüsü körlemesine kullanıcıya aktarılmasın. Kesinti otomatik logout veya olumlu doğrulama sayılmasın.

**Kerem'den beklenen:** Kararlı hata kodları, timeout ve güvenli tekrar davranışı. Uzak çağrılar QR DB transaction'ı/kilidi altında yapılmasın; mevcut timeout sözleşmesi gözetilsin. **Yanıt / ortak karar: Bekliyor.**

### Q5 — Sonuç önbelleğe alınacak mı; iptal gecikmesi ne olacak?

**Soru:** Logout, pasifleştirme, rol değişikliği ve idle dolumunun QR'a yansımasında kabul edilen gecikme nedir? Önbellek varsa TTL ve invalidation yöntemi ne olacak?

**Alper'in önerisi:** İlk uygulamada olumlu güncel oturum sonucunu cache'lememek değerlendirilsin. Cache seçilirse azami iptal gecikmesi açıkça kabul edilsin; kaynak süreleri aşılmasın ve Auth kesintisinde süresi geçmiş sonuç kullanılmasın. Yalnız offline JWT kontrolü tercih edilecekse token bitene kadar sürebilecek erişim ayrıca karara bağlansın.

Bu öneri devam eden isteği atomik biçimde geri alma veya anlık küresel iptal garantisi vermez; istek sürerken değişen yetkilerin etkisi de tanımlanmalıdır. **Yanıt / ortak karar: Bekliyor.**

### Q6 — Kontrol personelin idle süresini yenileyecek mi?

**Soru:** QR'ın doğrulama çağrısı salt kontrol mü olacak? Gerçek personel aktivitesi Auth'a hangi ayrı akışla bildirilecek?

**Alper'in önerisi:** Doğrulama idle süresini yenilemesin. Okuma/polling/arka plan istekleri oturumu sınırsız açık tutmasın. Anlamlı personel aktivitesinin hangi işlemlerle bildirileceği ayrıca belirlensin; guest'in 15 dakikalık aktivitesiyle personel oturumu karıştırılmasın.

**Kerem'den beklenen:** Salt doğrulama ve aktivite arasındaki sözleşme. **Yanıt / ortak karar: Bekliyor.**

### Q7 — JWT doğrulama ve public key dağıtımı nasıl yapılacak?

**Soru:** QR'a güvenilir public key nasıl sağlanacak; anahtar değişimi ve geçiş süresi nasıl yönetilecek? Issuer, audience, algoritma, token türü, zorunlu claim ve saat toleransı ne olacak?

**Alper'in önerisi:** Mevcut RS256/access sözleşmesi esas alınarak doğrulama allowlist'i tanımlansın. QR token imzalamasın. JWKS veya başka otomatik dağıtım varmış gibi davranılmasın; yeni bağımlılık/ENV/endpoint seçimi ayrıca onaylansın.

**Kerem'den beklenen:** Public key dağıtımı, rotation/iptal ve sürümlenmiş JWT sözleşmesi. **Yanıt / ortak karar: Bekliyor.**

### Q8 — Personel cookie ve CSRF sözleşmesi nasıl uyumlanacak?

**Soru:** `/api/v1` kapsamındaki access cookie'siyle gelen QR yazma isteği hangi CSRF kanıtıyla doğrulanacak? Cookie/header isimleri, bootstrap ve oturum değişimindeki yenileme nasıl işleyecek?

**Alper'in önerisi:** Auth'un refresh/MFA bağlamına dayanan CSRF kodu QR'a aynen kopyalanmasın. QR'a uygun, doğrulanmış personel oturumuna bağlı ve Auth cookie'sini ezmeyen CSRF çözümü değerlendirilsin. Refresh cookie'sinin yolu sırf bunun için genişletilmesin; Auth sırrı paylaşılmasın. Origin/CORS açık allowlist ile ele alınsın; CORS veya SameSite tek başına CSRF doğrulaması yerine konmasın.

**Kerem'den beklenen:** Ortak veya servise özel çözüm tercihi, browser akışı ve sahiplik. Personel ve guest CSRF bağlamları birbirinin yerine kabul edilmeyecek. **Yanıt / ortak karar: Bekliyor.**

### Q9 — Mevcut ve gelecekteki restoran kapsamı ne olacak?

**Soru:** Mevcut tek restoran yapılandırmasında doğrulanacak kapsam nedir? İleride çoklu üyelik/restoran seçimi nasıl temsil edilecek?

**Alper'in önerisi:** Bugünkü yapılandırmanın sınırı açıkça yazılsın. QR JWT/doğrulama sonucundaki yetkili restoranı kendi kaynak restoranıyla eşleştirsin; request parametresini veya serbest header'ı üyelik kanıtı saymasın. Çoklu üyelik tamamlanmadan varmış gibi belgelenmesin.

**Kerem'den beklenen:** MVP restoran kapsamı ve gelecekte sözleşmenin değiştirilme yöntemi. **Yanıt / ortak karar: Bekliyor.**

## 4. Servise özel hız sınırı ve ortak altyapı soruları

QR limit politikası, sayaçları ve aşım kararı QR sorumluluğundadır. Rate limit uygulamak için Auth'un çevrimiçi olması gerekmez. Bu karar Auth'un kendi limiter tasarımını değiştirmez; Gateway'deki ek kaba trafik korumasını da engellemez.

- Servise özel sahiplik, instance başına bağımsız sayaç kararı değildir. Çoklu QR instance'ında toplam limit tutarlılığı ayrıca tasarlanacak.
- Auth veya diğer servislerin DB'si kullanılmayacak. QR'ın kendi PostgreSQL'i bir seçenektir; Redis veya başka yeni altyapı seçilmiş değildir.
- Gerçek istemci adresi yalnız güvenilir Gateway/proxy zincirinden alınmalı. Güvenilir proxy listesi, dışarıdan gelen forwarding header'larının ele alınması ve doğrudan servise erişim sınırı ortak çalışmadır; bu belge ayar değiştirmez.
- Sayısal limitler, kapasite dolumu, sayaç deposu kesintisi ve 429/Retry-After davranışı henüz seçilmedi. Restoran Wi-Fi'si gibi ortak IP durumları gözetilecek.

## 5. Karar ve sorumluluk kaydı

| Konu | Durum / sorumlu |
| --- | --- |
| Guest kimliğinin QR'da kalması | Alper tarafından kabul edildi; ADR-QR-002 hedefi |
| QR limitlerinin QR tarafından Auth çağrısı olmadan yönetilmesi | Alper tarafından kabul edildi; henüz uygulanmadı |
| Q1–Q9 personel/servis/CSRF sözleşmesi | Kerem ve Alper'in ortak yanıtını bekliyor |
| Diğer servislerde limiter sahipliği | Alper'in önerisi; servis sahiplerinin ortak onayı varsayılmıyor |
| QR RBAC matrisi | Ayrı kesinleştirilecek; önceki ADMIN/WAITER önerisi bu belgeyle onaylanmış sayılmaz |
| Auth değişiklikleri ve teslim PR'ları | Kerem'in sahiplik teyidi ve ortak karardan sonra belirlenecek |

Her sorunun yanıtına tercih, gerekçe, reddedilen alternatif, sahip, tarih ve ilgili PR/yorum bağlantısı eklenecek. ADR numarası çalışma sırası veya uygulama tamamlanması anlamına gelmez.

## 6. İnceleme ve uygulama sırası

1. Alper belgeyi küçük bir dokümantasyon PR'ında paylaşır; Kerem'den değerlendirme, reviewer'dan inceleme ister.
2. Kerem Q1–Q9'a yanıt verir; üstlendiği Auth işlerini kendi hesabından teyit eder. Ortak karar ve onay kayıtları belgeye işlenir.
3. QR/Auth geliştirmeleri incelenebilir küçük PR'lara ayrılır. İlk QR açılışı için masa listeleme/detay önerisi ayrıca onaylanır; tüm API'ler topluca açılmaz.
4. İmza/claim, iptal/rol/idle, tenant/nesne sahipliği, sahte servis kimliği, kesinti/cache ve CSRF negatif testleri ile gerçek Auth–QR entegrasyonu doğrulanır.

**UYGULANMADI:** Yeni JWT/güncel oturum/CSRF/servis adaptörü, yeni endpoint veya ENV. **DOĞRULANMADI:** Gerçek Auth–QR E2E ve bu önerilere ait testler. Bu çalışma yalnız dokümantasyondur; test/pre-push/Sonar çalıştırıldığı iddia edilmez. Commit/push, PR, yorum, review ve merge geliştirici tarafından yapılır.

İlgili kaynaklar: [SECURITY](../SECURITY.md), [mevcut QR lifecycle ADR'si](ADR-QR-001-masa-kapanisi-ve-siparis-koordinasyonu.md), [Spring Security CSRF](https://docs.spring.io/spring-security/reference/features/exploits/csrf.html), [token iptali uygulama notu](https://www.rfc-editor.org/rfc/rfc7009.html#section-3).
