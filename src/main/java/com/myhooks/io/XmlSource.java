package com.myhooks.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A JRXML document's decoded text together with the charset it was stored in.
 *
 * <p>Reading a {@code .jrxml} as UTF-8 regardless of its declaration silently
 * replaces non-UTF-8 bytes with {@code U+FFFD} and then writes the replacement
 * characters back as UTF-8, permanently corrupting the file. This class instead
 * detects the encoding from a byte-order mark first and the XML declaration
 * second, decodes strictly (malformed input is an error, not a replacement
 * character), and can encode edited text back in the same charset with the BOM
 * restored.
 */
public final class XmlSource {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final byte[] UTF16LE_BOM = {(byte) 0xFF, (byte) 0xFE};
    private static final byte[] UTF16BE_BOM = {(byte) 0xFE, (byte) 0xFF};

    private static final Pattern DECLARED_ENCODING = Pattern.compile(
            "<\\?xml[^>]*?encoding\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    private static final int DECLARATION_SCAN_BYTES = 200;

    private final Charset charset;
    private final boolean bom;
    private final String text;

    private XmlSource(Charset charset, boolean bom, String text) {
        this.charset = charset;
        this.bom = bom;
        this.text = text;
    }

    /** Reads and decodes a file, or throws with a clear message. */
    public static XmlSource read(Path path) throws IOException {
        return of(Files.readAllBytes(path));
    }

    /** Decodes the given bytes, or throws with a clear message. */
    public static XmlSource of(byte[] raw) throws IOException {
        Objects.requireNonNull(raw, "raw");
        Charset charset;
        boolean bom = false;
        int offset = 0;
        if (startsWith(raw, UTF8_BOM)) {
            charset = StandardCharsets.UTF_8;
            bom = true;
            offset = UTF8_BOM.length;
        } else if (startsWith(raw, UTF16LE_BOM)) {
            charset = StandardCharsets.UTF_16LE;
            bom = true;
            offset = UTF16LE_BOM.length;
        } else if (startsWith(raw, UTF16BE_BOM)) {
            charset = StandardCharsets.UTF_16BE;
            bom = true;
            offset = UTF16BE_BOM.length;
        } else {
            charset = declaredCharset(raw);
        }
        String text = decode(raw, offset, raw.length - offset, charset);
        return new XmlSource(charset, bom, text);
    }

    /** The decoded document text (no BOM; the XML declaration is preserved). */
    public String text() {
        return text;
    }

    /** The charset the document was decoded from. */
    public Charset charset() {
        return charset;
    }

    /** Whether the original bytes began with a byte-order mark. */
    public boolean hasBom() {
        return bom;
    }

    /**
     * Encodes {@code newText} back in the document's charset, restoring the BOM
     * if the original had one. Fails rather than approximating when the charset
     * cannot represent a character introduced by an edit.
     */
    public byte[] encode(String newText) throws IOException {
        Objects.requireNonNull(newText, "newText");
        CharsetEncoder encoder = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer encoded;
        try {
            encoded = encoder.encode(CharBuffer.wrap(newText));
        } catch (CharacterCodingException e) {
            throw new IOException("cannot encode change as " + charset.name()
                    + ": the edit introduces characters this charset cannot represent", e);
        }
        byte[] bomBytes = bom ? bomBytes() : new byte[0];
        byte[] out = new byte[bomBytes.length + encoded.remaining()];
        System.arraycopy(bomBytes, 0, out, 0, bomBytes.length);
        encoded.get(out, bomBytes.length, encoded.remaining());
        return out;
    }

    /** The encoding named in the XML declaration, or UTF-8 when there is none. */
    private static Charset declaredCharset(byte[] raw) throws IOException {
        int limit = Math.min(raw.length, DECLARATION_SCAN_BYTES);
        String head = new String(raw, 0, limit, StandardCharsets.ISO_8859_1);
        Matcher matcher = DECLARED_ENCODING.matcher(head);
        if (!matcher.find()) {
            return StandardCharsets.UTF_8;
        }
        String name = matcher.group(1);
        try {
            return Charset.forName(name);
        } catch (RuntimeException e) {
            throw new IOException("unsupported XML encoding \"" + name + "\"", e);
        }
    }

    private static String decode(byte[] raw, int offset, int length, Charset charset) throws IOException {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(raw, offset, length)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("file is not valid " + charset.name()
                    + " (declare the encoding or fix the bytes)", e);
        }
    }

    private byte[] bomBytes() {
        if (StandardCharsets.UTF_16LE.equals(charset)) {
            return UTF16LE_BOM.clone();
        }
        if (StandardCharsets.UTF_16BE.equals(charset)) {
            return UTF16BE_BOM.clone();
        }
        return UTF8_BOM.clone();
    }

    private static boolean startsWith(byte[] raw, byte[] prefix) {
        if (raw.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (raw[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
