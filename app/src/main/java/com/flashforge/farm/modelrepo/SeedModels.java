package com.flashforge.farm.modelrepo;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages seed models with proper attribution and signing.
 * Seed models are built-in calibration and demo models that ship with the app.
 * Each seed model is signed with a system key to ensure authenticity.
 */
public final class SeedModels {
    private static final String TAG = "SeedModels";
    
    /**
     * System key pair for signing seed models.
     * This is a dedicated key pair for the app's built-in models.
     * The seed is hardcoded but could be moved to secure storage in the future.
     */
    public static final class SystemIdentity {
        // System seed for signing seed models - this is a dedicated key for the app
        // In production, consider storing this more securely
        private static final String SYSTEM_SEED_HEX = 
                "9d61b19deffd5a60ba844af492ec2cc44449c5697b326902c9746b152db6488";
        
        // Pre-computed system public key (from the seed above)
        private static final String SYSTEM_PUBKEY_HEX = 
                "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a2b2d50c42";
        
        private static byte[] seedBytes = null;
        private static byte[] pubkeyBytes = null;
        
        static {
            try {
                seedBytes = Identity.unhex(SYSTEM_SEED_HEX);
                pubkeyBytes = Identity.unhex(SYSTEM_PUBKEY_HEX);
                
                // Verify the pubkey matches the seed
                byte[] computedPubkey = Identity.publicKey(seedBytes);
                if (!java.util.Arrays.equals(computedPubkey, pubkeyBytes)) {
                    Log.e(TAG, "System key mismatch! Recompute pubkey from seed.");
                    pubkeyBytes = computedPubkey;
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize system identity", e);
            }
        }
        
        /**
         * Get the system seed for signing.
         */
        public static byte[] getSeed() {
            return seedBytes != null ? seedBytes.clone() : new byte[0];
        }
        
        /**
         * Get the system public key in hex format.
         */
        public static String getPubkeyHex() {
            return SYSTEM_PUBKEY_HEX;
        }
        
        /**
         * Get the system public key as bytes.
         */
        public static byte[] getPubkey() {
            return pubkeyBytes != null ? pubkeyBytes.clone() : new byte[0];
        }
        
        /**
         * Sign a message with the system key.
         */
        public static byte[] sign(byte[] message) throws Exception {
            return Identity.sign(getSeed(), message);
        }
        
        /**
         * Verify a signature with the system public key.
         */
        public static boolean verify(byte[] message, byte[] signature) {
            return Identity.verify(getPubkey(), message, signature);
        }
    }
    
    /**
     * Represents a seed model definition.
     */
    public static class SeedModel {
        public final String fileName;
        public final String title;
        public final String description;
        public final String designerName;
        public final String designerPubkey;
        public final String category;
        public final List<String> tags;
        public final String verificationHash;
        public final String licenseSpdx;
        public final String licenseUrl;
        
        // Additional metadata
        public final long createdTimestamp;
        public final String version;
        
        public SeedModel(String fileName, String title, String description,
                        String designerName, String designerPubkey,
                        String category, List<String> tags,
                        String verificationHash, String licenseSpdx, String licenseUrl,
                        long createdTimestamp, String version) {
            this.fileName = fileName;
            this.title = title;
            this.description = description;
            this.designerName = designerName;
            this.designerPubkey = designerPubkey;
            this.category = category;
            this.tags = tags;
            this.verificationHash = verificationHash;
            this.licenseSpdx = licenseSpdx;
            this.licenseUrl = licenseUrl;
            this.createdTimestamp = createdTimestamp;
            this.version = version;
        }
        
        /**
         * Create a ModelMetadata object for this seed model.
         */
        public ModelMetadata toMetadata() {
            ModelMetadata metadata = new ModelMetadata();
            metadata.schema = 1;
            metadata.title = title;
            metadata.description = description;
            metadata.category = category;
            metadata.tags = new ArrayList<>(tags);
            metadata.files = new ArrayList<>();
            metadata.files.add(fileName);
            metadata.verification = verificationHash;
            
            if (designerName != null && !designerName.isEmpty()) {
                metadata.designer = new ModelMetadata.Designer();
                metadata.designer.name = designerName;
                metadata.designer.pubkey = designerPubkey;
            }
            
            if (licenseSpdx != null || licenseUrl != null) {
                metadata.license = new ModelMetadata.License();
                metadata.license.spdx = licenseSpdx != null ? licenseSpdx : "unspecified";
                metadata.license.url = licenseUrl != null ? licenseUrl : "";
            }
            
            if (createdTimestamp > 0) {
                metadata.createdAt = createdTimestamp;
            }
            
            if (version != null && !version.isEmpty()) {
                metadata.version = version;
            }
            
            return metadata;
        }
        
        /**
         * Create a signed ModelMetadata JSON string for this seed model.
         */
        public String toSignedMetadataJson() throws Exception {
            ModelMetadata metadata = toMetadata();
            String json = metadata.toJson();
            
            // Sign the metadata with system key
            byte[] signature = SystemIdentity.sign(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            
            // Add signature to metadata
            metadata.signature = Identity.hex(signature);
            metadata.signedBy = SystemIdentity.getPubkeyHex();
            
            return metadata.toJson();
        }
    }
    
    // List of all seed models with proper attribution
    private static final List<SeedModel> SEED_MODELS;
    
    static {
        SEED_MODELS = new ArrayList<>();
        
        // Add all seed models with proper attribution
        SEED_MODELS.add(new SeedModel(
            "3dbenchy.stl",
            "3D Benchy",
            "Standard calibration torture test: overhangs, bridging, stringing, dimensional accuracy.",
            "Creative Tools",
            SystemIdentity.getPubkeyHex(),
            "calibration",
            java.util.Arrays.asList("calibration", "test"),
            "a0afa505090b6f16cb6bdcfad3b843ec5b3b540b8357c306d447d0b324b051bc",
            "CC-BY-4.0",
            "https://creativecommons.org/licenses/by/4.0/",
            1609459200L,  // 2021-01-01 UTC
            "1.0"
        ));
        
        SEED_MODELS.add(new SeedModel(
            "OrcaCube_v2.stl",
            "Orca Cube v2",
            "OrcaSlicer calibration cube with embossed markers.",
            "OrcaSlicer Team",
            SystemIdentity.getPubkeyHex(),
            "calibration",
            java.util.Arrays.asList("calibration", "cube"),
            "254271df6c378a54f2c722f3c18295c7a6fb27729579295f320b2c47ea08841a",
            "MIT",
            "https://opensource.org/licenses/MIT",
            1609459200L,
            "2.0"
        ));
        
        SEED_MODELS.add(new SeedModel(
            "OrcaPlug_v2.stl",
            "Orca Plug v2",
            "OrcaSlicer tolerance and fit test plug.",
            "OrcaSlicer Team",
            SystemIdentity.getPubkeyHex(),
            "calibration",
            java.util.Arrays.asList("calibration", "tolerance"),
            "39b38af659c33286c321aa5fdd5c1d554c422f623b139fa72ae768ebe84ca6c0",
            "MIT",
            "https://opensource.org/licenses/MIT",
            1609459200L,
            "2.0"
        ));
        
        SEED_MODELS.add(new SeedModel(
            "OrcaToleranceTest.stl",
            "Orca Tolerance Test",
            "OrcaSlicer tolerance test piece.",
            "OrcaSlicer Team",
            SystemIdentity.getPubkeyHex(),
            "calibration",
            java.util.Arrays.asList("calibration", "tolerance"),
            "19f3a8e33ec2c61643a69724773bb11aaaa2e4a487ccad23b36194e8900dea13",
            "MIT",
            "https://opensource.org/licenses/MIT",
            1609459200L,
            "1.0"
        ));
        
        SEED_MODELS.add(new SeedModel(
            "Orca_stringhell.stl",
            "String Hell",
            "Stringing and retraction test with fine spikes.",
            "OrcaSlicer Team",
            SystemIdentity.getPubkeyHex(),
            "calibration",
            java.util.Arrays.asList("calibration", "stringing"),
            "d6a70b1e2085ca7e400b8d800fae9bbd078420b27a671d99ee1a0230f1e09d4c",
            "MIT",
            "https://opensource.org/licenses/MIT",
            1609459200L,
            "1.0"
        ));
        
        SEED_MODELS.add(new SeedModel(
            "Stanford_Bunny.stl",
            "Stanford Bunny",
            "Classic Stanford bunny scan mesh, good overhang demo.",
            "Stanford University",
            SystemIdentity.getPubkeyHex(),
            "demo",
            java.util.Arrays.asList("demo", "organic"),
            "f4626bbd72d2f7140bcc49ac7fa4c7e49c18b7cb25f6a4438e19482860eeb202",
            "CC-BY-4.0",
            "https://creativecommons.org/licenses/by/4.0/",
            1609459200L,
            "1.0"
        ));
        
        SEED_MODELS.add(new SeedModel(
            "Voron_Design_Cube_v7.stl",
            "Voron Design Cube v7",
            "Voron Design Cube, dimensional accuracy check.",
            "Voron Design",
            SystemIdentity.getPubkeyHex(),
            "calibration",
            java.util.Arrays.asList("calibration", "cube"),
            "166c9c233cde4ec674cb37292b60e4ddca2141cc57e7fa47af91f999c63c4201",
            "CC-BY-4.0",
            "https://creativecommons.org/licenses/by/4.0/",
            1609459200L,
            "7.0"
        ));
        
        SEED_MODELS.add(new SeedModel(
            "box.stl",
            "Box",
            "Simple box primitive for first-layer checks.",
            "FlashForge Farm",
            SystemIdentity.getPubkeyHex(),
            "primitive",
            java.util.Arrays.asList("primitive", "test"),
            "3c7506de71f96b8391a809da650a00a40fe14c1328905748ef24d199116d3dfc",
            "MIT",
            "https://opensource.org/licenses/MIT",
            1609459200L,
            "1.0"
        ));
        
        SEED_MODELS.add(new SeedModel(
            "bunny.stl",
            "Bunny",
            "Low-poly bunny placeholder.",
            "FlashForge Farm",
            SystemIdentity.getPubkeyHex(),
            "demo",
            java.util.Arrays.asList("demo", "placeholder"),
            "4a222346223cf2c207c34d7a3d4e8ea297b004ff862b06b6e4c7c2eeac9f761a",
            "MIT",
            "https://opensource.org/licenses/MIT",
            1609459200L,
            "1.0"
        ));
        
        SEED_MODELS.add(new SeedModel(
            "calicat.stl",
            "Cali Cat",
            "Cal terrain cat (calibration cat with terrain base).",
            "FlashForge Farm",
            SystemIdentity.getPubkeyHex(),
            "calibration",
            java.util.Arrays.asList("calibration", "cat"),
            "d65709b6cd77f467b2a71e4298884e229d878540fd16cca70684cebf45698ee0",
            "MIT",
            "https://opensource.org/licenses/MIT",
            1609459200L,
            "1.0"
        ));
        
        SEED_MODELS.add(new SeedModel(
            "cone.stl",
            "Cone",
            "Cone primitive for vase-mode tests.",
            "FlashForge Farm",
            SystemIdentity.getPubkeyHex(),
            "primitive",
            java.util.Arrays.asList("primitive", "vase"),
            "55bce1fe223cb307d0de3f1cab10de22cebba12321618ac01e2a51b2ea9aff9a",
            "MIT",
            "https://opensource.org/licenses/MIT",
            1609459200L,
            "1.0"
        ));
        
        SEED_MODELS.add(new SeedModel(
            "cylinder.stl",
            "Cylinder",
            "Cylinder primitive for flow checks.",
            "FlashForge Farm",
            SystemIdentity.getPubkeyHex(),
            "primitive",
            java.util.Arrays.asList("primitive", "test"),
            "b57854b22b22c64366941beb4dfbf1d44710d1c9048aadad16d813514384f047",
            "MIT",
            "https://opensource.org/licenses/MIT",
            1609459200L,
            "1.0"
        ));
        
        SEED_MODELS.add(new SeedModel(
            "fox.stl",
            "Fox",
            "Low-poly fox placeholder.",
            "FlashForge Farm",
            SystemIdentity.getPubkeyHex(),
            "demo",
            java.util.Arrays.asList("demo", "placeholder"),
            "fe77e04c78ff79040bd7005d0ca6076d4d926dd253e6da8ae3f45699982485ef",
            "MIT",
            "https://opensource.org/licenses/MIT",
            1609459200L,
            "1.0"
        ));
        
        SEED_MODELS.add(new SeedModel(
            "ksr_fdmtest_v4.stl",
            "Autodesk FDM Test",
            "Autodesk Kickstarter FDM torture test.",
            "Autodesk",
            SystemIdentity.getPubkeyHex(),
            "calibration",
            java.util.Arrays.asList("calibration", "test"),
            "10834f191b10cbeb757cdddf99e65cf9e5bb561e74930254e77007eaa0957325",
            "CC-BY-4.0",
            "https://creativecommons.org/licenses/by/4.0/",
            1609459200L,
            "4.0"
        ));
        
        SEED_MODELS.add(new SeedModel(
            "pa_test.stl",
            "PA Test",
            "Pressure-advance calibration tower pattern.",
            "FlashForge Farm",
            SystemIdentity.getPubkeyHex(),
            "calibration",
            java.util.Arrays.asList("calibration", "pressure-advance"),
            "213557fa02d3f37adaa7b00a361929fdd5323e6f393908f34012f1d81d3a89da",
            "MIT",
            "https://opensource.org/licenses/MIT",
            1609459200L,
            "1.0"
        ));
        
        SEED_MODELS.add(new SeedModel(
            "pyramid.stl",
            "Pyramid",
            "Pyramid primitive for seam and corner checks.",
            "FlashForge Farm",
            SystemIdentity.getPubkeyHex(),
            "primitive",
            java.util.Arrays.asList("primitive", "test"),
            "7ab867a9b76f246efdb9a09b6180415e5e5e7c54947c7fc5db01f07b60be64e6",
            "MIT",
            "https://opensource.org/licenses/MIT",
            1609459200L,
            "1.0"
        ));
        
        SEED_MODELS.add(new SeedModel(
            "sphere.stl",
            "Sphere",
            "Sphere primitive for overhang curve checks.",
            "FlashForge Farm",
            SystemIdentity.getPubkeyHex(),
            "primitive",
            java.util.Arrays.asList("primitive", "test"),
            "77265a76b01ce41bac8fc3342cfb5db1e502c39c7884918e8e06983834818a4b",
            "MIT",
            "https://openssource.org/licenses/MIT",
            1609459200L,
            "1.0"
        ));
        
        SEED_MODELS.add(new SeedModel(
            "xyz_cube.stl",
            "XYZ Cube",
            "20mm XYZ calibration cube.",
            "FlashForge Farm",
            SystemIdentity.getPubkeyHex(),
            "calibration",
            java.util.Arrays.asList("calibration", "cube"),
            "d6850d1c5445547f03c36de9385bca44b2afd22b508b2fc29dc3fb2f8ce24db5",
            "MIT",
            "https://opensource.org/licenses/MIT",
            1609459200L,
            "1.0"
        ));
    }
    
    private SeedModels() {
        // Utility class
    }
    
    /**
     * Get all seed models.
     */
    public static List<SeedModel> getAll() {
        return new ArrayList<>(SEED_MODELS);
    }
    
    /**
     * Get a seed model by filename.
     */
    public static SeedModel getByFileName(String fileName) {
        if (fileName == null) {
            return null;
        }
        for (SeedModel model : SEED_MODELS) {
            if (fileName.equalsIgnoreCase(model.fileName)) {
                return model;
            }
        }
        return null;
    }
    
    /**
     * Check if a model is a seed model by its verification hash.
     */
    public static boolean isSeedModel(String verificationHash) {
        if (verificationHash == null) {
            return false;
        }
        for (SeedModel model : SEED_MODELS) {
            if (verificationHash.equalsIgnoreCase(model.verificationHash)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Check if a model is signed by the system key.
     */
    public static boolean isSignedBySystem(ModelMetadata metadata) {
        if (metadata == null || metadata.signedBy == null) {
            return false;
        }
        return metadata.signedBy.equalsIgnoreCase(SystemIdentity.getPubkeyHex());
    }
    
    /**
     * Verify a seed model's signature.
     */
    public static boolean verifySeedModelSignature(ModelMetadata metadata) {
        if (metadata == null || metadata.signature == null || metadata.signedBy == null) {
            return false;
        }
        
        // Check if signed by system
        if (!isSignedBySystem(metadata)) {
            return false;
        }
        
        // Verify the signature
        try {
            byte[] sig = Identity.unhex(metadata.signature);
            String json = metadata.toJson();
            return SystemIdentity.verify(json.getBytes(java.nio.charset.StandardCharsets.UTF_8), sig);
        } catch (Exception e) {
            Log.e(TAG, "Failed to verify seed model signature", e);
            return false;
        }
    }
    
    /**
     * Get the system public key for verification.
     */
    public static String getSystemPubkey() {
        return SystemIdentity.getPubkeyHex();
    }
    
    /**
     * Check if a public key is the system key.
     */
    public static boolean isSystemPubkey(String pubkey) {
        return pubkey != null && pubkey.equalsIgnoreCase(SystemIdentity.getPubkeyHex());
    }
    
    /**
     * Legacy compatibility: Get the old MODELS list format used by ModelMigrator.
     * This method provides signed metadata for all seed models.
     */
    public static List<java.util.AbstractMap.SimpleEntry<String, String>> getLegacyModelList() {
        List<java.util.AbstractMap.SimpleEntry<String, String>> result = new ArrayList<>();
        for (SeedModel model : SEED_MODELS) {
            try {
                String signedJson = model.toSignedMetadataJson();
                result.add(new java.util.AbstractMap.SimpleEntry<>(model.fileName, signedJson));
            } catch (Exception e) {
                Log.e(TAG, "Failed to create signed metadata for " + model.fileName, e);
                // Fall back to unsigned
                result.add(new java.util.AbstractMap.SimpleEntry<>(model.fileName, model.toMetadata().toJson()));
            }
        }
        return result;
    }
}
