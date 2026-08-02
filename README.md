# Depo Video Kayit Sistemi

E-ticaret kargo paketleri icin video kayit sistemi. Barkod okut, USB kameradan kaydet.

## Kurulum

```bash
cd ../var/vk
git clone https://github.com/kayra98/depo-video-kay-t
chmod +x setup.sh
./setup.sh
```

## Gereksinimler

- Java 25
- Docker (PostgreSQL + Redis)
- USB Kamera (UVC uyumlu)

## Kullanim

1. `https://sunucu-ip:8443` adresine git
2. Ilk giris: QR kod okut + email gir
3. Sonraki girisler: email + TOTP kodu
4. `/record` sayfasindan barkod okut, kayit baslat
5. `/files` sayfasindan kayitlari yonet
6. `/settings/storage` sayfasindan S3 ayarla

## Teknolojiler

Spring Boot 4.1, JavaCV, PostgreSQL, Redis, Thymeleaf, Bunny S3
