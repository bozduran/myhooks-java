package com.myhooks.diffui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
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
}
