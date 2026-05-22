#!/bin/bash
set -e

INSTALL_DIR="/home/mrit/mrit-server"
SERVICE_NAME="mrit-server"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"

echo "=== MRIT Server - Instalacao no Raspberry Pi ==="
echo ""

if [ "$EUID" -eq 0 ]; then
    echo "ERRO: Execute como usuario mrit, nao como root"
    echo "  ssh mrit@mrit-pi.local"
    echo "  bash scripts/install.sh"
    exit 1
fi

echo "[1/6] Criando diretorio de instalacao..."
mkdir -p "$INSTALL_DIR"

echo "[2/6] Copiando servidor..."
cp "$ROOT_DIR/server/tuya_server.py" "$INSTALL_DIR/"

echo "[3/6] Criando ambiente virtual Python..."
python3 -m venv "$INSTALL_DIR/venv"
source "$INSTALL_DIR/venv/bin/activate"

echo "[4/6] Instalando dependencias Python..."
pip install --upgrade pip --quiet
pip install -r "$ROOT_DIR/requirements.txt" --quiet
echo "      Dependencias instaladas."

echo "[5/6] Configurando config.json..."
if [ ! -f "$INSTALL_DIR/config.json" ]; then
    cp "$ROOT_DIR/config.example.json" "$INSTALL_DIR/config.json"
    echo ""
    echo "  ATENCAO: config.json criado com valores de exemplo."
    echo "  Edite o arquivo antes de iniciar o servidor:"
    echo "    nano $INSTALL_DIR/config.json"
    echo ""
else
    echo "      config.json ja existe, mantido sem alteracao."
fi

echo "[6/6] Instalando servico systemd..."
sudo cp "$ROOT_DIR/systemd/$SERVICE_NAME.service" /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable "$SERVICE_NAME"

echo ""
echo "=== Instalacao concluida! ==="
echo ""
echo "Proximos passos:"
echo "  1. Edite as credenciais: nano $INSTALL_DIR/config.json"
echo "  2. Inicie o servidor:    sudo systemctl start $SERVICE_NAME"
echo "  3. Veja os logs:         journalctl -u $SERVICE_NAME -f"
echo "  4. Status do servico:    sudo systemctl status $SERVICE_NAME"
echo ""
