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
    echo ""
    echo "  Digite o e-mail da unidade (sera usado como site_id):"
    read -p "  > " SITE_ID

    while [ -z "$SITE_ID" ]; do
        echo "  ERRO: site_id nao pode ser vazio."
        read -p "  > " SITE_ID
    done

    # Gerar config.json com site_id informado e credenciais pre-configuradas
    python3 -c "
import json, sys
template = json.load(open('$ROOT_DIR/config.example.json'))
template['site_name'] = sys.argv[1]
json.dump(template, open('$INSTALL_DIR/config.json', 'w'), indent=4, ensure_ascii=False)
" "$SITE_ID"

    echo ""
    echo "  config.json criado com site_id = $SITE_ID"
    echo ""
else
    echo "      config.json ja existe, mantido sem alteracao."
    SITE_ID=$(python3 -c "import json; print(json.load(open('$INSTALL_DIR/config.json')).get('site_name','?'))")
    echo "      site_id atual: $SITE_ID"
fi

echo "[6/6] Instalando servico systemd..."
sudo cp "$ROOT_DIR/systemd/$SERVICE_NAME.service" /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable "$SERVICE_NAME"

echo ""
echo "=== Instalacao concluida! ==="
echo ""
echo "  Inicie o servidor:  sudo systemctl start $SERVICE_NAME"
echo "  Veja os logs:       journalctl -u $SERVICE_NAME -f"
echo "  Status do servico:  sudo systemctl status $SERVICE_NAME"
echo ""
