package com.myhooks.diffui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
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
    void readKeyParsesSingleKeys() {
        assertEquals("a", readKey("a"));
        assertEquals("enter", readKey("\r"));
        assertEquals("enter", readKey("\n"));
    }

    @Test
    void readKeyParsesArrowSequences() {
        assertEquals("right", readKey("\u001b[C"));
        assertEquals("left", readKey("\u001b[D"));
        assertEquals("up", readKey("\u001b[A"));
        assertEquals("down", readKey("\u001b[B"));
    }

    @Test
    void readKeyHandlesEscapeAndUnknownSequences() {
        assertEquals("esc", readKey("\u001b"));
        assertEquals("esc", readKey("\u001bX"));
        assertEquals("esc", readKey("\u001b[Z"));
        assertEquals("", readKey(""));
    }

    @Test
    void readKeyParsesWindowsScanCodes() {
        // Windows console without ENABLE_VIRTUAL_TERMINAL_INPUT delivers an
        // extended key as a 0x00/0xE0 prefix followed by a scan code.
        assertEquals("up", readKey("\u00e0\u0048"));
        assertEquals("down", readKey("\u00e0\u0050"));
        assertEquals("left", readKey("\u00e0\u004b"));
        assertEquals("right", readKey("\u00e0\u004d"));
        assertEquals("up", readKey("\u0000\u0048"));
        assertEquals("esc", readKey("\u00e0\u0041"));
    }

    @Test
    void readKeyTimesOutLoneEscape() {
        // A lone ESC: the follow-up read times out (-1) rather than returning '['.
        KeySource timed = new KeySource() {
            private int calls;

            @Override
            public int read() throws IOException {
                return calls++ == 0 ? 0x1b : -1;
            }

            @Override
            public int readTimed() throws IOException {
                return -1;
            }
        };
        assertEquals("esc", Prompt.readKey(timed));
    }

    @Test
    void readKeyTimesOutUnknownArrowTail() {
        // ESC [ then a timeout before the direction byte.
        KeySource timed = new KeySource() {
            private int readCalls;
            private int timedCalls;

            @Override
            public int read() throws IOException {
                return readCalls++ == 0 ? 0x1b : -1;
            }

            @Override
            public int readTimed() throws IOException {
                return timedCalls++ == 0 ? '[' : -1;
            }
        };
        assertEquals("esc", Prompt.readKey(timed));
    }

    @Test
    void readKeyParsesArrowSequenceWithTimedReads() {
        KeySource timed = new KeySource() {
            private int readCalls;
            private int timedCalls;
            private final int[] tail = {'[', 'C'};

            @Override
            public int read() throws IOException {
                return readCalls++ == 0 ? 0x1b : -1;
            }

            @Override
            public int readTimed() throws IOException {
                return timedCalls < tail.length ? tail[timedCalls++] : -1;
            }
        };
        assertEquals("right", Prompt.readKey(timed));
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

    /** A {@link KeySource} over a string where the timed read is the same blocking read. */
    private static KeySource keySource(String data) {
        return new KeySource() {
            private final Reader reader = new StringReader(data);

            @Override
            public int read() throws IOException {
                return reader.read();
            }

            @Override
            public int readTimed() throws IOException {
                return reader.read();
            }
        };
    }

    private static String readKey(String data) {
        return Prompt.readKey(keySource(data));
    }
}
