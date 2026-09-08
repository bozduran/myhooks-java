package com.myhooks.git;

/**
 * Raised when a git command cannot be run or exits non-zero. Unlike the Go
 * original (which printed to stderr and returned no files), git failures are
 * surfaced loudly so mutating steps can stop the commit.
 */
public final class GitException extends RuntimeException {

    public GitException(String message) {
        super(message);
    }

    public GitException(String message, Throwable cause) {
        super(message, cause);
    }
}
