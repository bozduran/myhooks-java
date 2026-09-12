package com.myhooks.step;

/**
 * Running counts of what a step decided and did. One instance covers a single
 * step's run and is then added to the run-wide tally held by {@link Context}.
 *
 * <ul>
 *   <li>{@code applied} — fixes that were applied;</li>
 *   <li>{@code skipped} — fixes declined plus whole files skipped;</li>
 *   <li>{@code stopped} — files written (commit stopped) or failed.</li>
 * </ul>
 */
public final class Tally {

    private int applied;
    private int skipped;
    private int stopped;

    public void applied(int count) {
        applied += count;
    }

    public void skipped(int count) {
        skipped += count;
    }

    public void stopped(int count) {
        stopped += count;
    }

    public int applied() {
        return applied;
    }

    public int skipped() {
        return skipped;
    }

    public int stopped() {
        return stopped;
    }

    /** Adds another tally's counts into this one. */
    public void add(Tally other) {
        applied += other.applied;
        skipped += other.skipped;
        stopped += other.stopped;
    }

    public boolean isEmpty() {
        return applied == 0 && skipped == 0 && stopped == 0;
    }

    /** A summary such as {@code "applied 4, skipped 2, 1 file stopped"}. */
    public String summary() {
        return "applied " + applied + ", skipped " + skipped + ", " + stopped
                + (stopped == 1 ? " file stopped" : " files stopped");
    }
}
