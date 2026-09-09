package com.myhooks.steps.lint;

/**
 * A single static-analysis warning: the byte offset where the problem starts
 * (into the same text passed to {@link Rule#check}) and a human-readable
 * message. {@link LintStep} maps the offset to a line number for output.
 */
public record Warning(int offset, String message) {
}
