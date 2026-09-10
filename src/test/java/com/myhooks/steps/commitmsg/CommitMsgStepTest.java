package com.myhooks.steps.commitmsg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myhooks.diffui.Choice;
import com.myhooks.discover.FileDiscovery;
import com.myhooks.step.Context;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
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
    void findIssuesFlagsMisspellings() {
        List<CommitMsgStep.Issue> issues = CommitMsgStep.findIssues("teh adress is seperate").issues();
        assertEquals(3, issues.size());
        assertTrue(issues.stream().allMatch(CommitMsgStep.Issue::spelling));
        List<String> suggestions = issues.stream()
                .flatMap(issue -> issue.suggestions().stream())
                .toList();
        assertTrue(suggestions.contains("address"));
        assertTrue(suggestions.contains("separate"));
    }

    @Test
    void findIssuesFlagsGrammar() {
        List<CommitMsgStep.Issue> issues = CommitMsgStep.findIssues("He go to school every day").issues();
        CommitMsgStep.Issue grammar = issues.stream()
                .filter(issue -> !issue.spelling())
                .findFirst()
                .orElseThrow();
        assertTrue(grammar.suggestions().contains("goes"));
    }

    @Test
    void findIssuesTreatsTechnicalAndCommitWordsAsClean() {
        for (String clean : List.of(
                "feat: add commit message check",
                "fix: correct an issue",
                "docs: update readme",
                "jsonql field expression for the report",
                "This is a correct sentence.")) {
            assertEquals(List.of(), CommitMsgStep.findIssues(clean).issues(), clean);
        }
    }

    @Test
    void findIssuesReportsAFailingCheckerInsteadOfThrowing() {
        CommitMsgStep.SpellCheck result = CommitMsgStep.findIssues("anything", message -> {
            throw new IOException("dictionary missing");
        });

        assertEquals(List.of(), result.issues());
        assertEquals("dictionary missing", result.failure());
    }

    @Test
    void unavailableSpellCheckerIsReportedAndStillReturnsZero() throws Exception {
        Path file = write("feat: add check\n");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(new ByteArrayOutputStream());
        Context context = new Context(new FileDiscovery(), out, new PrintStream(err), false, q -> Choice.NO);
        CommitMsgStep step = new CommitMsgStep(message -> {
            throw new IOException("dictionary missing");
        });

        assertEquals(0, step.run(context, List.of(file.toString())));
        assertTrue(err.toString().contains("spell check unavailable"), err.toString());
    }

    @Test
    void correctMessageAppliesSpellingSuggestions() {
        String message = "teh adress is seperate";
        assertEquals("the address is separate",
                CommitMsgStep.correctMessage(message, CommitMsgStep.findIssues(message).issues()));
    }

    @Test
    void correctMessagePreservesTitleCaseAndLeavesGrammarUntouched() {
        String message = "Teh report and an adress";
        String corrected = CommitMsgStep.correctMessage(message, CommitMsgStep.findIssues(message).issues());
        assertTrue(corrected.contains("The report"));
        assertTrue(corrected.contains("address"));
    }

    @Test
    void runSemanticBlockReturnsOneAndLeavesFile() throws Exception {
        Path file = write("add a check without a type\n");
        assertEquals(1, new CommitMsgStep().run(context(List.of()), List.of(file.toString())));
        assertEquals("add a check without a type\n", Files.readString(file));
    }

    @Test
    void runSemanticBlockPrecedesSpellingAndLeavesFile() throws Exception {
        Path file = write("add a check without a type\n\nTeh adress is seperate\n");
        assertEquals(1, new CommitMsgStep().run(context(List.of()), List.of(file.toString())));
        assertEquals("add a check without a type\n\nTeh adress is seperate\n", Files.readString(file));
    }

    @Test
    void runSpellingYesReturnsZeroAndRewrites() throws Exception {
        Path file = write("feat: add check\n\nTeh adress is seperate\n");
        assertEquals(0, new CommitMsgStep().run(context(List.of(Choice.YES)), List.of(file.toString())));
        assertTrue(Files.readString(file).contains("The address is separate"));
    }

    @Test
    void runSpellingNoProceedsAndLeavesFile() throws Exception {
        Path file = write("feat: add check\n\nTeh adress is seperate\n");
        assertEquals(0, new CommitMsgStep().run(context(List.of(Choice.NO)), List.of(file.toString())));
        assertEquals("feat: add check\n\nTeh adress is seperate\n", Files.readString(file));
    }

    @Test
    void runSpellingSkipProceeds() throws Exception {
        Path file = write("feat: add check\n\nTeh adress is seperate\n");
        assertEquals(0, new CommitMsgStep().run(context(List.of(Choice.SKIP)), List.of(file.toString())));
        assertEquals("feat: add check\n\nTeh adress is seperate\n", Files.readString(file));
    }

    @Test
    void runGrammarNeverBlocks() throws Exception {
        Path file = write("feat: add check\n\nHe go to school every day\n");
        assertEquals(0, new CommitMsgStep().run(context(List.of(Choice.NO)), List.of(file.toString())));
        assertEquals("feat: add check\n\nHe go to school every day\n", Files.readString(file));
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
