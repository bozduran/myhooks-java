package com.myhooks.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myhooks.diffui.Choice;
import com.myhooks.diffui.NoTerminalException;
import com.myhooks.discover.FileDiscovery;
import com.myhooks.edit.Edit;
import com.myhooks.git.GitStaged;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineTest {

    @TempDir
    Path dir;

    @Test
    void cleanFileReturnsZero() throws Exception {
        Path file = write("test.jrxml", "abc");
        Discoverer noFixes = (ctx, path) -> List.of();
        Engine engine = new Engine(noFixes, context(new FileDiscovery(), List.of()));

        assertEquals(0, engine.run(List.of(file.toString())));
        assertEquals("abc", Files.readString(file));
    }

    @Test
    void approvedFixAppliesAndReturnsOne() throws Exception {
        Path file = write("test.jrxml", "abc");
        Fix fix = new EditFix("replace a with A", "a", "A", new Edit(0, 1, "A"), false);
        Engine engine = new Engine(oneGroup(fix), context(new FileDiscovery(), List.of(Choice.YES)));

        assertEquals(1, engine.run(List.of(file.toString())));
        assertEquals("Abc", Files.readString(file));
    }

    @Test
    void skipFileReturnsZeroAndDoesNotWrite() throws Exception {
        Path file = write("test.jrxml", "abc");
        Fix fix = new EditFix("replace a with A", "a", "A", new Edit(0, 1, "A"), false);
        Engine engine = new Engine(oneGroup(fix), context(new FileDiscovery(), List.of(Choice.SKIP)));

        assertEquals(0, engine.run(List.of(file.toString())));
        assertEquals("abc", Files.readString(file));
    }

    @Test
    void missingTerminalBlocksInsteadOfSilentlyDeclining() throws Exception {
        Path file = write("test.jrxml", "abc");
        Fix fix = new EditFix("replace a with A", "a", "A", new Edit(0, 1, "A"), false);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(new ByteArrayOutputStream());
        Context context = new Context(new FileDiscovery(), out, new PrintStream(err), false,
                q -> {
                    throw new NoTerminalException(q);
                });
        Engine engine = new Engine(oneGroup(fix), context);

        assertEquals(1, engine.run(List.of(file.toString())));
        assertEquals("abc", Files.readString(file), "the file must be left untouched");
        assertTrue(err.toString().contains("no interactive terminal"),
                "the failure must be reported on stderr: " + err);
    }

    @Test
    void symlinkedFileIsWrittenThroughToItsTarget() throws Exception {
        Path target = write("target.jrxml", "abcd");
        Path link = dir.resolve("link.jrxml");
        try {
            Files.createSymbolicLink(link, target.getFileName());
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assumptions.assumeTrue(false, "symbolic links unavailable: " + e);
            return;
        }
        Fix fix = new EditFix("replace a with A", "a", "A", new Edit(0, 1, "A"), false);
        Engine engine = new Engine(oneGroup(fix), context(new FileDiscovery(), List.of(Choice.YES)));

        assertEquals(1, engine.run(List.of(link.toString())));

        assertTrue(Files.isSymbolicLink(link), "the symlink must not be replaced by a regular file");
        assertEquals("Abcd", Files.readString(target), "the link target must receive the edit");
        assertEquals("Abcd", Files.readString(link));
    }

    @Test
    void fileChangedDuringPromptIsNotOverwrittenWithStaleEdits() throws Exception {
        Path file = write("test.jrxml", "abcd");
        Fix fix = new EditFix("replace a with A", "a", "A", new Edit(0, 1, "A"), false);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(new ByteArrayOutputStream());
        Context context = new Context(new FileDiscovery(), out, new PrintStream(err), false, q -> {
            try {
                // Same length, so the stale edit offsets remain in range.
                Files.writeString(file, "wxyz");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return Choice.YES;
        });
        Engine engine = new Engine(oneGroup(fix), context);

        assertEquals(1, engine.run(List.of(file.toString())));
        assertEquals("wxyz", Files.readString(file), "the concurrent change must be preserved");
        assertTrue(err.toString().contains("changed"), "the stale snapshot must be reported: " + err);
    }

    @Test
    void conflictingEditsBlockInsteadOfCrashing() throws Exception {
        Path file = write("test.jrxml", "abcd");
        Fix first = new EditFix("replace ab", "ab", "AB", new Edit(0, 2, "AB"), false);
        Fix second = new EditFix("replace bc", "bc", "BC", new Edit(1, 3, "BC"), false);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(new ByteArrayOutputStream());
        Context context = new Context(new FileDiscovery(), out, new PrintStream(err), false, q -> Choice.YES);
        Discoverer twoFixes = (ctx, path) -> List.of(new Group("g", List.of(first, second)));
        Engine engine = new Engine(twoFixes, context);

        assertEquals(1, engine.run(List.of(file.toString())));
        assertEquals("abcd", Files.readString(file), "the file must be left untouched");
        assertTrue(err.toString().contains("conflicting"), "the conflict must be reported: " + err);
    }

    @Test
    void checkIsReportOnlyAndNeverWrites() throws Exception {
        Path file = write("test.jrxml", "abc");
        Fix fix = new EditFix("replace a with A", "a", "A", new Edit(0, 1, "A"), false);
        Engine engine = new Engine(oneGroup(fix), context(new FileDiscovery(), List.of()));

        assertEquals(0, engine.check(List.of(file.toString())));
        assertEquals("abc", Files.readString(file));
    }

    @Test
    void gitErrorReturnsOne() {
        FileDiscovery discovery = new FileDiscovery(new GitStaged(args -> {
            throw new IOException("boom");
        }));
        Engine engine = new Engine((ctx, path) -> List.of(), context(discovery, List.of()));

        assertEquals(1, engine.run(List.of()));
    }

    @Test
    void allAppliesRestOfCurrentGroupOnly() throws Exception {
        Path file = write("test.jrxml", "abcd");
        Fix fixA = new EditFix("a->A", "a", "A", new Edit(0, 1, "A"), false);
        Fix fixB = new EditFix("b->B", "b", "B", new Edit(1, 2, "B"), false);
        Fix fixC = new EditFix("c->C", "c", "C", new Edit(2, 3, "C"), false);
        Fix fixD = new EditFix("d->D", "d", "D", new Edit(3, 4, "D"), false);
        Discoverer discoverer = (ctx, path) -> List.of(
                new Group("g1", List.of(fixA, fixB)),
                new Group("g2", List.of(fixC, fixD)));
        // "All" on group 1 applies the rest of g1; then group 2's fixes are rejected.
        Engine engine = new Engine(discoverer,
                context(new FileDiscovery(), List.of(Choice.ALL, Choice.NO, Choice.NO)));

        assertEquals(1, engine.run(List.of(file.toString())));
        assertEquals("ABcd", Files.readString(file));
    }

    private static Discoverer oneGroup(Fix fix) {
        return (ctx, path) -> List.of(new Group("g1", List.of(fix)));
    }

    private Path write(String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    private static Context context(FileDiscovery discovery, List<Choice> answers) {
        Iterator<Choice> it = answers.iterator();
        PrintStream out = new PrintStream(new ByteArrayOutputStream());
        return new Context(discovery, out, out, false, q -> it.hasNext() ? it.next() : Choice.NO);
    }
}
