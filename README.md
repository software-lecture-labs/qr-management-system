![Restofy Banner](assets/git-images/restofy-banner.jpg)

<p align="center">
  <img src="assets/git-images/restofy-yazi.png" alt="Restofy" width="420">
</p>

<h3 align="center">QR TABANLI RESTORAN YÖNETİM SİSTEMİ</h3>

<p align="center">
  Masada okutulan bir QR koddan siparişin hazırlanmasına ve ödemenin tamamlanmasına kadar tüm restoran akışını tek platformda toplamayı hedefler.
</p>

---

## 🍴 Restofy nedir?

Restofy, müşterinin masadaki QR kodu okutarak menüyü görmesini, sipariş vermesini ve ödemesini yapmasını; restoran ekibinin de bu siparişi mutfaktan servise kadar tek yerden yönetmesini hedefleyen bir restoran yönetim sistemidir.

<p align="center">
  <img src="assets/git-images/restofy-nedir.svg" alt="Restofy nedir? QR okut, sipariş ver, mutfak hazırlasın, garson servis etsin, ödeme tamamlansın" width="900">
</p>

Hedeflenen kapsam:

- **QR tabanlı dijital menü:** Her masaya özel QR kod ile menüye erişim.
- **Masa üzerinden sipariş:** Siparişin masa bilgisiyle oluşturulması.
- **Sipariş ve mutfak takibi:** Siparişin hazırlanma sürecinin izlenmesi.
- **Garson operasyonları:** Servis ve masa takibi.
- **Ödeme yönetimi:** Ödemenin sipariş akışına bağlanması.
- **Değerlendirme ve bildirimler:** Müşteri geri bildirimi ve ilgili kullanıcılara bildirim.
- **Yönetim paneli:** Restoran operasyonlarının merkezi yönetimi.

## 🎯 Neden Restofy?

Restofy yalnızca QR kod üzerinden menü gösteren bir uygulama olmayı değil, **restoran operasyonunun tamamını kapsayan bir platform** olmayı hedefler.

<p align="center">
  <img src="assets/git-images/restofy-neden.svg" alt="Neden Restofy? Uçtan uca operasyon, QR kodun ötesinde, rol bazlı kullanım, bağımsız mikroservisler, modern kullanıcı deneyimi, genişletilebilir altyapı" width="900">
</p>

## 🧩 Modüller

Aşağıda her servisin **hedeflenen sorumluluğu** listelenir. Tüm servislerin iş mantığı geliştirme aşamasındadır; bugün yalnızca servis iskeleti olarak mevcutturlar.

- **API Gateway** — İstemci isteklerini ilgili servise yönlendiren tek giriş noktası. *(rotalar tanımlı)*
- **QR Service** — Masalara özel QR kodların üretimi ve doğrulanması.
- **Auth Service** — Kullanıcı kimlik doğrulama ve rol/yetki yönetimi.
- **Mail Service** — E-posta gönderimi (doğrulama, bilgilendirme vb.).
- **Order Service** — Masa üzerinden sipariş oluşturma ve sipariş yaşam döngüsü.
- **Menu Service** — Restoran menüsü, kategoriler ve ürünler.
- **Kitchen Service** — Mutfağa düşen siparişlerin takibi ve hazırlık durumu.
- **Waiter Service** — Garsonun masa ve servis operasyonları.
- **Payment Service** — Sipariş ödemelerinin yönetimi.
- **Notification Service** — Kullanıcılara sipariş ve operasyon bildirimleri.
- **Admin Service** — Restoran yönetim paneli ve operasyon yönetimi.
- **Rating Service** — Müşteri değerlendirmeleri.
- **Timer Service** — Süre takibi gerektiren operasyonlar (ör. hazırlık süreleri).

## 🔧 Teknoloji yığını

![Restofy teknoloji yığını](assets/git-images/restofy-tech-stack.jpg)

<p align="center">
  <img src="assets/git-images/restofy-teknoloji.svg" alt="Teknoloji yığını. Frontend: Next.js 16.4.1, React 19.3.0, TypeScript 5, Tailwind CSS 4, next-intl. Backend: Java 25, Spring Boot 4.1.1, Spring Cloud Gateway, Spring Cloud 2025.1.3, Spring RestClient, Spring Data JPA, Hibernate, Spring Security, Flyway, Maven. Veritabanı: PostgreSQL 17. Altyapı: Docker, Docker Compose, ENV profilleri. Test ve kalite: JUnit, Testcontainers, JaCoCo, Vitest, Playwright, ESLint, Pre-Push kalite kontrolü. İsteğe bağlı: SonarQube Community Build." width="900">
</p>

## 🧱 Sistem mimarisi

Restofy, **13 mikroservisten** oluşur: 1 API Gateway ve 12 işlevsel servis.

- İstemci (frontend) istekleri yalnızca **API Gateway**'e gider.
- Servisler gerektiğinde birbirleriyle **REST (RestClient)** üzerinden doğrudan iletişim kurabilir.
- Tek bir PostgreSQL instance'ı vardır; 12 işlevsel servisin her biri **kendi veritabanını** kullanır. API Gateway veritabanı kullanmaz.
- Bir servis, başka bir servisin veritabanına doğrudan erişmez.
- Her servis kendi Flyway migration geçmişini, **kendi veritabanında bağımsız** olarak sürümler.

<p align="center">
  <img src="assets/git-images/restofy-mimari.svg" alt="Sistem mimarisi: İstemci, Frontend (Next.js :5000), API Gateway (:5050), 12 işlevsel mikroservis ve tek PostgreSQL 17 instance içinde servis başına ayrı veritabanı" width="900">
</p>

Frontend Docker Compose içinde çalışmaz; geliştirici bilgisayarında ayrıca başlatılır. Compose yalnızca PostgreSQL ve 13 backend servisini içerir.

## 🚀 Kurulum ve çalıştırma

<p align="center">
  <img src="assets/git-images/restofy-kurulum.svg" alt="Kurulum akışı: repository klonla, .env.dev oluştur, profili seç, docker compose ile backend'i başlat, frontend'i başlat, tarayıcıda localhost:5000 adresini aç" width="900">
</p>

Aşağıdaki adımları sırayla izleyin.

### Gereksinimler

- **Git** — Repository'yi klonlamak için.
- **Docker Desktop** (veya Docker Engine + Compose v2) — PostgreSQL ve 13 backend servisini çalıştırmak için. **Zorunlu.**
- **Node.js 22.12+** ve **npm** — Frontend için; Node.js ayrıca QR anahtar kurulum yardımcısını çalıştırır. **Zorunlu.**
- **JDK 25** ve **Maven** — Yalnızca [Pre-Push kalite kontrolü](#-test-ve-kalite-kontrolleri) için. Backend Docker içinde derlendiğinden uygulamayı çalıştırmak için gerekmez.

Pre-Push betikleri sistemdeki `mvn` komutunu kullanır ve Java 25 ile çalışan Maven bekler. Servis klasörlerindeki `mvnw` dosyaları bu akışta kullanılmaz.

### 1. Repository'yi klonlayın

```bash
git clone https://github.com/software-lecture-labs/qr-management-system.git
cd qr-management-system
```

### 2. ENV yapılandırması

Restofy'de iki ayrı ENV yapısı vardır. Birbirine karıştırılmamalıdır:

- **Kök ENV** (proje kökü): `.env.example`, `.env.dev`, `.env.prod`, `.env` — Docker Compose ve backend servisleri okur. Portlar, PostgreSQL, servis URL'leri, CORS, JWT süreleri ve SonarQube ayarlarını içerir.
- **Frontend ENV** (`frontend/`): `.env.example`, `.env.local` — Next.js okur. Yalnızca `NEXT_PUBLIC_API_BASE_URL` içerir.

#### Kök ENV dosyaları

- **`.env.example`** — Şifre içermeyen, tam **DEV şablonu**. Kopyalayıp kendi dosyanızı üretmek içindir; doğrudan kullanılmaz. *Git'e girer.*
- **`.env.dev`** — DEV profilinin gerçek değerleri. Siz oluşturursunuz. *Git'e girmez.*
- **`.env.prod`** — PROD profilinin gerçek değerleri. Yalnızca PROD gerekiyorsa oluşturulur. *Git'e girmez.*
- **`.env`** — Seçilen profilin **aktif kopyası**. `ops/env` betiği yazar, elle düzenlenmez; Docker Compose bu dosyayı okur. *Git'e girmez.*

Ayarlar her zaman `.env.dev` / `.env.prod` içinde düzenlenir; `.env` yalnızca seçilen profilin kopyasıdır.

**Windows (PowerShell):**

```powershell
Copy-Item .env.example .env.dev
```

**macOS / Linux:**

```bash
cp .env.example .env.dev
```

Ardından `.env.dev` dosyasını açıp **`POSTGRES_PASSWORD`** değerini doldurun. Boş bırakılırsa Docker Compose başlamaz. Diğer varsayılanlar (portlar, veritabanı adları, URL'ler) yerel geliştirme için hazırdır.

- **`POSTGRES_PASSWORD`** *(zorunlu)* — PostgreSQL şifresi. **Mutlaka siz belirlemelisiniz.**
- **`POSTGRES_USER`** *(zorunlu)* — Şablonda `restofy`.
- **`API_GATEWAY_HOST_PORT`** *(zorunlu)* — Gateway portu (`5050`).
- **`*_SERVICE_URL`, `CORS_ALLOWED_ORIGINS`, `SPRING_PROFILES_ACTIVE`** *(zorunlu)* — Şablonda doğru DEV değerleriyle gelir; değiştirmeniz gerekmez.
- **`SONAR_TOKEN`, `SONAR_DB_PASSWORD`** *(isteğe bağlı)* — Yalnızca [SonarQube](#sonarqube-isteğe-bağlı) kullanacaksanız. Boş bırakılabilir.

**Güçlü bir şifre üretmek için** (hex karakterler, `.env` ve Docker Compose için güvenlidir):

```powershell
# Windows (PowerShell)
$b = New-Object byte[] 24; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); ($b | ForEach-Object { $_.ToString('x2') }) -join ''
```

```bash
# macOS / Linux
openssl rand -hex 24
```

Çıkan değeri `.env.dev` içindeki `POSTGRES_PASSWORD=` satırına yapıştırın. Şifreyi sohbete, issue'ya veya commit'e yazmayın.

> [!WARNING]
> `POSTGRES_PASSWORD` yalnızca PostgreSQL volume'ü **ilk oluşturulurken** uygulanır. İlk `docker compose up` sonrasında şifreyi değiştirirseniz mevcut veritabanı eski şifreyle devam eder. Şifreyi **ilk başlatmadan önce** belirleyin. Veri silmeyi gerektiren sıfırlama adımları için [`ops/ops.md`](ops/ops.md) dosyasına bakın.

#### QR şifreleme anahtarı — ilk kurulum

QR Service için `.env.dev` içinde kalıcı, Base64 kodlanmış 32 byte anahtar gerekir. Profil dosyasını oluşturduktan sonra, proje kökünde Node.js ile çalıştırın:

```powershell
node ops/qr/qr-key.mjs init dev
node ops/qr/qr-key.mjs check dev
```

Aynı komutlar macOS/Linux'ta da geçerlidir. `init`, yalnız eksik/boş anahtarı üretir; mevcut geçerli anahtarı değiştirmez ve değeri terminale yazmaz. `.env` yoksa seçilen profilin aktif kopyasını oluşturur; aynı profil zaten aktif ve eşleşiyorsa ikisini birlikte günceller. Başka aktif profili değiştirmez; `.env` ve profil ayrışmışsa hiçbirinin üzerine yazmadan hata verir.

Boş QR anahtarı artık Compose modelinin tamamını engellemez; **QR Service boş/geçersiz anahtarla yine başlamaz**. Auth/Mail gibi diğer servislerin kendi zorunlu ayarları ayrıca doldurulmalıdır. Çalışan container yeni anahtarı kendiliğinden almaz; QR Service bir sonraki başlatma/yeniden oluşturma sırasında ENV'yi okur.

**Bu komut anahtar kaybını veya rotasyonunu çözmez.** Mevcut QR kayıtları varsa kaybolan anahtarı yedekten geri getirin; yeni anahtar eski şifreli içerikleri açamaz. Gerçek anahtarlar ve ENV dosyaları Git dışında kalır; `.env.example` alanı boş tutulur.

Ayrıntılı ilk kurulum, DEV/PROD, aktif `.env` eşitlemesi, hata kodları ve kurtarma adımları: [QR anahtar üretim rehberi](docs/qr/qr-key-uretimi.md).

#### DEV / PROD profili seçimi

Profil seçimi `ops/env` betiğiyle yapılır; betik `.env.dev` veya `.env.prod` dosyasını `.env` olarak kopyalar. **Docker başlatmaz veya durdurmaz.**

**Windows (PowerShell):**

```powershell
.\ops\env.cmd use dev      # DEV profilini seç
.\ops\env.cmd status       # Aktif profili göster
```

**macOS / Linux:**

```bash
sh ./ops/env.sh use dev    # DEV profilini seç
sh ./ops/env.sh status     # Aktif profili göster
```

PROD için önce `.env.prod` dosyasını `.env.example` üzerinden oluşturun (`APP_ENV=prod`, `SPRING_PROFILES_ACTIVE=prod`, `COMPOSE_PROJECT_NAME=restofy-prod`, `SWAGGER_ENABLED=false`, gerçek URL/CORS değerleri ve production kimlik bilgileri gerekir), ardından `use prod` çalıştırın. PROD ayrıntıları için [`ops/ops.md`](ops/ops.md) dosyasına bakın. `.env.example` dosyasını doğrudan production secret dosyası olarak kullanmayın.

> **DEV profili kimlik doğrulama yapmaz.** `SPRING_PROFILES_ACTIVE=dev` iken Order Service sabit yer tutucu misafir, personel (ADMIN dahil) ve servis kimliklerini kabul eder; bunlar API Gateway üzerinden gelen isteklerde de geçerlidir. Bu profil yalnızca kendi bilgisayarınızda kullanılmalıdır; paylaşılan, erişilebilir bir sunucuda veya production'da **asla** çalıştırmayın. Servis dev profiliyle başladığında günlüğe belirgin bir uyarı bloğu yazar. `dev` ve `test` dışındaki profillerde bu doğrulayıcılar kapalıdır (fail-closed, 503).

Daha önce başka yöntemle oluşturulmuş bir `.env` varsa ve betik "`.env` differs" hatası verirse, mevcut dosya `.env.bak` olarak yedeklenerek geçiş yapılabilir:

```powershell
.\ops\env.cmd use dev -Force
```

```bash
sh ./ops/env.sh use dev --force
```

### 3. Backend ve PostgreSQL'i başlatın

Komutlar proje kökünde çalıştırılır. İlk çalıştırmada servis imajları derlendiği için birkaç dakika sürebilir.

**Windows (PowerShell):**

```powershell
.\ops\env.cmd use dev
docker compose up --build -d
```

**macOS / Linux:**

```bash
sh ./ops/env.sh use dev
docker compose up --build -d
```

Durumu kontrol edin:

```bash
docker compose ps                      # 14 container: postgres + 13 servis
docker compose logs --tail 100 -f      # Logları izle (çıkmak için Ctrl+C)
```

API Gateway sağlık kontrolü (Windows PowerShell'de `curl` yerine `curl.exe` kullanın):

```bash
curl http://localhost:5050/actuator/health
```

Durdurmak için (veriler korunur):

```bash
docker compose down
```

> [!CAUTION]
> `docker compose down -v` komutu PostgreSQL volume'ünü ve **tüm yerel verileri siler**. Normal durdurma için kullanmayın.

#### Yerel portlar

- **Frontend:** `http://localhost:5000`
- **API Gateway:** `http://localhost:5050`
- **Mikroservisler:** `5051`–`5062` (Auth 5051, Order 5052, Kitchen 5053, Waiter 5054, Menu 5055, QR 5056, Payment 5057, Notification 5058, Admin 5059, Rating 5060, Mail 5061, Timer 5062)
- **PostgreSQL:** `127.0.0.1:5489`
- **SonarQube (isteğe bağlı):** `http://localhost:5099`

Frontend yalnızca API Gateway'e (`5050`) istek göndermelidir; mikroservis portlarına doğrudan bağlanmaz. Backend portları yalnızca `127.0.0.1` üzerinde yayınlanır.

### 4. Frontend'i başlatın

Frontend Docker'da çalışmaz; kendi bilgisayarınızda `frontend/` klasöründen başlatılır. Backend'i ayrı bir terminalde başlatmış olmanız önerilir.

**Windows (PowerShell):**

```powershell
cd frontend
npm ci
if (-not (Test-Path .env.local)) { Copy-Item .env.example .env.local }
npm run dev -- --port 5000
```

**macOS / Linux:**

```bash
cd frontend
npm ci
[ -f .env.local ] || cp .env.example .env.local
npm run dev -- --port 5000
```

Tarayıcıda **http://localhost:5000** adresini açın.

`frontend/.env.local` yalnızca şu değişkeni gerektirir:

```dotenv
NEXT_PUBLIC_API_BASE_URL=http://localhost:5050
```

**Dikkat edilecekler:**

- `package.json` içindeki `dev` komutu port belirtmez; **5000 portu için `-- --port 5000` parametresi gereklidir.**
- Next.js, kök dizindeki `.env.dev` dosyasını **okumaz**; frontend yalnızca `frontend/.env.local` kullanır.
- `NEXT_PUBLIC_*` değerleri tarayıcıya açılır; buraya şifre veya secret yazmayın.
- ENV değişirse geliştirme sunucusunu yeniden başlatın.
- Frontend henüz geliştirme aşamasında olduğundan ana sayfa boş içerik gösterir.

## 📁 Proje klasör yapısı

```text
qr-management-system/
├── backend/
│   ├── pom.xml                  # Maven çok modüllü ana proje
│   ├── services/                # 13 mikroservis
│   │   ├── api-gateway/
│   │   ├── auth-service/
│   │   ├── mail-service/
│   │   ├── qr-service/
│   │   ├── payment-service/
│   │   ├── order-service/
│   │   ├── notification-service/
│   │   ├── menu-service/
│   │   ├── timer-service/
│   │   ├── kitchen-service/
│   │   ├── rating-service/
│   │   ├── waiter-service/
│   │   └── admin-service/
│   └── test-support/            # Ortak test yapılandırması
├── frontend/                    # Next.js uygulaması (Docker dışında çalışır)
│   ├── src/
│   │   ├── app/                 # Route ve layout
│   │   ├── features/            # İş alanı bazlı modüller
│   │   └── shared/              # Ortak kod, i18n (TR/EN)
│   └── e2e/                     # Playwright testleri
├── assets/git-images/           # README görselleri
├── ops/                         # DEV/PROD ENV profil seçimi (env.cmd, env.sh, env.ps1)
├── postgres/                    # PostgreSQL ilk veritabanı kurulum betiği
├── pre-push/                    # Yerel kalite kontrol sistemi
├── sonarqube/                   # İsteğe bağlı SonarQube araçları
├── docs/                        # Güvenlik, web kuralları, mimari kararlar
├── docker-compose.yml           # PostgreSQL + 13 backend servisi
├── docker-compose.sonar.yml     # İsteğe bağlı SonarQube
└── .env.example                 # Şifresiz DEV ENV şablonu
```

## ✅ Test ve kalite kontrolleri

Restofy, diğer araçlardan bağımsız çalışan bir **Pre-Push yerel kalite kontrol sistemi** içerir. Git hook oluşturmaz, commit veya push yapmaz; kodu göndermeden önce manuel çalıştırılır. Tam kontrol şunları kapsar:

- Git / secret dosya kontrolü
- Maven testleri ve backend build
- JaCoCo coverage (backend ≥ %80)
- Frontend ESLint ve TypeScript
- Vitest ve frontend coverage (≥ %80)
- Next.js production build
- Playwright tarayıcı testleri
- `npm audit --omit=dev` (production bağımlılık denetimi)
- Docker Compose doğrulaması ve backend HTTP sağlık kontrolü

Tam kontrolden önce backend'in çalışıyor olması ve Chromium'un bir kez kurulmuş olması gerekir:

```powershell
cd frontend
npm run test:e2e:install
cd ..
```

**Windows (PowerShell):**

```powershell
.\pre-push\pre-push.cmd full dev
```

**macOS / Linux:**

```bash
chmod +x pre-push/pre-push.sh
./pre-push/pre-push.sh full dev
```

Argümansız çalıştırınca menü açılır; tek bir servisin testi (`auth-service` gibi) veya yalnızca backend testleri (`backend`) de seçilebilir. Sonuç `SAFE TO COMMIT` ise tüm zorunlu yerel kontroller geçmiştir. Bu sonuç SonarQube veya production güvenliği onayı değildir.

Tüm ayrıntılar için: [`pre-push/pre-push.md`](pre-push/pre-push.md)

### SonarQube (isteğe bağlı)

SonarQube Community Build **zorunlu değildir**; normal kurulum ve Pre-Push onsuz çalışır. Kullanmak isteyen her geliştirici kendi yerel SonarQube sunucusunu başlatır ve kendi token'ını kendi ignore edilen ENV dosyasında tutar. Kurulum ve kullanım için: [`sonarqube/sonarqube.md`](sonarqube/sonarqube.md)

## 👥 Geliştirici ekibi

Her mikroservis bir geliştiriciye aittir. Flyway migration sürümleri, her servisin **kendi veritabanında bağımsız** tutulur; ekip içi çakışmayı önlemek için servis başına ayrılmış sürüm aralıkları [`docs/decisions/migrations.md`](docs/decisions/migrations.md) dosyasında yer alır.

![Restofy geliştirici ekibi](assets/git-images/restofy-authors.png)

<p align="center">
  <img src="assets/git-images/restofy-ekip.svg" alt="Geliştirici ekibi: Kerem (Auth, Mail), Alper (QR, Payment), Hamza (Order, Notification), Merve (Menu, Timer), Nisa (Kitchen, Rating), Umut (Waiter, Admin)" width="900">
</p>

## 🚧 Geliştirme durumu

<p align="center">
  <img src="assets/git-images/restofy-durum.svg" alt="Geliştirme durumu: Başlangıç ve Altyapı tamamlandı, Geliştirme aşaması şu an devam ediyor, Entegrasyon ve Yayın sırada" width="900">
</p>
