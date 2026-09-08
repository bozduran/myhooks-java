package com.myhooks.step;

import java.nio.file.Path;
import java.util.List;

/**
 * Discovers the fix-groups for one file. Implementations read the file fresh
 * each call (no cross-step caching), since a previous step may have modified
 * it.
 */
@FunctionalInterface
public interface Discoverer {

    List<Group> discover(Context context, Path path) throws Exception;
}
