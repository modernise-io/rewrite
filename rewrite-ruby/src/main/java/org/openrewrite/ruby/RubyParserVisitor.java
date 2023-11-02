package org.openrewrite.ruby;

import org.jruby.ast.FCallNode;
import org.jruby.ast.Node;
import org.jruby.ast.RootNode;
import org.jruby.ast.StrNode;
import org.jruby.ast.visitor.AbstractNodeVisitor;
import org.openrewrite.FileAttributes;
import org.openrewrite.internal.EncodingDetectingInputStream;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.marker.OmitParentheses;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;
import org.openrewrite.ruby.tree.Ruby;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static java.util.Collections.emptyList;
import static org.openrewrite.Tree.randomId;
import static org.openrewrite.internal.StringUtils.indexOfNextNonWhitespace;
import static org.openrewrite.java.tree.Space.EMPTY;
import static org.openrewrite.java.tree.Space.format;

public class RubyParserVisitor extends AbstractNodeVisitor<J> {
    private final Path sourcePath;
    @Nullable
    private final FileAttributes fileAttributes;
    private final String source;
    private final Charset charset;
    private final boolean charsetBomMarked;

    private int cursor = 0;

    public RubyParserVisitor(Path sourcePath, @Nullable FileAttributes fileAttributes, EncodingDetectingInputStream source) {
        this.sourcePath = sourcePath;
        this.fileAttributes = fileAttributes;
        this.source = source.readFully();
        this.charset = source.getCharset();
        this.charsetBomMarked = source.isCharsetBomMarked();
    }

    @Override
    protected J defaultVisit(Node node) {
        throw new UnsupportedOperationException(String.format("Node type %s not yet implemented",
                node.getClass().getSimpleName()));
    }

    @Override
    public J visitFCallNode(FCallNode node) {
        String name = node.getName().asJavaString();
        Space prefix = sourceBefore(name);
        Space beforeArgs = whitespace();
        Markers firstArgMarkers;
        if (source.charAt(cursor) != '(') {
            firstArgMarkers = Markers.EMPTY.add(new OmitParentheses(randomId()));
        } else {
            firstArgMarkers = Markers.EMPTY;
            skip("(");
        }
        return new J.MethodInvocation(
                randomId(),
                prefix,
                Markers.EMPTY,
                null,
                null,
                new J.Identifier(randomId(), EMPTY, Markers.EMPTY, emptyList(), name, null, null),
                JContainer.<Expression>build(ListUtils.mapFirst(
                                convertAll(
                                        node.getArgsNode().childNodes(),
                                        n -> sourceBefore(","),
                                        n -> firstArgMarkers == Markers.EMPTY ?
                                                sourceBefore(")") : EMPTY
                                ),
                                arg -> arg.withElement(arg.getElement().withMarkers(firstArgMarkers))
                        ))
                        .withBefore(beforeArgs),
                null
        );
    }

    @Override
    public Ruby.CompilationUnit visitRootNode(RootNode node) {
        return new Ruby.CompilationUnit(
                randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                sourcePath,
                fileAttributes,
                charset,
                charsetBomMarked,
                null,
                node.getBodyNode().accept(this),
                Space.format(source.substring(cursor))
        );
    }

    @Override
    public J visitStrNode(StrNode node) {
        String value = new String(node.getValue().bytes(), StandardCharsets.UTF_8);
        J.Literal literal = new J.Literal(
                randomId(),
                sourceBefore("\""),
                Markers.EMPTY,
                sourceBefore(value),
                String.format("\"%s\"", value),
                null,
                JavaType.Primitive.String
        );
        skip("\"");
        return literal;
    }

    private <J2 extends J> List<J2> convertAll(List<? extends Node> trees) {
        List<J2> converted = new ArrayList<>(trees.size());
        for (Node tree : trees) {
            //noinspection unchecked
            converted.add((J2) tree.accept(this));
        }
        return converted;
    }

    @Nullable
    private <J2 extends J> JRightPadded<J2> convert(Node t, Function<Node, Space> suffix) {
        //noinspection unchecked
        J2 j = (J2) t.accept(this);
        return j == null ? null : new JRightPadded<>(j, suffix.apply(t), Markers.EMPTY);
    }

    private <J2 extends J> List<JRightPadded<J2>> convertAll(List<? extends Node> trees,
                                                             Function<Node, Space> innerSuffix,
                                                             Function<Node, Space> suffix) {
        if (trees.isEmpty()) {
            return emptyList();
        }
        List<JRightPadded<J2>> converted = new ArrayList<>(trees.size());
        for (int i = 0; i < trees.size(); i++) {
            converted.add(convert(trees.get(i), i == trees.size() - 1 ? suffix : innerSuffix));
        }
        return converted;
    }

    private Space sourceBefore(String untilDelim) {
        int delimIndex = positionOfNext(untilDelim);
        if (delimIndex < 0) {
            return EMPTY; // unable to find this delimiter
        }

        String prefix = source.substring(cursor, delimIndex);
        cursor += prefix.length() + untilDelim.length(); // advance past the delimiter
        return Space.format(prefix);
    }

    private <T> JRightPadded<T> padRight(T tree, Space right) {
        return new JRightPadded<>(tree, right, Markers.EMPTY);
    }

    private <T> JLeftPadded<T> padLeft(Space left, T tree) {
        return new JLeftPadded<>(left, tree, Markers.EMPTY);
    }

    private int positionOfNext(String untilDelim) {
        boolean inMultiLineComment = false;
        boolean inSingleLineComment = false;

        int delimIndex = cursor;
        for (; delimIndex < source.length() - untilDelim.length() + 1; delimIndex++) {
            if (inSingleLineComment) {
                if (source.charAt(delimIndex) == '\n') {
                    inSingleLineComment = false;
                }
            } else {
                if (source.length() - untilDelim.length() > delimIndex + 1) {
                    switch (source.substring(delimIndex, delimIndex + 2)) {
                        case "//":
                            inSingleLineComment = true;
                            delimIndex++;
                            break;
                        case "/*":
                            inMultiLineComment = true;
                            delimIndex++;
                            break;
                        case "*/":
                            inMultiLineComment = false;
                            delimIndex = delimIndex + 2;
                            break;
                    }
                }

                if (!inMultiLineComment && !inSingleLineComment) {
                    if (source.startsWith(untilDelim, delimIndex)) {
                        break; // found it!
                    }
                }
            }
        }

        return delimIndex > source.length() - untilDelim.length() ? -1 : delimIndex;
    }

    private Space whitespace() {
        String prefix = source.substring(cursor, indexOfNextNonWhitespace(cursor, source));
        cursor += prefix.length();
        return format(prefix);
    }

    private String skip(@Nullable String token) {
        if (token == null) {
            //noinspection ConstantConditions
            return null;
        }
        if (source.startsWith(token, cursor)) {
            cursor += token.length();
        }
        return token;
    }
}
