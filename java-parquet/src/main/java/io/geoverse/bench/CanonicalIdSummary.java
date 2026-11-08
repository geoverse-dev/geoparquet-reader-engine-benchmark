package io.geoverse.bench;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

final class CanonicalIdSummary {
    record Result(long rowCount, String hash, long nullCount, long duplicateCount) {}

    private final List<byte[]> encoded = new ArrayList<>();
    private final Map<String, Long> counts = new HashMap<>();
    private long nullCount;

    void add(String id) {
        encoded.add(encode(id));
        if (id == null) nullCount++;
        counts.merge(id, 1L, Long::sum);
    }

    Result finish() {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        encoded.sort(Arrays::compareUnsigned);
        encoded.forEach(digest::update);
        long duplicates = counts.values().stream().mapToLong(value -> Math.max(0, value - 1)).sum();
        return new Result(encoded.size(), HexFormat.of().formatHex(digest.digest()), nullCount, duplicates);
    }

    private static byte[] encode(String id) {
        if (id == null) return new byte[] {0};
        byte[] value = id.getBytes(StandardCharsets.UTF_8);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(1); out.writeLong(value.length); out.write(value);
            return bytes.toByteArray();
        } catch (java.io.IOException impossible) { throw new AssertionError(impossible); }
    }
}
