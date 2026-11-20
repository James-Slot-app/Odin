package io.hiddemoens.pluskey;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Linux evdev returns whole input_event records in the reading process's native ABI. */
final class InputEvents {
    private static final int EV_KEY = 1;
    private static final int BTN_TRIGGER_HAPPY32 = 0x2df;

    interface Listener { void key(long timestampUs, int value); }

    static void read(InputStream device, boolean wideTimeval, Listener listener) throws IOException {
        int timeBytes = wideTimeval ? 16 : 8;
        int recordBytes = timeBytes + 8;
        byte[] bytes = new byte[recordBytes * 32];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
        int size;
        while ((size = device.read(bytes)) != -1) {
            if (size == 0 || size % recordBytes != 0) throw new IOException("Incomplete input event");
            for (int offset = 0; offset < size; offset += recordBytes) {
                int type = offset + timeBytes;
                if (buffer.getShort(type) != EV_KEY || buffer.getShort(type + 2) != BTN_TRIGGER_HAPPY32) continue;
                int value = buffer.getInt(type + 4);
                if (value != 0 && value != 1) continue;
                long seconds = wideTimeval ? buffer.getLong(offset) : Integer.toUnsignedLong(buffer.getInt(offset));
                long micros = wideTimeval ? buffer.getLong(offset + 8) : buffer.getInt(offset + 4);
                listener.key(seconds * 1_000_000L + micros, value);
            }
        }
    }
}
