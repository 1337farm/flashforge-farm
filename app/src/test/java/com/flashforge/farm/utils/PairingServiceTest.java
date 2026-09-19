package com.flashforge.farm.utils;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Pins the beacon-validation semantics of USB reverse-pairing.
 *
 * <p>Exercises {@link PairingService#matchBeacon} — the engine-independent core
 * of {@code onPairBeacon} — so a token/expiry regression fails here on the JVM
 * instead of silently refusing beacons on device.
 */
public class PairingServiceTest {

    private static PrinterFleetManager.Printer printer(String token, long expiresAt) {
        PrinterFleetManager.Printer p = new PrinterFleetManager.Printer(
                "id1", "usb", "TestPrinter", "0.4", "PLA", "#FFFFFF", "pub", "code", null, null);
        p.pairToken = token;
        p.pairExpiresAt = expiresAt;
        return p;
    }

    @Test
    public void constants() {
        assertEquals("farm/pair/0", PairingService.PAIR_ALPN);
        assertEquals(48L * 60 * 60 * 1000, PairingService.TOKEN_VALIDITY_MS);
    }

    @Test
    public void generateTokenFormat() {
        String t = PairingService.generateToken();
        assertTrue(t.matches("[0-9a-f]{64}"));
    }

    @Test
    public void matchBindsAndClearsToken() {
        List<PrinterFleetManager.Printer> fleet = new ArrayList<>();
        fleet.add(printer("ABCDEF1234", 10_000L));
        PrinterFleetManager.Printer hit =
                PairingService.matchBeacon(fleet, 9_000L, "PEERnodeID", "abcdef1234");
        assertSame(fleet.get(0), hit);
        assertEquals("peernodeid", hit.nodeId);
        assertNull(hit.pairToken);
        assertEquals(0L, hit.pairExpiresAt);
    }

    @Test
    public void expiredTokenDoesNotMatch() {
        List<PrinterFleetManager.Printer> fleet = new ArrayList<>();
        PrinterFleetManager.Printer p = printer("tok", 9_000L);
        fleet.add(p);
        assertNull(PairingService.matchBeacon(fleet, 9_000L, "peer", "tok"));
        assertNull(PairingService.matchBeacon(fleet, 10_000L, "peer", "tok"));
        assertEquals("tok", p.pairToken);
        assertNull(p.nodeId);
    }

    @Test
    public void wrongTokenDoesNotMatch() {
        List<PrinterFleetManager.Printer> fleet = new ArrayList<>();
        fleet.add(printer("tok", 10_000L));
        assertNull(PairingService.matchBeacon(fleet, 9_000L, "peer", "other"));
    }

    @Test
    public void nullInputsDoNotMatch() {
        List<PrinterFleetManager.Printer> fleet = new ArrayList<>();
        fleet.add(printer("tok", 10_000L));
        assertNull(PairingService.matchBeacon(fleet, 9_000L, null, "tok"));
        assertNull(PairingService.matchBeacon(fleet, 9_000L, "peer", null));
        assertNull(PairingService.matchBeacon(null, 9_000L, "peer", "tok"));
        assertNull(PairingService.matchBeacon(new ArrayList<PrinterFleetManager.Printer>(), 9_000L, "peer", "tok"));
    }

    @Test
    public void secondPrinterMatchesWhenFirstLacksToken() {
        List<PrinterFleetManager.Printer> fleet = new ArrayList<>();
        PrinterFleetManager.Printer plain = printer(null, 0L);
        PrinterFleetManager.Printer pending = printer("tok", 10_000L);
        fleet.add(plain);
        fleet.add(pending);
        assertSame(pending, PairingService.matchBeacon(fleet, 9_000L, "peer", "tok"));
        assertNull(plain.nodeId);
    }
}
