#!/bin/bash
# Aguarda o NetworkManager inicializar completamente
sleep 20

STATE=$(nmcli -t -f STATE general 2>/dev/null | head -1)

if [ "$STATE" = "connected" ]; then
    logger -t mrit-wifi "WiFi conectado. Hotspot desnecessario."
    exit 0
fi

logger -t mrit-wifi "Sem WiFi. Criando hotspot MRIT-Setup..."
nmcli device wifi hotspot ifname wlan0 ssid "MRIT-Setup" password "mrit1234"

logger -t mrit-wifi "Hotspot MRIT-Setup ativo. Acesse http://mrit-pi.local para configurar."
