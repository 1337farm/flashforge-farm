package com.flashforge.farm.modelrepo;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent CopyOnWriteArrayList;

/**
 * Centralized security event logging facility.
 * Provides structured logging for security-related events with categorization,
 * severity levels, and optional callback listeners.
 */
public final class SecurityLogger {
    private static final String TAG = "FarmSecurity";
    
    /**
     * Severity levels for security events.
     */
    public enum Severity {
        DEBUG,
        INFO,
        WARNING,
        ERROR,
        CRITICAL
    }
    
    /**
     * Categories of security events.
     */
    public enum Category {
        AUTHENTICATION,
        VERIFICATION,
        TRUST,
        ENCRYPTION,
        NETWORK,
        CONTENT,
        SIGNATURE,
        RATE_LIMIT,
        QUARANTINE,
        MODERATION
    }
    
    /**
     * Listener interface for security events.
     */
    public interface SecurityEventListener {
        void onSecurityEvent(Severity severity, Category category, String message, String details, long timestamp);
    }
    
    /**
     * Represents a logged security event.
     */
    public static class SecurityEvent {
        public final Severity severity;
        public final Category category;
        public final String message;
        public final String details;
        public final long timestamp;
        public final String threadName;
        public final String className;
        public final String methodName;
        
        public SecurityEvent(Severity severity, Category category, String message, 
                           String details, long timestamp, String threadName, 
                           String className, String methodName) {
            this.severity = severity;
            this.category = category;
            this.message = message;
            this.details = details;
            this.timestamp = timestamp;
            this.threadName = threadName;
            this.className = className;
            this.methodName = methodName;
        }
        
        @Override
        public String toString() {
            return String.format("[%s/%s] %s: %s (thread=%s, class=%s, method=%s)",
                    severity, category, message, details, threadName, className, methodName);
        }
    }
    
    private static final List<SecurityEventListener> listeners = new CopyOnWriteArrayList<>();
    private static final List<SecurityEvent> eventHistory = Collections.synchronizedList(new ArrayList<>());
    private static final int MAX_HISTORY_SIZE = 1000;
    private static boolean enabled = true;
    private static Severity minLogLevel = Severity.DEBUG;
    
    private SecurityLogger() {
        // Utility class
    }
    
    /**
     * Enable or disable security logging.
     */
    public static void setEnabled(boolean enabled) {
        SecurityLogger.enabled = enabled;
    }
    
    /**
     * Check if security logging is enabled.
     */
    public static boolean isEnabled() {
        return enabled;
    }
    
    /**
     * Set the minimum log level to output.
     */
    public static void setMinLogLevel(Severity level) {
        minLogLevel = level;
    }
    
    /**
     * Get the current minimum log level.
     */
    public static Severity getMinLogLevel() {
        return minLogLevel;
    }
    
    /**
     * Add a listener for security events.
     */
    public static void addListener(SecurityEventListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }
    
    /**
     * Remove a listener.
     */
    public static void removeListener(SecurityEventListener listener) {
        listeners.remove(listener);
    }
    
    /**
     * Clear all listeners.
     */
    public static void clearListeners() {
        listeners.clear();
    }
    
    /**
     * Get the event history.
     */
    public static List<SecurityEvent> getEventHistory() {
        return new ArrayList<>(eventHistory);
    }
    
    /**
     * Clear the event history.
     */
    public static void clearHistory() {
        eventHistory.clear();
    }
    
    /**
     * Get the maximum history size.
     */
    public static int getMaxHistorySize() {
        return MAX_HISTORY_SIZE;
    }
    
    /**
     * Set the maximum history size.
     */
    public static void setMaxHistorySize(int size) {
        synchronized (eventHistory) {
            MAX_HISTORY_SIZE = size;
            // Trim history if needed
            while (eventHistory.size() > MAX_HISTORY_SIZE) {
                eventHistory.remove(0);
            }
        }
    }
    
    /**
     * Log a security event.
     */
    public static void log(Severity severity, Category category, String message) {
        log(severity, category, message, null);
    }
    
    /**
     * Log a security event with details.
     */
    public static void log(Severity severity, Category category, String message, String details) {
        log(severity, category, message, details, null);
    }
    
    /**
     * Log a security event with an exception.
     */
    public static void log(Severity severity, Category category, String message, Throwable throwable) {
        String details = throwable != null ? Log.getStackTraceString(throwable) : null;
        log(severity, category, message, details, throwable);
    }
    
    /**
     * Log a security event with all options.
     */
    public static void log(Severity severity, Category category, String message, 
                         String details, Throwable throwable) {
        if (!enabled || severity.ordinal() < minLogLevel.ordinal()) {
            return;
        }
        
        long timestamp = System.currentTimeMillis();
        String threadName = Thread.currentThread().getName();
        
        // Get caller information
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        String className = "unknown";
        String methodName = "unknown";
        
        if (stackTrace != null && stackTrace.length > 2) {
            StackTraceElement caller = stackTrace[2];
            className = caller.getClassName();
            methodName = caller.getMethodName();
        }
        
        // Build the full message
        String fullMessage = String.format("[%s/%s] %s: %s", 
                severity, category, className, message);
        
        // Log to Android Log
        switch (severity) {
            case DEBUG:
                Log.d(TAG, fullMessage);
                break;
            case INFO:
                Log.i(TAG, fullMessage);
                break;
            case WARNING:
                Log.w(TAG, fullMessage);
                break;
            case ERROR:
            case CRITICAL:
                Log.e(TAG, fullMessage, throwable);
                break;
        }
        
        // Create event object
        SecurityEvent event = new SecurityEvent(severity, category, message, 
                details != null ? details : (throwable != null ? throwable.getMessage() : ""),
                timestamp, threadName, className, methodName);
        
        // Add to history
        synchronized (eventHistory) {
            eventHistory.add(event);
            // Trim history if needed
            while (eventHistory.size() > MAX_HISTORY_SIZE) {
                eventHistory.remove(0);
            }
        }
        
        // Notify listeners
        for (SecurityEventListener listener : listeners) {
            try {
                listener.onSecurityEvent(severity, category, message, 
                        details != null ? details : (throwable != null ? throwable.getMessage() : ""),
                        timestamp);
            } catch (Exception e) {
                Log.e(TAG, "Security event listener error", e);
            }
        }
    }
    
    // Convenience methods for each category
    
    public static void logAuth(Severity severity, String message) {
        log(severity, Category.AUTHENTICATION, message);
    }
    
    public static void logAuth(Severity severity, String message, String details) {
        log(severity, Category.AUTHENTICATION, message, details);
    }
    
    public static void logAuth(Severity severity, String message, Throwable throwable) {
        log(severity, Category.AUTHENTICATION, message, throwable);
    }
    
    public static void logVerification(Severity severity, String message) {
        log(severity, Category.VERIFICATION, message);
    }
    
    public static void logVerification(Severity severity, String message, String details) {
        log(severity, Category.VERIFICATION, message, details);
    }
    
    public static void logVerification(Severity severity, String message, Throwable throwable) {
        log(severity, Category.VERIFICATION, message, throwable);
    }
    
    public static void logTrust(Severity severity, String message) {
        log(severity, Category.TRUST, message);
    }
    
    public static void logTrust(Severity severity, String message, String details) {
        log(severity, Category.TRUST, message, details);
    }
    
    public static void logTrust(Severity severity, String message, Throwable throwable) {
        log(severity, Category.TRUST, message, throwable);
    }
    
    public static void logEncryption(Severity severity, String message) {
        log(severity, Category.ENCRYPTION, message);
    }
    
    public static void logEncryption(Severity severity, String message, String details) {
        log(severity, Category.ENCRYPTION, message, details);
    }
    
    public static void logEncryption(Severity severity, String message, Throwable throwable) {
        log(severity, Category.ENCRYPTION, message, throwable);
    }
    
    public static void logNetwork(Severity severity, String message) {
        log(severity, Category.NETWORK, message);
    }
    
    public static void logNetwork(Severity severity, String message, String details) {
        log(severity, Category.NETWORK, message, details);
    }
    
    public static void logNetwork(Severity severity, String message, Throwable throwable) {
        log(severity, Category.NETWORK, message, throwable);
    }
    
    public static void logContent(Severity severity, String message) {
        log(severity, Category.CONTENT, message);
    }
    
    public static void logContent(Severity severity, String message, String details) {
        log(severity, Category.CONTENT, message, details);
    }
    
    public static void logContent(Severity severity, String message, Throwable throwable) {
        log(severity, Category.CONTENT, message, throwable);
    }
    
    public static void logSignature(Severity severity, String message) {
        log(severity, Category.SIGNATURE, message);
    }
    
    public static void logSignature(Severity severity, String message, String details) {
        log(severity, Category.SIGNATURE, message, details);
    }
    
    public static void logSignature(Severity severity, String message, Throwable throwable) {
        log(severity, Category.SIGNATURE, message, throwable);
    }
    
    public static void logRateLimit(Severity severity, String message) {
        log(severity, Category.RATE_LIMIT, message);
    }
    
    public static void logRateLimit(Severity severity, String message, String details) {
        log(severity, Category.RATE_LIMIT, message, details);
    }
    
    public static void logQuarantine(Severity severity, String message) {
        log(severity, Category.QUARANTINE, message);
    }
    
    public static void logQuarantine(Severity severity, String message, String details) {
        log(severity, Category.QUARANTINE, message, details);
    }
    
    public static void logModeration(Severity severity, String message) {
        log(severity, Category.MODERATION, message);
    }
    
    public static void logModeration(Severity severity, String message, String details) {
        log(severity, Category.MODERATION, message, details);
    }
    
    // Specific event methods for common security scenarios
    
    /**
     * Log a hash verification failure.
     */
    public static void logHashVerificationFailure(String modelHash, String expected, String actual) {
        String details = String.format("Expected: %s, Got: %s", 
                truncateHash(expected), truncateHash(actual));
        log(Severity.WARNING, Category.VERIFICATION, 
                "Hash verification failed for model", details);
    }
    
    /**
     * Log a signature verification failure.
     */
    public static void logSignatureVerificationFailure(String pubkey, String reason) {
        log(Severity.WARNING, Category.SIGNATURE,
                "Signature verification failed", 
                String.format("Pubkey: %s, Reason: %s", truncateKey(pubkey), reason));
    }
    
    /**
     * Log content being quarantined.
     */
    public static void logContentQuarantined(String ticket, String reason) {
        log(Severity.WARNING, Category.QUARANTINE,
                "Content quarantined", 
                String.format("Ticket: %s, Reason: %s", truncateHash(ticket), reason));
    }
    
    /**
     * Log a trust change.
     */
    public static void logTrustChange(String pubkey, TrustStore.Level oldLevel, TrustStore.Level newLevel) {
        log(Severity.INFO, Category.TRUST,
                "Trust level changed",
                String.format("Pubkey: %s, %s -> %s", truncateKey(pubkey), oldLevel, newLevel));
    }
    
    /**
     * Log a blocked peer.
     */
    public static void logPeerBlocked(String pubkey, String reason) {
        log(Severity.INFO, Category.TRUST,
                "Peer blocked",
                String.format("Pubkey: %s, Reason: %s", truncateKey(pubkey), reason));
    }
    
    /**
     * Log rate limit exceeded.
     */
    public static void logRateLimitExceeded(String operation, long waitTimeMs) {
        log(Severity.WARNING, Category.RATE_LIMIT,
                "Rate limit exceeded",
                String.format("Operation: %s, Wait: %dms", operation, waitTimeMs));
    }
    
    /**
     * Log encryption status.
     */
    public static void logEncryptionStatus(boolean enabled, String details) {
        log(Severity.INFO, Category.ENCRYPTION,
                "Encryption status",
                String.format("Enabled: %s, Details: %s", enabled, details));
    }
    
    /**
     * Log a model verification failure.
     */
    public static void logModelVerificationFailure(String modelHash, String reason, String detail) {
        log(Severity.WARNING, Category.VERIFICATION,
                "Model verification failed",
                String.format("Hash: %s, Reason: %s, Detail: %s", 
                        truncateHash(modelHash), reason, detail));
    }
    
    /**
     * Log authentication failure.
     */
    public static void logAuthenticationFailure(String pubkey, String reason) {
        log(Severity.WARNING, Category.AUTHENTICATION,
                "Authentication failed",
                String.format("Pubkey: %s, Reason: %s", truncateKey(pubkey), reason));
    }
    
    /**
     * Truncate a hash for logging (show first 8 and last 8 characters).
     */
    public static String truncateHash(String hash) {
        if (hash == null || hash.length() <= 16) {
            return hash != null ? hash : "null";
        }
        return hash.substring(0, 8) + "..." + hash.substring(hash.length() - 8);
    }
    
    /**
     * Truncate a public key for logging.
     */
    public static String truncateKey(String key) {
        if (key == null || key.length() <= 16) {
            return key != null ? key : "null";
        }
        return key.substring(0, 8) + "..." + key.substring(key.length() - 8);
    }
    
    /**
     * Get a summary of recent security events.
     */
    public static String getEventSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Security Event Summary ===\n");
        
        int[] counts = new int[Severity.values().length];
        int[] categoryCounts = new int[Category.values().length];
        
        synchronized (eventHistory) {
            for (SecurityEvent event : eventHistory) {
                counts[event.severity.ordinal()]++;
                categoryCounts[event.category.ordinal()]++;
            }
        }
        
        sb.append("By Severity:\n");
        for (Severity s : Severity.values()) {
            sb.append(String.format("  %s: %d\n", s, counts[s.ordinal()]));
        }
        
        sb.append("\nBy Category:\n");
        for (Category c : Category.values()) {
            sb.append(String.format("  %s: %d\n", c, categoryCounts[c.ordinal()]));
        }
        
        sb.append(String.format("\nTotal events in history: %d\n", eventHistory.size()));
        sb.append(String.format("Max history size: %d\n", MAX_HISTORY_SIZE));
        
        return sb.toString();
    }
}
