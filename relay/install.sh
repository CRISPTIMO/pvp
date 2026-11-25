#!/usr/bin/env bash
# Установщик релея ШТРАФОСТАН TOOLS на Linux-VDS (systemd).
# Токен уже вшит (общий для команды) — можно запускать вообще без аргументов:
#   sudo bash install.sh
# При желании переопределить:  sudo bash install.sh [TOKEN] [PORT]
set -e

TOKEN="${1:-SHTRAFOSTAN-7fK92mLpQ4xZ}"
PORT="${2:-8080}"
DEST=/opt/shtrafostan-relay
SRC="$(cd "$(dirname "$0")" && pwd)"

if [ "$(id -u)" -ne 0 ]; then
  echo "Запусти от root: sudo bash install.sh <TOKEN> [PORT]"; exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "Ставлю python3..."
  (apt-get update && apt-get install -y python3) \
    || yum install -y python3 \
    || { echo "Установи python3 вручную и повтори"; exit 1; }
fi

echo "Копирую в $DEST ..."
mkdir -p "$DEST"
cp "$SRC/relay.py" "$DEST/relay.py"

echo "Пишу systemd-юнит (порт $PORT, токен $TOKEN) ..."
sed -e "s/RELAY_TOKEN=CHANGE_ME/RELAY_TOKEN=${TOKEN}/" \
    -e "s/RELAY_PORT=8080/RELAY_PORT=${PORT}/" \
    "$SRC/shtrafostan-relay.service" > /etc/systemd/system/shtrafostan-relay.service

systemctl daemon-reload
systemctl enable --now shtrafostan-relay

echo
echo "Готово. Релей запущен и будет стартовать после ребута."
echo "  Проверка:  systemctl status shtrafostan-relay"
echo "  Логи:      journalctl -u shtrafostan-relay -f"
echo "  Health:    curl http://localhost:${PORT}/health"
echo
echo "В моде (config/pvphelper-client.toml, секция [relay]):"
echo "  enabled = true"
echo "  url = \"http://<IP_VDS>:${PORT}\""
echo "  token = \"${TOKEN}\""
echo
echo "!!! Открой порт ${PORT}/tcp в фаерволе VDS (ufw allow ${PORT}/tcp или в панели хостера)."
