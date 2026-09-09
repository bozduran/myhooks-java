package com.myhooks.steps.lint;

import com.myhooks.xmlspan.Node;
import java.util.List;

/**
 * One static-analysis rule. Inspects a parsed JRXML document (plus the raw text
 * for span/content extraction) and returns the warnings it finds. Rules never
 * modify the file; {@link LintStep} reports their warnings and always exits 0.
 */
@FunctionalInterface
public interface Rule {

    List<Warning> check(Node root, String raw);
}
