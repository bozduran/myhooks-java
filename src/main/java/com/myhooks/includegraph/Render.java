package com.myhooks.includegraph;

import java.util.List;
import java.util.Set;

/**
 * Renders an inverted include-tree with box-drawing branches, coloring staged
 * files green/bold when color is enabled.
 */
public final class Render {

    private static final String GREEN_BOLD = "\u001b[1;32m";
    private static final String RESET = "\u001b[0m";

    private Render() {
    }

    public static String render(TreeNode root, Set<String> staged, boolean color) {
        StringBuilder out = new StringBuilder();
        out.append(colorPath(root.path(), staged.contains(root.path()), color)).append('\n');
        if (root.children().isEmpty()) {
            out.append("  (not referenced)\n");
        } else {
            renderChildren(out, root, "", staged, color);
        }
        return out.toString();
    }

    private static void renderChildren(StringBuilder out, TreeNode node, String prefix,
            Set<String> staged, boolean color) {
        List<TreeNode> children = node.children();
        for (int i = 0; i < children.size(); i++) {
            TreeNode child = children.get(i);
            boolean last = i == children.size() - 1;
            String branch = last ? "└── " : "├── ";
            out.append(prefix).append(branch)
                    .append(colorPath(child.path(), staged.contains(child.path()), color))
                    .append('\n');
            renderChildren(out, child, prefix + (last ? "    " : "│   "), staged, color);
        }
    }

    private static String colorPath(String path, boolean staged, boolean color) {
        if (color && staged) {
            return GREEN_BOLD + path + RESET;
        }
        return path;
    }
}
