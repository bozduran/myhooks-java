package com.myhooks.diffui;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

/**
 * Minimal {@code kernel32} surface needed to drive a Windows console. JNA's
 * bundled {@code Kernel32} interface does not expose the console-mode calls, so
 * only the functions actually used are declared here.
 *
 * <p>Loading is lazy: this class is only touched on Windows, so the POSIX path
 * never loads the native JNA dispatch library.
 */
interface WinKernel32 extends Library {

    WinKernel32 INSTANCE = Native.load("kernel32", WinKernel32.class);

    int GENERIC_READ = 0x80000000;
    int GENERIC_WRITE = 0x40000000;
    int FILE_SHARE_READ = 0x00000001;
    int FILE_SHARE_WRITE = 0x00000002;
    int OPEN_EXISTING = 3;

    int ENABLE_PROCESSED_INPUT = 0x0001;
    int ENABLE_LINE_INPUT = 0x0002;
    int ENABLE_ECHO_INPUT = 0x0004;
    int ENABLE_WINDOW_INPUT = 0x0008;
    int ENABLE_MOUSE_INPUT = 0x0010;
    int ENABLE_VIRTUAL_TERMINAL_INPUT = 0x0200;

    int ENABLE_PROCESSED_OUTPUT = 0x0001;
    int ENABLE_VIRTUAL_TERMINAL_PROCESSING = 0x0004;

    int STD_OUTPUT_HANDLE = -11;

    int WAIT_OBJECT_0 = 0;
    int WAIT_TIMEOUT = 0x102;
    int INFINITE = 0xFFFFFFFF;

    Pointer CreateFileA(String name, int access, int share, Pointer securityAttributes,
            int creationDisposition, int flags, Pointer template);

    boolean GetConsoleMode(Pointer handle, IntByReference mode);

    boolean SetConsoleMode(Pointer handle, int mode);

    Pointer GetStdHandle(int stdHandle);

    boolean ReadFile(Pointer handle, byte[] buffer, int bytesToRead,
            IntByReference bytesRead, Pointer overlapped);

    int WaitForSingleObject(Pointer handle, int milliseconds);

    boolean CloseHandle(Pointer handle);
}
