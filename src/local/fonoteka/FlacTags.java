package local.fonoteka;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

/** Rewrites native FLAC metadata blocks and streams the encoded frames unchanged. */
final class FlacTags {
    static void write(InputStream source, OutputStream target, String title, String artist,
                      String album, byte[] jpeg, int width, int height) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(source));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(target));
        if (in.readInt() != 0x664c6143) throw new IOException("Требуется файл FLAC");
        out.writeInt(0x664c6143);
        boolean last, first = true;
        ArrayList<String> other = new ArrayList<>();
        do {
            int type = in.readUnsignedByte();
            last = (type & 128) != 0;
            type &= 127;
            int size = in.readUnsignedByte() << 16 | in.readUnsignedShort();
            if (first && (type != 0 || size != 34)) throw new IOException("Повреждён заголовок FLAC");
            first = false;
            byte[] data = new byte[size];
            in.readFully(data);
            if (type == 4) {
                ByteBuffer fields = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
                readField(fields);
                if (fields.remaining() < 4) throw new IOException("Повреждены теги");
                int count = fields.getInt();
                if (count < 0 || count > fields.remaining()/4) throw new IOException("Повреждены теги");
                for (int i = 0; i < count; i++) {
                    String value = readField(fields);
                    String key = value.split("=", 2)[0].toUpperCase(Locale.ROOT);
                    if (!key.equals("TITLE") && !key.equals("ARTIST") && !key.equals("ALBUM")) other.add(value);
                }
            } else if (!(type == 6 && jpeg != null && size >= 4 && ByteBuffer.wrap(data).getInt() == 3)) {
                block(out, type, data);
            }
        } while (!last);
        other.add("TITLE=" + title);
        other.add("ARTIST=" + artist);
        other.add("ALBUM=" + album);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(buffer);
        field(data, "Fonoteka");
        data.writeInt(Integer.reverseBytes(other.size()));
        for (String value : other) field(data, value);
        block(out, jpeg == null ? 132 : 4, buffer.toByteArray());
        if (jpeg != null) {
            buffer.reset();
            data.writeInt(3);
            data.writeInt(10);
            data.writeBytes("image/jpeg");
            data.writeInt(0);
            data.writeInt(width);
            data.writeInt(height);
            data.writeInt(24);
            data.writeInt(0);
            data.writeInt(jpeg.length);
            data.write(jpeg);
            block(out, 134, buffer.toByteArray());
        }
        byte[] chunk = new byte[65536];
        int n;
        while ((n = in.read(chunk)) != -1) out.write(chunk, 0, n);
        out.flush();
    }
    private static String readField(ByteBuffer buf) throws IOException {
        if (buf.remaining() < 4) throw new IOException("Повреждены теги");
        int len = buf.getInt();
        if (len < 0 || len > buf.remaining()) throw new IOException("Повреждены теги");
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
    private static void field(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(Integer.reverseBytes(bytes.length));
        out.write(bytes);
    }
    private static void block(DataOutputStream out, int type, byte[] data) throws IOException {
        if (data.length > 0xffffff) throw new IOException("Слишком большой блок метаданных");
        out.writeByte(type);
        out.writeByte(data.length >> 16);
        out.writeShort(data.length);
        out.write(data);
    }
}
