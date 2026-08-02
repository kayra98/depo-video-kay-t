#!/bin/bash
set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo "========================================"
echo "  Depo Video Kayit Sistemi - Kurulum"
echo "========================================"
echo ""

# ---- Java kontrolu ----
echo -n "Java kontrolu... "
if command -v java &> /dev/null; then
    JAVA_VER=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
    if [ "$JAVA_VER" -ge 21 ]; then
        echo -e "${GREEN}OK${NC} (Java $JAVA_VER)"
    else
        echo -e "${RED}HATA: Java 21+ gerekli. Su an: Java $JAVA_VER${NC}"
        exit 1
    fi
else
    echo -e "${RED}HATA: Java bulunamadi. Java 21+ kurun.${NC}"
    echo "  sudo apt install openjdk-21-jdk"
    exit 1
fi

# ---- Docker kontrolu ----
echo -n "Docker kontrolu... "
if command -v docker &> /dev/null; then
    if docker info &> /dev/null; then
        echo -e "${GREEN}OK${NC}"
    else
        echo -e "${YELLOW}Docker servisi calismiyor. Baslatiliyor...${NC}"
        sudo systemctl start docker 2>/dev/null || true
        sleep 2
    fi
else
    echo -e "${YELLOW}Docker bulunamadi. PostgreSQL ve Redis manuel kurulmali.${NC}"
    echo "  sudo apt install docker.io docker-compose"
fi

# ---- FFmpeg kontrolu ----
echo -n "FFmpeg kontrolu... "
if command -v ffmpeg &> /dev/null; then
    echo -e "${GREEN}OK${NC}"
else
    echo -e "${YELLOW}YOK${NC}"
    echo "  sudo apt install ffmpeg"
fi

# ---- Kamera kontrolu ----
echo -n "Kamera kontrolu... "
CAM_COUNT=$(ls /dev/video* 2>/dev/null | wc -l)
if [ "$CAM_COUNT" -gt 0 ]; then
    echo -e "${GREEN}$CAM_COUNT kamera bulundu${NC}"
    ls /dev/video* 2>/dev/null | while read dev; do
        NAME=$(cat /sys/class/video4linux/$(basename $dev)/name 2>/dev/null || echo "bilinmiyor")
        echo "  $dev: $NAME"
    done
else
    echo -e "${YELLOW}Kamera bulunamadi${NC}"
fi

# ---- UFW firewall ----
echo -n "Firewall (8443)... "
if command -v ufw &> /dev/null; then
    sudo ufw allow 8443/tcp 2>/dev/null && echo -e "${GREEN}8443 acildi${NC}" || echo -e "${YELLOW}ufw acilamadi (manuel acin)${NC}"
else
    echo -e "${YELLOW}ufw yok, atlandi${NC}"
fi

# ---- Docker servisleri ----
echo ""
echo "--- Docker servisleri ---"
if docker info &> /dev/null; then
    docker compose up -d 2>/dev/null
    echo -e "${GREEN}PostgreSQL: localhost:5433${NC}"
    echo -e "${GREEN}Redis: localhost:6380${NC}"
else
    echo "Docker calismadigi icin atlandi."
fi

# ---- Build ----
echo ""
echo "--- Derleme ---"
./gradlew build -q 2>&1
echo -e "${GREEN}Build basarili${NC}"

# ---- Video grubu ----
echo ""
echo -n "Kamera izinleri... "
if groups | grep -q video; then
    echo -e "${GREEN}OK${NC}"
else
    echo -e "${YELLOW}video grubuna eklenmemissiniz: sudo usermod -a -G video \$USER${NC}"
fi

# ---- Baslat ----
echo ""
echo "========================================"
echo -e "${GREEN}Kurulum tamam. Baslatiliyor...${NC}"
echo "  https://localhost:8443"
echo "  https://\$(hostname -I | awk '{print \$1}'):8443"
echo "========================================"
echo ""

./gradlew bootRun
