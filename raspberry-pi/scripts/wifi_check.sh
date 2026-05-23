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

logger -t mrit-wifi "wlan0 nao conectado (state: $WLAN_STATE). Ativando hotspot MRIT-Setup..."

# Criar perfil se nao existir
if ! nmcli connection show MRIT-Setup &>/dev/null; then
    logger -t mrit-wifi "Criando perfil do hotspot MRIT-Setup..."
    nmcli connection add \
        type wifi ifname wlan0 con-name MRIT-Setup ssid "MRIT-Setup" \
        802-11-wireless.mode ap \
        802-11-wireless.band bg \
        802-11-wireless.channel 6 \
        ipv4.method shared \
        wifi-sec.key-mgmt wpa-psk \
        wifi-sec.psk "mrit1234" \
        autoconnect no
fi

nmcli connection up MRIT-Setup
RC=$?
if [ $RC -eq 0 ]; then
    logger -t mrit-wifi "Hotspot MRIT-Setup ativo (IP: 10.42.0.1). Acesse http://mrit-pi.local ou http://10.42.0.1"
else
    logger -t mrit-wifi "Falha ao ativar hotspot MRIT-Setup (rc=$RC)"
fi
