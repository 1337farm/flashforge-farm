#!/bin/sh
# uninstall.sh
# Uninstallation Script for Iroh on Flashforge 3D Printers (ARMv7 embedded Linux)

IROH_PERSISTENT_DIR="/usr/data/iroh_farm"
START_APP_SCRIPT="/usr/data/apps/start_app.sh"
START_COMMAND="/usr/data/iroh_farm/iroh node start --config-path=/usr/data/iroh_farm/ &"

echo "[*] Starting Flashforge Iroh Uninstallation"

# 1. Kill any running iroh processes
echo "[*] Terminating any active Iroh node daemons..."
killall iroh 2>/dev/null || true

# 2. Flush and remove iptables rules
echo "[*] Reverting network isolation profile on wlan0..."
iptables -D OUTPUT -o wlan0 -j IROH_ISOLATION 2>/dev/null || true
iptables -F IROH_ISOLATION 2>/dev/null || true
iptables -X IROH_ISOLATION 2>/dev/null || true
echo "[*] Network isolation rules removed."

# 3. Remove persistent tracking directory
if [ -d "$IROH_PERSISTENT_DIR" ]; then
    echo "[*] Deleting persistent tracking directory: $IROH_PERSISTENT_DIR"
    rm -rf "$IROH_PERSISTENT_DIR"
fi

# 4. Remove RAM mode binary if present
if [ -f "/tmp/iroh" ]; then
    echo "[*] Removing volatile memory binary: /tmp/iroh"
    rm -f "/tmp/iroh"
fi

# 5. Restore the original start_app.sh backup if available
if [ -f "$START_APP_SCRIPT.backup" ]; then
    echo "[*] Restoring original $START_APP_SCRIPT backup..."
    mv "$START_APP_SCRIPT.backup" "$START_APP_SCRIPT"
    chmod +x "$START_APP_SCRIPT"
    echo "[*] Autostart script restored from backup."
elif [ -f "$START_APP_SCRIPT" ]; then
    echo "[*] No backup found. Cleaning autostart hooks from $START_APP_SCRIPT..."
    FIREWALL_SCRIPT="$IROH_PERSISTENT_DIR/firewall.sh"
    grep -vF "$START_COMMAND" "$START_APP_SCRIPT" | grep -vF "$FIREWALL_SCRIPT" > "$START_APP_SCRIPT.tmp"

    # Remove empty lines that might have been left over
    sed -i '/^[[:space:]]*$/d' "$START_APP_SCRIPT.tmp"

    mv "$START_APP_SCRIPT.tmp" "$START_APP_SCRIPT"
    chmod +x "$START_APP_SCRIPT"
    echo "[*] Autostart hooks removed."
fi

echo "[*] Uninstallation fully complete. System returned to factory-fresh status."
exit 0
