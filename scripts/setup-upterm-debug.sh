#!/usr/bin/env bash
# Custom upterm debugging script for GitHub Actions failures
# Sets up upterm for SSH debugging when a CI job fails

set -euo pipefail

# Configuration
TIMEOUT_MINUTES=5
SSH_PORT=2222

define_upterm_script() {
    cat << 'EOF'
#!/usr/bin/env bash
set -euo pipefail

# Upterm script to start the debugging session
echo "Starting upterm session..."

# Set up environment
export UPTERM_SERVER_TYPE="single"
export UPTERM_PORT=${SSH_PORT}

# Start upterm in the background
upterm-server &
UPTERM_PID=$!

echo "Upterm server started on port ${SSH_PORT}"
echo "PID: ${UPTERM_PID}"

# Display connection information
echo "=== UPTERM DEBUGGING SESSION ==="
echo "To connect, run:"
echo "  ssh -p ${SSH_PORT} localhost"
echo ""
echo "Session will timeout after ${TIMEOUT_MINUTES} minutes"
echo ""

# Wait for user to connect or timeout
sleep ${TIMEOUT_MINUTES}m

echo "Timeout reached, shutting down upterm server"
kill $UPTERM_PID 2>/dev/null || true
exit 0
EOF
}

setup_upterm_server() {
    echo "Installing upterm..."
    
    # Detect platform and install upterm
    if command -v curl >/dev/null 2>&1; then
        curl -sSL "https://github.com/owenthereal/upterm/releases/latest/download/upterm-linux-amd64.tar.gz" -o /tmp/upterm.tar.gz
        tar -xzf /tmp/upterm.tar.gz -C /usr/local/bin upterm
        chmod +x /usr/local/bin/upterm
        rm /tmp/upterm.tar.gz
    elif command -v wget >/dev/null 2>&1; then
        wget -q "https://github.com/owenthereal/upterm/releases/latest/download/upterm-linux-amd64.tar.gz" -O /tmp/upterm.tar.gz
        tar -xzf /tmp/upterm.tar.gz -C /usr/local/bin upterm
        chmod +x /usr/local/bin/upterm
        rm /tmp/upterm.tar.gz
    else
        echo "ERROR: Neither curl nor wget found"
        exit 1
    fi
}

# Create upterm script directory
UPTERM_DIR="/tmp/prusa-upterm"
mkdir -p $UPTERM_DIR

# Write upterm script
cat > $UPTERM_DIR/upterm-debug.sh << 'EOF'
#!/usr/bin/env bash
set -euo pipefail

SSH_PORT=${UPTERM_PORT:-2222}
TIMEOUT_MINUTES=${UPTERM_TIMEOUT_MINUTES:-5}

echo "=== UPTERM DEBUGGING SESSION ==="
echo "SSH Port: ${SSH_PORT}"
echo "Timeout: ${TIMEOUT_MINUTES} minutes"
echo ""
echo "To connect, run:"
echo "  ssh -p ${SSH_PORT} localhost"
echo ""
echo "Press Ctrl-C to exit this script and continue workflow"
echo ""

echo "Starting upterm server..."
upterm-server --port ${SSH_PORT} --host 127.0.0.1 &
UPTERM_PID=$!

echo "Upterm server started with PID: ${UPTERM_PID}"
echo "Server logs will continue in background"
echo ""

echo "Debug session is active. Connect via SSH to debug the runner."
echo "After connecting, use tmux to resize if needed: Ctrl-b, then :resize-window -A"
echo ""

echo "This session will timeout in ${TIMEOUT_MINUTES} minutes"
echo "Press Ctrl-C to shutdown server and continue workflow"
echo ""

trap 'echo "Shutting down upterm server..."; kill $UPTERM_PID 2>/dev/null || true; echo "Upterm server stopped. Continuing workflow..."; exit 0' INT TERM

sleep ${TIMEOUT_MINUTES}m

echo "Timeout reached, shutting down upterm server"
kill $UPTERM_PID 2>/dev/null || true
exit 0
EOF

chmod +x $UPTERM_DIR/upterm-debug.sh

# Setup upterm
echo "Setting up upterm debugging environment..."
setup_upterm_server

# Make sure upterm server is available
if ! command -v upterm-server >/dev/null 2>&1; then
    echo "ERROR: upterm-server not available"
    exit 1
fi

# Start upterm in background
echo "Starting upterm debugging session..."
UPTERM_PORT=$SSH_PORT
UPTERM_TIMEOUT_MINUTES=$TIMEOUT_MINUTES

# Environment variables
export UPTERM_SERVER_TYPE="single"
export UPTERM_HOST="127.0.0.1"
export UPTERM_PORT

# Start the upterm server
echo "Upterm server starting on 127.0.0.1:${UPTERM_PORT}"
echo "Connection URL: ssh -p ${UPTERM_PORT} localhost"
echo ""
echo "This is a limited-access debugging session for CI failures only."
echo "Press Ctrl-C to shutdown the session and continue the workflow."
echo ""
echo "========================================"
echo "DEBUGGING SESSION ACTIVE"
echo "========================================"
echo ""

# Start upterm server
upterm-server --host 127.0.0.1 --port ${UPTERM_PORT} --tmux

echo "Debug session ended."
