package com.myhooks.diffui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import org.jline.utils.NonBlocking;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.Test;

class PromptTest {

    private static BufferedReader reader(String s) {
        return new BufferedReader(new StringReader(s));
    }

    @Test
    void lineAnswers() {
        assertEquals(Choice.YES, Prompt.ask("q?", reader("y\n")));
        assertEquals(Choice.YES, Prompt.ask("q?", reader("Y\n")));
        assertEquals(Choice.YES, Prompt.ask("q?", reader("yes\n")));
        assertEquals(Choice.NO, Prompt.ask("q?", reader("n\n")));
        assertEquals(Choice.NO, Prompt.ask("q?", reader("no\n")));
        assertEquals(Choice.ALL, Prompt.ask("q?", reader("a\n")));
        assertEquals(Choice.ALL, Prompt.ask("q?", reader("all\n")));
        assertEquals(Choice.SKIP, Prompt.ask("q?", reader("s\n")));
        assertEquals(Choice.SKIP, Prompt.ask("q?", reader("skip\n")));
        assertEquals(Choice.NO, Prompt.ask("q?", reader("garbage\n")));
        assertEquals(Choice.NO, Prompt.ask("q?", reader("")));
    }

    @Test
    void partialLineWithoutNewline() {
        assertEquals(Choice.YES, Prompt.ask("q?", reader("y")));
    }

    @Test
    void sharedReaderAcrossPrompts() {
        BufferedReader in = reader("y\nn\n");
        assertEquals(Choice.YES, Prompt.ask("first?", in));
        assertEquals(Choice.NO, Prompt.ask("second?", in));
    }

    @Test
    void parseLineAnswer() {
        assertEquals(Choice.YES, Prompt.parseLineAnswer("y"));
        assertEquals(Choice.YES, Prompt.parseLineAnswer(" yes "));
        assertEquals(Choice.NO, Prompt.parseLineAnswer("n"));
        assertEquals(Choice.ALL, Prompt.parseLineAnswer("a"));
        assertEquals(Choice.SKIP, Prompt.parseLineAnswer("s"));
        assertEquals(Choice.NO, Prompt.parseLineAnswer("bogus"));
    }

    @Test
    void readKeyParsesSingleKeys() throws IOException {
        assertEquals("a", readKey("a"));
        assertEquals("enter", readKey("\r"));
        assertEquals("enter", readKey("\n"));
    }

    @Test
    void readKeyParsesArrowSequences() throws IOException {
        assertEquals("right", readKey("\u001b[C"));
        assertEquals("left", readKey("\u001b[D"));
        assertEquals("up", readKey("\u001b[A"));
        assertEquals("down", readKey("\u001b[B"));
    }

    @Test
    void readKeyHandlesEscapeAndUnknownSequences() throws IOException {
        assertEquals("esc", readKey("\u001b"));
        assertEquals("esc", readKey("\u001bX"));
        assertEquals("esc", readKey("\u001b[Z"));
        assertEquals("", readKey(""));
    }

    @Test
    void readKeyParsesArrowSequencesNonBlocking() throws IOException {
        assertEquals("right", readKeyRaw("\u001b[C"));
        assertEquals("left", readKeyRaw("\u001b[D"));
        assertEquals("up", readKeyRaw("\u001b[A"));
        assertEquals("down", readKeyRaw("\u001b[B"));
    }

    @Test
    void readKeyNonBlockingLoneEscapeAndUnknown() throws IOException {
        assertEquals("esc", readKeyRaw("\u001b"));
        assertEquals("esc", readKeyRaw("\u001bX"));
        assertEquals("esc", readKeyRaw("\u001b[Z"));
        assertEquals("", readKeyRaw(""));
    }

    @Test
    void renderPromptShowsOptionsAndInvertsSelection() {
        String out = Prompt.renderPrompt("Question?", 1);
        assertTrue(out.contains("Question?"));
        assertTrue(out.contains("Yes"));
        assertTrue(out.contains("No"));
        assertTrue(out.contains("All"));
        assertTrue(out.contains("Skip file"));
        assertTrue(out.contains("\u001b[7mNo\u001b[0m"));

        String first = Prompt.renderPrompt("Q?", 0);
        assertTrue(first.contains("\u001b[7mYes\u001b[0m"));
    }

    private static String readKey(String data) throws IOException {
        return Prompt.readKey(new StringReader(data));
    }

    private static String readKeyRaw(String data) throws IOException {
        NonBlockingReader reader = NonBlocking.nonBlocking(
                "test", new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        return Prompt.readKey(reader);
    }
}
