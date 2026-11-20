package at.haha007.edenmcp.hooks.spark;

import at.haha007.edenmcp.api.*;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtobufInspectorTest {
    @Test
    void maxDepthControlsNestedMessageExpansion() {
        // field 1 = { field 1 = 7 }
        byte[] data = new byte[]{0x0A, 0x04, 0x0A, 0x02, 0x08, 0x07};

        ProtobufInspector.Result shallow = ProtobufInspector.inspect(data, 1, 100, 1024);
        ProtobufInspector.Result deep = ProtobufInspector.inspect(data, 3, 100, 1024);

        assertTrue(shallow.tree().toString().contains("message"), shallow.tree().toString());
        assertFalse(shallow.tree().toString().contains("=7"), shallow.tree().toString());
        assertTrue(deep.tree().toString().contains("=7"), deep.tree().toString());
        assertNotEquals(shallow.tree(), deep.tree());
        assertFalse(shallow.truncated());
        assertFalse(deep.truncated());
    }

    @Test
    void longPrintableStringsAreCapped() {
        byte[] text = "abcdefghij".repeat(600).getBytes(StandardCharsets.UTF_8);
        byte[] data = concat(new byte[]{0x0A}, varint(text.length), text);

        ProtobufInspector.Result result = ProtobufInspector.inspect(data, 4, 100, 8192);

        String rendered = result.tree().toString();
        assertTrue(rendered.contains("abcdefghij"), rendered);
        assertTrue(rendered.contains("... (truncated)"), rendered);
        assertTrue(rendered.length() < text.length, rendered);
        assertEquals(data.length, result.totalBytes());
    }

    @Test
    void fixed32FieldsAreDecodedWithoutTruncation() {
        // field 2, wire type 5 (fixed32), value 0x01020304 little-endian
        byte[] data = new byte[]{0x15, 0x04, 0x03, 0x02, 0x01};

        ProtobufInspector.Result result = ProtobufInspector.inspect(data, 4, 100, 1024);

        assertTrue(result.tree().toString().contains("wireType=5"), result.tree().toString());
        assertFalse(result.truncated());
        assertEquals(data.length, result.bytesInspected());
    }

    private static byte[] varint(int value) {
        List<Byte> bytes = new ArrayList<>();
        int remaining = value;
        while (remaining >= 0x80) {
            bytes.add((byte) ((remaining & 0x7F) | 0x80));
            remaining >>>= 7;
        }
        bytes.add((byte) remaining);
        byte[] result = new byte[bytes.size()];
        for (int i = 0; i < result.length; i++) result[i] = bytes.get(i);
        return result;
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) length += part.length;
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }
}
