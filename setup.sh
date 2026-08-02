#!/bin/bash
set -e
echo "=== Depo Video Kayit Sistemi ==="
echo ""

# Docker servislerini baslat
if command -v docker &> /dev/null; then
    echo "[1/3] Docker servisleri baslatiliyor..."
    docker compose up -d
    echo "PostgreSQL: localhost:5433"
    echo "Redis: localhost:6380"
else
    echo "[!] Docker bulunamadi. PostgreSQL ve Redis manuel kurulmali."
fi

# Build
echo ""
echo "[2/3] Proje derleniyor..."
./gradlew build -q

# Baslat
echo ""
echo "[3/3] Uygulama baslatiliyor..."
echo "https://localhost:8443"
echo ""
./gradlew bootRun
