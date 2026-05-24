#!/bin/bash

HOTSPOT_START_FILE=/run/mrit-hotspot-start
RECONNECT_INTERVAL=600  # tenta reconectar ao WiFi apos 10 min de hotspot ativo

check_internet() {
    ping -c 2 -W 3 8.8.8.8 > /dev/null 2>&1
}

hotspot_active() {
    pgrep -x hostapd > /dev/null 2>&1
}

start_hotspot() {
    pkill -x hostapd 2>/dev/null
    [ -f /run/mrit-dnsmasq.pid ] && kill "$(cat /run/mrit-dnsmasq.pid)" 2>/dev/null
    rm -f /run/mrit-dnsmasq.pid
    sleep 1

    nmcli device set wlan0 managed no 2>/dev/null
    sleep 2
    ip addr flush dev wlan0 2>/dev/null
    ip addr add 10.42.0.1/24 dev wlan0
    ip link set wlan0 up

    hostapd -B /etc/hostapd/mrit-hotspot.conf -P /run/mrit-hostapd.pid
    if [ $? -ne 0 ]; then
        logger -t mrit-wifi "ERRO: hostapd falhou. Devolvendo wlan0 ao NM."
        nmcli device set wlan0 managed yes 2>/dev/null
        return 1
    fi
    sleep 1

    dnsmasq --conf-file=/etc/dnsmasq.d/mrit-hotspot.conf \
            --pid-file=/run/mrit-dnsmasq.pid

    date +%s > "$HOTSPOT_START_FILE"
    logger -t mrit-wifi "Hotspot MRIT-Setup ativo (IP: 10.42.0.1)"
}

try_reconnect() {
    logger -t mrit-wifi "Tentando reconectar ao WiFi..."

    pkill -x hostapd 2>/dev/null
    [ -f /run/mrit-dnsmasq.pid ] && kill "$(cat /run/mrit-dnsmasq.pid)" 2>/dev/null
    rm -f /run/mrit-dnsmasq.pid /run/mrit-hostapd.pid
    ip addr flush dev wlan0 2>/dev/null

    nmcli device set wlan0 managed yes 2>/dev/null
    sleep 2
    nmcli device connect wlan0 2>/dev/null
    sleep 25

    if check_internet; then
        logger -t mrit-wifi "WiFi restaurado com internet."
        rm -f "$HOTSPOT_START_FILE"
    else
        logger -t mrit-wifi "WiFi nao disponivel. Reativando hotspot..."
        start_hotspot
    fi
}

# --- Fluxo principal ---

# Hotspot ativo: verifica se ja passou tempo suficiente para tentar reconectar
if hotspot_active; then
    HOTSPOT_START=$(cat "$HOTSPOT_START_FILE" 2>/dev/null || echo 0)
    NOW=$(date +%s)
    ELAPSED=$((NOW - HOTSPOT_START))

    if [ "$ELAPSED" -ge "$RECONNECT_INTERVAL" ]; then
        try_reconnect
    else
        REMAINING=$((RECONNECT_INTERVAL - ELAPSED))
        logger -t mrit-wifi "Hotspot ativo. Tentativa de reconexao em ${REMAINING}s."
    fi
    exit 0
fi

# Recupera wlan0 se estiver unmanaged sem hotspot (estado quebrado)
nmcli device set wlan0 managed yes 2>/dev/null

# Tem internet?
if check_internet; then
    logger -t mrit-wifi "WiFi com internet OK."
    rm -f "$HOTSPOT_START_FILE"
    exit 0
fi

# Sem internet: cria hotspot
logger -t mrit-wifi "Sem internet. Criando hotspot..."
start_hotspot
