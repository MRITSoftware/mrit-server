#!/bin/bash
set -e

INSTALL_DIR="/home/mrit/mrit-server"
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

echo "[1/8] Criando diretorio de instalacao..."
mkdir -p "$INSTALL_DIR/scripts"

echo "[2/8] Copiando arquivos do servidor..."
cp "$ROOT_DIR/server/tuya_server.py" "$INSTALL_DIR/"
cp "$ROOT_DIR/server/web_ui.py" "$INSTALL_DIR/"
cp -r "$ROOT_DIR/server/templates" "$INSTALL_DIR/"
cp "$ROOT_DIR/scripts/wifi_check.sh" "$INSTALL_DIR/scripts/"
chmod +x "$INSTALL_DIR/scripts/wifi_check.sh"

echo "[3/8] Criando ambiente virtual Python..."
python3 -m venv "$INSTALL_DIR/venv"
source "$INSTALL_DIR/venv/bin/activate"

echo "[4/8] Instalando dependencias Python..."
pip install --upgrade pip --quiet
pip install -r "$ROOT_DIR/requirements.txt" --quiet
echo "      Dependencias instaladas."

echo "[5/8] Configurando config.json..."
if [ ! -f "$INSTALL_DIR/config.json" ]; then
    echo ""
    echo "  Digite o e-mail da unidade (sera usado como site_id):"
    read -p "  > " SITE_ID

    while [ -z "$SITE_ID" ]; do
        echo "  ERRO: site_id nao pode ser vazio."
        read -p "  > " SITE_ID
    done

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

echo "[6/8] Configurando permissoes sudo..."
{
    echo "mrit ALL=(ALL) NOPASSWD: /usr/bin/nmcli"
    echo "mrit ALL=(ALL) NOPASSWD: /usr/bin/systemctl restart mrit-server"
    echo "mrit ALL=(ALL) NOPASSWD: /usr/bin/systemctl restart mrit-webui"
} | sudo tee /etc/sudoers.d/mrit > /dev/null
sudo chmod 440 /etc/sudoers.d/mrit
echo "      Permissoes configuradas."

echo "[7/8] Instalando servicos systemd..."
sudo cp "$ROOT_DIR/systemd/mrit-server.service" /etc/systemd/system/
sudo cp "$ROOT_DIR/systemd/mrit-webui.service" /etc/systemd/system/
sudo cp "$ROOT_DIR/systemd/mrit-wifi-check.service" /etc/systemd/system/
sudo cp "$ROOT_DIR/systemd/mrit-wifi-check.timer" /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable mrit-server mrit-webui mrit-wifi-check.timer
echo "      Servicos habilitados."

echo "[8/8] Iniciando servicos..."
sudo systemctl start mrit-webui
sudo systemctl start mrit-wifi-check.timer
sudo systemctl start mrit-server

echo ""
echo "=== Instalacao concluida! ==="
echo ""
echo "  Interface web:  http://mrit-pi.local"
echo "  Senha:          MRITSERVER#REDEGELAFIT"
echo ""
echo "  Logs servidor:  journalctl -u mrit-server -f"
echo "  Logs webui:     journalctl -u mrit-webui -f"
echo ""
