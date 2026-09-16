#!/usr/bin/env bash
# Custom upterm debugging script for GitHub Actions failures
# Sets up upterm for SSH debugging when a CI job fails

set -euo pipefail

# Configuration
TIMEOUT_MINUTES=5
UPTERM_DIR="/tmp/prusa-upterm"
UPTERM_BIN="/usr/local/bin/upterm"
mkdir -p "$UPTERM_DIR"

install_upterm() {
    echo "Installing upterm..."
    
    local tarball="$UPTERM_DIR/upterm.tar.gz"
    if command -v curl >/dev/null 2>&1; then
        curl -sSL "https://github.com/owenthereal/upterm/releases/latest/download/upterm-linux-amd64.tar.gz" -o "$tarball"
    elif command -v wget >/dev/null 2>&1; then
        wget -q "https://github.com/owenthereal/upterm/releases/latest/download/upterm-linux-amd64.tar.gz" -O "$tarball"
    else
        echo "ERROR: Neither curl nor wget found"
        exit 1
    fi
    
    tar -xzf "$tarball" -C /usr/local/bin upterm
    chmod +x /usr/local/bin/upterm
    rm "$tarball"
    
    command -v upterm >/dev/null 2>&1 || { echo "ERROR: Failed to install upterm"; exit 1; }
    echo "upterm installed successfully"
}

setup_ssh_keys() {
    local ssh_dir="$HOME/.ssh"
    mkdir -p "$ssh_dir"
    
    if [ ! -f "$ssh_dir/id_rsa" ]; then
        ssh-keygen -q -t rsa -N "" -f "$ssh_dir/id_rsa"
    fi
    if [ ! -f "$ssh_dir/id_ed25519" ]; then
        ssh-keygen -q -t ed25519 -N "" -f "$ssh_dir/id_ed25519"
    fi
    
    cat >> "$ssh_dir/config" << 'EOF'
Host *
  StrictHostKeyChecking no
  CheckHostIP no
  TCPKeepAlive yes
  ServerAliveInterval 30
  ServerAliveCountMax 180
EOF
}

install_upterm
setup_ssh_keys

echo ""
echo "========================================"
echo "UPTERM DEBUGGING SESSION"
echo "========================================"
echo ""
echo "Starting upterm session on this runner..."
echo "Session will timeout after ${TIMEOUT_MINUTES} minutes"
echo ""

# Start upterm host session
# Uses the default upterm server (uptermd.upterm.dev)
# The --force-command runs a tmux shell when someone connects
upterm host --skip-host-key-check --accept --server ssh://uptermd.upterm.dev:22 \
    --force-command 'tmux attach -t upterm' -- \
    tmux new -s upterm -f read-only -x 132 -y 43 &

UPTERM_PID=$!
echo "Upterm session started with PID: $UPTERM_PID"

# Wait for upterm to initialize
sleep 5

echo ""
echo "Waiting for user to connect (or timeout in ${TIMEOUT_MINUTES} minutes)..."
echo "Press Ctrl-C to shutdown the session and continue the workflow."
echo ""

trap 'echo "Shutting down..."; kill $UPTERM_PID 2>/dev/null || true; exit 0' INT TERM

sleep ${TIMEOUT_MINUTES}m

echo ""
echo "Timeout reached, shutting down upterm server"
kill $UPTERM_PID 2>/dev/null || true
exit 0