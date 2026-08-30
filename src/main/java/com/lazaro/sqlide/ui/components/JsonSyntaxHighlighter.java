package com.lazaro.sqlide.ui.components;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Regex tokeniser for JSON display in the inline viewer. */
public final class JsonSyntaxHighlighter {

    static final String KEY = "json-key";
    static final String STRING = "json-string";
    static final String NUMBER = "json-number";
    static final String LITERAL = "json-literal";
    static final String PUNCTUATION = "json-punctuation";

    private static final Pattern SYNTAX = Pattern.compile(
            "(?<KEY>\"(?:\\\\.|[^\"\\\\])*\"\\s*:)"
                    + "|(?<STRING>\"(?:\\\\.|[^\"\\\\])*\")"
                    + "|(?<NUMBER>-?\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b)"
                    + "|(?<LITERAL>\\b(?:true|false|null)\\b)"
                    + "|(?<PUNCTUATION>[{}\\[\\],:])");

    private JsonSyntaxHighlighter() {
    }

    public record Token(String styleClass, int start, int end) {
    }

    public static List<Token> tokenize(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        Matcher matcher = SYNTAX.matcher(text);
        List<Token> tokens = new ArrayList<>();
        while (matcher.find()) {
            tokens.add(new Token(styleClassOf(matcher), matcher.start(), matcher.end()));
        }
        return tokens;
    }

    public static StyleSpans<Collection<String>> computeHighlighting(String text) {
        StyleSpansBuilder<Collection<String>> spans = new StyleSpansBuilder<>();
        if (text == null || text.isEmpty()) {
            spans.add(Collections.emptyList(), 0);
            return spans.create();
        }
        List<Token> tokens = tokenize(text);
        int last = 0;
        for (Token token : tokens) {
            if (token.start() > last) {
                spans.add(Collections.emptyList(), token.start() - last);
            }
            spans.add(List.of(token.styleClass()), token.end() - token.start());
            last = token.end();
        }
        if (last < text.length()) {
            spans.add(Collections.emptyList(), text.length() - last);
        }
        return spans.create();
    }

    private static String styleClassOf(Matcher matcher) {
        if (matcher.group("KEY") != null) {
            return KEY;
        }
        if (matcher.group("STRING") != null) {
            return STRING;
        }
        if (matcher.group("NUMBER") != null) {
            return NUMBER;
        }
        if (matcher.group("LITERAL") != null) {
            return LITERAL;
        }
        return PUNCTUATION;
    }
}
