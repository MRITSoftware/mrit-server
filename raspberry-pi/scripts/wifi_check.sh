#!/bin/bash

check_internet() {
    ping -c 2 -W 3 8.8.8.8 > /dev/null 2>&1
}

hotspot_active() {
    pgrep -x hostapd > /dev/null 2>&1
}

# Se wlan0 estiver unmanaged sem hotspot ativo (estado quebrado), recupera
if ! hotspot_active; then
    nmcli device set wlan0 managed yes 2>/dev/null
fi

# Hotspot ja ativo: nao interfere
if hotspot_active; then
    logger -t mrit-wifi "Hotspot MRIT-Setup ja ativo."
    exit 0
fi

# Tem internet: tudo OK
if check_internet; then
    logger -t mrit-wifi "WiFi com internet OK."
    exit 0
fi

# Sem internet e sem hotspot: cria hotspot
logger -t mrit-wifi "Sem internet. Criando hotspot..."

# Para instancia anterior de hostapd se existir
pkill -x hostapd 2>/dev/null
sleep 1

# Retira wlan0 do NM e aguarda liberacao da interface
nmcli device set wlan0 managed no 2>/dev/null
sleep 2

# Configura IP
ip addr flush dev wlan0 2>/dev/null
ip addr add 10.42.0.1/24 dev wlan0
ip link set wlan0 up

# Inicia hostapd
hostapd -B /etc/hostapd/mrit-hotspot.conf -P /run/mrit-hostapd.pid
if [ $? -ne 0 ]; then
    logger -t mrit-wifi "ERRO: hostapd falhou. Devolvendo wlan0 ao NM."
    nmcli device set wlan0 managed yes 2>/dev/null
    exit 1
fi
sleep 1

# Para dnsmasq anterior se existir
if [ -f /run/mrit-dnsmasq.pid ]; then
    kill "$(cat /run/mrit-dnsmasq.pid)" 2>/dev/null
    rm -f /run/mrit-dnsmasq.pid
    sleep 1
fi

# Inicia dnsmasq para DHCP
dnsmasq --conf-file=/etc/dnsmasq.d/mrit-hotspot.conf \
        --pid-file=/run/mrit-dnsmasq.pid \
        --except-interface=lo

if [ $? -eq 0 ]; then
    logger -t mrit-wifi "Hotspot MRIT-Setup ativo (IP: 10.42.0.1)"
else
    logger -t mrit-wifi "AVISO: dnsmasq falhou mas hostapd esta ativo"
fi
