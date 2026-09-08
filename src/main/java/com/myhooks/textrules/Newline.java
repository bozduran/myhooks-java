package com.myhooks.textrules;

/**
 * Normalizes the newline representation inside rendered text to the element's
 * {@code markup} attribute:
 *
 * <table>
 *   <tr><th>markup</th><th>token</th></tr>
 *   <tr><td>(none) / {@code none}</td><td>{@code \n}</td></tr>
 *   <tr><td>{@code styled}</td><td>{@code <br/>}</td></tr>
 *   <tr><td>{@code html}</td><td>{@code <br>}</td></tr>
 *   <tr><td>any other value</td><td>left unchanged</td></tr>
 * </table>
 *
 * Existing {@code <br/>}, {@code <br>} and literal {@code \n} sequences are all
 * replaced with the target token.
 */
public final class Newline {

    private Newline() {
    }

    public static String apply(String text, String markup) {
        String token;
        switch (markup == null ? "" : markup) {
            case "styled":
                token = "<br/>";
                break;
            case "html":
                token = "<br>";
                break;
            case "", "none":
                token = "\\n";
                break;
            default:
                return text;
        }
        return text
                .replace("<br/>", token)
                .replace("<br>", token)
                .replace("\\n", token);
    }
}
