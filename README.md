<p align="center">
  <img src="src/main/resources/logo.png" alt="ProjectE Research Logo" width="128"/>
</p>

# ProjectE Research

Download: https://www.curseforge.com/minecraft/mc-mods/projecte-research
-
An addon for ProjectE (Minecraft 1.20.1) that introduces a research mechanic.
Instead of instantly learning an item by picking it up, players must accumulate a certain amount of items (defined in the config) before they can learn it.

## Features
- Custom UI in the Transmutation Table showing research progress.
- Highly configurable (JSON config) to set custom requirements per item, tags, or globally.
- Compatibility with Project Expansion and AppliedE.

## Requirements
- Minecraft 1.20.1
- Forge 47+
- ProjectE 1.20.1 (1.0.1)


## Commands

All commands require **Operator (OP) permission level 2** and are primarily used for testing, server administration, or debugging.

| Command | Arguments | Description |
| :--- | :--- | :--- |
| `/projecte_research set` | `<player> <amount> [item]` | Sets the exact research progress for an item. If progress reaches the required amount, the item is learned. If it's less, the item becomes unlearned. If `[item]` is omitted, it uses the item in the player's main hand. |
| `/projecte_research clear` | `<player> all` | Completely clears all research progress for the specified player. |
| `/projecte_research clear` | `<player> <item>` | Clears the research progress for a specific item for the specified player. |
| `/projecte_research reload` | *(None)* | Reloads the `projecte_research.json` configuration file and syncs it to all online players. |

### Usage Examples
```mcfunction
# Set 50 diamonds research progress for player "Notch"
/projecte_research set Notch 50 minecraft:diamond

# Set research progress to the max for the item currently held in your hand
/projecte_research set Notch 1000

# Clear all research progress for player "Notch"
/projecte_research clear Notch all

# Clear research progress for iron ingots for player "Notch"
/projecte_research clear Notch minecraft:iron_ingot

# Reload the config
/projecte_research reload
```


## Configuration

The mod generates a configuration file at `config/projecte_research.json`. You can adjust the number of items required to learn specific blocks, tools, or entire categories of items.

After editing the file, run the `/projecte_research reload` command in-game or restart the server to apply the changes.

### Rule Priority
When determining how many items are required for a specific item, the mod checks the rules in the following order (from highest to lowest priority):
1. **`items`** (Specific item IDs)
2. **`tags`** (Item tags, e.g., `forge:tools`)
3. **`nonStackableRequired`** (Fallback for items with a max stack size of 1)
4. **`defaultRequired`** (Global fallback for anything else)

### Default Configuration
```json
{
  "_help": "Rule priority: items > tags > nonStackableRequired > defaultRequired. nonStackableRequired applies to items that do not stack (max stack size 1); set it to -1 to disable. After editing this file run /projecte_research reload or restart the game.",
  "defaultRequired": 64,
  "nonStackableRequired": 1,

  "_help": "condenserRequiresLearned: if true, the Energy Condenser (and MK2) lock slot only accepts items the player has learned; set to false to disable.",
  "condenserRequiresLearned": true,
  
  "_help": "noEmcWhileLearning: if true, items that count toward learning (not yet learned) give NO EMC when inserted into the Transmutation Table, EMC Link, AE2 terminal with AppliedE, etc. Already learned items give EMC as usual. If false (default), EMC is given as usual.",
  "noEmcWhileLearning": true,

  "tags": [
    { "tag": "forge:tools", "required": 1 },
    { "tag": "forge:armors", "required": 1 },
    { "tag": "minecraft:swords", "required": 1 },
    { "tag": "minecraft:pickaxes", "required": 1 },
    { "tag": "minecraft:axes", "required": 1 },
    { "tag": "minecraft:shovels", "required": 1 },
    { "tag": "minecraft:hoes", "required": 1 },
    { "tag": "minecraft:trimmable_armor", "required": 1 }
  ],
  "items": [
    { "id": "minecraft:stone_sword", "required": 1 }
  ]
}
```

