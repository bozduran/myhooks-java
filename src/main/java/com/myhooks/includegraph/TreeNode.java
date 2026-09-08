package com.myhooks.includegraph;

import java.util.List;

/** One file in an inverted include-tree. */
public record TreeNode(String path, List<TreeNode> children) {

    public TreeNode {
        children = List.copyOf(children);
    }
}
