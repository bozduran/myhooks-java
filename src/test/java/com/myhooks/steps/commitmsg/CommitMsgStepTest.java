package com.myhooks.steps.commitmsg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myhooks.diffui.Choice;
import com.myhooks.discover.FileDiscovery;
import com.myhooks.step.Context;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommitMsgStepTest {

    @TempDir
    Path dir;

    @Test
    void checkSemanticAcceptsValidSubjects() {
        for (String valid : List.of(
                "feat: add commit message check",
                "fix(api): correct a typo",
                "chore!: breaking change",
                "feat(scope)!: subject",
                "docs: update readme",
                "refactor(core): split parser")) {
            assertNull(CommitMsgStep.checkSemantic(valid), valid);
        }
    }

    @Test
    void checkSemanticRejectsInvalidSubjects() {
        for (String invalid : List.of(
                "",
                "   ",
                "feat:add no space",
                "foo: unknown type",
                "feat:",
                "feat: ",
                "feat subject with no colon",
                "FEAT: uppercase type",
                "feat(scope):")) {
            assertTrue(CommitMsgStep.checkSemantic(invalid) != null, "expected error for: " + invalid);
        }
    }

    @Test
    void checkSemanticOnlyChecksTheSubject() {
        String msg = "feat: add check\n\nbody may contain a typo teh without failing the semantic check";
        assertNull(CommitMsgStep.checkSemantic(msg));
    }

    @Test
    void findTyposFlagsKnownMisspellings() {
        List<CommitMsgStep.Typo> typos = CommitMsgStep.findTypos("Teh report has an adress and is seperate");
        assertEquals(3, typos.size());
        Map<String, String> want = Map.of("teh", "the", "adress", "address", "seperate", "separate");
        for (CommitMsgStep.Typo typo : typos) {
            assertEquals(want.get(typo.original().toLowerCase()), typo.corrected().toLowerCase());
        }
    }

    @Test
    void correctMessagePreservesCase() {
        String out = CommitMsgStep.correctMessage("Teh teh TEH and an adress");
        assertTrue(out.contains("The"));
        assertTrue(out.contains("the"));
        assertTrue(out.contains("THE"));
        assertTrue(out.contains("address"));
    }

    @Test
    void technicalAndNonEnglishWordsAreNotFlagged() {
        assertEquals(List.of(), CommitMsgStep.findTypos("jsonql field expression für den Bericht über jasperreports"));
    }

    @Test
    void runSemanticBlockReturnsOneAndLeavesFile() throws Exception {
        Path file = write("add a check without a type\n");
        assertEquals(1, new CommitMsgStep().run(context(List.of()), List.of(file.toString())));
        assertEquals("add a check without a type\n", Files.readString(file));
    }

    @Test
    void runTypoCorrectReturnsZeroAndRewrites() throws Exception {
        Path file = write("feat: add check\n\nTeh adress is seperate\n");
        assertEquals(0, new CommitMsgStep().run(context(List.of(Choice.YES)), List.of(file.toString())));
        assertTrue(Files.readString(file).contains("The address is separate"));
    }

    @Test
    void runTypoNoBlocks() throws Exception {
        Path file = write("feat: add check\n\nTeh adress is seperate\n");
        assertEquals(1, new CommitMsgStep().run(context(List.of(Choice.NO)), List.of(file.toString())));
        assertEquals("feat: add check\n\nTeh adress is seperate\n", Files.readString(file));
    }

    @Test
    void runTypoSkipReturnsZero() throws Exception {
        Path file = write("feat: add check\n\nTeh adress is seperate\n");
        assertEquals(0, new CommitMsgStep().run(context(List.of(Choice.SKIP)), List.of(file.toString())));
        assertEquals("feat: add check\n\nTeh adress is seperate\n", Files.readString(file));
    }

    @Test
    void runCleanReturnsZero() throws Exception {
        Path file = write("fix: correct an issue\n");
        assertEquals(0, new CommitMsgStep().run(context(List.of()), List.of(file.toString())));
    }

    @Test
    void checkDoesNotModify() throws Exception {
        Path file = write("feat: add check\n\nTeh adress\n");
        assertEquals(0, new CommitMsgStep().check(context(List.of()), List.of(file.toString())));
        assertEquals("feat: add check\n\nTeh adress\n", Files.readString(file));
    }

    @Test
    void runHelpReturnsZero() {
        CommitMsgStep step = new CommitMsgStep();
        assertEquals(0, step.run(context(List.of()), List.of("-h")));
        assertEquals(0, step.run(context(List.of()), List.of("--help")));
    }

    private Path write(String content) throws Exception {
        Path file = dir.resolve("msg");
        Files.writeString(file, content);
        return file;
    }

    private static Context context(List<Choice> answers) {
        Iterator<Choice> it = answers.iterator();
        PrintStream out = new PrintStream(new ByteArrayOutputStream());
        return new Context(new FileDiscovery(), out, out, false, q -> it.hasNext() ? it.next() : Choice.NO);
    }
}
