package com.flashforge.farm.modelrepo;

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
        return m;
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public boolean isRemix() {
        return remixOf != null && remixOf.contentHash != null && !remixOf.contentHash.isEmpty();
    }
}
