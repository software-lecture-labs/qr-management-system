# RESTOFY SonarQube

Docker Engine/Desktop, Node.js, JDK 25 ve kurulu frontend paketleri gerekir. Maven için repository'deki Maven Wrapper kullanılır; ayrıca global Maven kurulumu gerekmez. Mevcut root `.env` Sonar veritabanı ayarlarını içerir; gerçek ENV dosyaları Git'e gönderilmez. Kök `sonarqube/` yerleşimi ve ayrı `docker-compose.sonar.yml` korunur; pre-push bu sistemi çağırmaz.

Windows:

```powershell
.\sonarqube\sonar.cmd up
.\sonarqube\sonar.cmd status
.\sonarqube\sonar.cmd scan
.\sonarqube\sonar.cmd down
```

macOS / Linux:

```bash
chmod +x sonarqube/sonar.sh
./sonarqube/sonar.sh up
./sonarqube/sonar.sh status
./sonarqube/sonar.sh scan
./sonarqube/sonar.sh down
```

Varsayılan ortam `dev`; komutun sonuna `prod` eklenebilir. Script başka dizinden de çağrılabilir. `up` yalnızca ayrı `restofy-sonar` Compose projesindeki SonarQube ve kendi PostgreSQL'ini başlatır. `status`, container durumuna ek olarak gerçek HTTP hazırlığını kontrol eder. `down` bu iki servisi durdurur; container ve volume'leri korur. `down -v` kullanılmaz. Execute biti henüz Git'e kaydedilmediyse Bash komutları `sh ./sonarqube/sonar.sh ...` biçiminde de çalıştırılabilir.

Her komutun terminal çıktısı proje kökündeki `logs/sonarqube-<komut>-<tarih>-<kimlik>.log` dosyasına da kaydedilir. Eski loglar korunur; token çıktıda maskelenir. Log içerikleri Git'e gönderilmez, yalnızca boş `logs/.gitkeep` paylaşılır.

1. **http://localhost:5099** adresini aç. İlk giriş **admin / admin**; istenen yeni parolayı kendin belirle. Mevcut kurulumda kendi parolanı kullan.
2. `restofy-backend` ve `restofy-frontend` projelerini oluştur. **Sonar way** kapısını kullan ve proje ayarlarında New Code tanımını belirle.
3. **My Account → Security** bölümünde bir **Global Analysis Token** oluştur. İki projede analiz ve sonuçları görüntüleme yetkin olmalı.
4. Token'ı ignore edilen root `.env.dev` dosyasındaki `SONAR_TOKEN=` satırına kaydet. Terminale, sohbete veya komut argümanına yazma. Shell'de tanımlı `SONAR_*` değerleri önceliklidir. `up`, `status` ve `down` token istemez.
5. `scan` çalıştır. Hazır sunucu ve geçerli token olmadan test/scanner başlatılmaz. Güncel JUnit/JaCoCo ve Vitest/LCOV raporları üretilir; raporları eksik veya başarısız olan proje taranmaz.

İki analizin işlenmesi ve Quality Gate sonuçları beklenir. **Sonar way yeni kod** koşulları sunucudan incelenir; **bütün kod** için A/A/A rating, coverage ≥ %80 ve duplication ≤ %3 ayrıca kontrol edilir. Her ikisi geçerse `SONAR SCAN PASSED` ve exit code `0`; başarısız/eksik sonuçta exit code `1` verilir. Community Build için branch/PR analizi yapılandırılmaz.

Her projenin gerçekten atanmış Quality Gate koşulları da raporlanır. Başarılı bir custom gate yalnızca adı farklı diye reddedilmez. Varsayılan Sonar way'in overall hedefleri de uyguladığı varsayılmaz; gerekli overall gate koşulları yönetici tarafından mevcut ayarlar korunarak değerlendirilir. Çalıştırılmayan kontroller SKIPPED olarak gösterilir. Token yokken gerçek analiz, coverage import veya gate doğrulaması yapılmış sayılmaz.

Her geliştirici kendi hesabından token üretmelidir. Token belirli SonarQube instance'ında geçerlidir; başka bilgisayardaki ayrı instance'ta çalışmaz. Aynı sunucuda ortak token teknik olarak kullanılabilir, ancak kişisel token'ı paylaşmak yerine CI için ayrı erişim tercih edilir. Gerçek ENV dosyasını ekip üyelerine dağıtmayın; `.env.example` üzerinden yerel ayarlar hazırlansın. Mevcut script yalnızca yerel SonarQube adreslerini kabul eder; uzaktaki ekip sunucusu desteği ayrıca ele alınır.

Kaynaklar: [Token yönetimi](https://docs.sonarsource.com/sonarqube-community-build/user-guide/managing-tokens), [Quality Gate](https://docs.sonarsource.com/sonarqube-community-build/quality-standards-administration/managing-quality-gates/introduction-to-quality-gates).

Sonuçları web arayüzündeki iki projenin **Overview**, **Measures** ve **Issues** ekranlarında görebilirsin. Terminal yeni kod ve bütün kod metriklerini ayrı gösterir. Yerel raporlar backend servislerinin `target/site/jacoco/` ve frontend'in `coverage/` dizinindedir. Rapor üretimi ve analiz sonucu farklıdır; yerel coverage değeri Sonar coverage sonucu olarak sunulmaz.
