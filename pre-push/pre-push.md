# RESTOFY yerel kalite kontrolü

Bu manuel script testleri, coverage ve build sonuçlarını kontrol eder; Git hook oluşturmaz, commit veya push yapmaz. SonarQube bu scriptin parçası değildir. Daha önce seçilen kök `pre-push/` ve `sonarqube/` yerleşimi korunur; script'ler `ops/` altına taşınmaz.

JDK 25, Node.js 22.12+ (veya 24+) ve kurulu frontend paketleri gerekir. Backend kontrolleri repository'deki Maven Wrapper'ı kullanır; ayrıca global Maven kurulumu gerekmez.

Tam kontrolden önce frontend dizininde bir kez `npm run test:e2e:install` ile
Chromium'u kurun. Seçilen profilin backend servisleri çalışıyor olmalıdır:

```powershell
.\ops\env.cmd use dev
docker compose up --build -d
.\pre-push\pre-push.cmd full dev
```

PROD kontrolünde `use prod` ve `full prod` kullanılır. HTTP kontrolü seçilen Compose
projesinin çalışan 13 backend servisine bakar. Container'ları başlatmaz veya durdurmaz.

Windows:

```powershell
.\pre-push\pre-push.cmd
```

macOS / Linux:

```bash
chmod +x pre-push/pre-push.sh
./pre-push/pre-push.sh
```

Argümansız çalıştırınca menü açılır. `backend/pom.xml` içindeki 13 servis ayrı ayrı listelenir:

- **Bir servis:** yalnızca o servisin Maven testleri ve coverage kontrolü çalışır.
- **Tüm servislerin testleri:** 13 backend servisi çalıştırılır, sonuçları ayrı ayrı gösterilir.
- **Tam kalite kontrolü:** backend, frontend, audit ve Docker kontrollerinin tamamı çalışır.
- **Çıkış:** test çalıştırmadan kapanır.

Menüyü açmadan doğrudan çalıştırmak da mümkündür:

```powershell
.\pre-push\pre-push.cmd auth-service
.\pre-push\pre-push.cmd backend
.\pre-push\pre-push.cmd full
```

macOS/Linux tarafında aynı argümanlar `./pre-push/pre-push.sh` ile kullanılır. Varsayılan ortam `dev`; örneğin `full prod` seçilebilir. Eski `dev` / `prod` komutları tam kalite kontrolünü çalıştırmaya devam eder. Başka dizinden de çağrılabilir. Etkileşimsiz terminalde bir kapsam argümanı gerekir.

Terminal çıktısı otomatik olarak proje kökündeki `logs/pre-push-<tarih>-<kimlik>.log` dosyasına da kaydedilir. Her çalıştırma ayrı dosya oluşturur; eski loglar korunur. Log içerikleri Git'e gönderilmez, yalnızca boş `logs/.gitkeep` paylaşılır.

Tam kalite kontrolündeki zorunlu kontroller:

- Git / Secret Check: track edilen ve stage edilen dosya adlarında gerçek `.env`/profil/yedek dosyalarını, anahtar/sertifika dosyalarını ve `secrets`, `.aws`, `credentials.json`, `service-account.json` yollarını engeller. `.env.example` ve `.env.*.example` şablonlarına izin verir. Hem staged hem unstaged `git diff --check` çalışır. Kontrol dosya adlarına yöneliktir; kaynak kod içerisine yazılmış tüm secret değerlerini tespit eden bir içerik tarayıcısı değildir. Secret içerikleri loglara yazılmaz.
- Maven `clean verify` ile testleri ve build; her serviste gerçekten çalışmış JUnit sonuçları. Backend Tests ve Backend Build ayrı sonuç satırlarıdır.
- Mevcut backend başlangıç testleri uygulamayı gerçek `main` girişinden açar. Yeni iş mantığı eklendikçe davranışlarını doğrulayan testler de eklenmelidir.
- Güncel JaCoCo XML/HTML raporları ve toplam backend line coverage ≥ %80.
- Frontend ESLint, TypeScript, Vitest ve güncel LCOV raporu; line, branch, function ve statement coverage ≥ %80.
- Next.js production build.
- Frontend HTTP Health ve Frontend Playwright: yeni build, Playwright tarafından ayrı `127.0.0.1:5090` test sunucusunda açılır. Ana sayfanın HTTP/HTML yanıtı ve Chromium TR/EN dil testleri çalışır. Mevcut frontend sunucusu kullanılmaz; test sunucusu test sonunda kapanır. Eksik Chromium, dolu test portu, boş/skip edilmiş/başarısız test ya da eski rapor PASS sayılmaz. `PLAYWRIGHT_PORT` ile test portu değiştirilebilir. Build başarısızsa bu iki bağımlı kontrol SKIPPED olur.
- `npm audit --omit=dev`: sıfırdan farklı production audit exit code'u veya herhangi bir production bulgusu başarısız sayılır. Tam audit de raporlanır; yalnızca geliştirme bağımlılığı bulguları uyarıdır. Otomatik dependency düzeltmesi yapılmaz.
- Ana `docker-compose.yml` doğrulaması ve 14 servisin kontrolü; seçilen profil için doğrudan `.env.dev` veya `.env.prod` kullanılır. Aktif `.env` değiştirilmez. Container başlatılmaz. Docker CLI/Compose yoksa bu adım FAIL olur, diğer kontroller yine çalıştırılır. Yapılandırma kontrolü daemon istemez; mevcut backend Testcontainers testleri çalışan Docker Engine/Desktop gerektirir.
- Backend HTTP Health: API Gateway dahil her backend servisinin seçilen Compose projesinde çalıştığını ve yayınlanan yerel portundaki `/actuator/health` yanıtının HTTP 200 + `status=UP` olduğunu doğrular. Portlar Compose modelinden alınır; 13 servis ayrı ayrı raporlanır. Her servis için en fazla 30 saniye yeniden deneme yapılır. Kapalı servis, yanlış proje, hatalı HTTP/JSON veya DOWN sonucu FAIL olur; başka servisin kontrolü devam eder.

**SAFE TO COMMIT:** bütün zorunlu yerel kontroller geçti, exit code `0`.

Tek servis ve tüm backend testleri seçimlerinde başarı `BACKEND TEST CHECK PASSED` olarak gösterilir. Bu seçenekler frontend, Git/secret, audit, Compose veya HTTP kontrolü yapmaz; `SAFE TO COMMIT` sonucu yalnızca tam kalite kontrolünde verilir. Servis tablosunda gerçek JUnit test sayısı ve line coverage gösterilir; her seçilen servisin coverage hedefi %80'dir.

**NOT SAFE TO COMMIT:** en az bir kontrol başarısız, eksik veya ölçülemiyor; exit code `1`. Çalıştırılmayan kontroller SKIPPED olarak gösterilir. Coverage raporu üretilmesi tek başına coverage hedefini geçmek değildir; boş veya eski test raporları PASS sayılmaz. SAFE TO COMMIT, SonarQube Quality Gate sonucunu garanti etmez.

Bir kontroldeki hata sonraki bağımsız kontrolleri durdurmaz. Git/secret, browser veya HTTP
kontrolü başarısız olsa da kalan adımlar çalışır; son tabloda bütün sonuçlar gösterilir.

Backend raporları servislerin `target/surefire-reports/` ve `target/site/jacoco/` dizinlerinde; frontend raporları `frontend/test-results/` ve `frontend/coverage/` içindedir. Raporları kullanan kontroller aynı anda çalıştırılmaz. Kesintiden sonra `test-results/quality.lock` kalırsa önce çalışan kontrol olmadığını doğrula.
