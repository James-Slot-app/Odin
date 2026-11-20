package io.hiddemoens.pluskey;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class InputEventsTest {
    private static void put(ByteBuffer data, boolean wide, int micros, int type, int code, int value) {
        if (wide) data.putLong(42).putLong(micros);
        else data.putInt(42).putInt(micros);
        data.putShort((short) type).putShort((short) code).putInt(value);
    }

    private static void check(boolean wide) throws IOException {
        int recordBytes = wide ? 24 : 16;
        ByteBuffer fixture = ByteBuffer.allocate(recordBytes * 6).order(ByteOrder.nativeOrder());
        put(fixture, wide, 100, 1, 0x2df, 1);
        put(fixture, wide, 100, 0, 0, 0);      // SYN_REPORT
        put(fixture, wide, 800, 1, 0x74, 0);   // Power button on the same device
        put(fixture, wide, 900, 1, 0x2df, 2);  // Key repeat
        put(fixture, wide, 120000, 1, 0x2df, 0);
        put(fixture, wide, 120000, 0, 0, 0);
        List<String> events = new ArrayList<>();
        InputEvents.read(new ByteArrayInputStream(fixture.array()), wide,
            (time, value) -> events.add(time + "/" + value));
        CoreTest.check(events.equals(Arrays.asList("42000100/1", "42120000/0")), "key edges and kernel timestamps");
        try {
            InputEvents.read(new ByteArrayInputStream(new byte[recordBytes - 1]), wide,
                (time, value) -> { throw new AssertionError("partial key"); });
            throw new AssertionError("accepted partial evdev record");
        } catch (IOException expected) { }
    }

    public static void main(String[] args) throws IOException {
        check(false);
        check(true);
        System.out.println("32-bit and 64-bit input event tests passed");
    }
}
