package com.attendance.service.insightface;

import java.util.Random;

/** Helpers to build deterministic unit vectors with a chosen similarity for tests. */
final class TestVectors {

    private TestVectors() {
    }

    static float[] random(long seed, int dim) {
        Random r = new Random(seed);
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) {
            v[i] = (float) r.nextGaussian();
        }
        return EmbeddingCodec.normalize(v);
    }

    /**
     * A unit vector whose cosine similarity to {@code base} is approximately {@code similarity}:
     * mixes {@code base} with a random direction made orthogonal to it.
     */
    static float[] withSimilarity(float[] base, double similarity, long seed) {
        float[] noise = random(seed, base.length);
        double dot = 0;
        for (int i = 0; i < base.length; i++) {
            dot += base[i] * noise[i];
        }
        float[] ortho = new float[base.length];
        for (int i = 0; i < base.length; i++) {
            ortho[i] = (float) (noise[i] - dot * base[i]);
        }
        ortho = EmbeddingCodec.normalize(ortho);
        double s = Math.sqrt(Math.max(0, 1 - similarity * similarity));
        float[] out = new float[base.length];
        for (int i = 0; i < base.length; i++) {
            out[i] = (float) (similarity * base[i] + s * ortho[i]);
        }
        return EmbeddingCodec.normalize(out);
    }
}
