package com.flashforge.farm.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PrinterFleetManager {
    private static final String PREF_KEY = "printer_fleet";
    private static final Gson gson = new Gson();

    public static class Printer {
        public String id;
        public String ipOrUrl;
        public String name;
        public String nozzleSize;
        public String loadedFilamentType;
        public String loadedFilamentColor;
        public String publicKey;
        public String accessCode;

        public Printer(String id, String ipOrUrl, String name, String nozzleSize, String loadedFilamentType, String loadedFilamentColor) {
            this(id, ipOrUrl, name, nozzleSize, loadedFilamentType, loadedFilamentColor, generatePublicKey(), generateAccessCode(generatePublicKey()));
        }

        public Printer(String id, String ipOrUrl, String name, String nozzleSize, String loadedFilamentType, String loadedFilamentColor, String publicKey, String accessCode) {
            this.id = id;
            this.ipOrUrl = ipOrUrl;
            this.name = name;
            this.nozzleSize = nozzleSize;
            this.loadedFilamentType = loadedFilamentType;
            this.loadedFilamentColor = loadedFilamentColor;
            this.publicKey = publicKey;
            this.accessCode = accessCode;
        }
    }

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    /** Generate the p2p public identity. Prefers Ed25519 (API 28+); falls back to a
     *  SHA-256-derived identity on older devices so minSdk 21 still works. */
    public static String generatePublicKey() {
        try {
            // Try Ed25519 first (API 28+)
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
            kpg.initialize(256, new SecureRandom());
            KeyPair kp = kpg.generateKeyPair();
            return Base64.encodeToString(kp.getPublic().getEncoded(), Base64.NO_WRAP);
        } catch (Exception e) {
            // Fall back to SHA-256 derived identity
            try {
                byte[] seed = new SecureRandom().generateSeed(32);
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(seed);
                return Base64.encodeToString(digest, Base64.NO_WRAP);
            } catch (Exception ee) {
                // Last resort: use nanoTime as hex string
                return Long.toHexString(System.nanoTime());
            }
        }
    }

    /** Derive a short, stable, human-friendly unique code from the public key. */
    public static String generateAccessCode(String publicKey) {
        if (publicKey == null) return "UNKNOWN";
        String upper = publicKey.replaceAll("\\W", "").toUpperCase(Locale.ROOT);
        if (upper.length() < 8) return upper;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.append(upper.charAt(upper.length() - 1 - i * 3));
        return sb.reverse().toString();
    }

    /** Find a printer by its id in the fleet. */
    public static Printer findPrinter(String printerId) {
        List<Printer> printers = getPrinters();
        for (Printer p : printers) {
            if (p.id.equals(printerId)) {
                return p;
            }
        }
        return null;
    }

    /** Find a printer by its access code in the fleet. */
    public static Printer findByAccessCode(String accessCode) {
        List<Printer> printers = getPrinters();
        for (Printer p : printers) {
            if (p.accessCode != null && p.accessCode.equals(accessCode)) {
                return p;
            }
        }
        return null;
    }

    public static List<Printer> getPrinters() {
        SharedPreferences prefs = Prefs.getPrefs();
        String json = prefs.getString(PREF_KEY, "[]");
        return gson.fromJson(json, new TypeToken<List<Printer>>(){}.getType());
    }

    public static void savePrinters(List<Printer> printers) {
        SharedPreferences.Editor editor = Prefs.getPrefs().edit();
        editor.putString(PREF_KEY, gson.toJson(printers));
        editor.apply();
    }

    public static void addPrinter(Printer printer) {
        List<Printer> printers = getPrinters();
        printers.add(printer);
        savePrinters(printers);
    }

    public static void removePrinter(String id) {
        List<Printer> printers = getPrinters();
        for (int i = 0; i < printers.size(); i++) {
            if (printers.get(i).id.equals(id)) {
                printers.remove(i);
                break;
            }
        }
        savePrinters(printers);
    }
}
