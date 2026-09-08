package com.myhooks.includegraph;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The include graph: which {@code .jrxml} files reference which other base
 * names (the usual Jaspersoft subreport reference, by double-quoted base name).
 * Immutable once built.
 */
public final class Graph {

    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");

    private final Map<String, String> baseOf;
    private final Map<String, List<String>> pathsByBase;
    private final Map<String, List<String>> referencers;
    private final Map<String, List<String>> referencedBases;

    private Graph(Map<String, String> baseOf, Map<String, List<String>> pathsByBase,
            Map<String, List<String>> referencers, Map<String, List<String>> referencedBases) {
        this.baseOf = baseOf;
        this.pathsByBase = pathsByBase;
        this.referencers = referencers;
        this.referencedBases = referencedBases;
    }

    public static Graph build(Map<String, String> files) {
        Map<String, String> baseOf = new HashMap<>();
        Set<String> bases = new HashSet<>();
        Map<String, List<String>> pathsByBase = new HashMap<>();
        for (String path : files.keySet()) {
            String base = baseName(path);
            baseOf.put(path, base);
            bases.add(base);
            pathsByBase.computeIfAbsent(base, k -> new ArrayList<>()).add(path);
        }

        Map<String, List<String>> referencedBases = new LinkedHashMap<>();
        Map<String, List<String>> referencers = new HashMap<>();
        for (Map.Entry<String, String> entry : files.entrySet()) {
            String path = entry.getKey();
            String own = baseOf.get(path);
            Set<String> seen = new HashSet<>();
            for (String token : quotedTokens(entry.getValue())) {
                if (bases.contains(token) && !token.equals(own) && seen.add(token)) {
                    referencedBases.computeIfAbsent(path, k -> new ArrayList<>()).add(token);
                    referencers.computeIfAbsent(token, k -> new ArrayList<>()).add(path);
                }
            }
        }
        return new Graph(baseOf, pathsByBase, referencers, referencedBases);
    }

    public static String baseName(String path) {
        String name = Path.of(path).getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    static List<String> quotedTokens(String content) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = QUOTED.matcher(content);
        while (matcher.find()) {
            tokens.add(matcher.group(1));
        }
        return tokens;
    }

    Map<String, String> baseOf() {
        return baseOf;
    }

    Map<String, List<String>> pathsByBase() {
        return pathsByBase;
    }

    Map<String, List<String>> referencers() {
        return referencers;
    }

    Map<String, List<String>> referencedBases() {
        return referencedBases;
    }
}
