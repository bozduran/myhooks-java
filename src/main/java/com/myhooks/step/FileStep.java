package com.myhooks.step;

import java.util.List;

/**
 * Adapts a {@link Discoverer} into a {@link Step} via the shared {@link Engine}.
 * The five file-oriented steps (clear, format, sort, textcheck, validate) are
 * {@code FileStep}s; commitmsg and report implement {@link Step} directly.
 */
public final class FileStep implements Step {

    private final String name;
    private final String usage;
    private final Engine engine;

    public FileStep(String name, String usage, Discoverer discoverer, Context context) {
        this.name = name;
        this.usage = usage;
        this.engine = new Engine(discoverer, context);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String usage() {
        return usage;
    }

    @Override
    public int run(Context context, List<String> args) {
        return engine.run(args);
    }

    @Override
    public int check(Context context, List<String> args) {
        return engine.check(args);
    }
}
