// Package report implements the "report" step of the myhooks pre-commit hook.
//
// It is informational only: it does not modify anything and always returns 0,
// so it never stops the commit. It prints the include-chain of the staged
// (changed) .jrxml files, inverted so that the root of each tree is the
// top-most template that (transitively) includes the changed file, and the
// staged file is the leaf. A file "uses" another file when it contains the
// other file's base name as a double-quoted string: filename.jrxml is "used"
// by a file that contains "filename" (the usual Jaspersoft subreport
// reference).
//
// When one top template includes several staged files, they are grouped under
// that single template; when a staged file is included by several top
// templates, it appears under each of them. Staged files are colored
// green/bold; every other file is printed without color.
package report

import (
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strings"

	"myhooks/internal/jrxmlutil"
)

const (
	ansiStaged = "\x1b[1;32m" // green + bold
	ansiReset  = "\x1b[0m"
)

// quotedRe captures the contents of a double-quoted string ("...").
var quotedRe = regexp.MustCompile(`"([^"]*)"`)

// node is one file in a usage tree.
type node struct {
	path     string
	base     string
	children []*node
}

// Run implements the report step. It always returns 0: it only prints the
// usage trees for the staged files.
func Run(args []string) int {
	if len(args) > 0 && (args[0] == "-h" || args[0] == "--help") {
		fmt.Println("myhooks report: print the include-chain of staged .jrxml files.")
		fmt.Println("Usage: myhooks report [file.jrxml ...]")
		return 0
	}

	roots := jrxmlutil.StagedFiles(args)
	if len(roots) == 0 {
		fmt.Println("myhooks report: no staged .jrxml files to report on.")
		return 0
	}

	files := readTrackedFiles()
	// Make sure the roots are present even if they are not yet tracked
	// (e.g. a freshly staged file whose read was not returned by ls-files).
	for _, p := range roots {
		if _, ok := files[p]; !ok {
			if data, err := os.ReadFile(p); err == nil {
				files[p] = string(data)
			}
		}
	}

	staged := stagedJrxmlSet()

	sort.Strings(roots)
	for _, tree := range buildUsageTree(files, roots) {
		renderTree(os.Stdout, tree, staged)
		fmt.Println()
	}
	return 0
}

// readTrackedFiles returns every git-tracked .jrxml file (repo-relative path ->
// content). Files that cannot be read are skipped; a git error returns an empty
// map (the report step never fails the commit).
func readTrackedFiles() map[string]string {
	files := map[string]string{}
	out, err := exec.Command("git", "ls-files", "-z").Output()
	if err != nil {
		return files
	}
	for _, p := range strings.Split(string(out), "\x00") {
		if !strings.HasSuffix(strings.ToLower(p), ".jrxml") {
			continue
		}
		if data, err := os.ReadFile(p); err == nil {
			files[p] = string(data)
		}
	}
	return files
}

// stagedJrxmlSet returns the set of repo-relative .jrxml paths staged in the
// index (git diff --cached), used to decide which tree nodes get color.
func stagedJrxmlSet() map[string]bool {
	set := map[string]bool{}
	out, err := exec.Command("git", "diff", "--cached", "--name-only", "-z", "--diff-filter=ACMR").Output()
	if err != nil {
		return set
	}
	for _, p := range strings.Split(string(out), "\x00") {
		if strings.HasSuffix(strings.ToLower(p), ".jrxml") {
			set[p] = true
		}
	}
	return set
}

// reportBaseName returns the base name of a .jrxml path: the file name without
// its extension ("reports/sub.jrxml" -> "sub").
func reportBaseName(path string) string {
	base := filepath.Base(path)
	if ext := filepath.Ext(base); ext != "" {
		base = strings.TrimSuffix(base, ext)
	}
	return base
}

// buildUsageTree builds one inverted include-tree per top template. files maps
// every repo-relative .jrxml path to its content; roots are the staged
// (changed) files, which become the leaves of the resulting trees.
func buildUsageTree(files map[string]string, roots []string) []*node {
	baseOf := make(map[string]string, len(files))
	bases := make(map[string]bool, len(files))
	pathsByBase := make(map[string][]string, len(files))
	for p := range files {
		b := reportBaseName(p)
		baseOf[p] = b
		bases[b] = true
		pathsByBase[b] = append(pathsByBase[b], p)
	}

	// referencedBases[p] lists the base names p contains as a quoted string;
	// referencers[base] lists the files that reference that base.
	referencedBases := map[string][]string{}
	referencers := map[string][]string{}
	for p, content := range files {
		own := baseOf[p]
		seen := map[string]bool{}
		for _, tok := range quotedTokens(content) {
			if bases[tok] && tok != own && !seen[tok] {
				seen[tok] = true
				referencedBases[p] = append(referencedBases[p], tok)
				referencers[tok] = append(referencers[tok], p)
			}
		}
	}

	// affected is every file that transitively references a staged file (found
	// by walking upward from the staged files through referencers).
	affected := map[string]bool{}
	queue := append([]string(nil), roots...)
	for len(queue) > 0 {
		p := queue[0]
		queue = queue[1:]
		if affected[p] {
			continue
		}
		affected[p] = true
		for _, r := range referencers[baseOf[p]] {
			if !affected[r] {
				queue = append(queue, r)
			}
		}
	}

	// top templates are affected files that nothing references — the outermost
	// reports that ultimately include a staged file.
	var tops []string
	for p := range affected {
		if len(referencers[baseOf[p]]) == 0 {
			tops = append(tops, p)
		}
	}
	sort.Strings(tops)

	// Fall back to the staged files themselves when there is no top template
	// (only possible with a reference cycle).
	if len(tops) == 0 {
		tops = append(tops, roots...)
		sort.Strings(tops)
	}

	var trees []*node
	for _, top := range tops {
		trees = append(trees, buildDownward(top, baseOf, pathsByBase, referencedBases, affected, map[string]bool{}))
	}
	return trees
}

// buildDownward builds the include-tree below p (p includes its children). Only
// affected files are kept so branches that do not lead to a staged file are
// pruned. seen is the current ancestor chain (keyed by base name) so a
// reference cycle is cut, while still letting the same file appear under
// separate branches.
func buildDownward(p string, baseOf map[string]string, pathsByBase map[string][]string, referencedBases map[string][]string, affected map[string]bool, seen map[string]bool) *node {
	n := &node{path: p, base: baseOf[p]}
	seen[baseOf[p]] = true
	for _, base := range referencedBases[p] {
		if seen[base] {
			continue
		}
		for _, childPath := range pathsByBase[base] {
			if !affected[childPath] {
				continue
			}
			n.children = append(n.children, buildDownward(childPath, baseOf, pathsByBase, referencedBases, affected, seen))
		}
	}
	delete(seen, baseOf[p])
	sort.Slice(n.children, func(i, j int) bool { return n.children[i].path < n.children[j].path })
	return n
}

// quotedTokens returns the contents of every double-quoted string in s.
func quotedTokens(s string) []string {
	var toks []string
	for _, m := range quotedRe.FindAllStringSubmatch(s, -1) {
		toks = append(toks, m[1])
	}
	return toks
}

// renderTree writes one usage tree to w. Staged files are colored; others are
// printed plain.
func renderTree(w io.Writer, root *node, staged map[string]bool) {
	fmt.Fprintln(w, colorPath(root.path, staged[root.path]))
	if len(root.children) == 0 {
		fmt.Fprintln(w, "  (not referenced)")
		return
	}
	renderChildren(w, root, "", staged)
}

func renderChildren(w io.Writer, n *node, prefix string, staged map[string]bool) {
	for i, c := range n.children {
		last := i == len(n.children)-1
		branch := "├── "
		if last {
			branch = "└── "
		}
		fmt.Fprintf(w, "%s%s%s\n", prefix, branch, colorPath(c.path, staged[c.path]))
		childPrefix := prefix
		if last {
			childPrefix += "    "
		} else {
			childPrefix += "│   "
		}
		renderChildren(w, c, childPrefix, staged)
	}
}

// colorPath wraps a staged file path in ANSI color; unstaged paths are plain.
func colorPath(path string, staged bool) string {
	if staged {
		return ansiStaged + path + ansiReset
	}
	return path
}
