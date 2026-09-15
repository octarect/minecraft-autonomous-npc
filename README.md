# minecraft-autonomous-npc

Paper 26.2 plugin that runs persistent `ServerPlayer` NPCs.

## Build

Requires Java 25.

```text
cd plugin
./gradlew build
```

The production JAR is Mojang-mapped for Paper 26.2 and is written to `plugin/build/libs/`.

## Operations

`/autonomousnpc list`, `pause <name>`, `resume <name>`, `spawn <name>`, `remove <name>`, and `debug <name>` require `autonomousnpc.admin`.

On first start the plugin creates Aster, Birch, and Cinder at the `world` spawn. They persist their UUID, name, location, inventory, pause state, and current PvP disposition in `plugins/AutonomousNPC/config.yml`.

The plugin keeps a 3x3 chunk ticket area around each active NPC and releases it while paused, removed, dead, or disabled.

## Releases

- `ci.yml` builds pull requests and pushes.
- `nightly.yml` publishes `autonomous-npc-plugin-nightly.jar` from `master`.
- `release.yml` attaches `autonomous-npc-plugin-*.jar` for `v*` tags.
