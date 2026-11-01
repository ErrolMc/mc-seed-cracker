# Seed Cracker

A client-side Fabric mod for Minecraft 26.3 that works out a world's seed from the bedrock in the Nether. Go to the Nether, type `/seedcracker start`, keep playing, and within a minute or so the seed shows up in chat (click it to copy). It never starts by itself. It only reads blocks your client already has, so it works on any vanilla server without anything installed there.

## How it works

Since 1.18, each block in the Nether's random bedrock layers (floor y 1–4, roof y 123–126) is a weighted coin flip seeded by the block's position mixed with a key that comes from the world seed. The Nether uses Java's old 48-bit random generator, so that key depends on only 48 bits of the seed, and one chunk's worth of bedrock pins it down.

1. **Collect**: every Nether chunk the server sends is read: which of its 4 × 256 floor and roof blocks are bedrock. Only the bedrock that is there is used. Survival players can break bedrock (pistons and TNT, usually to get onto the roof) but nothing in survival places it, so the bedrock that's left is always where generation put it, however much of the rest is gone.
2. **Search**: when you run `/seedcracker start`, it searches all 2^48 keys for the floor of one chunk on background threads. The top 36 bits are tried one by one; bedrock at y 4 rules out most of them before the low 12 bits are even looked at. On an 8-thread CPU the whole search takes under a minute, half that on average.
3. **Finish the seed**: each key that fits is run backwards to the low 48 bits of the seed and checked against the roof. The server sends every client a SHA-256 hash of the world seed (for biome blending), so trying all 65,536 values of the top 16 bits gives the exact seed, whether it was typed in or random.

It then says how many of the loaded chunks match the seed, checks it against the Overworld bedrock (below), and remembers it in `config/seed-cracker-seeds.json`: the next time you join that world it just tells you the seed again.

### Checking a seed against the Overworld

The Nether only pins down the low 48 bits of the seed. The Overworld's bedrock floor (y -64 to -60) comes from a different generator (Xoroshiro128++) seeded from all 64 bits, so it can't be cracked, but generating it forwards for a given seed is quick. `/seedcracker check` does that for every Overworld chunk read this session and compares. Generation can take floor bedrock away (a cave cutting in) and players can break it, but neither adds any, so a chunk fits a seed when it has no bedrock anywhere the seed doesn't put it. The right seed fits every chunk; a wrong one, even one with the same Nether bedrock, fits none (tested on all 65,535 seeds that share a seed's low 48 bits, and in a real world: 142 of 142 chunks for the right seed, 0 of 142 for the wrong one). Chunks with less than half their bedrock left don't count, and it says "matches" when at least 90% of the others fit, so a few chunks with bedrock placed in creative don't spoil it. A superflat Overworld has no random bedrock, so there's nothing to check.

If a chunk's floor gives nothing (bedrock placed there in creative, or that part of the Nether is older than 1.18), it tries the roof of a chunk far away, then another floor. Chunks with less than half their bedrock left are skipped.

## Commands

- `/seedcracker start`: crack the seed from the Nether chunks read so far (go to the Nether first), or try again with chunks it hasn't used yet
- `/seedcracker stop`: stop the search
- `/seedcracker check`: check the seed it found against the Overworld bedrock; `/seedcracker check <seed>` checks any seed (be in the Overworld for a moment first this session)
- `/seedcracker`: what it's doing (chunks read, progress, the seed once found)

## Options

Open them with **Mod Menu → Seed Cracker → Configure** (needs Cloth Config), or edit `config/seed-cracker.json`.

- **Show progress**: percentage and time left above the hotbar while it runs
- **Threads**: Auto uses all CPU threads but one; the search runs at low priority either way

## When it can't find the seed

- **Nether generated before 1.18** (an old world that was upgraded): that bedrock doesn't depend on the seed. Go to a part of the Nether nobody had visited before 1.18 and run `/seedcracker start`.
- **Bedrock changed by players**: broken bedrock doesn't matter. Bedrock placed in creative does, but the search moves on to another chunk if one is off.
- **Not vanilla**: anti-xray plugins that fake hidden blocks, custom world generation, or old Paper builds (before 1.19.2-213, which generated bedrock slightly differently).
- **The server hides the seed hash**: you still get the low 48 bits (enough for structures and most seed maps) and the seed it would be if it was picked at random, marked as not confirmed. `/seedcracker check` tells you whether that guess is right.

Many servers count seed cracking as cheating; check the rules before you use it on someone else's server.

## Install

1. Build it (below) or take `seed-cracker-<version>.jar` from `build/libs` (not the `-sources` jar).
2. Put it in the instance's `mods` folder next to Fabric API. Mod Menu and Cloth Config are optional and only needed for the settings screen.
3. Restart the game.

Requires Minecraft 26.3, Fabric Loader 0.20.1+, Fabric API and Java 25.

## Development (VS Code)

1. Install JDK 25 (this machine has it at `C:\Program Files\Java\jdk-25.0.5.0.2`; `.vscode/settings.json` points there).
2. Open the folder in VS Code and install the recommended extensions when asked (Extension Pack for Java, Gradle for Java).
3. Run **Terminal → Run Task → Decompile Minecraft sources** once, then **Java: Clean Java Language Server Workspace** from the command palette.
4. Press **F5** (launch config "Minecraft Client") to start a dev client through Gradle with the debugger attached.

Gradle tasks, from VS Code's task menu or a terminal:

```
.\gradlew.bat build               # compile, unit tests, jar in build\libs
.\gradlew.bat benchmark           # how long a full search takes on this machine
.\gradlew.bat runClient           # dev client without the debugger
.\gradlew.bat runClientGameTest   # in-game test (muted): cracks a world with a known seed from its Nether, checks it in the Overworld
.\gradlew.bat genSources          # decompiled Minecraft sources for the editor
```

The icon is drawn by `python -I tools/make-icon.py`.

## Credits

The approach (a 36-bit walk with the low 12 bits pruned, then reversing `nextLong`) follows 19MisterX98's [Nether_Bedrock_Cracker](https://github.com/19MisterX98/Nether_Bedrock_Cracker), a standalone tool where you type the block positions in by hand. This mod is a separate Java implementation that collects the blocks itself and uses the seed hash to get the full seed.

## Licence

MIT
