package com.myhooks.includegraph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Inverts the include graph: from the staged (leaf) files, walk upward through
 * referencers to find the top templates that transitively include them, then
 * build the downward tree (with cycle cut).
 */
public final class Invert {

    private Invert() {
    }

    /** Files that transitively reference any of {@code roots} (upward walk). */
    public static Set<String> affected(Graph graph, List<String> roots) {
        Set<String> affected = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            String path = queue.poll();
            if (!affected.add(path)) {
                continue;
            }
            for (String referencer : graph.referencers().getOrDefault(graph.baseOf().get(path), List.of())) {
                if (!affected.contains(referencer)) {
                    queue.add(referencer);
                }
            }
        }
        return affected;
    }

    public static List<String> topTemplates(Graph graph, List<String> roots) {
        Set<String> affected = affected(graph, roots);

        List<String> tops = new ArrayList<>();
        for (String path : affected) {
            if (graph.referencers().getOrDefault(graph.baseOf().get(path), List.of()).isEmpty()) {
                tops.add(path);
            }
        }
        Collections.sort(tops);
        if (tops.isEmpty()) {
            tops = new ArrayList<>(roots);
            Collections.sort(tops);
        }
        return tops;
    }

    public static TreeNode tree(Graph graph, String top, Set<String> affected) {
        return buildDownward(graph, top, affected, new HashSet<>());
    }

    private static TreeNode buildDownward(Graph graph, String path, Set<String> affected, Set<String> seen) {
        String base = graph.baseOf().get(path);
        seen.add(base);
        List<TreeNode> children = new ArrayList<>();
        for (String referencedBase : graph.referencedBases().getOrDefault(path, List.of())) {
            if (seen.contains(referencedBase)) {
                continue;
            }
            for (String childPath : graph.pathsByBase().getOrDefault(referencedBase, List.of())) {
                if (affected.contains(childPath)) {
                    children.add(buildDownward(graph, childPath, affected, seen));
                }
            }
        }
        seen.remove(base);
        children.sort(Comparator.comparing(TreeNode::path));
        return new TreeNode(path, children);
    }
}
