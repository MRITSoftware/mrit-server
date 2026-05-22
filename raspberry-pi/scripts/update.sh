#!/bin/bash
set -e

INSTALL_DIR="/home/mrit/mrit-server"
SERVICE_NAME="mrit-server"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"

echo "=== MRIT Server - Atualizacao ==="
echo ""

echo "[1/4] Parando servico..."
sudo systemctl stop "$SERVICE_NAME"

echo "[2/4] Copiando novo servidor..."
cp "$ROOT_DIR/server/tuya_server.py" "$INSTALL_DIR/"

echo "[3/4] Atualizando dependencias Python..."
source "$INSTALL_DIR/venv/bin/activate"
pip install -r "$ROOT_DIR/requirements.txt" --upgrade --quiet
echo "      Dependencias atualizadas."

echo "[4/4] Reiniciando servico..."
sudo systemctl start "$SERVICE_NAME"

echo ""
echo "=== Atualizacao concluida! ==="
echo "Logs: journalctl -u $SERVICE_NAME -f"
echo ""
