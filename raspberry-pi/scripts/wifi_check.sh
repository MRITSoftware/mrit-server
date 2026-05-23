#!/bin/bash

# Verificar se hotspot ja esta ativo (hostapd rodando)
if pgrep -f "hostapd.*mrit-hotspot" > /dev/null 2>&1; then
    logger -t mrit-wifi "Hotspot MRIT-Setup ja ativo."
    exit 0
fi

# Verificar estado do wlan0 especificamente
WLAN_STATE=$(nmcli -t -f DEVICE,STATE device 2>/dev/null | grep "^wlan0:" | cut -d: -f2)
if [ "$WLAN_STATE" = "connected" ]; then
    logger -t mrit-wifi "wlan0 conectado. Hotspot desnecessario."
    exit 0
fi

logger -t mrit-wifi "wlan0 nao conectado (state: $WLAN_STATE). Criando hotspot via hostapd..."

# Retirar wlan0 do controle do NM
nmcli device set wlan0 managed no 2>/dev/null

# Atribuir IP fixo ao wlan0
ip addr flush dev wlan0 2>/dev/null
ip addr add 10.42.0.1/24 dev wlan0
ip link set wlan0 up

# Iniciar hostapd em background
hostapd -B /etc/hostapd/mrit-hotspot.conf -P /run/mrit-hostapd.pid
sleep 1

# Iniciar dnsmasq para DHCP (apenas na interface wlan0)
dnsmasq --conf-file=/etc/dnsmasq.d/mrit-hotspot.conf \
        --pid-file=/run/mrit-dnsmasq.pid \
        --except-interface=lo

RC=$?
if [ $RC -eq 0 ]; then
    logger -t mrit-wifi "Hotspot MRIT-Setup ativo (IP: 10.42.0.1). Acesse http://10.42.0.1"
else
    logger -t mrit-wifi "Falha ao iniciar dnsmasq (rc=$RC)"
fi
