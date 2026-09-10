package com.flashforge.farm.modelrepo;

import com.flashforge.farm.modelrepo.safety.SafetyPolicy;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

public class ModelMetadata {
    private static final Gson GSON = new Gson();
    public static final int SCHEMA = 1;

    @SerializedName("schema")
    public int schema = SCHEMA;
    @SerializedName("title")
    public String title = "";
    @SerializedName("description")
    public String description = "";
    @SerializedName("designer")
    public Designer designer = new Designer();
    @SerializedName("license")
    public License license = new License();
    @SerializedName("category")
    public String category = "";
    @SerializedName("tags")
    public List<String> tags = new ArrayList<>();
    @SerializedName("images")
    public List<String> images = new ArrayList<>();
    @SerializedName("files")
    public List<String> files = new ArrayList<>();
    @SerializedName("printSettings")
    public PrintSettings printSettings = new PrintSettings();
    @SerializedName("remixOf")
    public RemixRef remixOf;
    @SerializedName("verification")
    public String verification = "";
    @SerializedName("signature")
    public String signature = "";
    @SerializedName("signedBy")
    public String signedBy = "";

    public static class Designer {
        @SerializedName("name")
        public String name = "";
        @SerializedName("pubkey")
        public String pubkey = "";
    }

    public static class License {
        @SerializedName("spdx")
        public String spdx = "unspecified";
        @SerializedName("url")
        public String url = "";
    }

    public static class PrintSettings {
        @SerializedName("layerHeight")
        public double layerHeight;
        @SerializedName("infill")
        public double infill;
        @SerializedName("supports")
        public boolean supports;
        @SerializedName("notes")
        public String notes = "";
    }

    public static class RemixRef {
        @SerializedName("contentHash")
        public String contentHash = "";
        @SerializedName("title")
        public String title = "";
    }

    public static ModelMetadata parse(String json) {
        ModelMetadata m = GSON.fromJson(json, ModelMetadata.class);
        if (m == null) {
            throw new IllegalArgumentException("empty metadata");
        }
        if (m.tags == null) m.tags = new ArrayList<>();
        if (m.images == null) m.images = new ArrayList<>();
        if (m.files == null) m.files = new ArrayList<>();
        if (m.designer == null) m.designer = new Designer();
        if (m.license == null) m.license = new License();
        if (m.printSettings == null) m.printSettings = new PrintSettings();
        
        // Validate all fields
        m.validate();
        
        return m;
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public boolean isRemix() {
        return remixOf != null && remixOf.contentHash != null && !remixOf.contentHash.isEmpty();
    }
    
    /**
     * Sign this metadata with the given seed.
     */
    public void sign(byte[] seed) throws Exception {
        String json = this.toJson();
        byte[] sig = Identity.sign(seed, json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        this.signature = Identity.hex(sig);
        this.signedBy = Identity.hex(Identity.publicKey(seed));
    }
    
    /**
     * Verify the signature on this metadata.
     * Returns true if the signature is valid and matches the signedBy public key.
     */
    public boolean verifySignature() {
        if (signature == null || signature.isEmpty() || signedBy == null || signedBy.isEmpty()) {
            return false;
        }
        try {
            String json = this.toJson();
            byte[] sig = Identity.unhex(signature);
            byte[] pubkey = Identity.unhex(signedBy);
            return Identity.verify(pubkey, json.getBytes(java.nio.charset.StandardCharsets.UTF_8), sig);
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Verify the signature matches the expected public key.
     */
    public boolean verifySignature(String expectedPubkeyHex) {
        if (signature == null || signature.isEmpty()) {
            return false;
        }
        if (expectedPubkeyHex == null || expectedPubkeyHex.isEmpty()) {
            return false;
        }
        try {
            String json = this.toJson();
            byte[] sig = Identity.unhex(signature);
            byte[] pubkey = Identity.unhex(expectedPubkeyHex);
            return Identity.verify(pubkey, json.getBytes(java.nio.charset.StandardCharsets.UTF_8), sig);
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Validate all fields in this metadata against safety policy.
     */
    public void validate() {
        if (schema != SCHEMA) {
            throw new IllegalArgumentException("unsupported schema version: " + schema);
        }
        
        if (title == null) {
            title = "";
        }
        if (title.length() > SafetyPolicy.MAX_TITLE_LEN) {
            throw new IllegalArgumentException("title too long: " + title.length() + " > " + SafetyPolicy.MAX_TITLE_LEN);
        }
        
        if (description == null) {
            description = "";
        }
        if (description.length() > SafetyPolicy.MAX_DESC_LEN) {
            throw new IllegalArgumentException("description too long: " + description.length() + " > " + SafetyPolicy.MAX_DESC_LEN);
        }
        
        if (category == null) {
            category = "";
        }
        if (category.length() > 100) {
            throw new IllegalArgumentException("category too long");
        }
        
        if (designer == null) {
            designer = new Designer();
        }
        if (designer.name != null && designer.name.length() > SafetyPolicy.MAX_PROFILE_NAME) {
            throw new IllegalArgumentException("designer name too long");
        }
        if (designer.pubkey != null) {
            if (designer.pubkey.length() > 64) {
                throw new IllegalArgumentException("designer pubkey too long");
            }
            if (!designer.pubkey.isEmpty() && !designer.pubkey.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("invalid designer pubkey format");
            }
        }
        
        if (license == null) {
            license = new License();
        }
        
        if (tags == null) {
            tags = new ArrayList<>();
        }
        if (tags.size() > 50) {
            throw new IllegalArgumentException("too many tags: " + tags.size());
        }
        for (String tag : tags) {
            if (tag != null && tag.length() > 64) {
                throw new IllegalArgumentException("tag too long: " + tag);
            }
        }
        
        if (files == null) {
            files = new ArrayList<>();
        }
        if (files.size() > SafetyPolicy.MAX_FILE_COUNT) {
            throw new IllegalArgumentException("too many files: " + files.size() + " > " + SafetyPolicy.MAX_FILE_COUNT);
        }
        for (String file : files) {
            if (file != null) {
                if (file.length() > SafetyPolicy.MAX_FILENAME_LEN) {
                    throw new IllegalArgumentException("filename too long: " + file);
                }
                if (!ModelSafety.isAllowedName(file)) {
                    throw new IllegalArgumentException("invalid filename: " + file);
                }
            }
        }
        
        if (images == null) {
            images = new ArrayList<>();
        }
        if (images.size() > 20) {
            throw new IllegalArgumentException("too many images");
        }
        
        if (printSettings == null) {
            printSettings = new PrintSettings();
        }
        
        if (remixOf != null) {
            if (remixOf.contentHash != null && remixOf.contentHash.length() > 64) {
                throw new IllegalArgumentException("remix hash too long");
            }
            if (remixOf.title != null && remixOf.title.length() > SafetyPolicy.MAX_TITLE_LEN) {
                throw new IllegalArgumentException("remix title too long");
            }
        }
        
        if (verification != null && verification.length() > 64) {
            throw new IllegalArgumentException("verification hash too long");
        }
        
        if (signature != null && signature.length() > 128) {
            throw new IllegalArgumentException("signature too long");
        }
        
        if (signedBy != null && signedBy.length() > 64) {
            throw new IllegalArgumentException("signedBy pubkey too long");
        }
    }
}
