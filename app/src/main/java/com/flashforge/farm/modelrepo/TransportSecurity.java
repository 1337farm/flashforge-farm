package com.flashforge.farm.modelrepo;

import android.util.Log;

import com.flashforge.farm.modelrepo.ModelTransport.UnavailableException;

/**
 * Transport layer security verification and monitoring.
 * Provides encryption status checking and security validation for the P2P transport.
 */
public final class TransportSecurity {
    private static final String TAG = "TransportSecurity";
    
    /**
     * Encryption status of the transport.
     */
    public enum EncryptionStatus {
        /** Encryption is enabled and verified */
        ENABLED,
        /** Encryption is disabled */
        DISABLED,
        /** Encryption status is unknown/indeterminate */
        UNKNOWN,
        /** Encryption verification failed */
        VERIFICATION_FAILED
    }
    
    private TransportSecurity() {
        // Utility class
    }
    
    /**
     * Verify that transport encryption is enabled.
     * 
     * @param transport The IrohModelTransport to verify
     * @return EncryptionStatus indicating the current encryption state
     */
    public static EncryptionStatus verifyEncryption(IrohModelTransport transport) {
        if (transport == null) {
            SecurityLogger.log(SecurityLogger.Severity.ERROR, SecurityLogger.Category.ENCRYPTION,
                    "Transport encryption verification failed",
                    "Transport is null");
            return EncryptionStatus.UNKNOWN;
        }
        
        if (!transport.isReady()) {
            SecurityLogger.log(SecurityLogger.Severity.WARNING, SecurityLogger.Category.ENCRYPTION,
                    "Transport encryption verification skipped",
                    "Transport not initialized");
            return EncryptionStatus.UNKNOWN;
        }
        
        try {
            // Check if transport has a secret key (implies encryption is enabled)
            byte[] secretKey = transport.getSecretKey();
            
            if (secretKey == null || secretKey.length == 0) {
                SecurityLogger.log(SecurityLogger.Severity.ERROR, SecurityLogger.Category.ENCRYPTION,
                        "Transport encryption verification failed",
                        "No secret key configured - encryption is DISABLED");
                return EncryptionStatus.DISABLED;
            }
            
            // Check if the secret key is the correct length (32 bytes for Ed25519)
            if (secretKey.length != 32) {
                SecurityLogger.log(SecurityLogger.Severity.WARNING, SecurityLogger.Category.ENCRYPTION,
                        "Transport encryption verification: unexpected key length",
                        "Key length: " + secretKey.length + " bytes (expected 32)");
                // Still consider it enabled if there's a key, just log the unusual length
            }
            
            // Verify endpoint ID is available (implies successful initialization)
            String endpointId = transport.getEndpointId();
            if (endpointId == null || endpointId.isEmpty()) {
                SecurityLogger.log(SecurityLogger.Severity.ERROR, SecurityLogger.Category.ENCRYPTION,
                        "Transport encryption verification failed",
                        "No endpoint ID available");
                return EncryptionStatus.VERIFICATION_FAILED;
            }
            
            // Log successful encryption verification
            SecurityLogger.logEncryptionStatus(true, 
                    "Endpoint: " + SecurityLogger.truncateHash(endpointId) + 
                    ", Key: " + SecurityLogger.truncateHash(IrohModelTransport.bytesToHex(secretKey)));
            
            return EncryptionStatus.ENABLED;
            
        } catch (Exception e) {
            SecurityLogger.log(SecurityLogger.Severity.ERROR, SecurityLogger.Category.ENCRYPTION,
                    "Transport encryption verification error",
                    "Error: " + e.getMessage());
            Log.e(TAG, "Encryption verification error", e);
            return EncryptionStatus.VERIFICATION_FAILED;
        }
    }
    
    /**
     * Check if encryption is enabled on the transport.
     * 
     * @param transport The IrohModelTransport to check
     * @return true if encryption is enabled, false otherwise
     */
    public static boolean isEncryptionEnabled(IrohModelTransport transport) {
        EncryptionStatus status = verifyEncryption(transport);
        return status == EncryptionStatus.ENABLED;
    }
    
    /**
     * Require encryption to be enabled, throwing an exception if not.
     * This should be called during initialization to ensure encryption is active.
     * 
     * @param transport The IrohModelTransport to verify
     * @throws UnavailableException if encryption is not enabled
     */
    public static void requireEncryption(IrohModelTransport transport) throws UnavailableException {
        EncryptionStatus status = verifyEncryption(transport);
        
        if (status != EncryptionStatus.ENABLED) {
            String message = "Transport encryption is required but is " + status;
            SecurityLogger.log(SecurityLogger.Severity.ERROR, SecurityLogger.Category.ENCRYPTION,
                    "Transport encryption requirement failed",
                    message);
            throw new UnavailableException(message);
        }
        
        SecurityLogger.log(SecurityLogger.Severity.INFO, SecurityLogger.Category.ENCRYPTION,
                "Transport encryption requirement satisfied",
                "Encryption is enabled and verified");
    }
    
    /**
     * Verify encryption during transport initialization.
     * This method should be called after transport.initialize() to ensure encryption is active.
     * 
     * @param transport The IrohModelTransport that was just initialized
     * @param requireEncryption If true, throw an exception if encryption is not enabled
     * @return EncryptionStatus
     * @throws UnavailableException if requireEncryption is true and encryption is not enabled
     */
    public static EncryptionStatus verifyPostInitialization(
            IrohModelTransport transport, boolean requireEncryption) throws UnavailableException {
        EncryptionStatus status = verifyEncryption(transport);
        
        if (requireEncryption && status != EncryptionStatus.ENABLED) {
            throw new UnavailableException("Transport encryption verification failed: " + status);
        }
        
        return status;
    }
    
    /**
     * Check if a secret key is valid (32 bytes for Ed25519).
     * 
     * @param secretKey The secret key to validate
     * @return true if the key appears valid
     */
    public static boolean isValidSecretKey(byte[] secretKey) {
        if (secretKey == null) {
            return false;
        }
        // Ed25519 keys are 32 bytes
        return secretKey.length == 32;
    }
    
    /**
     * Generate a secure random secret key for transport encryption.
     * This can be used when no key is provided and encryption is required.
     * 
     * @return A new 32-byte secret key
     */
    public static byte[] generateSecretKey() {
        return Identity.generateSeed();
    }
    
    /**
     * Get the public key corresponding to a secret key.
     * Useful for verifying that the transport was initialized with the expected key.
     * 
     * @param secretKey The secret key
     * @return The corresponding public key, or null if invalid
     */
    public static byte[] getPublicKey(byte[] secretKey) {
        if (!isValidSecretKey(secretKey)) {
            SecurityLogger.log(SecurityLogger.Severity.ERROR, SecurityLogger.Category.ENCRYPTION,
                    "Invalid secret key for public key derivation",
                    "Key length: " + (secretKey != null ? secretKey.length : 0));
            return null;
        }
        return Identity.publicKey(secretKey);
    }
    
    /**
     * Get the public key as a hex string for a given secret key.
     * 
     * @param secretKey The secret key
     * @return The corresponding public key in hex, or empty string if invalid
     */
    public static String getPublicKeyHex(byte[] secretKey) {
        byte[] pubkey = getPublicKey(secretKey);
        return pubkey != null ? Identity.hex(pubkey) : "";
    }
}
