package com.myhooks.discover;

import com.myhooks.git.GitStaged;
import java.util.List;
import java.util.Locale;

/**
 * Selects the {@code .jrxml} files to process: the command-line arguments when
 * given, otherwise the staged files, in both cases filtered to names ending in
 * {@code .jrxml} (case-insensitive).
 */
public final class FileDiscovery {

    private final GitStaged git;

    public FileDiscovery() {
        this(new GitStaged());
    }

    public FileDiscovery(GitStaged git) {
        this.git = git;
    }

    public List<String> files(List<String> args) {
        List<String> candidates = (args != null && !args.isEmpty()) ? args : git.staged();
        return candidates.stream()
                .filter(FileDiscovery::isJrxml)
                .toList();
    }

    private static boolean isJrxml(String path) {
        return path.toLowerCase(Locale.ROOT).endsWith(".jrxml");
    }
}
