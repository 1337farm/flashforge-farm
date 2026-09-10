package com.flashforge.farm.modelrepo.verify;

import com.flashforge.farm.modelrepo.ModelMetadata;
import com.flashforge.farm.modelrepo.ModelSafety;
import com.flashforge.farm.modelrepo.SecurityLogger;
import com.flashforge.farm.modelrepo.TrustStore;
import com.flashforge.farm.modelrepo.moderation.LabelAggregator;
import com.flashforge.farm.modelrepo.safety.PathSanitizer;
import com.flashforge.farm.modelrepo.safety.SafetyPolicy;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.List;

public final class ModelVerifier {
    private ModelVerifier() {
    }

    public static class LocalFile {
        public final File file;
        public final String claimedSha256;

        public LocalFile(File file, String claimedSha256) {
            this.file = file;
            this.claimedSha256 = claimedSha256;
        }
    }

    public static Verdict verifyDownload(ModelMetadata meta, List<LocalFile> files,
            TrustStore trust, LabelAggregator labels) {
        if (meta == null || meta.title == null || meta.title.isEmpty()
                || meta.title.length() > SafetyPolicy.MAX_TITLE_LEN) {
            SecurityLogger.log(SecurityLogger.Severity.WARNING, SecurityLogger.Category.VERIFICATION,
                    "Download verification failed: bad title",
                    "Title length: " + (meta != null && meta.title != null ? meta.title.length() : 0));
            return Verdict.deny(Verdict.Reason.BAD_METADATA, "bad title");
        }
        if (meta.description != null && meta.description.length() > SafetyPolicy.MAX_DESC_LEN) {
            SecurityLogger.log(SecurityLogger.Severity.WARNING, SecurityLogger.Category.VERIFICATION,
                    "Download verification failed: description too long",
                    "Description length: " + meta.description.length());
            return Verdict.deny(Verdict.Reason.BAD_METADATA, "description too long");
        }
        if (meta.files == null || meta.files.isEmpty()
                || meta.files.size() > SafetyPolicy.MAX_FILE_COUNT) {
            SecurityLogger.log(SecurityLogger.Severity.WARNING, SecurityLogger.Category.VERIFICATION,
                    "Download verification failed: bad file list",
                    "File count: " + (meta.files != null ? meta.files.size() : 0));
            return Verdict.deny(Verdict.Reason.BAD_METADATA, "bad file list");
        }
        String pubkey = meta.designer == null ? "" : meta.designer.pubkey;
        if (labels != null && labels.isBlocked(pubkey)) {
            SecurityLogger.log(SecurityLogger.Severity.WARNING, SecurityLogger.Category.MODERATION,
                    "Download verification failed: blocked publisher",
                    "Pubkey: " + SecurityLogger.truncateKey(pubkey) + ", Reason: " + labels.noteFor(pubkey));
            return Verdict.deny(Verdict.Reason.BLOCKED_PUBLISHER, labels.noteFor(pubkey));
        }
        if (trust != null && trust.level(pubkey) == TrustStore.Level.BLOCKED) {
            SecurityLogger.log(SecurityLogger.Severity.WARNING, SecurityLogger.Category.TRUST,
                    "Download verification failed: blocked publisher",
                    "Pubkey: " + SecurityLogger.truncateKey(pubkey));
            return Verdict.deny(Verdict.Reason.BLOCKED_PUBLISHER, "blocked publisher");
        }
        if (files == null || files.size() != meta.files.size()) {
            SecurityLogger.log(SecurityLogger.Severity.WARNING, SecurityLogger.Category.VERIFICATION,
                    "Download verification failed: file count mismatch",
                    "Expected: " + (meta.files != null ? meta.files.size() : 0) + 
                    ", Got: " + (files != null ? files.size() : 0));
            return Verdict.deny(Verdict.Reason.BAD_METADATA, "file count mismatch");
        }
        long total = 0;
        for (int i = 0; i < files.size(); i++) {
            LocalFile lf = files.get(i);
            String clean;
            try {
                clean = PathSanitizer.clean(meta.files.get(i));
            } catch (IllegalArgumentException bad) {
                return Verdict.deny(Verdict.Reason.UNSAFE_NAME, bad.getMessage());
            }
            if (!lf.file.isFile()) {
                SecurityLogger.log(SecurityLogger.Severity.WARNING, SecurityLogger.Category.VERIFICATION,
                        "Download verification failed: file missing",
                        "File: " + clean);
                return Verdict.deny(Verdict.Reason.HASH_MISMATCH, "missing: " + clean);
            }
            long size = lf.file.length();
            try {
                ModelSafety.checkBudget(size, total, i);
            } catch (IllegalArgumentException bad) {
                SecurityLogger.log(SecurityLogger.Severity.WARNING, SecurityLogger.Category.VERIFICATION,
                        "Download verification failed: over budget",
                        "File: " + clean + ", Reason: " + bad.getMessage());
                return Verdict.deny(Verdict.Reason.OVER_BUDGET, bad.getMessage());
            }
            total += size;
            String got;
            try {
                got = sha256File(lf.file);
            } catch (Exception e) {
                SecurityLogger.log(SecurityLogger.Severity.WARNING, SecurityLogger.Category.VERIFICATION,
                        "Download verification failed: file unreadable",
                        "File: " + clean + ", Error: " + e.getMessage());
                return Verdict.deny(Verdict.Reason.HASH_MISMATCH, "unreadable: " + clean);
            }
            if (lf.claimedSha256 != null && !lf.claimedSha256.isEmpty()
                    && !lf.claimedSha256.equalsIgnoreCase(got)) {
                SecurityLogger.log(SecurityLogger.Severity.WARNING, SecurityLogger.Category.VERIFICATION,
                        "Download verification failed: hash mismatch",
                        "File: " + clean + ", Expected: " + lf.claimedSha256 + ", Got: " + got);
                return Verdict.deny(Verdict.Reason.HASH_MISMATCH, clean);
            }
            String sniffed = ModelSafety.sniffKind(lf.file);
            if (!ModelSafety.extensionMatchesSniff(ModelSafety.extensionOf(clean), sniffed)) {
                SecurityLogger.log(SecurityLogger.Severity.WARNING, SecurityLogger.Category.CONTENT,
                        "Download verification failed: unsafe type",
                        "File: " + clean + ", Sniffed: " + sniffed);
                return Verdict.deny(Verdict.Reason.UNSAFE_TYPE, clean + " is " + sniffed);
            }
        }
        if (labels != null && labels.isFlagged(pubkey)) {
            SecurityLogger.log(SecurityLogger.Severity.WARNING, SecurityLogger.Category.MODERATION,
                    "Download verification requires confirmation: flagged publisher",
                    "Pubkey: " + SecurityLogger.truncateKey(pubkey) + ", Reason: " + labels.noteFor(pubkey));
            return Verdict.confirm(Verdict.Reason.FLAGGED, labels.noteFor(pubkey));
        }
        if (trust != null && trust.needsConfirm(trust.level(pubkey))) {
            String name = meta.designer == null ? pubkey : meta.designer.name;
            SecurityLogger.log(SecurityLogger.Severity.INFO, SecurityLogger.Category.TRUST,
                    "Download verification requires confirmation: untrusted publisher",
                    "Publisher: " + name + " (" + SecurityLogger.truncateKey(pubkey) + ")");
            return Verdict.confirm(Verdict.Reason.UNTRUSTED_PUBLISHER, name);
        }
        SecurityLogger.log(SecurityLogger.Severity.DEBUG, SecurityLogger.Category.VERIFICATION,
                "Download verification passed",
                "Title: " + meta.title + ", Files: " + (meta.files != null ? meta.files.size() : 0));
        return Verdict.ok();
    }

    public static Verdict verifyForPublish(File file) {
        if (file == null || !file.isFile()) {
            SecurityLogger.log(SecurityLogger.Severity.WARNING, SecurityLogger.Category.VERIFICATION,
                    "Publish verification failed: no file",
                    "File: null");
            return Verdict.deny(Verdict.Reason.BAD_METADATA, "no file");
        }
        String clean;
        try {
            clean = PathSanitizer.clean(file.getName());
        } catch (IllegalArgumentException bad) {
            SecurityLogger.log(SecurityLogger.Severity.WARNING, SecurityLogger.Category.CONTENT,
                    "Publish verification failed: unsafe name",
                    "File: " + file.getName() + ", Reason: " + bad.getMessage());
            return Verdict.deny(Verdict.Reason.UNSAFE_NAME, bad.getMessage());
        }
        if (file.length() > SafetyPolicy.MAX_PARSE_BYTES) {
            SecurityLogger.log(SecurityLogger.Severity.WARNING, SecurityLogger.Category.VERIFICATION,
                    "Publish verification failed: file too large",
                    "File: " + clean + ", Size: " + file.length());
            return Verdict.deny(Verdict.Reason.OVER_BUDGET, "file too large to safely parse");
        }
        String sniffed = ModelSafety.sniffKind(file);
        if (!ModelSafety.extensionMatchesSniff(ModelSafety.extensionOf(clean), sniffed)) {
            SecurityLogger.log(SecurityLogger.Severity.WARNING, SecurityLogger.Category.CONTENT,
                    "Publish verification failed: unsafe type",
                    "File: " + clean + ", Sniffed: " + sniffed);
            return Verdict.deny(Verdict.Reason.UNSAFE_TYPE, "content is " + sniffed);
        }
        if (sniffed.equals("stl-binary") || sniffed.equals("stl-ascii")) {
            long facets = estimateFacets(file, sniffed);
            if (facets > SafetyPolicy.MAX_FACETS) {
                SecurityLogger.log(SecurityLogger.Severity.WARNING, SecurityLogger.Category.VERIFICATION,
                        "Publish verification failed: too many facets",
                        "File: " + clean + ", Facets: " + facets);
                return Verdict.deny(Verdict.Reason.OVER_BUDGET, "too many facets");
            }
        }
        SecurityLogger.log(SecurityLogger.Severity.DEBUG, SecurityLogger.Category.VERIFICATION,
                "Publish verification passed",
                "File: " + clean);
        return Verdict.ok();
    }

    static long estimateFacets(File file, String sniffed) {
        if (sniffed.equals("stl-binary")) {
            long len = file.length();
            if (len < 84) {
                return Long.MAX_VALUE;
            }
            return (len - 84) / 50;
        }
        return 0;
    }

    static String sha256File(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        InputStream in = new java.io.BufferedInputStream(new FileInputStream(f));
        try {
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        } finally {
            in.close();
        }
        StringBuilder sb = new StringBuilder();
        for (byte v : md.digest()) {
            sb.append(Character.forDigit((v >> 4) & 0xF, 16));
            sb.append(Character.forDigit(v & 0xF, 16));
        }
        return sb.toString();
    }
}