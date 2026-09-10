package com.myhooks.diffui;

import java.io.IOException;

/**
 * A source of raw terminal key bytes.
 *
 * <p>{@link #read()} blocks until a byte is available; {@link #readTimed()}
 * returns -1 when no byte arrives within a short window. The timed read is used
 * to tell a lone ESC from the start of an arrow-key escape sequence without a
 * background reader thread (a background thread would outlive its
 * {@link Terminal} and steal the first character of a later line-based prompt).
 */
interface KeySource {

    int read() throws IOException;

    int readTimed() throws IOException;
}
