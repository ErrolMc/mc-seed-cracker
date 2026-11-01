package com.errolmc.seedcracker.crack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class KeySearchTest {
	private static final long SEED = -4172144997902289642L;

	@Test
	void findsTheKeyFromOneChunk() {
		for (Surface surface : Surface.values()) {
			long key = NetherBedrock.surfaceKeyOfWorld(SEED, surface);
			KeySearch search = new KeySearch(surface, Chunks.observe(SEED, surface, 3, -7));
			assertTrue(search.expectedFalseKeys() < 1e-9, surface + " sample too weak: " + search.expectedFalseKeys());
			long prefix = KeySearch.prefixOf(key);
			List<Long> hits = hits(search, prefix - 5000, prefix + 5000);
			assertTrue(hits.contains(key), surface.toString());
			// The lowest key bits hardly move any block's roll, so near neighbours of the key fit too, but nothing else.
			assertTrue(hits.stream().allMatch(hit -> Math.abs(hit - key) < 64), surface + ": " + hits);
		}
	}

	@Test
	void otherSurfaceTellsTheNeighboursApart() {
		long key = NetherBedrock.surfaceKeyOfWorld(SEED, Surface.FLOOR);
		List<Observation> roof = Chunks.observe(SEED, Surface.ROOF, 3, -7);
		KeySearch search = new KeySearch(Surface.FLOOR, Chunks.observe(SEED, Surface.FLOOR, 3, -7));
		long prefix = KeySearch.prefixOf(key);
		List<Long> fitting = hits(search, prefix - 1, prefix + 2).stream()
			.filter(hit -> SeedRecovery.structureSeeds(hit, Surface.FLOOR, rootKey -> {
				long roofKey = NetherBedrock.surfaceKey(rootKey, Surface.ROOF);
				return roof.stream().allMatch(o -> o.matches(roofKey, Surface.ROOF));
			}).contains(SEED & JavaLcg.MASK))
			.toList();
		assertEquals(List.of(key), fitting);
	}

	@Test
	void findsNothingElsewhere() {
		KeySearch search = new KeySearch(Surface.FLOOR, Chunks.observe(SEED, Surface.FLOOR, 0, 0));
		assertEquals(List.of(), hits(search, 1L << 30, (1L << 30) + (1L << 20)));
	}

	@Test
	void missesTheKeyWhenOneBlockIsWrong() {
		long key = NetherBedrock.surfaceKeyOfWorld(SEED, Surface.FLOOR);
		List<Observation> observations = Chunks.tamper(Chunks.observe(SEED, Surface.FLOOR, 0, 0), 700);
		KeySearch search = new KeySearch(Surface.FLOOR, observations);
		long prefix = KeySearch.prefixOf(key);
		assertEquals(List.of(), hits(search, prefix - 100, prefix + 100));
	}

	/**
	 * With only a few blocks lots of keys fit, so the pruned search can be compared with trying every key. It must find
	 * exactly the same ones: the pruning may never drop a key that fits.
	 */
	@Test
	void sameAsTryingEveryKey() {
		Random random = new Random(7);
		for (Surface surface : Surface.values()) {
			for (int round = 0; round < 4; round++) {
				long trueKey = random.nextLong() & JavaLcg.MASK;
				List<Observation> observations = new ArrayList<>();
				for (int i = 0; i < 8; i++) {
					int x = random.nextInt(2000) - 1000;
					int y = surface.minY + random.nextInt(surface.layers());
					int z = random.nextInt(2000) - 1000;
					observations.add(new Observation(x, y, z, NetherBedrock.isBedrock(trueKey, surface, x, y, z)));
				}
				KeySearch search = new KeySearch(surface, observations);
				long from = KeySearch.prefixOf(trueKey) - 64;
				long to = from + 256;

				List<Long> expected = new ArrayList<>();
				for (long key = from << KeySearch.OPEN_BITS; key < to << KeySearch.OPEN_BITS; key++) {
					long k = key;
					if (observations.stream().allMatch(o -> o.matches(k, surface))) {
						expected.add(key);
					}
				}
				List<Long> found = hits(search, from, to);
				found.sort(null);
				assertTrue(expected.contains(trueKey));
				assertEquals(expected, found, surface + " round " + round + ", " + expected.size() + " keys fit");
			}
		}
	}

	@Test
	void prefixesCoverEveryKey() {
		assertEquals(1L << 36, KeySearch.PREFIXES);
		assertEquals(KeySearch.PREFIXES - 1, KeySearch.prefixOf(JavaLcg.MASK));
		assertTrue(Arrays.stream(new long[] {0, 4095}).allMatch(key -> KeySearch.prefixOf(key) == 0));
	}

	static List<Long> hits(KeySearch search, long from, long to) {
		List<Long> hits = new ArrayList<>();
		search.search(Math.max(0, from), Math.min(KeySearch.PREFIXES, to), hits::add);
		return hits;
	}
}
