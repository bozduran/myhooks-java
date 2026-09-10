package com.myhooks.steps.report;

import com.myhooks.git.GitException;
import com.myhooks.includegraph.Graph;
import com.myhooks.includegraph.Invert;
import com.myhooks.includegraph.Render;
import com.myhooks.includegraph.TreeNode;
import com.myhooks.io.XmlSource;
import com.myhooks.step.Context;
import com.myhooks.step.Step;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The informational include-chain report. It is cross-file (reads all tracked
 * {@code .jrxml} files), never modifies anything, and always returns 0. Staged
 * files are colored green/bold when color is enabled.
 */
public final class ReportStep implements Step {

    @Override
    public String name() {
        return "report";
    }

    @Override
    public String usage() {
        return "myhooks report [file.jrxml ...]";
    }

    @Override
    public int run(Context context, List<String> args) {
        return report(context, args);
    }

    @Override
    public int check(Context context, List<String> args) {
        return report(context, args);
    }

    private int report(Context context, List<String> args) {
        List<String> roots;
        try {
            roots = context.discovery().files(args);
        } catch (GitException e) {
            roots = List.of();
        }
        if (roots.isEmpty()) {
            context.out().println("no .jrxml files to report on.");
            return 0;
        }

        Map<String, String> files = new HashMap<>();
        try {
            for (String path : context.discovery().tracked()) {
                readInto(files, path);
            }
        } catch (GitException ignored) {
            // report never fails the commit
        }
        for (String root : roots) {
            if (!files.containsKey(root)) {
                readInto(files, root);
            }
        }

        Set<String> staged = new HashSet<>();
        try {
            staged.addAll(context.discovery().staged());
        } catch (GitException ignored) {
            // report never fails the commit
        }

        List<String> sortedRoots = new ArrayList<>(roots);
        Collections.sort(sortedRoots);

        Graph graph = Graph.build(files);
        Set<String> affected = Invert.affected(graph, sortedRoots);
        for (String top : Invert.topTemplates(graph, sortedRoots)) {
            TreeNode tree = Invert.tree(graph, top, affected);
            context.out().print(Render.render(tree, staged, context.color()));
            context.out().println();
        }
        return 0;
    }

    private static void readInto(Map<String, String> files, String path) {
        try {
            files.put(path, XmlSource.read(Path.of(path)).text());
        } catch (IOException ignored) {
            // unreadable files are skipped
        }
    }
}
