# Restofy — OPS Kullanım Rehberi

## Ortam seçimi

`.env.dev` ve `.env.prod`, her ortamın tüm ayarlarını içerir. `.env`, seçilen profilin aktif kopyasıdır. Ayarları profil dosyalarında düzenleyin; `.env` dosyasına elle yazmayın.

Proje kökünde Windows komutları:

```powershell
.\ops\env.cmd status
.\ops\env.cmd use dev
.\ops\env.cmd use prod
```

Örnek projedeki PowerShell komutları da desteklenir:

```powershell
.\ops\env.ps1 status
.\ops\env.ps1 use dev
.\ops\env.ps1 use prod
```

macOS / Linux:

```sh
sh ./ops/env.sh status
sh ./ops/env.sh use dev
sh ./ops/env.sh use prod
```

Bu komutlar profil dosyasını `.env` içine kopyalar ve ilk satıra `# RESTOFY-ACTIVE-PROFILE=dev` veya `prod` yazar. Docker başlatılmaz veya durdurulmaz. Seçim sonraki terminal oturumlarında da korunur. `status`, aktif profili ve `.env` ile kaynak dosyanın eşleşip eşleşmediğini gösterir.

## Docker komutları

Profil seçtikten sonra proje kökünde normal Docker Compose komutlarını kullanın:

```powershell
.\ops\env.cmd use dev
docker compose up --build -d
docker compose ps
docker compose logs --tail 100 -f
docker compose down
```

PROD için önce `use prod` çalıştırın. Düz Compose, aktif `.env` dosyasını kullanır. Ortam değiştirmek çalışan container'ları yeniden yapılandırmaz; yeni ayarları uygulamak için `docker compose up --build -d` çalıştırın. DEV ve PROD farklı Compose proje adlarını kullanır. Profil değiştirmeden önce eski ortamı `docker compose down` ile durdurun; iki ortam aynı host portlarını kullanabilir. `down` komutu da o sırada aktif olan projeye uygulanır.

```powershell
docker compose down -v
```

`down -v`, aktif projenin PostgreSQL volume'ünü ve verilerini siler. Verileri korumak için `down` kullanın. Sonraki `up`, yeni volume'ü ve ilk veritabanlarını oluşturur. SonarQube ayrı Compose projesindedir.

İlk veritabanı kurulumu proje kökündeki `postgres/init-databases.sh` betiğiyle yapılır. Çalışma şekli ve Docker bağlantısı için [PostgreSQL rehberine](../postgres/postgres.md) bakın.

## Ayarları güncelleme ve yedekleme

İlgili `.env.dev` veya `.env.prod` dosyasını düzenleyin, sonra profili yeniden seçin. `.env` kaynak profilinden farklıysa betik üzerine yazmayı reddeder. Bu kontrol, kaynak dosyayı güncellediğinizde de devreye girer; eski aktif kopyayı yedekleyerek devam edin:

```powershell
.\ops\env.cmd use dev -Force
```

```sh
sh ./ops/env.sh use dev --force
```

`-Force` / `--force`, mevcut `.env` dosyasını önce `.env.bak` olarak yedekler. Bir sonraki zorunlu geçiş bu yedeği yeniler. Eksik profil dosyası olduğunda mevcut `.env` korunur.

## İlk kurulum

`.env.example`, şifre içermeyen tam DEV şablonudur. Kendi profil dosyalarınızı hazırlayın:

```powershell
Copy-Item .env.example .env.dev
Copy-Item .env.example .env.prod
```

DEV dosyasındaki yerel kimlik bilgilerini doldurun. PROD dosyasında en az `APP_ENV=prod`, `SPRING_PROFILES_ACTIVE=prod`, `COMPOSE_PROJECT_NAME=restofy-prod`, `SWAGGER_ENABLED=false`, gerçek URL/CORS değerleri ve production kimlik bilgilerini düzenleyin. Ardından `use dev` çalıştırın. Önceden başka bir yöntemle oluşturulmuş `.env` varsa ilk geçişte `-Force` kullanın.

QR'ın kalıcı şifreleme anahtarının ilk kurulumu için `node ops/qr/qr-key.mjs init dev` kullanılabilir. Mevcut anahtarı yenilemez; profil/aktif kopya ayrışmışsa üzerine yazmaz. Kullanım ve hata durumları [QR anahtar üretim rehberinde](../docs/qr/qr-key-uretimi.md) açıklanır. Gerçek anahtarı kaybettiğinizde yeniden üretmek eski QR verisini kurtarmaz.

`.env`, `.env.dev`, `.env.prod` ve yedekleri Git tarafından ignore edilir; gerçek şifreleri paylaşmayın. Her iki ortamda ortak olan bir ayarı değiştirirken iki profil dosyasını da güncelleyin.

## Kalite kontrolü

`pre-push/pre-push.cmd` veya `sh pre-push/pre-push.sh`, servis seçimi ve tam kalite kontrolü içindir. Docker yapılandırma kontrolü seçilen `.env.dev` / `.env.prod` dosyasını doğrudan kullanır; aktif profili değiştirmez. **SAFE TO COMMIT** / **NOT SAFE TO COMMIT** sonucu yalnızca tam kalite kontrolüne aittir.

[Yerel kalite kontrolü](../pre-push/pre-push.md) ve [SonarQube kullanımı](../sonarqube/sonarqube.md) bağımsızdır. Kişisel Sonar token'ını ilgili profil dosyasında tutabilirsiniz.
