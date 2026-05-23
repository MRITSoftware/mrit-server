#!/bin/bash
STATE=$(nmcli -t -f STATE general 2>/dev/null | head -1)

if [ "$STATE" = "connected" ]; then
    logger -t mrit-wifi "WiFi conectado. Hotspot desnecessario."
    exit 0
fi

# Verificar se hotspot já está ativo
HOTSPOT_ACTIVE=$(nmcli -t -f NAME,TYPE connection show --active 2>/dev/null | grep ":wifi$" | grep -c "MRIT-Setup")
if [ "$HOTSPOT_ACTIVE" -gt "0" ]; then
    logger -t mrit-wifi "Hotspot MRIT-Setup ja ativo."
    exit 0
fi

logger -t mrit-wifi "Sem WiFi e sem hotspot. Criando hotspot MRIT-Setup..."
nmcli device wifi hotspot ifname wlan0 ssid "MRIT-Setup" password "mrit1234"
logger -t mrit-wifi "Hotspot MRIT-Setup ativo. Acesse http://mrit-pi.local para configurar."
