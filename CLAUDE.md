# Seed Cracker

Client-only Fabric mod for Minecraft 26.3: reads the bedrock of every Nether chunk the server sends and, on `/seedcracker start`, works out the world seed on background threads, then prints it in chat. `/seedcracker check [seed]` verifies a seed against the Overworld bedrock floor (forwards only; the user asked for checking, not cracking from the Overworld). It must never start by itself (the user had auto start removed on 2026-11-01). Built for the user's MultiMC instance at `E:\MultiMC\instances\26.3` (mods in `.minecraft\mods`). Set up like `..\light-level-overlay` and `..\inventory-sorter` (same toolchain, config and test conventions).

## Commands

- `.\gradlew.bat build`: compile, run unit tests, jar in `build\libs` (ship `seed-cracker-<version>.jar`, not `-sources`)
- `.\gradlew.bat benchmark`: tagged JUnit test excluded from `build`; prints prefixes/s and the full-search time (about 51 s worst case on this 8-thread machine, 2026-11-01)
- `.\gradlew.bat runClientGameTest`: real client, default-preset world (Fabric's test worlds are superflat unless the preset is set) with seed 607 (its floor key is 0.05% into the search, so the real unrestricted crack finishes in a second or two), checks every Overworld chunk fits `OverworldCheck` and a seed with the same low 48 bits fits none, goes to the Nether, checks every bedrock block received against `NetherBedrock`, checks it hasn't started by itself after 10 s, runs `/seedcracker start` and the other commands, screenshots into `build\run\clientGameTest\screenshots`. Muted via `src/gametest/.../MuteAudio`. Deletes `config/seed-cracker-seeds.json` first, or the seed would be recalled instead of cracked.
- `.\gradlew.bat runClient`: dev client (Mod Menu and Cloth Config are on the dev runtime classpath)
- `.\gradlew.bat genSources`: decompiled Minecraft sources, needed for checking 26.x names
- `.\tools\check-deps.ps1 -ModsDir 'E:\MultiMC\instances\26.3\.minecraft\mods' -Staged build\libs\seed-cracker-<version>.jar` (PowerShell 7): must print `PROBLEMS: 0`

## How the crack works (checked in the 26.3 jar)

- Rules: `data/minecraft/worldgen/material_rule/bedrock_floor.json` / `bedrock_roof.json`, `vertical_gradient` with `random_name` `minecraft:bedrock_floor` / `_roof`; floor true at y <= 0, false at y >= 5; roof is `not(gradient 122..127)`. Nether noise settings: `legacy_random_source: true`, height 128.
- Chain (`crack/NetherBedrock`): `root = new LegacyRandomSource(seed).nextLong()`; `key = new LegacyRandomSource(name.hashCode() ^ root).nextLong()`; block: `new LegacyRandomSource(Mth.getSeed(x,y,z) ^ key).nextFloat() < Mth.map(y, ...)`. Only low 48 bits matter at every step.
- `crack/KeySearch`: 2^36 prefixes with the low 12 bits open, interval test per block, then bit-by-bit descent. The key's near neighbours (low bits differ) usually fit one chunk too; the other surface or the seed hash rules them out.
- `crack/SeedRecovery`: `JavaLcg.inputsForNextLong` reverses `nextLong` (2^16 guesses). Top 16 bits from the seed hash: `BiomeManager.biomeZoomSeed` (= `obfuscateSeed`, SHA-256) read through the `BiomeManagerAccessor` mixin. Random seeds are `RandomSource.create().nextLong()`, so their low 48 bits also give the full seed.
- Overworld (`crack/OverworldBedrock`, `OverworldCheck`): same `bedrock_floor` rule at y -64..-60, but Xoroshiro128++: `new XoroshiroRandomSource(seed).forkPositional().fromHashOf("minecraft:bedrock_floor").forkPositional().at(x,y,z).nextFloat()`; `OverworldBedrockTest` compares it with those game classes directly. Xoroshiro's first output mixes weakly, so some wrong seeds differ in only ~2% of blocks; the check therefore counts chunks with zero bedrock where the seed has none (generation only removes floor bedrock), not a mismatch percentage.
- Test vectors in `NetherBedrockTest` come from Nether_Bedrock_Cracker's own tests (LGPL-3.0; the code here is a separate implementation, only the idea and the numbers are shared).

## Gotchas

- Needs JDK 25; `gradle/gradle-daemon-jvm.properties` makes the wrapper use it although `JAVA_HOME` is JDK 17.
- 26.x is unobfuscated Mojang names: `ChunkPos` is a record (`x()`, `z()`, `ChunkPos.pack`), `ClickEvent.CopyToClipboard` / `HoverEvent.ShowText` are records, `player.sendOverlayMessage` / `sendSystemMessage`. Check names in the genSources output instead of guessing.
- Loom plugin is `net.fabricmc.fabric-loom` (no remapping): plain `implementation`/`compileOnly`/`localRuntime`.
- `ClientChunkEvents.CHUNK_LOAD` fires at the end of `ClientChunkCache.replaceWithPacketData`, blocks already in. `ClientPlayConnectionEvents.JOIN` fires at the end of `handleLogin`, before any chunk, so resetting there is safe.
- The search must not see a single wrong block, which is why each attempt uses one chunk's surface (intact = complete y 0 / y 127 layer, nothing above y 127) and the other surface only as a tolerant filter (3%).
- IDE is VS Code. `.vscode/launch.json` is hand-written; don't run `gradlew vscode`.

## Layout

- `src/main`: `crack/` is pure Java (no Minecraft), unit tested in `src/test`: `JavaLcg`, `Surface`, `NetherBedrock`, `Observation`, `KeySearch`, `SeedRecovery`, `Crack` (threads, progress, cancel, early stop), `OverworldBedrock`, `OverworldCheck`.
- `src/client`: `collect/` reads chunks (`BedrockCollector`: Nether `ChunkBedrock`s and up to 1024 Overworld floors); `crack/CrackController` picks chunks, runs attempts, writes chat; `crack/KnownSeeds` remembers found seeds by hash; `command/` is `/seedcracker`; `config/` holds the Gson config, Mod Menu entrypoint and Cloth Config screen; `mixin/BiomeManagerAccessor`.
- `src/gametest`: the client game test.

## Working with the user

Terse; wants things done and recommended, not surveyed. Ask before committing, pushing, or touching the MultiMC `mods` folder.
