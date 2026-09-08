package com.myhooks.step;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.myhooks.diffui.Choice;
import com.myhooks.discover.FileDiscovery;
import com.myhooks.edit.Edit;
import com.myhooks.git.GitStaged;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
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
