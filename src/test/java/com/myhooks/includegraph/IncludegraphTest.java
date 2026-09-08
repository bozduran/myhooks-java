package com.myhooks.includegraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IncludegraphTest {

    @Test
    void graphMatchesBaseNames() {
        Graph graph = Graph.build(Map.of(
                "main.jrxml", "x \"sub\" y",
                "sub.jrxml", "",
                "other.jrxml", "\"main\""));
        assertEquals(Set.of("sub"), Set.copyOf(graph.referencedBases().get("main.jrxml")));
        assertEquals(Set.of("main"), Set.copyOf(graph.referencedBases().get("other.jrxml")));
        assertEquals("main", Graph.baseName("main.jrxml"));
    }

    @Test
    void topTemplatesAndTree() {
        Graph graph = Graph.build(Map.of(
                "main.jrxml", "\"sub\"",
                "sub.jrxml", "",
                "other.jrxml", ""));
        List<String> tops = Invert.topTemplates(graph, List.of("sub.jrxml"));
        assertEquals(List.of("main.jrxml"), tops);

        TreeNode tree = Invert.tree(graph, "main.jrxml", Invert.affected(graph, List.of("sub.jrxml")));
        assertEquals("main.jrxml", tree.path());
        assertEquals(List.of("sub.jrxml"), tree.children().stream().map(TreeNode::path).toList());
    }

    @Test
    void treeCutsCycles() {
        Graph graph = Graph.build(Map.of(
                "a.jrxml", "\"b\"",
                "b.jrxml", "\"a\""));
        // Both reference each other: no top template, fall back to the root.
        List<String> tops = Invert.topTemplates(graph, List.of("a.jrxml"));
        assertEquals(List.of("a.jrxml"), tops);

        TreeNode tree = Invert.tree(graph, "a.jrxml", Invert.affected(graph, List.of("a.jrxml")));
        assertEquals("a.jrxml", tree.path());
        assertEquals(1, tree.children().size());
        assertEquals("b.jrxml", tree.children().get(0).path());
        assertEquals(0, tree.children().get(0).children().size(), "cycle must be cut");
    }

    @Test
    void renderUsesBoxDrawingAndColor() {
        TreeNode root = new TreeNode("main.jrxml", List.of(
                new TreeNode("a.jrxml", List.of()),
                new TreeNode("b.jrxml", List.of())));
        String out = Render.render(root, Set.of("a.jrxml"), true);
        assertTrue(out.contains("\u251c\u2500\u2500 "), out); // ├──
        assertTrue(out.contains("\u2514\u2500\u2500 "), out); // └──
        assertTrue(out.contains("\u001b[1;32ma.jrxml\u001b[0m"), out);

        String plain = Render.render(root, Set.of("a.jrxml"), false);
        assertTrue(!plain.contains("\u001b["), plain);
    }
}
