package io.geoverse.bench;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

final class MaterializedChecksum {
    private final List<byte[]> rows = new ArrayList<>();

    void add(String id, byte[] geometry, Float xmin, Float ymin, Float xmax, Float ymax) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            nullable(out, id == null ? null : id.getBytes(StandardCharsets.UTF_8));
            nullable(out, geometry);
            boolean bboxNull = xmin == null && ymin == null && xmax == null && ymax == null;
            out.writeByte(bboxNull ? 0 : 1);
            if (!bboxNull) {
                nullableFloat(out, xmin); nullableFloat(out, ymin);
                nullableFloat(out, xmax); nullableFloat(out, ymax);
            }
            rows.add(bytes.toByteArray());
        } catch (java.io.IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    String finish() {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        rows.sort(MaterializedChecksum::compareUnsigned);
        for (byte[] row : rows) {
            digest.update(new byte[] {(byte)(row.length >>> 56), (byte)(row.length >>> 48), (byte)(row.length >>> 40), (byte)(row.length >>> 32), (byte)(row.length >>> 24), (byte)(row.length >>> 16), (byte)(row.length >>> 8), (byte)row.length});
            digest.update(row);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void nullable(DataOutputStream out, byte[] value) throws java.io.IOException {
        out.writeByte(value == null ? 0 : 1);
        if (value != null) { out.writeLong(value.length); out.write(value); }
    }
    private static void nullableFloat(DataOutputStream out, Float value) throws java.io.IOException {
        out.writeByte(value == null ? 0 : 1); if (value != null) out.writeFloat(value);
    }
    private static int compareUnsigned(byte[] left, byte[] right) {
        return java.util.Arrays.compareUnsigned(left, right);
    }
}
