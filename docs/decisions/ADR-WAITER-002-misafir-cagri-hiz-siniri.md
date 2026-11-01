# ADR-WAITER-002 — Misafir çağrıları için hız sınırı

**Durum:** Önerildi (varsayılan değerler ekip onayı bekliyor)
**Kapsam:** `backend/services/waiter-service`, `POST /api/v1/waiters/calls`

## Bağlam

QR (PR #11) incelemesinde, müşteriye açık uç noktalar açılmadan önce hız sınırı gerektiği belirtildi.
Waiter'ın müşteriye açık tek yazma ucu misafir çağrısıdır. Mevcut kurallar (misafir ve tür başına tek aktif
çağrı, idempotency anahtarı) bir çağrı çözüldükten sonra aynı misafirin sürekli yeni çağrı açmasını
engellemez; personeli bildirimle doldurabilir.

## Karar

Bir misafir oturumu, kayan bir pencere içinde en fazla `max-new-calls` **yeni** çağrı açabilir; aşıldığında
`429 Too Many Requests`, `code=CALL_RATE_LIMITED` ve `Retry-After` döner. Hiçbir şey yazılmaz.

- Varsayılan: pencere başına 6 çağrı, pencere 600 saniye. Değerler `restofy.calls.guest-rate-limit.*`
  özellikleridir; ortam değişkeni değildir.
- Sayım **veritabanındaki çağrı satırlarından** yapılır (`created_at`). Böylece birden fazla örnekte tutarlıdır
  ve saldırganın doldurabileceği bir bellek tablosu yoktur (Auth PR #2'deki `LoginRateLimiter` bulgusu).
- Sayılmayanlar: aynı Idempotency-Key ile tekrar (ilk yanıt döner) ve zaten aktif olan aynı türdeki çağrının
  geri verilmesi (200).
- Anahtar misafir oturumudur. IP veya istemci adresi kullanılmaz; güvenilir proxy başlığı kararı henüz yok.

## Sonuçlar

- Aynı misafirden gelen iki gerçek eşzamanlı istek sınırı en fazla bir kadar aşabilir (sayma ile ekleme aynı
  transaction'da, ama serileştirilmiş değil). Taşkın koruması için kabul edilebilir; tek aktif çağrı indeksi
  korunur.
- Sınır misafir oturumu başınadır. Çok sayıda oturum açan bir saldırgan QR'ın tarama sınırına tabidir; bu
  koruma QR tarafında ayrıca gereklidir.
- Varsayılan değerler ürün kararıdır; masa/hizmet yoğunluğuna göre ekip tarafından değiştirilebilir.
