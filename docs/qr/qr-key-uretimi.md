# QR şifreleme anahtarı — üretim, kontrol ve hata durumları

QR Service, tekrar görüntülenip indirilecek QR kimliğini AES-GCM ile şifreli saklar. `QR_PAYLOAD_ENCRYPTION_KEY`, **32 byte'ın standart Base64 karşılığı** olan kalıcı anahtardır. Guest tokenı, personel JWT anahtarı veya public entry URL değildir.

Yardımcı: [`ops/qr/qr-key.mjs`](../../ops/qr/qr-key.mjs). Node.js'in yerleşik `crypto.randomBytes` fonksiyonunu kullanır; ek paket gerektirmez. Container başlangıcına veya Git hook'una bağlanmaz; yalnız geliştirici açıkça çalıştırır.

## İlk kurulum — DEV

1. Henüz yoksa `.env.example` dosyasından `.env.dev` oluşturun; mevcut profilin üzerine şablon kopyalamayın.
2. PostgreSQL ve kullanacağınız Auth/Mail gibi servislerin zorunlu ayarlarını kendi profil dosyanızda doldurun. QR yardımcısı diğer servislerin sırlarını üretmez veya değiştirmez.
3. **Yeni/boş QR veritabanının ilk kurulumunda**, repository kökünden çalıştırın:

```powershell
node ops/qr/qr-key.mjs init dev
node ops/qr/qr-key.mjs check dev
```

Aynı komutlar macOS/Linux'ta da kullanılır. `init` eksik veya boş QR alanını doldurur; alan yoksa ekler. Var olan geçerli anahtarı korur. Anahtar ve diğer ENV değerleri terminale yazılmaz. `.env.example` boş kalır.

**Mevcut QR verisi için kaybolan anahtarı bu komutla yeniden üretmeyin.** Yardımcı veritabanını sorgulamaz; ilk kurulum ile anahtar kaybını ayırt edemez. Yeni anahtar eski şifreli QR içeriğini çözmez. Önceki anahtar yedekten veya yetkili secret kaynağından geri yüklenmelidir. Anahtar rotasyonu ve verinin yeniden şifrelenmesi bu aracın kapsamı değildir.

## `.env.dev`, `.env.prod` ve aktif `.env`

Kalıcı ayarlar profil dosyalarındadır. `.env`, [`ops/env`](../../ops/ops.md) tarafından işaretlenen aktif kopyadır.

| Başlangıç durumu | `init dev` davranışı |
| --- | --- |
| `.env.dev` yok | Hata; gerçek dosya veya varsayılan şifre oluşturmaz |
| Anahtar boş/eksik, aktif `.env` yok | `.env.dev` için bir kez üretir ve DEV işaretli aktif `.env` kopyasını oluşturur |
| DEV aktif, `.env` kaynak profille eşleşiyor | Gerekiyorsa anahtarı üretir; profil ve aktif kopyayı birlikte günceller |
| PROD aktif | Yalnız DEV profilini hazırlar; aktif PROD dosyasına veya anahtarına dokunmaz |
| `.env` işaretsiz veya aktif DEV profiliyle farklı | Hiçbir ENV dosyasının üzerine yazmadan durur; önce yerel farklar uzlaştırılmalıdır |
| Geçerli anahtar zaten var | Değeri korur; otomatik yenileme yapmaz |
| Geçersiz dolu anahtar veya tekrarlı QR satırı var | Hata; sessizce yeni anahtar üretmez |

Başka profil aktifse sonraki profil seçimi geliştiriciye bırakılır:

```powershell
.\ops\env.cmd use dev
.\ops\env.cmd status
```

```sh
sh ./ops/env.sh use dev
sh ./ops/env.sh status
```

`check dev` **salt okunurdur**: anahtar üretmez, `.env` oluşturmaz, profil seçmez. Profildeki formatı ve varsa aynı aktif profilin eşleşmesini kontrol eder. `Active ENV: missing` veya `other-profile`, profilin hazırlanmış olduğunu fakat düz Compose'un henüz bu profili kullanmadığını belirtir. Formatın geçerli olması eski veritabanını açabildiğinin kanıtı değildir.

## PROD ve birden fazla instance

PROD profilini kendiniz hazırlayıp `APP_ENV=prod` değerini doğrulayın. Mevcut üretim anahtarını yetkili secret/yedek kaynağından aktarın; kayıp anahtarı yenisiyle değiştirmeyin. Yalnız ilk kurulum için açıkça kullanılabilir:

```powershell
node ops/qr/qr-key.mjs init prod
node ops/qr/qr-key.mjs check prod
```

Komut üretim veritabanına bağlanmaz, servisleri başlatmaz veya yeniden başlatmaz. DEV ve PROD farklı anahtarlar kullanmalıdır. **Aynı QR veritabanına bağlanan instance'lar aynı kalıcı anahtarı kullanmalıdır**; her instance ayrı anahtar üretmez.

ENV dosyaları ve yedekleri Git dışında kalmalıdır. Yardımcı geçici dosyaları kısıtlı dosya modu ile oluşturur; dosya erişim/secret yönetimi ve güvenli yedekleme ortam sahibinin sorumluluğudur. Başka bir kullanıcıyla gerçek ENV dosyası veya anahtar paylaşmak yerine kendi izole geliştirme ortamınızı kurun.

## Compose ve QR başlangıcı arasındaki fark

Compose aktarımı `${QR_PAYLOAD_ENCRYPTION_KEY:-}` kullanır. QR anahtarı yoksa **Compose dosyasının ayrıştırılması yalnız bu nedenle durmaz**; başka bir servis seçilebilir. Auth/Mail/PostgreSQL gibi servislerin kendi zorunlu ayarları yine gereklidir.

Bu, QR'ın anahtarsız çalıştığı anlamına gelmez. QR uygulaması başlangıçta `QrPayloadCodec` ile boş veya yanlış uzunluktaki anahtarı reddeder:

```text
QR payload encryption requires a Base64-encoded 32-byte key.
```

Gerçek anahtar hata mesajına eklenmez. Başlamayan QR container'ında tekrar başlatma politikası yeni anahtar üretmez; geliştirici doğru kalıcı değeri sağlamalıdır. ENV düzeltmesi çalışan container'a kendiliğinden aktarılmaz; doğru profili seçtikten sonra yalnız gerekli servis yeniden oluşturulur:

```powershell
docker compose up --build -d qr-service
```

Bu komutun bağlı PostgreSQL servisini de başlatabileceğini dikkate alın. Volume silme, Flyway clean/repair veya veritabanı sıfırlama anahtar sorununun çözümü değildir.

## Hata kodları ve yapılacak işlem

Başarı exit code `0`, hata exit code `1` döner. Mesajlar anahtar değerini içermez.

| Kod | Anlam / yapılacak işlem |
| --- | --- |
| `QR_KEY_USAGE` | `init dev`, `check dev`, `init prod` veya `check prod` kullanın. Otomatik rotation/force komutu yoktur. |
| `QR_KEY_PROFILE_MISSING` | Seçilen profil dosyasını kendi yerel ayarlarınızla hazırlayın. |
| `QR_KEY_PROFILE_MISMATCH` | Dosya adının profili ile `APP_ENV` eşleşmeli; yanlış ortama yazmayın. |
| `QR_KEY_MISSING` | `check` boş/eksik alan gördü. İlk kurulumsa `init`; mevcut veri varsa eski anahtarı geri yükleyin. |
| `QR_KEY_INVALID` | Standart, kanonik Base64 ve tam 32 byte gerekir. Dolu değer korunur; doğru eski kaynağı kontrol edin. |
| `QR_KEY_DUPLICATE` | Birden çok aynı anahtar/APP_ENV tanımı var. Değerleri paylaşmadan yerelde uzlaştırın. |
| `QR_KEY_ACTIVE_UNMANAGED` | Aktif `.env` profil işareti taşımıyor. Değerleri profil dosyasına kayıpsız taşıyıp mevcut ops/env akışıyla uzlaştırın. |
| `QR_KEY_ENV_DIVERGED` | Aktif kopya ve kaynak farklı. Özellikle eski doğru QR anahtarının hangi dosyada olduğunu yerelde doğrulamadan üzerine yazmayın. |
| `QR_KEY_SHELL_OVERRIDE` | Shell'deki aynı adlı değişken Compose'ta dosyayı gölgeleyebilir. Override'ı kontrollü kaldırıp dosya akışını kullanın; yardımcı shell değerini dosyaya kopyalamaz. |
| `QR_KEY_BUSY` | Başka init işlemi olabilir. Çalışan işlem olmadığını doğrulamadan kilit dosyasını kaldırmayın. |
| `QR_KEY_LOCK_CLEANUP` | İşlem kilidi kaldırılamadı. ENV dosyalarının durumunu ve çalışan işlemleri kontrol edip dosya erişimini düzeltin. |
| `QR_KEY_UNSAFE_FILE` | Profil/aktif dosya normal dosya olmalı; symbolic/hard link üzerinden yazma reddedilir. |
| `QR_KEY_FILE_CHANGED` | Dosya işlem sırasında değişti. Yerel farkları yeniden kontrol edin. |
| `QR_KEY_IO` | Dosya erişimi/yazma başarısız. Profilde anahtar yazılmış, aktif kopya yazılamamış olabilir; yeni anahtar üretmeden mevcut dosyaları uzlaştırın. |

Aktif kopya farklarında `ops/env use ... -Force` / `--force` mevcut `.env` dosyasını `.env.bak` olarak yedekleyebilir. **Önce farkları ve doğru QR anahtarını kontrol edin**; force birleştirme veya anahtar kurtarma yapmaz. Ayrıntı: [OPS rehberi](../../ops/ops.md).

## Testler

```powershell
node --test ops/qr/qr-key.test.mjs
node --test ops/qr/qr-compose.test.mjs
```

İlk komut Node.js gerektirir ve geçici dosyalarda üretim, tekrar çalıştırma, aktif profil eşitleme, hatalar ve gizliliği doğrular. İkinci komut Docker Compose v2 CLI gerektirir; gerçek Compose dosyasını yalnız sahte ENV değerleriyle ayrıştırır. Docker daemon/container/gerçek ENV veya veritabanı kullanmaz. QR uygulamasının başlangıç doğrulaması `QrPayloadCodecTests` içinde ayrıca test edilir.
