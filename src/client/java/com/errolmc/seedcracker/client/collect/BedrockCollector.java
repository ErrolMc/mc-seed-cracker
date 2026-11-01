package com.errolmc.seedcracker.client.collect;

import com.errolmc.seedcracker.crack.OverworldBedrock;
import com.errolmc.seedcracker.crack.OverworldCheck;
import com.errolmc.seedcracker.crack.Surface;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Reads the bedrock layers of every Nether chunk the server sends, and the floor of Overworld chunks for checking a
 * seed. Kept for the whole connection, so data from an earlier visit to either still counts. Client thread only.
 */
public final class BedrockCollector {
	/** Plenty for a check; the first ones read are kept. */
	private static final int MAX_OVERWORLD_CHUNKS = 1024;
	private static final Map<Long, ChunkBedrock> CHUNKS = new LinkedHashMap<>();
	private static final Map<Long, OverworldCheck.Chunk> OVERWORLD = new LinkedHashMap<>();
	private static int received;

	private BedrockCollector() {
	}

	/** {@code ClientChunkEvents.CHUNK_LOAD}: the chunk's blocks are in by then. */
	public static void onChunkLoad(ClientLevel level, LevelChunk chunk) {
		if (level.dimension() == Level.OVERWORLD && level.getMinY() == OverworldBedrock.SOLID_Y) {
			readOverworld(chunk);
			return;
		}
		if (level.dimension() != Level.NETHER || level.getMinY() != 0) {
			return;
		}
		ChunkBedrock bedrock = read(chunk);
		CHUNKS.put(ChunkPos.pack(bedrock.chunkX(), bedrock.chunkZ()), bedrock);
		received++;
	}

	public static void clear() {
		CHUNKS.clear();
		OVERWORLD.clear();
		received = 0;
	}

	public static List<OverworldCheck.Chunk> overworldChunks() {
		return new ArrayList<>(OVERWORLD.values());
	}

	public static int overworldCount() {
		return OVERWORLD.size();
	}

	public static List<ChunkBedrock> chunks() {
		return new ArrayList<>(CHUNKS.values());
	}

	public static int count() {
		return CHUNKS.size();
	}

	/** Chunk packets seen so far; goes up while the server is still sending the area. */
	public static int received() {
		return received;
	}

	static ChunkBedrock read(LevelChunk chunk) {
		ChunkPos pos = chunk.getPos();
		int minX = pos.getMinBlockX();
		int minZ = pos.getMinBlockZ();
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();

		BitSet floor = new BitSet(Surface.FLOOR.layers() * ChunkBedrock.BLOCKS_PER_LAYER);
		BitSet roof = new BitSet(Surface.ROOF.layers() * ChunkBedrock.BLOCKS_PER_LAYER);
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				for (Surface surface : Surface.values()) {
					BitSet bits = surface == Surface.FLOOR ? floor : roof;
					for (int y = surface.minY; y <= surface.maxY; y++) {
						if (chunk.getBlockState(at.set(minX + x, y, minZ + z)).is(Blocks.BEDROCK)) {
							bits.set(ChunkBedrock.index(surface, y, x, z));
						}
					}
				}
			}
		}
		return new ChunkBedrock(pos.x(), pos.z(), floor, roof);
	}

	private static void readOverworld(LevelChunk chunk) {
		ChunkPos pos = chunk.getPos();
		long packed = ChunkPos.pack(pos.x(), pos.z());
		if (OVERWORLD.size() >= MAX_OVERWORLD_CHUNKS && !OVERWORLD.containsKey(packed)) {
			return;
		}
		int minX = pos.getMinBlockX();
		int minZ = pos.getMinBlockZ();
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		BitSet bedrock = new BitSet(OverworldCheck.BLOCKS);
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				for (int y = OverworldBedrock.MIN_Y; y <= OverworldBedrock.MAX_Y; y++) {
					if (chunk.getBlockState(at.set(minX + x, y, minZ + z)).is(Blocks.BEDROCK)) {
						bedrock.set(OverworldCheck.index(y, x, z));
					}
				}
			}
		}
		OVERWORLD.put(packed, new OverworldCheck.Chunk(pos.x(), pos.z(), bedrock));
	}
}
