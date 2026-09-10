package com.myhooks.diffui;

/**
 * Thrown when an interactive answer is required but no controlling terminal is
 * available (for example a hook run from a GUI client, a CI pipe, or a Windows
 * process with no attached console).
 *
 * <p>This exists so the tool can never silently fall back to the default
 * {@code No} answer: the caller is expected to report the problem and block
 * rather than skip every fix without telling anyone.
 */
public final class NoTerminalException extends RuntimeException {

    public NoTerminalException(String question) {
        super("no interactive terminal is available to answer \"" + question + "\""
                + (Terminals.isWindows()
                        ? " (on Windows this needs a real console; mintty/Git Bash and GUI git"
                                + " clients hide it — run git commit from Windows Terminal,"
                                + " PowerShell or cmd.exe)"
                        : ""));
    }
}
