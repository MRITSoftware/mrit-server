#!/bin/bash

SERVICE_NAME="mrit-server"

echo "=== MRIT Server - Status ==="
echo ""
sudo systemctl status "$SERVICE_NAME" --no-pager
echo ""
echo "Ultimas 30 linhas de log:"
journalctl -u "$SERVICE_NAME" -n 30 --no-pager
