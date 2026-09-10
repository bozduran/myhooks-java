package com.myhooks.diffui;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.Charset;

/**
 * Windows implementation of {@link Terminal}.
 *
 * <p>git hooks run with stdin bound to {@code NUL}, so {@code System.in} is at
 * EOF and must not be used. The console is opened directly through the
 * {@code CONIN$}/{@code CONOUT$} devices instead. Raw mode is entered with
 * {@code SetConsoleMode}: line input, echo, processed input, window and mouse
 * events are disabled and {@code ENABLE_VIRTUAL_TERMINAL_INPUT} is enabled so
 * arrow keys arrive as the same {@code ESC [ A..D} sequences the POSIX path
 * sees (on consoles without VT input, arrow keys arrive as a {@code 0x00}/
 * {@code 0xE0} scan-code pair, which {@link Prompt#readKey} also understands).
 * On output, {@code ENABLE_VIRTUAL_TERMINAL_PROCESSING} makes the ANSI
 * cursor/inverse-video sequences render.
 *
 * <p>Blocking reads wait on the console handle with {@code WaitForSingleObject}
 * and then call {@code ReadFile}; the timed read uses a ~100ms wait, mirroring
 * the POSIX {@code stty min 0 time 1} poll without a background thread.
 *
 * <p>This only works when the process has a real Windows console. Terminal
 * emulators that hide it (mintty/Git Bash) and GUI git clients have none, so
 * opening fails and the caller reports a clear, non-silent error. Git's own
 * {@code compat/terminal.c} documents the same CONIN$/CONOUT$ limitation.
 */
final class WindowsTerminal implements Terminal {

    private static final String CONIN = "CONIN$";
    private static final String CONOUT = "CONOUT$";

    private final Pointer inHandle;         // raw-mode console input, null in line mode
    private final Pointer outHandle;        // console output, needed for mode changes
    private final FileInputStream lineIn;   // line-mode input, null in raw mode
    private final BufferedReader lineReader;
    private final FileOutputStream output;
    private final PrintStream out;
    private final int savedInMode;
    private final int savedOutMode;
    private final boolean vtInput;

    private WindowsTerminal(Pointer inHandle, Pointer outHandle, FileInputStream lineIn,
            BufferedReader lineReader, FileOutputStream output,
            int savedInMode, int savedOutMode, boolean vtInput) {
        this.inHandle = inHandle;
        this.outHandle = outHandle;
        this.lineIn = lineIn;
        this.lineReader = lineReader;
        this.output = output;
        this.out = new PrintStream(output, true, consoleCharset());
        this.savedInMode = savedInMode;
        this.savedOutMode = savedOutMode;
        this.vtInput = vtInput;
    }

    /**
     * Opens the console in raw mode, or {@code null} when the process has no
     * attached console or the console cannot do ANSI/VT raw mode (the caller
     * then falls back to line-based prompts).
     */
    static WindowsTerminal openRaw() {
        Pointer in = openDevice(CONIN);
        if (in == null) {
            return null;
        }
        Pointer outHandle = openDevice(CONOUT);
        if (outHandle == null) {
            WinKernel32.INSTANCE.CloseHandle(in);
            return null;
        }
        IntByReference inMode = new IntByReference();
        IntByReference outMode = new IntByReference();
        if (!WinKernel32.INSTANCE.GetConsoleMode(in, inMode)
                || !WinKernel32.INSTANCE.GetConsoleMode(outHandle, outMode)) {
            WinKernel32.INSTANCE.CloseHandle(in);
            WinKernel32.INSTANCE.CloseHandle(outHandle);
            return null;
        }
        int savedIn = inMode.getValue();
        int savedOut = outMode.getValue();

        // Prefer VT input; fall back to raw scan codes on older consoles.
        boolean vt = true;
        if (!WinKernel32.INSTANCE.SetConsoleMode(in, rawInputMode(savedIn, true))) {
            vt = false;
            if (!WinKernel32.INSTANCE.SetConsoleMode(in, rawInputMode(savedIn, false))) {
                WinKernel32.INSTANCE.CloseHandle(in);
                WinKernel32.INSTANCE.CloseHandle(outHandle);
                return null;
            }
        }
        // The inline prompt relies on ANSI cursor control, so require VT output.
        if (!WinKernel32.INSTANCE.SetConsoleMode(outHandle, rawOutputMode(savedOut))) {
            WinKernel32.INSTANCE.SetConsoleMode(in, savedIn);
            WinKernel32.INSTANCE.CloseHandle(in);
            WinKernel32.INSTANCE.CloseHandle(outHandle);
            return null;
        }
        try {
            FileOutputStream fos = new FileOutputStream(CONOUT);
            return new WindowsTerminal(in, outHandle, null, null, fos, savedIn, savedOut, vt);
        } catch (IOException e) {
            WinKernel32.INSTANCE.SetConsoleMode(in, savedIn);
            WinKernel32.INSTANCE.SetConsoleMode(outHandle, savedOut);
            WinKernel32.INSTANCE.CloseHandle(in);
            WinKernel32.INSTANCE.CloseHandle(outHandle);
            return null;
        }
    }

    /** Opens the console in cooked (line) mode, or {@code null} if unavailable. */
    static WindowsTerminal openLine() {
        try {
            FileInputStream in = new FileInputStream(CONIN);
            FileOutputStream fos = new FileOutputStream(CONOUT);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, consoleCharset()));
            return new WindowsTerminal(null, null, in, reader, fos, 0, 0, false);
        } catch (IOException e) {
            return null;
        }
    }

    /** True when this process has a console to write to. */
    static boolean consoleAvailable() {
        Pointer out = WinKernel32.INSTANCE.GetStdHandle(WinKernel32.STD_OUTPUT_HANDLE);
        if (out == null) {
            return false;
        }
        return WinKernel32.INSTANCE.GetConsoleMode(out, new IntByReference());
    }

    /**
     * Enables ANSI/VT processing on stdout so diff color renders on Windows.
     * Returns whether the console now accepts VT sequences.
     */
    static boolean enableAnsiOnStdout() {
        Pointer out = WinKernel32.INSTANCE.GetStdHandle(WinKernel32.STD_OUTPUT_HANDLE);
        if (out == null) {
            return false;
        }
        IntByReference mode = new IntByReference();
        if (!WinKernel32.INSTANCE.GetConsoleMode(out, mode)) {
            return false;
        }
        return WinKernel32.INSTANCE.SetConsoleMode(out, rawOutputMode(mode.getValue()));
    }

    private static int rawInputMode(int mode, boolean vtInput) {
        int cleared = mode & ~(WinKernel32.ENABLE_LINE_INPUT
                | WinKernel32.ENABLE_ECHO_INPUT
                | WinKernel32.ENABLE_PROCESSED_INPUT
                | WinKernel32.ENABLE_WINDOW_INPUT
                | WinKernel32.ENABLE_MOUSE_INPUT);
        return vtInput ? cleared | WinKernel32.ENABLE_VIRTUAL_TERMINAL_INPUT : cleared;
    }

    private static int rawOutputMode(int mode) {
        return mode | WinKernel32.ENABLE_PROCESSED_OUTPUT
                | WinKernel32.ENABLE_VIRTUAL_TERMINAL_PROCESSING;
    }

    /** Opens a console device, returning {@code null} on failure. */
    private static Pointer openDevice(String name) {
        Pointer handle = WinKernel32.INSTANCE.CreateFileA(name,
                WinKernel32.GENERIC_READ | WinKernel32.GENERIC_WRITE,
                WinKernel32.FILE_SHARE_READ | WinKernel32.FILE_SHARE_WRITE,
                null, WinKernel32.OPEN_EXISTING, 0, null);
        if (handle == null) {
            return null;
        }
        long value = Pointer.nativeValue(handle);
        if (value == -1L || value == 0xFFFFFFFFL) {
            return null;
        }
        return handle;
    }

    /**
     * The charset the console expects. Java records the console/stdout encoding
     * it started with in {@code sun.stdout.encoding}; falling back to the
     * platform default keeps bytes consistent with what the console decodes.
     */
    private static Charset consoleCharset() {
        for (String property : new String[] {"sun.stdout.encoding", "native.encoding"}) {
            String name = System.getProperty(property);
            if (name != null) {
                try {
                    return Charset.forName(name);
                } catch (RuntimeException ignored) {
                    // fall through to the default
                }
            }
        }
        return Charset.defaultCharset();
    }

    @Override
    public int read() throws IOException {
        if (inHandle == null) {
            return lineIn.read();
        }
        while (true) {
            int wait = WinKernel32.INSTANCE.WaitForSingleObject(inHandle, WinKernel32.INFINITE);
            if (wait != WinKernel32.WAIT_OBJECT_0) {
                return -1;
            }
            int b = readPending();
            if (b >= 0) {
                return b;
            }
        }
    }

    @Override
    public int readTimed() throws IOException {
        if (inHandle == null) {
            return lineIn.read();
        }
        int wait = WinKernel32.INSTANCE.WaitForSingleObject(inHandle, 100);
        if (wait != WinKernel32.WAIT_OBJECT_0) {
            return -1; // timeout (or failure) — treated the same as "no byte"
        }
        return readPending();
    }

    /** Reads one byte that {@code WaitForSingleObject} reported as pending. */
    private int readPending() throws IOException {
        byte[] buffer = new byte[1];
        IntByReference read = new IntByReference();
        if (!WinKernel32.INSTANCE.ReadFile(inHandle, buffer, 1, read, null)) {
            throw new IOException("ReadFile on " + CONIN + " failed");
        }
        if (read.getValue() == 0) {
            return -1; // non-key event; caller retries (blocking) or reports timeout
        }
        return buffer[0] & 0xff;
    }

    @Override
    public void cook() {
        if (inHandle == null) {
            return;
        }
        WinKernel32.INSTANCE.SetConsoleMode(inHandle, savedInMode);
        if (outHandle != null) {
            WinKernel32.INSTANCE.SetConsoleMode(outHandle, savedOutMode);
        }
    }

    @Override
    public void raw() {
        if (inHandle == null) {
            return;
        }
        WinKernel32.INSTANCE.SetConsoleMode(inHandle, rawInputMode(savedInMode, vtInput));
        if (outHandle != null) {
            WinKernel32.INSTANCE.SetConsoleMode(outHandle, rawOutputMode(savedOutMode));
        }
    }

    @Override
    public BufferedReader lineReader() {
        return lineReader;
    }

    @Override
    public PrintStream out() {
        return out;
    }

    @Override
    public void close() {
        if (inHandle == null) {
            try {
                if (lineReader != null) {
                    lineReader.close();
                } else if (lineIn != null) {
                    lineIn.close();
                }
            } catch (IOException ignored) {
                // best-effort
            }
        } else {
            WinKernel32.INSTANCE.SetConsoleMode(inHandle, savedInMode);
            if (outHandle != null) {
                WinKernel32.INSTANCE.SetConsoleMode(outHandle, savedOutMode);
            }
            WinKernel32.INSTANCE.CloseHandle(inHandle);
            if (outHandle != null) {
                WinKernel32.INSTANCE.CloseHandle(outHandle);
            }
        }
        out.close();
    }
}
