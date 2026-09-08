package com.myhooks.step;

import java.util.List;

/**
 * One pipeline step. {@code run} is interactive and may modify files, returning
 * the process exit code (0 to continue, 1 to stop). {@code check} is
 * report-only: it lists what would change without prompting or writing and
 * always returns 0 (unless an underlying error, such as a git failure, is
 * surfaced).
 */
public interface Step {

    String name();

    String usage();

    int run(Context context, List<String> args);

    int check(Context context, List<String> args);
}
