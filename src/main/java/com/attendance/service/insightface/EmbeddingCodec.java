package com.attendance.service.insightface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Converts a face embedding between {@code float[]} and {@code byte[]}.
 *
 * <p>The vector is stored in the database as raw little-endian bytes (4 bytes per float). This is
 * portable across PostgreSQL ({@code bytea}) and the H2 test database, unlike a native float array
 * column or the pgvector type, and is compact (512 floats = 2048 bytes).
 */
public final class EmbeddingCodec {

    private EmbeddingCodec() {
    }

    /** Serialize a float vector to little-endian bytes. */
    public static byte[] toBytes(float[] vector) {
        if (vector == null) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asFloatBuffer().put(vector);
        return buffer.array();
    }

    /** Deserialize little-endian bytes back to a float vector. */
    public static float[] toFloats(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new float[0];
        }
        FloatBuffer floatBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        float[] out = new float[floatBuffer.remaining()];
        floatBuffer.get(out);
        return out;
    }

    /**
     * Return an L2-normalized copy of the vector (unit length), so cosine similarity is a plain dot
     * product. A zero vector is returned unchanged.
     */
    public static float[] normalize(float[] vector) {
        if (vector == null || vector.length == 0) {
            return vector;
        }
        double sumSq = 0.0;
        for (float v : vector) {
            sumSq += (double) v * v;
        }
        double norm = Math.sqrt(sumSq);
        if (norm == 0.0) {
            return vector.clone();
        }
        float[] out = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            out[i] = (float) (vector[i] / norm);
        }
        return out;
    }
}
