package xero.dialect.justice;

import xero.compile.Items;
import xero.compile.Nbt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;

final class Stacks {
    private static final byte[] HEADER = {0x1f, (byte) 0x8b, 8, 0, 0, 0, 0, 0, 0, (byte) 0xff};
    private static final String DATA_VERSION = "DataVersion";
    private static final String NAMESPACE = "minecraft:";

    private Stacks() {}

    static Nbt.Compound decode(String base64) {
        try {
            byte[] raw = Base64.getDecoder().decode(base64);
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                return Nbt.readRoot(in.readAllBytes());
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static String encode(Nbt.Compound root) {
        return Base64.getEncoder().encodeToString(gzip(Nbt.root(root)));
    }

    private static byte[] gzip(byte[] data) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(HEADER, 0, HEADER.length);
        try {
            deflater.setInput(data, 0, data.length);
            deflater.finish();
            byte[] buffer = new byte[data.length + 64];
            while (!deflater.finished()) {
                int n = deflater.deflate(buffer, 0, buffer.length);
                out.write(buffer, 0, n);
            }
        } finally {
            deflater.end();
        }
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length);
        int sum = (int) crc.getValue();
        for (int v : new int[]{sum, data.length})
            for (int i = 0; i < 4; i++) out.write((v >>> (8 * i)) & 0xff);
        return out.toByteArray();
    }

    static Nbt.Compound full(Nbt.Compound given) {
        Nbt.Compound root = new Nbt.Compound();
        Nbt.Tag version = given.get(DATA_VERSION);
        root.put(DATA_VERSION, version != null ? version : Nbt.Num.ofInt(Items.DATA_VERSION));
        Nbt.Tag id = given.get("id");
        if (id instanceof Nbt.Text t && t.value().indexOf(':') < 0) id = new Nbt.Text(NAMESPACE + t.value());
        if (id != null) root.put("id", id);
        Nbt.Tag count = given.get("count");
        root.put("count", count != null ? count : Nbt.Num.ofInt(1));
        for (Map.Entry<String, Nbt.Tag> e : given.entries().entrySet())
            if (!root.entries().containsKey(e.getKey())) root.put(e.getKey(), e.getValue());
        return root;
    }

    static Nbt.Compound shown(Nbt.Compound root) {
        Nbt.Compound out = new Nbt.Compound();
        for (Map.Entry<String, Nbt.Tag> e : root.entries().entrySet()) {
            String key = e.getKey();
            Nbt.Tag value = e.getValue();
            if (key.equals(DATA_VERSION) && value instanceof Nbt.Num n && n.type() == Nbt.INT && n.bits() == Items.DATA_VERSION) continue;
            if (key.equals("count") && value instanceof Nbt.Num n && n.type() == Nbt.INT && n.bits() == 1) continue;
            if (key.equals("id") && value instanceof Nbt.Text t && t.value().startsWith(NAMESPACE))
                value = new Nbt.Text(t.value().substring(NAMESPACE.length()));
            out.put(key, value);
        }
        return out;
    }
}
