# Settings Code Inventory (generated — do not hand-edit)

> Regenerate: `python3.14 scripts/settings-inventory.py`.
> Engine FDM defs: **376**. Total stale UI keys: **0**.

## `app/src/main/java/com/flashforge/farm/fragment/ProfilePrintSettingsBuilder.java`
- methods: 12, sections: 34, keys referenced: 149, stale: 0
  - L76 `Layer height` (3 keys)
  - L77 `Line width` (8 keys)
  - L78 `Seam` (2 keys)
  - L79 `Scarf joint seam` (7 keys)
  - L80 `Precision` (3 keys)
  - L81 `Ironing` (3 keys)
  - L82 `Wall generator` (7 keys)
  - L83 `Walls and surfaces` (9 keys)
  - L84 `Overhangs` (1 keys)
  - L85 `Bridging` (2 keys)
  - L95 `Walls` (3 keys)
  - L96 `Top/bottom shells` (6 keys)
  - L97 `Infill` (11 keys)
  - L98 `Advanced` (2 keys)
  - L108 `Speed` (11 keys)
  - L109 `Overhang speed` (5 keys)
  - L110 `Acceleration` (9 keys)
  - L111 `Junction deviation` (1 keys)
  - L112 `Pressure advance` (1 keys)
  - L122 `Support` (11 keys)
  - L123 `Raft` (5 keys)
  - L124 `Support filament` (2 keys)
  - L125 `Advanced` (6 keys)
  - L135 `Prime tower` (7 keys)
  - L136 `Filament for Features` (6 keys)
  - L137 `Ooze prevention` (2 keys)
  - L138 `Advanced` (3 keys)
  - L148 `Skirt` (4 keys)
  - L149 `Brim` (2 keys)
  - L150 `Special mode` (1 keys)
  - L151 `Fuzzy Skin` (8 keys)
  - L152 `G-code output` (2 keys)
  - L153 `Notes` (1 keys)
  - L154 `Profile dependencies` (2 keys)
- ENUM branch conditions: 0
- Log.w diagnostics: 1

## `app/src/main/java/com/flashforge/farm/fragment/FilamentConfigFragment.java`
- methods: 12, sections: 0, keys referenced: 66, stale: 0
- ENUM branch conditions: 0
- Log.w diagnostics: 0

## `app/src/main/java/com/flashforge/farm/fragment/PrinterConfigFragment.java`
- methods: 12, sections: 0, keys referenced: 54, stale: 0
- ENUM branch conditions: 0
- Log.w diagnostics: 0

## `app/src/main/java/com/flashforge/farm/fragment/ProfileListFragment.java`
- methods: 73, sections: 0, keys referenced: 0, stale: 0
- ENUM branch conditions: 12
  - L986: `def.type == ConfigOptionDef.ConfigOptionType.ENUM && (def.enumLabels == null || def.enumValues == null)`
  - L986: `def.type == ConfigOptionDef.ConfigOptionType.ENUM`
  - L991: `"ENUM without choices from engine defs: " + def.key`
  - L1024: `def.type == ConfigOptionDef.ConfigOptionType.ENUM && def.enumLabels != null && def.enumLabels.length > 0 && def.enumValues != null`
  - L1024: `def.type == ConfigOptionDef.ConfigOptionType.ENUM && def.enumLabels != null && def.enumLabels.length > 0`
  - L1024: `def.type == ConfigOptionDef.ConfigOptionType.ENUM && def.enumLabels != null`
  - L1024: `def.type == ConfigOptionDef.ConfigOptionType.ENUM`
  - L1046: `def.type == ConfigOptionDef.ConfigOptionType.ENUM`
  - L1049: `"ENUM without choices from engine defs: " + def.key`
  - L1174: `def.type == ConfigOptionDef.ConfigOptionType.ENUM && def.enumLabels != null && def.enumValues != null`
  - L1174: `def.type == ConfigOptionDef.ConfigOptionType.ENUM && def.enumLabels != null`
  - L1174: `def.type == ConfigOptionDef.ConfigOptionType.ENUM`
- Log.w diagnostics: 3

## `app/src/main/java/com/flashforge/farm/slic3r/EnumLabelResolver.java`
- methods: 3, sections: 0, keys referenced: 0, stale: 0
- ENUM branch conditions: 0
- Log.w diagnostics: 0

## `app/src/main/java/com/flashforge/farm/slic3r/SectionPruner.java`
- methods: 1, sections: 0, keys referenced: 0, stale: 0
- ENUM branch conditions: 0
- Log.w diagnostics: 0

## `app/src/main/jni/farm/farm_native.cpp`
- type mappings emitted: BOOL, BOOLS, ENUM, FLOAT, INT, NONE, PERCENT, STRING
- strcmp fallback present: True
- mangled-name logging present: True

## Stale keys: none. All UI keys resolve to engine defs.
