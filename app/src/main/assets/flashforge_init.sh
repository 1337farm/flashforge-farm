#!/bin/sh
# flashforge_init.sh
# Automated Deployment Script for Iroh on Flashforge 3D Printers (ARMv7 embedded Linux)

USB_MOUNT="/mnt/usb"
MODE_FILE="$USB_MOUNT/mode.txt"
IROH_BIN="$USB_MOUNT/iroh"
IROH_KEY="$USB_MOUNT/iroh_key.priv"
IROH_PERSISTENT_DIR="/usr/data/iroh_farm"
START_APP_SCRIPT="/usr/data/apps/start_app.sh"
START_COMMAND="/usr/data/iroh_farm/iroh node start --config-path=/usr/data/iroh_farm/ &"

echo "[*] Starting Flashforge Iroh Deployment"

if [ ! -f "$IROH_BIN" ]; then
    echo "[!] Error: Iroh binary not found at $IROH_BIN"
    exit 1
fi

MODE="RAM"
if [ -f "$MODE_FILE" ]; then
    # Read the mode, stripping any trailing whitespace/newlines
    MODE=$(cat "$MODE_FILE" | tr -d '\r' | tr -d '\n' | tr '[:lower:]' '[:upper:]')
fi

apply_firewall() {
    echo "[*] Applying network isolation profile on wlan0..."

    # Flush existing rules to prevent duplicates across multiple deployments
    iptables -D OUTPUT -o wlan0 -j IROH_ISOLATION 2>/dev/null || true
    iptables -F IROH_ISOLATION 2>/dev/null || true
    iptables -X IROH_ISOLATION 2>/dev/null || true

    # Create a custom firewall chain for proactive network isolation
    iptables -N IROH_ISOLATION

    # 1. Whitelist universal private local blocks to allow local computers/slicers on the LAN to connect
    iptables -A IROH_ISOLATION -d 192.168.0.0/16 -j ACCEPT
    iptables -A IROH_ISOLATION -d 10.0.0.0/8 -j ACCEPT

    # 2. Allow outbound UDP for DNS (53) and Secure QUIC/DERP mapping (443) for Iroh hole-punching
    iptables -A IROH_ISOLATION -p udp --dport 53 -j ACCEPT
    iptables -A IROH_ISOLATION -p udp --dport 443 -j ACCEPT

    # 3. Block all outbound TCP traffic globally (kills HTTP cloud connections & OTA pings)
    iptables -A IROH_ISOLATION -p tcp -j DROP

    # 4. Drop any remaining generic external outbound WAN internet traffic
    iptables -A IROH_ISOLATION -j DROP

    # Insert the custom chain at the very top of the OUTPUT chain for wlan0
    iptables -I OUTPUT 1 -o wlan0 -j IROH_ISOLATION

    echo "[*] Firewall applied successfully."
}

# Apply network isolation rules for both modes
apply_firewall

if [ "$MODE" = "INSTALL" ]; then
    echo "[*] Selected Mode: PERSISTENT STORAGE (INSTALL)"

    # 1. Create the persistent tracking directory
    mkdir -p "$IROH_PERSISTENT_DIR"

    # 2. Copy the ARMv7 Iroh binary and identity key, and set execution permissions
    cp "$IROH_BIN" "$IROH_PERSISTENT_DIR/iroh"
    chmod +x "$IROH_PERSISTENT_DIR/iroh"

    if [ -f "$IROH_KEY" ]; then
        cp "$IROH_KEY" "$IROH_PERSISTENT_DIR/keypair.priv"
    else
        echo "[!] Warning: No identity key found at $IROH_KEY. Identity will be random."
    fi

    # 3. Ensure the vendor autostart script exists (create if necessary)
    if [ ! -f "$START_APP_SCRIPT" ]; then
        echo "[!] Warning: $START_APP_SCRIPT does not exist. Creating it."
        mkdir -p "$(dirname "$START_APP_SCRIPT")"
        touch "$START_APP_SCRIPT"
        chmod +x "$START_APP_SCRIPT"
    fi

    # Create a backup of the original start_app.sh if it doesn't already exist
    if [ ! -f "$START_APP_SCRIPT.backup" ]; then
        cp "$START_APP_SCRIPT" "$START_APP_SCRIPT.backup"
        echo "[*] Created backup of $START_APP_SCRIPT"
    fi

    # 4. Persist the firewall rules by creating a persistent firewall script
    FIREWALL_SCRIPT="$IROH_PERSISTENT_DIR/firewall.sh"
    echo "#!/bin/sh" > "$FIREWALL_SCRIPT"
    # Extract the body of apply_firewall to the script
    echo "iptables -D OUTPUT -o wlan0 -j IROH_ISOLATION 2>/dev/null || true" >> "$FIREWALL_SCRIPT"
    echo "iptables -F IROH_ISOLATION 2>/dev/null || true" >> "$FIREWALL_SCRIPT"
    echo "iptables -X IROH_ISOLATION 2>/dev/null || true" >> "$FIREWALL_SCRIPT"
    echo "iptables -N IROH_ISOLATION" >> "$FIREWALL_SCRIPT"
    echo "iptables -A IROH_ISOLATION -d 192.168.0.0/16 -j ACCEPT" >> "$FIREWALL_SCRIPT"
    echo "iptables -A IROH_ISOLATION -d 10.0.0.0/8 -j ACCEPT" >> "$FIREWALL_SCRIPT"
    echo "iptables -A IROH_ISOLATION -p udp --dport 53 -j ACCEPT" >> "$FIREWALL_SCRIPT"
    echo "iptables -A IROH_ISOLATION -p udp --dport 443 -j ACCEPT" >> "$FIREWALL_SCRIPT"
    echo "iptables -A IROH_ISOLATION -p tcp -j DROP" >> "$FIREWALL_SCRIPT"
    echo "iptables -A IROH_ISOLATION -j DROP" >> "$FIREWALL_SCRIPT"
    echo "iptables -I OUTPUT 1 -o wlan0 -j IROH_ISOLATION" >> "$FIREWALL_SCRIPT"
    chmod +x "$FIREWALL_SCRIPT"

    # 5. Append the execution hooks directly to the bottom of the start script if not present
    if ! grep -qF "$FIREWALL_SCRIPT" "$START_APP_SCRIPT"; then
        echo "" >> "$START_APP_SCRIPT"
        echo "$FIREWALL_SCRIPT" >> "$START_APP_SCRIPT"
        echo "[*] Autostart firewall hook appended to $START_APP_SCRIPT"
    fi

    if ! grep -qF "$START_COMMAND" "$START_APP_SCRIPT"; then
        echo "$START_COMMAND" >> "$START_APP_SCRIPT"
        echo "[*] Autostart node hook appended to $START_APP_SCRIPT"
    else
        echo "[*] Autostart hooks already exist in $START_APP_SCRIPT"
    fi

    # 5. Execute the Iroh node daemon for the current session
    echo "[*] Launching Iroh daemon..."
    eval "$START_COMMAND"

else
    echo "[*] Selected Mode: RAM (Volatile Memory)"

    # 1. Copy binary directly to volatile memory (/tmp) to ensure a zero footprint on reboot
    cp "$IROH_BIN" /tmp/iroh

    if [ -f "$IROH_KEY" ]; then
        cp "$IROH_KEY" /tmp/keypair.priv
    fi

    # 2. Set execution permissions
    chmod +x /tmp/iroh

    # 3. Launch the P2P daemon strictly in volatile memory
    echo "[*] Launching Iroh daemon in /tmp..."
    # The config-path points to /tmp to pick up the keypair.priv file
    /tmp/iroh node start --config-path=/tmp/ &
fi

echo "[*] Deployment fully complete."
exit 0
