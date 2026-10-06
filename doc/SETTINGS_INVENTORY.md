# Settings Code Inventory (generated — do not hand-edit)

> Regenerate: `python3.14 scripts/settings-inventory.py`.
> Engine FDM defs: **371**. Total stale UI keys: **0**.

## `app/src/main/java/com/flashforge/farm/fragment/ProfilePrintSettingsBuilder.java`
- methods: 12, sections: 32, keys referenced: 132, stale: 0
  - L76 `Layer height` (3 keys)
  - L77 `Line width` (8 keys)
  - L78 `Seam` (2 keys)
  - L79 `Precision` (3 keys)
  - L80 `Ironing` (3 keys)
  - L81 `Wall generator` (7 keys)
  - L82 `Walls and surfaces` (9 keys)
  - L83 `Overhangs` (1 keys)
  - L84 `Bridging` (2 keys)
  - L94 `Walls` (3 keys)
  - L95 `Top/bottom shells` (6 keys)
  - L96 `Infill` (11 keys)
  - L97 `Advanced` (2 keys)
  - L107 `Speed` (11 keys)
  - L108 `Acceleration` (9 keys)
  - L109 `Junction deviation` (1 keys)
  - L110 `Pressure advance` (1 keys)
  - L120 `Support` (11 keys)
  - L121 `Raft` (5 keys)
  - L122 `Support filament` (2 keys)
  - L123 `Advanced` (6 keys)
  - L133 `Prime tower` (7 keys)
  - L134 `Filament for Features` (6 keys)
  - L135 `Ooze prevention` (2 keys)
  - L136 `Advanced` (3 keys)
  - L146 `Skirt` (4 keys)
  - L147 `Brim` (2 keys)
  - L148 `Special mode` (1 keys)
  - L149 `Fuzzy Skin` (3 keys)
  - L150 `G-code output` (2 keys)
  - L151 `Notes` (1 keys)
  - L152 `Profile dependencies` (2 keys)
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
- methods: 61, sections: 0, keys referenced: 0, stale: 0
- ENUM branch conditions: 12
  - L802: `def.type == ConfigOptionDef.ConfigOptionType.ENUM && (def.enumLabels == null || def.enumValues == null)`
  - L802: `def.type == ConfigOptionDef.ConfigOptionType.ENUM`
  - L807: `"ENUM without choices from engine defs: " + def.key`
  - L840: `def.type == ConfigOptionDef.ConfigOptionType.ENUM && def.enumLabels != null && def.enumLabels.length > 0 && def.enumValues != null`
  - L840: `def.type == ConfigOptionDef.ConfigOptionType.ENUM && def.enumLabels != null && def.enumLabels.length > 0`
  - L840: `def.type == ConfigOptionDef.ConfigOptionType.ENUM && def.enumLabels != null`
  - L840: `def.type == ConfigOptionDef.ConfigOptionType.ENUM`
  - L862: `def.type == ConfigOptionDef.ConfigOptionType.ENUM`
  - L865: `"ENUM without choices from engine defs: " + def.key`
  - L990: `def.type == ConfigOptionDef.ConfigOptionType.ENUM && def.enumLabels != null && def.enumValues != null`
  - L990: `def.type == ConfigOptionDef.ConfigOptionType.ENUM && def.enumLabels != null`
  - L990: `def.type == ConfigOptionDef.ConfigOptionType.ENUM`
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
