#!/bin/bash
# Para o hotspot MRIT-Setup e devolve wlan0 ao controle do NetworkManager

# Para dnsmasq do hotspot
if [ -f /run/mrit-dnsmasq.pid ]; then
    kill "$(cat /run/mrit-dnsmasq.pid)" 2>/dev/null
    rm -f /run/mrit-dnsmasq.pid
fi

# Para hostapd
pkill -f "hostapd.*mrit-hotspot" 2>/dev/null

# Limpa IP do wlan0
ip addr flush dev wlan0 2>/dev/null

# Devolve wlan0 ao NM
nmcli device set wlan0 managed yes 2>/dev/null

logger -t mrit-wifi "Hotspot encerrado. wlan0 devolvido ao NetworkManager."
