package com.myhooks.diffui;

import java.io.BufferedReader;
import java.io.PrintStream;

/**
 * A controlling terminal used for interactive prompts, independent of the
 * process's stdin/stdout.
 *
 * <p>git runs hooks with stdin bound to {@code /dev/null} on POSIX and
 * {@code NUL} on Windows, so {@code System.in} cannot be used to ask the user
 * anything — the keystrokes only ever arrive on the controlling terminal. This
 * interface abstracts the two platform implementations: {@link PosixTerminal}
 * ({@code /dev/tty} + {@code stty}) and {@link WindowsTerminal}
 * ({@code CONIN$}/{@code CONOUT$} + {@code SetConsoleMode}).
 *
 * <p>{@link #cook()} temporarily leaves raw mode (used while a fix runs a
 * line-based free-form prompt) and {@link #raw()} re-enters it.
 */
interface Terminal extends AutoCloseable, KeySource {

    /** Reader for cooked (line-buffered) input, or {@code null} in raw mode. */
    BufferedReader lineReader();

    /** Output stream bound to the terminal, not to stdout. */
    PrintStream out();

    /** Restores cooked (line) mode after a temporary {@link #raw()}. */
    void cook();

    /** Re-enters raw mode after a temporary {@link #cook()}. */
    void raw();

    /** Reads one logical key (arrow sequence or single char) in raw mode. */
    default String readKey() {
        return Prompt.readKey(this);
    }

    @Override
    void close();
}
