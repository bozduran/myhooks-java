package com.myhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myhooks.diffui.Choice;
import com.myhooks.discover.FileDiscovery;
import com.myhooks.step.Context;
import com.myhooks.step.Step;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MainTest {

    private static final List<String> PRE_COMMIT = List.of(
            "clear", "format", "sort", "textcheck", "validate", "report");

    @Test
    void dispatchesToStep() {
        RecordingStep clear = new RecordingStep("clear");
        Main main = new Main(Map.of("clear", clear), context(), Set.of());
        assertEquals(0, main.execute(new String[]{"clear"}));
        assertEquals(1, clear.runCalls);
    }

    @Test
    void unknownArgumentReturnsError() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Main main = new Main(Map.of(), context(err), Set.of());
        assertEquals(2, main.execute(new String[]{"foo"}));
        assertTrue(err.toString().contains("unknown argument"), err.toString());
    }

    @Test
    void envToggleRunsCheckInsteadOfRun() {
        RecordingStep clear = new RecordingStep("clear");
        Main main = new Main(Map.of("clear", clear), context(), Set.of("clear"));
        assertEquals(0, main.execute(new String[]{"clear"}));
        assertEquals(0, clear.runCalls);
        assertEquals(1, clear.checkCalls);
    }

    @Test
    void runsAllStepsInOrder() {
        List<String> order = new ArrayList<>();
        Map<String, Step> steps = new LinkedHashMap<>();
        for (String name : PRE_COMMIT) {
            steps.put(name, new RecordingStep(name, order));
        }
        Main main = new Main(steps, context(), Set.of());
        assertEquals(0, main.execute(new String[]{}));
        assertEquals(PRE_COMMIT, order);
    }

    @Test
    void parseDisabledSplitsAndTrims() {
        assertEquals(Set.of("format", "sort"), Main.parseDisabled("format,sort"));
        assertEquals(Set.of("format"), Main.parseDisabled(" format "));
        assertEquals(Set.of(), Main.parseDisabled(""));
        assertEquals(Set.of(), Main.parseDisabled(null));
    }

    private static Context context() {
        return context(new ByteArrayOutputStream());
    }

    private static Context context(ByteArrayOutputStream err) {
        PrintStream out = new PrintStream(new ByteArrayOutputStream());
        return new Context(new FileDiscovery(), out, new PrintStream(err), false, q -> Choice.NO);
    }

    static class RecordingStep implements Step {
        final String name;
        final List<String> order;
        int runCalls;
        int checkCalls;

        RecordingStep(String name) {
            this(name, new ArrayList<>());
        }

        RecordingStep(String name, List<String> order) {
            this.name = name;
            this.order = order;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String usage() {
            return "myhooks " + name;
        }

        @Override
        public int run(Context context, List<String> args) {
            runCalls++;
            order.add(name);
            return 0;
        }

        @Override
        public int check(Context context, List<String> args) {
            checkCalls++;
            order.add(name);
            return 0;
        }
    }
}
