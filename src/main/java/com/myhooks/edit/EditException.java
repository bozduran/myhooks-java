package com.myhooks.edit;

/**
 * Thrown when a set of edits cannot be applied to a document: an edit falls
 * outside the document's bounds, or two edits overlap or target the identical
 * span so their order is ambiguous.
 *
 * <p>Extends {@link IllegalStateException} so callers that already treat a bad
 * edit set as an illegal state keep working.
 */
public final class EditException extends IllegalStateException {

    public EditException(String message) {
        super(message);
    }
}
