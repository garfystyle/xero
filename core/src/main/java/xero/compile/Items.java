package xero.compile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;

public final class Items {
    public static final int DATA_VERSION = 4903;
    public static final String EMPTY = "AAAAAAAAAAA=";

    public record Stack(String id, int count, Nbt.Compound components) {}

    public static String encode(String id, int count) {
        return encode(new Stack(id, count, null));
    }

    public static String encode(Stack s) {
        Nbt.Compound root = new Nbt.Compound()
                .put("DataVersion", Nbt.Num.ofInt(DATA_VERSION))
                .put("id", new Nbt.Text(s.id()))
                .put("count", Nbt.Num.ofInt(s.count()));
        if (s.components() != null && !s.components().entries().isEmpty()) root.put("components", s.components());
        return Base64.getEncoder().encodeToString(gzip(Nbt.root(root)));
    }

    public static Stack decode(String base64) {
        try {
            byte[] raw = Base64.getDecoder().decode(base64);
            byte[] nbt;
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                nbt = in.readAllBytes();
            }
            Nbt.Compound root = Nbt.readRoot(nbt);
            var e = root.entries();
            if (e.size() < 3 || e.size() > 4) return null;
            if (!(root.get("DataVersion") instanceof Nbt.Num v) || v.type() != Nbt.INT || v.bits() != DATA_VERSION) return null;
            if (!(root.get("id") instanceof Nbt.Text id)) return null;
            if (!(root.get("count") instanceof Nbt.Num count) || count.type() != Nbt.INT) return null;
            Nbt.Compound components = null;
            if (e.size() == 4) {
                if (!(root.get("components") instanceof Nbt.Compound c) || c.entries().isEmpty()) return null;
                components = c;
            }
            Stack s = new Stack(id.value(), (int) count.bits(), components);
            return encode(s).equals(base64) ? s : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static String canonical(String base64) {
        try {
            byte[] nbt;
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(base64)))) {
                nbt = in.readAllBytes();
            }
            Nbt.Compound root = Nbt.readRoot(nbt);
            if (!(root.get("id") instanceof Nbt.Text id)) return base64;
            int count = root.get("count") instanceof Nbt.Num c ? (int) c.bits() : 1;
            Nbt.Compound components = root.get("components") instanceof Nbt.Compound x && !x.entries().isEmpty()
                    ? ItemSpec.tidy(x) : null;
            return encode(new Stack(id.value(), count, components));
        } catch (IOException | RuntimeException e) {
            return base64;
        }
    }

    private static final byte[] HEADER = {0x1f, (byte) 0x8b, 8, 0, 0, 0, 0, 0, 0, (byte) 0xff};

    static byte[] gzip(byte[] data) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(HEADER, 0, HEADER.length);
        try {
            deflater.setInput(data, 0, data.length);
            deflater.finish();
            byte[] buf = new byte[data.length + 64];
            while (!deflater.finished()) {
                int n = deflater.deflate(buf, 0, buf.length);
                out.write(buf, 0, n);
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

    private Items() {}
}
