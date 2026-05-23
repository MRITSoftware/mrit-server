#!/bin/bash

# Verificar se hotspot ja esta ativo
HOTSPOT_ACTIVE=$(nmcli -t -f NAME,TYPE connection show --active 2>/dev/null | grep ":wifi$" | grep -c "MRIT-Setup")
if [ "$HOTSPOT_ACTIVE" -gt "0" ]; then
    logger -t mrit-wifi "Hotspot MRIT-Setup ja ativo."
    exit 0
fi

# Verificar estado do wlan0 especificamente (nao o estado geral do NM)
WLAN_STATE=$(nmcli -t -f DEVICE,STATE device 2>/dev/null | grep "^wlan0:" | cut -d: -f2)
if [ "$WLAN_STATE" = "connected" ]; then
    logger -t mrit-wifi "wlan0 conectado. Hotspot desnecessario."
    exit 0
fi

logger -t mrit-wifi "wlan0 nao conectado (state: $WLAN_STATE). Criando hotspot MRIT-Setup..."
nmcli device wifi hotspot ifname wlan0 ssid "MRIT-Setup" password "mrit1234"
RC=$?
if [ $RC -eq 0 ]; then
    logger -t mrit-wifi "Hotspot MRIT-Setup ativo (IP: 10.42.0.1). Acesse http://mrit-pi.local ou http://10.42.0.1"
else
    logger -t mrit-wifi "Falha ao criar hotspot MRIT-Setup (rc=$RC)"
fi
