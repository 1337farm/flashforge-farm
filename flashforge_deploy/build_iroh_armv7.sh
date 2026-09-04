#!/bin/bash
# build_iroh_armv7.sh
# Automated Cross-Compilation Script for Iroh on Flashforge 3D Printers (armv7-unknown-linux-gnueabihf)

set -e

# Target Architecture for the Flashforge Motherboard
TARGET="armv7-unknown-linux-gnueabihf"
IROH_REPO="https://github.com/n0-computer/iroh.git"
BUILD_DIR="/tmp/iroh_build"
OUTPUT_DIR="$(pwd)/bin"

echo "[*] Starting automated cross-compilation for $TARGET..."

# 1. Verify dependencies
if ! command -v cargo &> /dev/null; then
    echo "[!] Error: Rust/Cargo is not installed. Please install Rust (https://rustup.rs/) before continuing."
    exit 1
fi

if ! command -v cross &> /dev/null; then
    echo "[*] 'cross' is not installed. Installing cross (Docker-based Rust cross-compiler)..."
    cargo install cross --git https://github.com/cross-rs/cross
fi

if ! command -v docker &> /dev/null; then
    echo "[!] Error: Docker is not installed or not in PATH. 'cross' requires Docker to run."
    exit 1
fi

# 2. Setup build directory
echo "[*] Cloning Iroh repository..."
rm -rf "$BUILD_DIR"
git clone "$IROH_REPO" "$BUILD_DIR"
cd "$BUILD_DIR"

# Checkout latest stable release tag (optional, currently checking out main or latest tag)
LATEST_TAG=$(git describe --tags `git rev-list --tags --max-count=1`)
echo "[*] Checking out latest release tag: $LATEST_TAG"
git checkout "$LATEST_TAG"

# 3. Cross-compile
echo "[*] Compiling Iroh node for $TARGET..."
# We use cross to ensure the correct glibc and linker are used inside a controlled container
cross build --bin iroh --target "$TARGET" --release

# 4. Extract binary and hash
mkdir -p "$OUTPUT_DIR"
BINARY_PATH="$BUILD_DIR/target/$TARGET/release/iroh"

if [ ! -f "$BINARY_PATH" ]; then
    echo "[!] Error: Binary not found after build at $BINARY_PATH"
    exit 1
fi

echo "[*] Copying binary to output directory: $OUTPUT_DIR"
cp "$BINARY_PATH" "$OUTPUT_DIR/iroh"

# 5. Calculate SHA-256 Hash
echo "[*] Calculating SHA-256 Hash for verification phase..."
if command -v sha256sum &> /dev/null; then
    HASH=$(sha256sum "$OUTPUT_DIR/iroh" | awk '{print $1}')
elif command -v shasum &> /dev/null; then
    HASH=$(shasum -a 256 "$OUTPUT_DIR/iroh" | awk '{print $1}')
else
    echo "[!] Warning: Cannot calculate hash, sha256sum/shasum not found."
    HASH="<calculate-manually>"
fi

echo "============================================================"
echo " BUILD SUCCESSFUL!"
echo " Binary Location: $OUTPUT_DIR/iroh"
echo " Target Arch:     $TARGET"
echo " SHA-256 Hash:    $HASH"
echo "============================================================"
echo ""
echo "Next Steps:"
echo "1. Upload '$OUTPUT_DIR/iroh' to a trusted secure hosting server."
echo "2. Update 'downloadUrl' in 'IrohProvisioningManager.kt' to point to your hosted URL."
echo "3. Update 'expectedHash' in 'IrohProvisioningManager.kt' to exactly: \"$HASH\""
echo "============================================================"
exit 0
