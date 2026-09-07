#!/usr/bin/env python3
"""
migrate_models_to_iroh.py — Migrate test models from models/ to Iroh P2P network.

This script:
1. Reads models/catalog.json for metadata
2. Reads models/models-manifest.json for file hashes
3. For each model, creates ModelMetadata, stores file as blob, publishes to Iroh
4. Outputs a migration report with model hashes

Usage:
  python3 migrate_models_to_iroh.py [--endpoint ENDPOINT_ID] [--secret SECRET_HEX]
  
Requires:
  - Android device with app installed and Iroh endpoint running
  - Or: run on device via adb shell with app's IrohModelTransport
"""

import json
import hashlib
import sys
import os
import subprocess
from pathlib import Path
from typing import Dict, List, Optional

MODELS_DIR = Path(__file__).parent.parent / "models"
CATALOG_FILE = MODELS_DIR / "catalog.json"
MANIFEST_FILE = MODELS_DIR / "models-manifest.json"

def load_catalog() -> List[Dict]:
    with open(CATALOG_FILE) as f:
        data = json.load(f)
    return data.get("models", [])

def load_manifest() -> Dict[str, str]:
    with open(MANIFEST_FILE) as f:
        data = json.load(f)
    return data.get("files", {})

def compute_sha256(filepath: Path) -> str:
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()

def create_metadata_json(model: Dict, file_hash: str) -> Dict:
    """Create ModelMetadata JSON matching Java ModelMetadata schema."""
    return {
        "schema": 1,
        "title": model.get("title", ""),
        "description": model.get("description", ""),
        "designer": {
            "name": model.get("designer", "Unknown"),
            "pubkey": ""  # Will be filled by publisher's identity
        },
        "license": {
            "spdx": model.get("license", "unspecified"),
            "url": ""
        },
        "category": model.get("tags", ["uncategorized"])[0] if model.get("tags") else "uncategorized",
        "tags": model.get("tags", []),
        "images": [],
        "files": [model.get("file", "")],
        "printSettings": {
            "layerHeight": 0.2,
            "infill": 0.2,
            "supports": False,
            "notes": ""
        },
        "remixOf": None,
        "verification": file_hash
    }

def generate_kotlin_migration_code(models: List[Dict], manifest: Dict[str, str]) -> str:
    """Generate Kotlin code that can be run on Android to migrate models."""
    lines = []
    lines.append("package com.flashforge.farm.modelrepo")
    lines.append("")
    lines.append("import android.content.Context")
    lines.append("import android.util.Log")
    lines.append("import java.io.File")
    lines.append("import java.io.FileInputStream")
    lines.append("")
    lines.append("class ModelMigrator(private val context: Context, private val transport: IrohModelTransport) {")
    lines.append("    private val TAG = \"ModelMigrator\"")
    lines.append("")
    lines.append("    interface Callback {")
    lines.append("        fun onProgress(model: String, stage: String, percent: Int)")
    lines.append("        fun onCompleted(model: String, modelHash: String)")
    lines.append("        fun onError(model: String, error: String)")
    lines.append("        fun onAllDone()")
    lines.append("    }")
    lines.append("")
    lines.append("    fun migrateAll(callback: Callback) {")
    lines.append("        Thread {")
    lines.append("            try {")
    lines.append("                for ((fileName, metadataJson) in MODELS) {")
    lines.append("                    try {")
    lines.append("                        callback.onProgress(fileName, \"Reading\", 10)")
    lines.append("                        val file = File(File(context.filesDir, \"models\"), fileName)")
    lines.append("                        if (!file.exists()) {")
    lines.append("                            callback.onError(fileName, \"File not found: models/\" + fileName)")
    lines.append("                            continue")
    lines.append("                        }")
    lines.append("                        val data = readAll(file)")
    lines.append("                        callback.onProgress(fileName, \"Publishing\", 50)")
    lines.append("                        val datas = mutableListOf<ByteArray>()")
    lines.append("                        datas.add(data)")
    lines.append("                        val ticket = transport.publishModel(metadataJson, datas)")
    lines.append("                        callback.onCompleted(fileName, ticket)")
    lines.append("                    } catch (e: Exception) {")
    lines.append("                        Log.e(TAG, \"migrate failed\", e)")
    lines.append("                        callback.onError(fileName, e.message ?: \"failed\")")
    lines.append("                    }")
    lines.append("                }")
    lines.append("                callback.onAllDone()")
    lines.append("            } catch (e: Exception) {")
    lines.append("                Log.e(TAG, \"Migration failed\", e)")
    lines.append("            }")
    lines.append("        }.start()")
    lines.append("    }")
    lines.append("")
    lines.append("    private fun readAll(file: File): ByteArray {")
    lines.append("        FileInputStream(file).use { fis ->")
    lines.append("            val out = java.io.ByteArrayOutputStream()")
    lines.append("            val buf = ByteArray(32768)")
    lines.append("            var n = fis.read(buf)")
    lines.append("            while (n != -1) {")
    lines.append("                out.write(buf, 0, n)")
    lines.append("                n = fis.read(buf)")
    lines.append("            }")
    lines.append("            return out.toByteArray()")
    lines.append("        }")
    lines.append("    }")
    lines.append("")

    lines.append("    companion object {")
    lines.append("        private val MODELS = listOf(")
    for model in models:
        file_name = model.get("file", "")
        if not file_name:
            continue
        file_hash = manifest.get(file_name, "")
        metadata = create_metadata_json(model, file_hash)
        metadata_json = json.dumps(metadata, separators=(',', ':'))
        escaped = metadata_json.replace('\\', '\\\\').replace('"', '\\"')
        lines.append(f"            Pair(\"{file_name}\", \"{escaped}\"),")
    lines.append("        )")
    lines.append("    }")
    lines.append("}")
    
    return "\n".join(lines)

def main():
    import argparse
    parser = argparse.ArgumentParser(description="Migrate models to Iroh")
    parser.add_argument("--generate-kotlin", action="store_true", 
                        help="Generate Kotlin migration code")
    parser.add_argument("--output", type=str, help="Output file for Kotlin code")
    parser.add_argument("--verify", action="store_true", help="Verify file hashes match manifest")
    args = parser.parse_args()
    
    models = load_catalog()
    manifest = load_manifest()
    
    print(f"Loaded {len(models)} models from catalog")
    print(f"Loaded {len(manifest)} file hashes from manifest")
    
    if args.verify:
        print("\nVerifying file hashes...")
        all_ok = True
        for model in models:
            file_name = model.get("file", "")
            if not file_name:
                continue
            filepath = MODELS_DIR / file_name
            if not filepath.exists():
                print(f"  MISSING: {file_name}")
                all_ok = False
                continue
            computed = compute_sha256(filepath)
            expected = manifest.get(file_name, "")
            if computed != expected:
                print(f"  MISMATCH: {file_name}")
                print(f"    expected: {expected}")
                print(f"    computed: {computed}")
                all_ok = False
            else:
                print(f"  OK: {file_name}")
        if all_ok:
            print("\nAll hashes verified!")
        else:
            print("\nSome hashes failed verification!")
            sys.exit(1)
    
    if args.generate_kotlin:
        kotlin_code = generate_kotlin_migration_code(models, manifest)
        if args.output:
            with open(args.output, "w") as f:
                f.write(kotlin_code)
            print(f"\nGenerated Kotlin code to {args.output}")
        else:
            print("\n" + kotlin_code)
    
    # Print summary
    print("\n=== Migration Summary ===")
    for model in models:
        file_name = model.get("file", "")
        if not file_name:
            continue
        file_hash = manifest.get(file_name, "")
        print(f"  {model.get('title', 'Unknown')}: {file_name} ({file_hash[:16]}...)")

if __name__ == "__main__":
    main()