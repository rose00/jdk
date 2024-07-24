/*
 * Copyright (c) 2003, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package java.util.regex;

import java.util.Map;
import java.util.Objects;

/**
 * The result of a match operation.
 *
 * <p>This interface contains query methods used to determine the
 * results of a match against a regular expression. The match boundaries,
 * groups and group boundaries can be seen but not modified through
 * a {@code MatchResult}.
 *
 * @implNote
 * Support for named groups is implemented by the default methods
 * {@link #start(String)}, {@link #end(String)}, {@link #group(String)},
 * and {@link #replacement}.
 * They all make use of the map returned by {@link #namedGroups()}, whose
 * default implementation simply throws {@link UnsupportedOperationException}.
 * It is thus sufficient to override {@link #namedGroups()} for these methods
 * to work. However, overriding them directly might be preferable for
 * performance or other reasons.
 *
 * @author  Michael McCloskey
 * @see Matcher
 * @since 1.5
 */
public interface MatchResult {

    /**
     * Returns the start index of the match.
     *
     * <p> For {@link #isNegative a negative match}, as produced by
     * some method that can return a stream including unmatched spans
     * of text, the returned index is the start of the unmatched span.
     *
     * @return  The index of the first character matched
     *
     * @throws  IllegalStateException
     *          If no match has yet been attempted,
     *          or if the previous match operation failed,
     *          and this match is not {@link #isNegative a negative match}
     */
    int start();

    /**
     * Returns the start index of the subsequence captured by the given group
     * during this match.
     *
     * <p> <a href="Pattern.html#cg">Capturing groups</a> are indexed from left
     * to right, starting at one.  Group zero denotes the entire pattern, so
     * the expression <i>m.</i>{@code start(0)} is equivalent to
     * <i>m.</i>{@code start()}.  </p>
     *
     * <p> For {@link #isNegative a negative match}, as produced by
     * some method that can return a stream including unmatched spans
     * of text, any group other than group zero returns {@code -1}.
     *
     * @param  group
     *         The index of a capturing group in this matcher's pattern
     *
     * @return  The index of the first character captured by the group,
     *          or {@code -1} if the match was successful but the group
     *          itself did not match anything
     *
     * @throws  IllegalStateException
     *          If no match has yet been attempted,
     *          or if the previous match operation failed,
     *          and this match is not {@link #isNegative a negative match}
     *
     * @throws  IndexOutOfBoundsException
     *          If there is no capturing group in the pattern
     *          with the given index
     */
    int start(int group);

    /**
     * Returns the start index of the subsequence captured by the given
     * <a href="Pattern.html#groupname">named-capturing group</a> during the
     * previous match operation.
     *
     * @param  name
     *         The name of a named-capturing group in this matcher's pattern
     *
     * @return  The index of the first character captured by the group,
     *          or {@code -1} if the match was successful but the group
     *          itself did not match anything
     *
     * @throws  IllegalStateException
     *          If no match has yet been attempted,
     *          or if the previous match operation failed,
     *          and this match is not {@link #isNegative a negative match}
     *
     * @throws  IllegalArgumentException
     *          If there is no capturing group in the pattern
     *          with the given name
     *
     * @implSpec
     * The default implementation of this method invokes {@link #namedGroups()}
     * to obtain the group number from the {@code name} argument, and uses it
     * as argument to an invocation of {@link #start(int)}.
     *
     * @since 20
     */
    default int start(String name) {
        return start(groupNumber(name));
    }

    /**
     * Returns the offset after the last character matched.
     *
     * <p> For {@link #isNegative a negative match}, as produced by
     * some method that can return a stream including unmatched spans
     * of text, the returned index is the end of the unmatched span.
     *
     * @return  The offset after the last character matched
     *
     * @throws  IllegalStateException
     *          If no match has yet been attempted,
     *          or if the previous match operation failed,
     *          and this match is not {@link #isNegative a negative match}
     */
    int end();

    /**
     * Returns the offset after the last character of the subsequence
     * captured by the given group during this match.
     *
     * <p> <a href="Pattern.html#cg">Capturing groups</a> are indexed from left
     * to right, starting at one.  Group zero denotes the entire pattern, so
     * the expression <i>m.</i>{@code end(0)} is equivalent to
     * <i>m.</i>{@code end()}.  </p>
     *
     * <p> For {@link #isNegative a negative match}, as produced by
     * some method that can return a stream including unmatched spans
     * of text, any group other than group zero returns {@code -1}.
     *
     * @param  group
     *         The index of a capturing group in this matcher's pattern
     *
     * @return  The offset after the last character captured by the group,
     *          or {@code -1} if the match was successful
     *          but the group itself did not match anything
     *
     * @throws  IllegalStateException
     *          If no match has yet been attempted,
     *          or if the previous match operation failed,
     *          and this match is not {@link #isNegative a negative match}
     *
     * @throws  IndexOutOfBoundsException
     *          If there is no capturing group in the pattern
     *          with the given index
     */
    int end(int group);

    /**
     * Returns the offset after the last character of the subsequence
     * captured by the given <a href="Pattern.html#groupname">named-capturing
     * group</a> during the previous match operation.
     *
     * @param  name
     *         The name of a named-capturing group in this matcher's pattern
     *
     * @return  The offset after the last character captured by the group,
     *          or {@code -1} if the match was successful
     *          but the group itself did not match anything
     *
     * @throws  IllegalStateException
     *          If no match has yet been attempted,
     *          or if the previous match operation failed,
     *          and this match is not {@link #isNegative a negative match}
     *
     * @throws  IllegalArgumentException
     *          If there is no capturing group in the pattern
     *          with the given name
     *
     * @implSpec
     * The default implementation of this method invokes {@link #namedGroups()}
     * to obtain the group number from the {@code name} argument, and uses it
     * as argument to an invocation of {@link #end(int)}.
     *
     * @since 20
     */
    default int end(String name) {
        return end(groupNumber(name));
    }

    /**
     * Returns the input subsequence matched by the previous match.
     *
     * <p> For a matcher <i>m</i> with input sequence <i>s</i>,
     * the expressions <i>m.</i>{@code group()} and
     * <i>s.</i>{@code substring(}<i>m.</i>{@code start(),}&nbsp;<i>m.</i>{@code end())}
     * are equivalent.  </p>
     *
     * <p> Note that some patterns, for example {@code a*}, match the empty
     * string.  This method will return the empty string when the pattern
     * successfully matches the empty string in the input.  </p>
     *
     * <p> For {@link #isNegative a negative match}, as produced by
     * some method that can return a stream including unmatched spans
     * of text, {@link #group()} returns some span of unmatched
     * characters.
     *
     * @return The (possibly empty) subsequence matched by the previous match,
     *         in string form
     *
     * @throws  IllegalStateException
     *          If no match has yet been attempted,
     *          or if the previous match operation failed,
     *          and this match is not {@link #isNegative a negative match}
     */
    String group();

    /**
     * Returns the input subsequence captured by the given group during the
     * previous match operation.
     *
     * <p> For a matcher <i>m</i>, input sequence <i>s</i>, and group index
     * <i>g</i>, the expressions <i>m.</i>{@code group(}<i>g</i>{@code )} and
     * <i>s.</i>{@code substring(}<i>m.</i>{@code start(}<i>g</i>{@code
     * ),}&nbsp;<i>m.</i>{@code end(}<i>g</i>{@code ))}
     * are equivalent.  </p>
     *
     * <p> <a href="Pattern.html#cg">Capturing groups</a> are indexed from left
     * to right, starting at one.  Group zero denotes the entire pattern, so
     * the expression {@code m.group(0)} is equivalent to {@code m.group()}.
     * </p>
     *
     * <p> If the match was successful but the group specified failed to match
     * any part of the input sequence, then {@code null} is returned. Note
     * that some groups, for example {@code (a*)}, match the empty string.
     * This method will return the empty string when such a group successfully
     * matches the empty string in the input.  </p>
     *
     * <p> For {@link #isNegative a negative match}, as produced by
     * some method that can return a stream including unmatched spans
     * of text, {@link group(int)}{@code (0)} returns some span of
     * unmatched characters, and requests for other groups return
     * {@code null}, since they are not matched.
     *
     * @param  group
     *         The index of a capturing group in this matcher's pattern
     *
     * @return  The (possibly empty) subsequence captured by the group
     *          during the previous match, or {@code null} if the group
     *          failed to match part of the input
     *
     * @throws  IllegalStateException
     *          If no match has yet been attempted,
     *          or if the previous match operation failed,
     *          and this match is not {@link #isNegative a negative match}
     *
     * @throws  IndexOutOfBoundsException
     *          If there is no capturing group in the pattern
     *          with the given index
     */
    String group(int group);

    /**
     * Returns the input subsequence captured by the given
     * <a href="Pattern.html#groupname">named-capturing group</a> during the
     * previous match operation.
     *
     * <p> If the match was successful but the group specified failed to match
     * any part of the input sequence, then {@code null} is returned. Note
     * that some groups, for example {@code (a*)}, match the empty string.
     * This method will return the empty string when such a group successfully
     * matches the empty string in the input.  </p>
     *
     * <p> For {@link #isNegative a negative match}, as produced by
     * some method that can return a stream including unmatched spans
     * of text, this method returns {@code null}.
     *
     * @param  name
     *         The name of a named-capturing group in this matcher's pattern
     *
     * @return  The (possibly empty) subsequence captured by the named group
     *          during the previous match, or {@code null} if the group
     *          failed to match part of the input
     *
     * @throws  IllegalStateException
     *          If no match has yet been attempted,
     *          or if the previous match operation failed,
     *          and this match is not {@link #isNegative a negative match}
     *
     * @throws  IllegalArgumentException
     *          If there is no capturing group in the pattern
     *          with the given name
     *
     * @implSpec
     * The default implementation of this method invokes {@link #namedGroups()}
     * to obtain the group number from the {@code name} argument, and uses it
     * as argument to an invocation of {@link #group(int)}.
     *
     * @since 20
     */
    default String group(String name) {
        return group(groupNumber(name));
    }

    /**
     * Returns the number of capturing groups in this match result's pattern.
     *
     * <p> Group zero denotes the entire pattern by convention. It is not
     * included in this count.
     *
     * <p> Any non-negative integer smaller than or equal to the value
     * returned by this method is guaranteed to be a valid group index for
     * this matcher.  </p>
     *
     * @return The number of capturing groups in this matcher's pattern
     */
    int groupCount();

    /**
     * Returns an unmodifiable map from capturing group names to group numbers.
     * If there are no named groups, returns an empty map.
     *
     * @return an unmodifiable map from capturing group names to group numbers
     *
     * @throws UnsupportedOperationException if the implementation does not
     *          support named groups.
     *
     * @implSpec The default implementation of this method always throws
     *          {@link UnsupportedOperationException}
     *
     * @apiNote
     * This method must be overridden by an implementation that supports
     * named groups.
     *
     * @since 20
     */
    default Map<String,Integer> namedGroups() {
        throw new UnsupportedOperationException("namedGroups()");
    }

    private int groupNumber(String name) {
        Objects.requireNonNull(name, "Group name");
        Integer number = namedGroups().get(name);
        if (number != null) {
            return number;
        }
        throw new IllegalArgumentException("No group with name <" + name + ">");
    }

    /**
     * Returns whether {@code this} contains a valid match from
     * a previous match or find operation.
     *
     * @return whether {@code this} contains a valid match
     *
     * @throws UnsupportedOperationException if the implementation cannot report
     *          whether it has a match
     *
     * @implSpec The default implementation of this method always throws
     *          {@link UnsupportedOperationException}
     *
     * @since 20
     */
    default boolean hasMatch() {
        throw new UnsupportedOperationException("hasMatch()");
    }

    /**
     * Returns whether {@code this} contains is a negative match,
     * which is a span of input characters which does <i>not</i>
     * contain a match.
     * <p>
     * Negative matches are used to mark spans between matches in
     * streams of match results which locate spans of input
     * <i>between</i> matches of some pattern.  Once such method is
     * {@link Matcher#splits(int,boolean) Matcher.splits}, which
     * produces a negative match results (non-delimiter text),
     * optionally mixed with regular match results (delimiters).
     *
     * <p> Because it corresponds to no actual match, a negative match
     * returns false for {@link #hasMatch hasMatch}.  Even so, the
     * programmer may call any query method on this match result, and
     * it will respond as if there had been a successful match over
     * some span of input characters but no subgroups had matched.
     * Thus the main group ({@code "$0"}) will appear to be the span
     * of unmatched characters (i.e., the negative match) and all
     * other groups ({@code "$1"}, etc.) will appear to be unmatched.
     *
     * <p> If a method can produce negative match results, its
     * documentation must reveal this fact clearly.  Absent such
     * documentation, programmers will be correct to ignore the
     * possibility of negative match results.  In the documentation of
     * such streams, regular match results may also be referred to as
     * <i>positive match</i> results.
     *
     * <!-- FIXME: Should we define a super MatchSpan :> MatchResult,
     * and hold back the APIs points for numbered groups? -->
     *
     * @return whether {@code this} is a negative match
     *
     * @implSpec The default implementation of this method always
     *          return {@code false}
     *
     * @see Matcher#resultsWithNegatives(int)
     * @see Matcher#splits(int,boolean)
     * @since NN
     */
    default boolean isNegative() {
        return false;
    }

    /**
     * Expand a replacement string for this match, interpolating
     * group references if they are present.
     *
     * <p> The replacement string may contain references to subsequences
     * captured during the previous match: Each occurrence of
     * <code>${</code><i>name</i><code>}</code> or {@code $}<i>g</i>
     * will be replaced by the result of evaluating the corresponding
     * {@link #group(String) group(name)} or {@link #group(int) group(g)}
     * respectively. For {@code $}<i>g</i>,
     * the first number after the {@code $} is always treated as part of
     * the group reference. Subsequent numbers are incorporated into g if
     * they would form a legal group reference. Only the numerals '0'
     * through '9' are considered as potential components of the group
     * reference. If the second group matched the string {@code "foo"}, for
     * example, then passing the replacement string {@code "$2bar"} would
     * cause {@code "foobar"} to be returned.  If the second group were
     * named {@code "baz"} then the replacement {@code "${baz}bar"}
     * would expand to "{@code "foobar"} as well.  The replacement
     * {$code "${2}0"} would expand to {@code "foo0"} even if there
     * were 20 groups available, while {@code "$20"} would return
     * the 20th group if available, or else {@code "foo0"}.
     * If a selected group failed to match any part of the input
     * sequence the empty string (not the string {@code "null"}) is
     * interpolated.
     *
     * <p> Note that backslashes ({@code \}) and dollar signs ({@code
     * $}) in the replacement string may cause the results to be
     * different than if it were being treated as a literal
     * replacement string. Dollar signs are treated as references to
     * captured subsequences as described above, and backslashes serve
     * to escape literal characters in the replacement string.  Use
     * the {@link Matcher#quoteReplacement Matcher.quoteReplacement}
     * method to insert any necessary preceding backslashes into a
     * literal string to force all of its backslashes and dollar signs
     * to be treated as literals.
     *
     * <p> For {@link #isNegative a negative match}, as produced by
     * some method that can return a stream including unmatched spans
     * of text, {@code "$0"} always expands to some span of unmatched
     * characters.  Other groups like {@code "$1"} expand to the empty
     * string, since indeed no subgroups were matched.
     *
     * @param  replacement
     *         The replacement string
     * @return  The expanded replacement string, with group references interpolated
     *
     * @throws  IllegalStateException
     *          If no match has yet been attempted,
     *          or if the previous match operation failed,
     *          and this match is not {@link #isNegative a negative match}
     *
     * @throws  IllegalArgumentException
     *          If the replacement string refers to a named-capturing
     *          group that does not exist in the pattern
     *
     * @throws  IndexOutOfBoundsException
     *          If the replacement string refers to a capturing group
     *          that does not exist in the pattern
     *
     * @see Matcher#quoteReplacement(String)
     * @since NN
     */
    default String replacement(String replacement) {
        return Matcher.expandReplacement(this, replacement);
    }

}
