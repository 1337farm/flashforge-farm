package com.flashforge.farm.utils;

import java.util.List;
import java.util.Locale;

/**
 * USB reverse-pairing: token lifecycle + beacon validation.
 *
 * <p>The USB stick carries the phone's NodeId and a one-time token to the printer
 * ({@code phone-*.addr}, see docs/usb-pairing.md). The printer dials the phone first
 * on ALPN {@link #PAIR_ALPN} presenting the token. This class validates the beacon
 * and binds the authenticated peer NodeId to the provisioned fleet printer.
 *
 * <p>Transport delivery (engine accept-loop) calls {@link #onPairBeacon}; all
 * validation and binding logic lives here and is engine-independent.
 */
public final class PairingService {
    private PairingService() {
    }

    /** ALPN the printer dials for the pairing handshake. */
    public static final String PAIR_ALPN = "farm/pair/0";

    public static final String PHONE_ADDR_FORMAT = "flashforge-farm-phone-addr";
    public static final int PHONE_ADDR_VERSION = 1;

    /** Pairing tokens expire 48h after provisioning. */
    public static final long TOKEN_VALIDITY_MS = 48L * 60 * 60 * 1000;

    /** New 256-bit pairing token, lowercase hex. */
    public static String generateToken() {
        return NodeIdentity.toHex(NodeIdentity.randomBytes(32));
    }

    /**
     * Validate a pair-beacon from an incoming connection.
     *
     * @param peerNodeIdHex printer NodeId from the authenticated handshake (peer public key)
     * @param tokenHex      token presented by the printer
     * @return the bound printer, or null when no pending, unexpired record matches
     */
    public static synchronized PrinterFleetManager.Printer onPairBeacon(String peerNodeIdHex, String tokenHex) {
        if (peerNodeIdHex == null || tokenHex == null) return null;
        List<PrinterFleetManager.Printer> fleet = PrinterFleetManager.getPrinters();
        if (fleet == null) return null;
        long now = System.currentTimeMillis();
        for (PrinterFleetManager.Printer p : fleet) {
            if (p.pairToken != null && p.pairToken.equalsIgnoreCase(tokenHex) && p.pairExpiresAt > now) {
                p.nodeId = peerNodeIdHex.toLowerCase(Locale.ROOT);
                p.pairToken = null;
                p.pairExpiresAt = 0;
                PrinterFleetManager.savePrinters(fleet);
                return p;
            }
        }
        return null;
    }
}
