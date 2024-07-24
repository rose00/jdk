/*
 * Copyright (c) 1999, 2023, Oracle and/or its affiliates. All rights reserved.
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * An engine that performs match operations on a {@linkplain
 * java.lang.CharSequence character sequence} by interpreting a {@link Pattern}.
 *
 * <p> A matcher is created from a pattern by invoking the pattern's {@link
 * Pattern#matcher matcher} method.  Once created, a matcher can be used to
 * perform three different kinds of match operations:
 *
 * <ul>
 *
 *   <li><p> The {@link #matches matches} method attempts to match the entire
 *   input sequence against the pattern.  </p></li>
 *
 *   <li><p> The {@link #lookingAt lookingAt} method attempts to match the
 *   input sequence, starting at the beginning, against the pattern.  </p></li>
 *
 *   <li><p> The {@link #find find} method scans the input sequence looking
 *   for the next subsequence that matches the pattern.  </p></li>
 *
 * </ul>
 *
 * <p> Each of these methods returns a boolean indicating success or failure.
 * More information about a successful match can be obtained by querying the
 * state of the matcher.
 *
 * <p> A matcher finds matches in a subset of its input called the
 * <i>region</i>. By default, the region contains all of the matcher's input.
 * The region can be modified via the {@link #region(int, int) region} method
 * and queried via the {@link #regionStart() regionStart} and {@link
 * #regionEnd() regionEnd} methods. The way that the region boundaries interact
 * with some pattern constructs can be changed. See {@link
 * #useAnchoringBounds(boolean) useAnchoringBounds} and {@link
 * #useTransparentBounds(boolean) useTransparentBounds} for more details.
 *
 * <p> This class also defines methods for replacing matched subsequences with
 * new strings whose contents can, if desired, be computed from the match
 * result.  The {@link #appendReplacement appendReplacement} and {@link
 * #appendTail appendTail} methods can be used in tandem in order to collect
 * the result into an existing string buffer or string builder. Alternatively,
 * the more convenient {@link #replaceAll replaceAll} method can be used to
 * create a string in which every matching subsequence in the input sequence
 * is replaced.  There is also a stream-based presentation of the
 * subsequences traversed by {@link #replaceAll replaceAll}, called
 * {@link resultsWithNegatives() resultsWithNegatives}, which can
 * perform the same functions as {@link #replaceAll replaceAll}, and
 * more.  To work with match replacements as separate strings,
 * isolated from the surrounding input text, use {@link
 * #replacement replacement} for the current match, or {@link
 * #replacements replacements} for a stream of replacements for a *
 * #series of successive matches.
 *
 * <p> The explicit state of a matcher includes the start and end indices of
 * the most recent successful match.  It also includes the start and end
 * indices of the input subsequence captured by each <a
 * href="Pattern.html#cg">capturing group</a> in the pattern as well as a total
 * count of such subsequences.  As a convenience, methods are also provided for
 * returning these captured subsequences in string form.
 *
 * <p> The explicit state of a matcher is initially undefined; attempting to
 * query any part of it before a successful match will cause an {@link
 * IllegalStateException} to be thrown.  The explicit state of a matcher is
 * recomputed by every match operation.
 *
 * <p> The implicit state of a matcher includes the input character sequence as
 * well as the <i>append position</i>, which is initially zero and is updated
 * by the {@link #appendReplacement appendReplacement} method.
 *
 * <p> A matcher may be reset explicitly by invoking its {@link #reset()}
 * method or, if a new input sequence is desired, its {@link
 * #reset(java.lang.CharSequence) reset(CharSequence)} method.  Resetting a
 * matcher discards its explicit state information and sets the append position
 * to zero.
 *
 * <p> Instances of this class are not safe for use by multiple concurrent
 * threads. </p>
 *
 *
 * @author      Mike McCloskey
 * @author      Mark Reinhold
 * @author      JSR-51 Expert Group
 * @since       1.4
 */

public final class Matcher implements MatchResult {

    /**
     * The Pattern object that created this Matcher.
     */
    Pattern parentPattern;

    /**
     * The storage used by groups. They may contain invalid values if
     * a group was skipped during the matching.
     */
    int[] groups;

    /**
     * The range within the sequence that is to be matched. Anchors
     * will match at these "hard" boundaries. Changing the region
     * changes these values.
     */
    int from, to;

    /**
     * Lookbehind uses this value to ensure that the subexpression
     * match ends at the point where the lookbehind was encountered.
     */
    int lookbehindTo;

    /**
     * The original string being matched.
     */
    CharSequence text;

    /**
     * Matcher state used by the last node. NOANCHOR is used when a
     * match does not have to consume all of the input. ENDANCHOR is
     * the mode used for matching all the input.
     */
    static final int ENDANCHOR = 1;
    static final int NOANCHOR = 0;
    int acceptMode = NOANCHOR;

    /**
     * The range of string that last matched the pattern. If the last
     * match failed then first is -1; last initially holds 0 then it
     * holds the index of the end of the last match (which is where the
     * next search starts).
     */
    int first = -1, last = 0;

    /**
     * The end index of what matched in the last match operation.
     * This is used only for the \G construct.  It is not necessarily
     * the true previous match end, because it is bumped forward
     * by one if the previous match had zero length.
     */
    int oldLast = -1;

    /**
     * This is the accurate end index of the match operation before
     * the current one, or the region beginning if there is no current
     * match, and/or no previous match.  It is used to locate the
     * negative match result before the current (positive) match
     * result.
     */
    int previousMatchLast = 0;

    /**
     * The index of the last position appended in a substitution.
     * Used only by appendReplacement and appendTail.
     */
    int lastAppendPosition = 0;

    /**
     * Storage used by nodes to tell what repetition they are on in
     * a pattern, and where groups begin. The nodes themselves are stateless,
     * so they rely on this field to hold state during a match.
     */
    int[] locals;

    /**
     * Storage used by top greedy Loop node to store a specific hash set to
     * keep the beginning index of the failed repetition match. The nodes
     * themselves are stateless, so they rely on this field to hold state
     * during a match.
     */
    IntHashSet[] localsPos;

    /**
     * Boolean indicating whether or not more input could change
     * the results of the last match.
     *
     * If hitEnd is true, and a match was found, then more input
     * might cause a different match to be found.
     * If hitEnd is true and a match was not found, then more
     * input could cause a match to be found.
     * If hitEnd is false and a match was found, then more input
     * will not change the match.
     * If hitEnd is false and a match was not found, then more
     * input will not cause a match to be found.
     */
    boolean hitEnd;

    /**
     * Boolean indicating whether or not more input could change
     * a positive match into a negative one.
     *
     * If requireEnd is true, and a match was found, then more
     * input could cause the match to be lost.
     * If requireEnd is false and a match was found, then more
     * input might change the match but the match won't be lost.
     * If a match was not found, then requireEnd has no meaning.
     */
    boolean requireEnd;

    /**
     * If transparentBounds is true then the boundaries of this
     * matcher's region are transparent to lookahead, lookbehind,
     * and boundary matching constructs that try to see beyond them.
     */
    boolean transparentBounds = false;

    /**
     * If anchoringBounds is true then the boundaries of this
     * matcher's region match anchors such as ^ and $.
     */
    boolean anchoringBounds = true;

    /**
     * Number of times this matcher's state has been modified
     */
    int modCount;

    private Map<String, Integer> namedGroups;

    /**
     * No default constructor.
     */
    Matcher() {
    }

    /**
     * All matchers have the state used by Pattern during a match.
     */
    Matcher(Pattern parent, CharSequence text) {
        this.parentPattern = parent;
        this.text = text;

        // Allocate state storage
        groups = new int[parent.capturingGroupCount * 2];
        locals = new int[parent.localCount];
        localsPos = new IntHashSet[parent.localTCNCount];

        // Put fields into initial states
        reset();
    }

    /**
     * Returns the pattern that is interpreted by this matcher.
     *
     * @return  The pattern for which this matcher was created
     */
    public Pattern pattern() {
        return parentPattern;
    }

    /**
     * Returns the match state of this matcher as a {@link MatchResult}.
     * The result is unaffected by subsequent operations performed upon this
     * matcher.
     *
     * @return  a {@code MatchResult} with the state of this matcher
     * @since 1.5
     */
    public MatchResult toMatchResult() {
        int minStart, maxEnd;
        String capturedText;
        if (hasMatch()) {
            long minMax = minMaxStartEnd();
            minStart = (int)(minMax >> 32);
            maxEnd = (int)minMax;
            capturedText = minStart == maxEnd ? "" : text.subSequence(minStart, maxEnd).toString();
        } else {
            minStart = -1;  // this signals "no match"
            capturedText = null;
        }
        return new ImmutableMatchResult(first, last, groupCount(),
                groups.clone(), capturedText,
                namedGroups(), minStart);
    }

    private long minMaxStartEnd() {
        // Get both min and max results in one simple pass over the array.
        int[] groups = this.groups;
        int min = groups[0];
        int max = groups[1];
        // You might think that min/max are always groups[0]/groups[1].
        // If that were true then every group would be a substring of group zero.
        // But that fails when a context match captures text outside the match proper.
        // Example: "football".replaceAll("(?<=(foo))...", "<$1/$0>") ==> "foo<foo/tba>ll"
        for (int i = groups.length; i > 2; ) {
            int end = groups[--i];
            if (max < end) {  // rarely taken branch
                max = end;
            }
            int start = groups[--i];
            if (min > start && start >= 0) {  // rarely taken branch
                min = start;
            }
        }
        return ((long)min << 32) + max;
    }

    /**
     * Returns a {@link MatchResult#isNegative negative match result}.
     * for this matcher, as a {@link MatchResult} which spans all
     * consecutive unmatched characters adjacent to the current match,
     * on the left if {@code rightSide} is {@code false}, on the
     * right otherwise.  If there is no current match, either because
     * there was never a match (since the last reset) or because the
     * last match attempt failed, then the parameter is ignored and
     * all consecutive unmatched characters up to the end of the
     * region are spanned.
     *
     * <p> The result is unaffected by subsequent operations performed
     * upon this matcher.
     *
     * <p> The beginning of the returned span of characters is the end
     * of the most recent successful match (since the last reset),
     * including the current match if and only if {@code rightSide}
     * is {@code true}.  The end of the returned span of characters is
     * the beginning of the current match, only if there is one and
     * only if {@code rightSide} is {@code false}.  Otherwise the
     * end of the returned span of characters is the end of the region.
     *
     * @param rightSide controls which side of the current match to
     *          locate a consecutive span of never-matched characters.
     *          If {@code true}, the span is after any current match;
     *          otherwise, it is before any current match.
     * @return  a negative {@code MatchResult} containing the most
     *          recent unmatched span of text
     * @since NN
     * @see #toNegativeMatchResult(int,int)
     * @see #resultsWithNegatives()
     * @see #splits
     */
    public MatchResult toNegativeMatchResult(boolean rightSide) {
        int start, end;
        if (!hasMatch()) {
            start = previousMatchLast;   // last successful match or else beginning of region
            end = to;                    // ... to end of region
        } else if (!rightSide) {
            start = previousMatchLast;   // previous match or else beginning of region
            end = first;                 // ... to beginning of this match
        } else {
            start = last;                // end of this match
            end = to;                    // ... to end of region
        }
        return toNegativeMatchResult(start, end);
    }

    /**
     * Returns a {@link MatchResult#isNegative negative match result}.
     * for this matcher, as a {@link MatchResult} which spans the
     * given indexes within the input sequence of the matcher.  The
     * result is unaffected by subsequent operations performed upon
     * this matcher.
     *
     * @param  start
     *         The index to start searching at (inclusive)
     * @param  end
     *         The index to end searching at (exclusive)
     * @throws  IndexOutOfBoundsException
     *          If start or end is less than zero, if
     *          start is greater than the length of the input sequence, if
     *          end is greater than the length of the input sequence, or if
     *          start is greater than end.
     * @return  a negative {@code MatchResult} with the given region
     * @since NN
     * @see #toNegativeMatchResult(boolean)
     * @see #resultsWithNegatives()
     * @see #splits
     */
    public MatchResult toNegativeMatchResult(int start, int end) {
        regionChecks(start, end);
        String capturedText = start == end ? "" : text.subSequence(start, end).toString();
        int minStart = -1;  // this signals "no positive match"
        int[] groups = null;  // this signals "a negative match"
        return new ImmutableMatchResult(start, end, groupCount(),
                groups, capturedText, namedGroups(), minStart);
    }

    private static class ImmutableMatchResult implements MatchResult {
        private final int first;
        private final int last;
        private final int groupCount;
        private final int[] groups;
        private final String text;
        private final Map<String, Integer> namedGroups;
        private final int minStart;

        ImmutableMatchResult(int first, int last, int groupCount,
                             int[] groups, String text,
                             Map<String, Integer> namedGroups, int minStart) {
            this.first = first;
            this.last = last;
            this.groupCount = groupCount;
            // Note: groupCount is necessary to store even if groups
            // is null, in order for edge cases to be consistent
            // across normal and negative matches.  But a negative
            // match never needs to inspect a group other than "$0".
            this.groups = groups;
            this.text = text;
            this.namedGroups = namedGroups;
            this.minStart = minStart;
        }

        @Override
        public int start() {
            checkMatch();
            return first;
        }

        @Override
        public int start(int group) {
            checkMatch();
            checkGroup(group);
            if (isNegative()) {
                return group == 0 ? first : -1;
            }
            return groups[group * 2];
        }

        @Override
        public int end() {
            checkMatch();
            return last;
        }

        @Override
        public int end(int group) {
            checkMatch();
            checkGroup(group);
            if (isNegative()) {
                return group == 0 ? last : -1;
            }
            return groups[group * 2 + 1];
        }

        @Override
        public int groupCount() {
            return groupCount;
        }

        @Override
        public String group() {
            checkMatch();
            return group(0);
        }

        @Override
        public String group(int group) {
            checkMatch();
            checkGroup(group);
            if (isNegative()) {
                return group == 0 ? text : null;
            }
            if ((groups[group * 2] == -1) || (groups[group * 2 + 1] == -1))
                return null;
            return text.substring(groups[group * 2] - minStart, groups[group * 2 + 1] - minStart);
        }

        @Override
        public Map<String, Integer> namedGroups() {
            return namedGroups;
        }

        @Override
        public boolean hasMatch() {
            return minStart >= 0;
        }

        @Override
        public boolean isNegative() {
            return groups == null;  // it never captures beyond "$0"
        }

        private void checkGroup(int group) {
            if (group < 0 || group > groupCount)
                throw new IndexOutOfBoundsException("No group " + group);
        }

        private void checkMatch() {
            if (!hasMatch() && !isNegative())
                throw new IllegalStateException("No match found");
        }

        @Override public String toString() {
            return matchResultToString(this);
        }
    }

    /**
     * Changes the {@code Pattern} that this {@code Matcher} uses to
     * find matches with.
     *
     * <p> This method causes this matcher to lose information
     * about the groups of the last match that occurred. The
     * matcher's position in the input is maintained and its
     * last append position is unaffected.</p>
     *
     * @param  newPattern
     *         The new pattern used by this matcher
     * @return  This matcher
     * @throws  IllegalArgumentException
     *          If newPattern is {@code null}
     * @since 1.5
     */
    public Matcher usePattern(Pattern newPattern) {
        if (newPattern == null)
            throw new IllegalArgumentException("Pattern cannot be null");
        parentPattern = newPattern;
        namedGroups = null;

        // Reallocate state storage
        groups = new int[newPattern.capturingGroupCount * 2];
        locals = new int[newPattern.localCount];
        for (int i = 0; i < groups.length; i++)
            groups[i] = -1;
        for (int i = 0; i < locals.length; i++)
            locals[i] = -1;
        localsPos = new IntHashSet[parentPattern.localTCNCount];
        modCount++;
        return this;
    }

    /**
     * Resets this matcher.
     *
     * <p> Resetting a matcher discards all of its explicit state information
     * and sets its append position to zero. The matcher's region is set to the
     * default region, which is its entire character sequence. The anchoring
     * and transparency of this matcher's region boundaries are unaffected.
     *
     * @return  This matcher
     */
    public Matcher reset() {
        first = -1;
        last = 0;
        oldLast = -1;
        previousMatchLast = 0;
        for(int i=0; i<groups.length; i++)
            groups[i] = -1;
        for(int i=0; i<locals.length; i++)
            locals[i] = -1;
        for (int i = 0; i < localsPos.length; i++) {
            if (localsPos[i] != null)
                localsPos[i].clear();
        }
        lastAppendPosition = 0;
        from = 0;
        to = getTextLength();
        modCount++;
        return this;
    }

    /**
     * Resets this matcher with a new input sequence.
     *
     * <p> Resetting a matcher discards all of its explicit state information
     * and sets its append position to zero.  The matcher's region is set to
     * the default region, which is its entire character sequence.  The
     * anchoring and transparency of this matcher's region boundaries are
     * unaffected.
     *
     * @param  input
     *         The new input character sequence
     *
     * @return  This matcher
     */
    public Matcher reset(CharSequence input) {
        text = input;
        return reset();
    }

    /**
     * Returns the start index of the previous match.
     *
     * @return  The index of the first character matched
     *
     * @throws  IllegalStateException
     *          If no match has yet been attempted,
     *          or if the previous match operation failed
     */
    public int start() {
        checkMatch();
        return first;
    }

    /**
     * Returns the start index of the subsequence captured by the given group
     * during the previous match operation.
     *
     * <p> <a href="Pattern.html#cg">Capturing groups</a> are indexed from left
     * to right, starting at one.  Group zero denotes the entire pattern, so
     * the expression <i>m.</i>{@code start(0)} is equivalent to
     * <i>m.</i>{@code start()}.  </p>
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
     *          or if the previous match operation failed
     *
     * @throws  IndexOutOfBoundsException
     *          If there is no capturing group in the pattern
     *          with the given index
     */
    public int start(int group) {
        checkMatch();
        checkGroup(group);
        return groups[group * 2];
    }

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
     *          or if the previous match operation failed
     *
     * @throws  IllegalArgumentException
     *          If there is no capturing group in the pattern
     *          with the given name
     * @since 1.8
     */
    public int start(String name) {
        return groups[getMatchedGroupIndex(name) * 2];
    }

    /**
     * Returns the offset after the last character matched.
     *
     * @return  The offset after the last character matched
     *
     * @throws  IllegalStateException
     *          If no match has yet been attempted,
     *          or if the previous match operation failed
     */
    public int end() {
        checkMatch();
        return last;
    }

    /**
     * Returns the offset after the last character of the subsequence
     * captured by the given group during the previous match operation.
     *
     * <p> <a href="Pattern.html#cg">Capturing groups</a> are indexed from left
     * to right, starting at one.  Group zero denotes the entire pattern, so
     * the expression <i>m.</i>{@code end(0)} is equivalent to
     * <i>m.</i>{@code end()}.  </p>
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
     *          or if the previous match operation failed
     *
     * @throws  IndexOutOfBoundsException
     *          If there is no capturing group in the pattern
     *          with the given index
     */
    public int end(int group) {
        checkMatch();
        checkGroup(group);
        return groups[group * 2 + 1];
    }

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
     *          or if the previous match operation failed
     *
     * @throws  IllegalArgumentException
     *          If there is no capturing group in the pattern
     *          with the given name
     * @since 1.8
     */
    public int end(String name) {
        return groups[getMatchedGroupIndex(name) * 2 + 1];
    }

    /**
     * Returns the input subsequence matched by the previous match.
     *
     * <p> For a matcher <i>m</i> with input sequence <i>s</i>,
     * the expressions <i>m.</i>{@code group()} and
     * <i>s.</i>{@code substring(}<i>m.</i>{@code start(),}&nbsp;<i>m.</i>
     * {@code end())} are equivalent.  </p>
     *
     * <p> Note that some patterns, for example {@code a*}, match the empty
     * string.  This method will return the empty string when the pattern
     * successfully matches the empty string in the input.  </p>
     *
     * @return The (possibly empty) subsequence matched by the previous match,
     *         in string form or {@code null} if a matcher with a previous
     *         match has changed its {@link java.util.regex.Pattern},
     *         but no new match has yet been attempted
     *
     * @throws  IllegalStateException
     *          If no match has yet been attempted,
     *          or if the previous match operation failed
     */
    public String group() {
        return group(0);
    }

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
     * @param  group
     *         The index of a capturing group in this matcher's pattern
     *
     * @return  The (possibly empty) subsequence captured by the group
     *          during the previous match, or {@code null} if the group
     *          failed to match part of the input or if the matcher's
     *          {@link java.util.regex.Pattern} has changed after a
     *          successful match, but a new match has not been attempted
     *
     * @throws  IllegalStateException
     *          If no match has yet been attempted,
     *          or if the previous match operation failed
     *
     * @throws  IndexOutOfBoundsException
     *          If there is no capturing group in the pattern
     *          with the given index
     */
    public String group(int group) {
        checkMatch();
        checkGroup(group);
        if ((groups[group*2] == -1) || (groups[group*2+1] == -1))
            return null;
        return getSubSequence(groups[group * 2], groups[group * 2 + 1]).toString();
    }

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
     * @param  name
     *         The name of a named-capturing group in this matcher's pattern
     *
     * @return  The (possibly empty) subsequence captured by the named group
     *          during the previous match, or {@code null} if the group
     *          failed to match part of the input
     *
     * @throws  IllegalStateException
     *          If no match has yet been attempted,
     *          or if the previous match operation failed
     *
     * @throws  IllegalArgumentException
     *          If there is no capturing group in the pattern
     *          with the given name
     * @since 1.7
     */
    public String group(String name) {
        int group = getMatchedGroupIndex(name);
        if ((groups[group*2] == -1) || (groups[group*2+1] == -1))
            return null;
        return getSubSequence(groups[group * 2], groups[group * 2 + 1]).toString();
    }

    /**
     * Returns the number of capturing groups in this matcher's pattern.
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
    public int groupCount() {
        return parentPattern.capturingGroupCount - 1;
    }

    /**
     * Attempts to match the entire region against the pattern.
     *
     * <p> If the match succeeds then more information can be obtained via the
     * {@code start}, {@code end}, and {@code group} methods.  </p>
     *
     * @return  {@code true} if, and only if, the entire region sequence
     *          matches this matcher's pattern
     */
    public boolean matches() {
        return match(from, ENDANCHOR);
    }

    /**
     * Attempts to find the next subsequence of the input sequence that matches
     * the pattern.
     *
     * <p> This method starts at the beginning of this matcher's region, or, if
     * a previous invocation of the method was successful and the matcher has
     * not since been reset, at the first character not matched by the previous
     * match.
     *
     * <p> To prevent repeated matches at the same position, if there
     * was a previous match and it was of length zero, this method
     * starts a the character position following the previous match.
     *
     * <p> If the match succeeds then more information can be obtained via the
     * {@code start}, {@code end}, and {@code group} methods.  </p>
     *
     * @return  {@code true} if, and only if, a subsequence of the input
     *          sequence matches this matcher's pattern
     */
    public boolean find() {
        int nextSearchIndex = last;
        previousMatchLast = nextSearchIndex;
        if (nextSearchIndex == first)
            nextSearchIndex++;

        // If next search starts before region, start it at region
        if (nextSearchIndex < from)
            nextSearchIndex = from;

        // If next search starts beyond region then it fails
        if (nextSearchIndex > to) {
            for (int i = 0; i < groups.length; i++)
                groups[i] = -1;
            return false;
        }
        return search(nextSearchIndex);
    }

    /**
     * Resets this matcher and then attempts to find the next subsequence of
     * the input sequence that matches the pattern, starting at the specified
     * index.
     *
     * <p> If the match succeeds then more information can be obtained via the
     * {@code start}, {@code end}, and {@code group} methods, and subsequent
     * invocations of the {@link #find()} method will start at the first
     * character not matched by this match.  </p>
     *
     * @param start the index to start searching for a match
     * @throws  IndexOutOfBoundsException
     *          If start is less than zero or if start is greater than the
     *          length of the input sequence.
     *
     * @return  {@code true} if, and only if, a subsequence of the input
     *          sequence starting at the given index matches this matcher's
     *          pattern
     */
    public boolean find(int start) {
        int limit = getTextLength();
        if ((start < 0) || (start > limit))
            throw new IndexOutOfBoundsException("Illegal start index");
        reset();
        return search(start);
    }

    /**
     * Attempts to match the input sequence, starting at the beginning of the
     * region, against the pattern.
     *
     * <p> Like the {@link #matches matches} method, this method always starts
     * at the beginning of the region; unlike that method, it does not
     * require that the entire region be matched.
     *
     * <p> If the match succeeds then more information can be obtained via the
     * {@code start}, {@code end}, and {@code group} methods.  </p>
     *
     * @return  {@code true} if, and only if, a prefix of the input
     *          sequence matches this matcher's pattern
     */
    public boolean lookingAt() {
        return match(from, NOANCHOR);
    }

    /**
     * Returns a literal replacement {@code String} for the specified
     * {@code String}.
     *
     * This method produces a {@code String} that will work
     * as a literal replacement {@code s} in the
     * {@code appendReplacement} method of the {@link Matcher} class,
     * and to any other method that performs replacement string expansion.
     * The {@code String} produced will match the sequence of characters
     * in {@code s} treated as a literal sequence. Backslashes ({@code \}) and
     * dollar signs ({@code $}) will be given no special meaning,
     * because they will be preceded by additional backslashes.
     *
     * <p> Applying the {@link #replacement} method to the result of
     * {@code quoteReplacement} will return the original string.
     *
     * @param  s The string to be literalized
     * @return  A literal string replacement
     * @see #appendReplacement
     * @see #replacement(String)
     * @see #replaceFirst
     * @see #replaceAll
     * @see #replacements
     * @since 1.5
     */
    public static String quoteReplacement(String s) {
        if ((s.indexOf('\\') == -1) && (s.indexOf('$') == -1))
            return s;
        StringBuilder sb = new StringBuilder();
        for (int i=0; i<s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '$') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Implements a non-terminal append-and-replace step.
     *
     * <p> This method performs the following actions: </p>
     *
     * <ol>
     *
     *   <li><p> It reads characters from the input sequence, starting at the
     *   append position, and appends them to the given string buffer.  It
     *   stops after reading the last character preceding the previous match,
     *   that is, the character at index {@link
     *   #start()}&nbsp;{@code -}&nbsp;{@code 1}.  </p></li>
     *
     *   <li><p> It appends the given replacement string to the string buffer,
     *   as if by a call to {@code sb.append(replacement(replacement))}.
     *   </p></li>
     *
     *   <li><p> It sets the append position of this matcher to the index of
     *   the last character matched, plus one, that is, to {@link #end()}.
     *   </p></li>
     *
     * </ol>
     * <p> This method is intended to be used in a loop together with the
     * {@link #appendTail(StringBuffer) appendTail} and {@link #find() find}
     * methods.  The following code, for example, writes {@code one dog two dogs
     * in the yard} to the standard-output stream: </p>
     *
     * <blockquote><pre>
     * Pattern p = Pattern.compile("cat");
     * Matcher m = p.matcher("one cat two cats in the yard");
     * StringBuffer sb = new StringBuffer();
     * while (m.find()) {
     *     m.appendReplacement(sb, "dog");
     * }
     * m.appendTail(sb);
     * System.out.println(sb.toString());</pre></blockquote>
     *
     * <p> See the documentation for {@link #replaceAll(String)} or
     * {@link #resultsWithNegatives(int)} for a second alternative,
     * using a stream instead of a loop.
     * 
     * @param  sb
     *         The target string buffer
     *
     * @param  replacement
     *         The replacement string
     *
     * @return  This matcher
     *
     * @throws  IllegalStateException
     *          If no match has yet been attempted,
     *          or if the previous match operation failed
     *
     * @throws  IllegalArgumentException
     *          If the replacement string refers to a named-capturing
     *          group that does not exist in the pattern
     *
     * @throws  IndexOutOfBoundsException
     *          If the replacement string refers to a capturing group
     *          that does not exist in the pattern
     *
     * @see #replacement(String)
     * @see #replacements(String,int)
     */
    public Matcher appendReplacement(StringBuffer sb, String replacement) {
        checkMatch();
        int curLen = sb.length();
        try {
            // Append the intervening text
            sb.append(text, lastAppendPosition, first);
            // Append the match substitution
            appendExpandedReplacement(sb, replacement);
        } catch (IllegalArgumentException e) {
            sb.setLength(curLen);
            throw e;
        }
        lastAppendPosition = last;
        modCount++;
        return this;
    }

    /**
     * Implements a non-terminal append-and-replace step.
     *
     * <p> This method performs the following actions: </p>
     *
     * <ol>
     *
     *   <li><p> It reads characters from the input sequence, starting at the
     *   append position, and appends them to the given string builder.  It
     *   stops after reading the last character preceding the previous match,
     *   that is, the character at index {@link
     *   #start()}&nbsp;{@code -}&nbsp;{@code 1}.  </p></li>
     *
     *   <li><p> It appends the given replacement string to the string builder
     *   as if by a call to {@code sb.append(replacement(replacement))}.
     *   </p></li>
     *
     *   <li><p> It sets the append position of this matcher to the index of
     *   the last character matched, plus one, that is, to {@link #end()}.
     *   </p></li>
     *
     * </ol>
     *
     * <p> This method is intended to be used in a loop together with the
     * {@link #appendTail(StringBuilder) appendTail} and
     * {@link #find() find} methods. The following code, for example, writes
     * {@code one dog two dogs in the yard} to the standard-output stream: </p>
     *
     * <blockquote><pre>
     * Pattern p = Pattern.compile("cat");
     * Matcher m = p.matcher("one cat two cats in the yard");
     * StringBuilder sb = new StringBuilder();
     * while (m.find()) {
     *     m.appendReplacement(sb, "dog");
     * }
     * m.appendTail(sb);
     * System.out.println(sb.toString());</pre></blockquote>
     *
     * <p> See the documentation for {@link #replaceAll(String)} or
     * {@link #resultsWithNegatives(int)} for a second alternative,
     * using a stream instead of a loop.
     * 
     * @param  sb
     *         The target string builder
     * @param  replacement
     *         The replacement string
     * @return  This matcher
     *
     * @throws  IllegalStateException
     *          If no match has yet been attempted,
     *          or if the previous match operation failed
     * @throws  IllegalArgumentException
     *          If the replacement string refers to a named-capturing
     *          group that does not exist in the pattern
     * @throws  IndexOutOfBoundsException
     *          If the replacement string refers to a capturing group
     *          that does not exist in the pattern
     * @see #replacement(String)
     * @see #replacements(String,int)
     * @since 9
     */
    public Matcher appendReplacement(StringBuilder sb, String replacement) {
        checkMatch();
        int curLen = sb.length();
        try {
            // Append the intervening text
            sb.append(text, lastAppendPosition, first);
            // Append the match substitution
            appendExpandedReplacement(sb, replacement);
        } catch (IllegalArgumentException e) {
            sb.setLength(curLen);
            throw e;
        }
        lastAppendPosition = last;
        modCount++;
        return this;
    }

    /**
     * Processes replacement string to replace group references with
     * groups.
     */
    private void appendExpandedReplacement(Appendable app, String replacement) {
        try {
            int cursor = 0;
            while (cursor < replacement.length()) {
                long ij = scanReplacement(replacement, cursor, this);
                int start = (int)(ij >> 32);
                cursor = (int)ij;  // low 32 bits is end of scanned token
                if (start >= 0) {
                    // Append one or more chars of literal replacement
                    app.append(replacement, start, cursor);
                } else {
                    // Append group
                    int refNum = ~start, s, e;
                    if ((s = start(refNum)) != -1 && (e = end(refNum)) != -1)
                        app.append(text, s, e);
                }
            }
        } catch (IOException e) {  // cannot happen on String[Buffer|Builder]
            throw new AssertionError(e.getMessage());
        }
    }

    /**
     * Parses \x or ${x} at cursor in replacement, if possible.
     * If not possible, skips forward to \ or $ or end, whichever is first.
     * Returns {@code ((long)i<<32)+j}, where {@code j} is the next cursor value.
     * If {@code i<0} then {@code -1-i} is a group number to interpolate.
     * Otherwise, {@code i} is the index of the first character in the
     * replacement string and {@code j-i} is the length of replacement
     * characters to interpolate.
     * <p>
     * As a boundary condition, return {@code ((long)len<<32)+len} if
     * cursor does not point at a char of the replacement string.  Otherwise,
     * it is always the case that {@code j > cursor} and there is either
     * a group to interpolate or else a non-empty replacment substring.
     */
    private static long scanReplacement(String replacement, int cursor, MatchResult mr) {
        int len = replacement.length();
        if (cursor < 0 || cursor >= len) {  // end of loop
            return ((long)len << 32) + len;
        }
        char nextChar = replacement.charAt(cursor);
        if (nextChar == '$') {
            // Skip past $
            cursor++;
            // Throw IAE if this "$" is the last character in replacement
            if (cursor == len)
                throw new IllegalArgumentException(
                        "Illegal group reference: group index is missing");
            nextChar = replacement.charAt(cursor);
            int refNum = -1;
            if (nextChar == '{') {
                cursor++;
                int begin = cursor;
                while (cursor < len) {
                    nextChar = replacement.charAt(cursor);
                    if (ASCII.isLower(nextChar) ||
                            ASCII.isUpper(nextChar) ||
                            ASCII.isDigit(nextChar)) {
                        cursor++;
                    } else {
                        break;
                    }
                }
                if (begin == cursor)
                    throw new IllegalArgumentException(
                            "named capturing group has 0 length name");
                if (nextChar != '}')
                    throw new IllegalArgumentException(
                            "named capturing group is missing trailing '}'");
                String gname = replacement.substring(begin, cursor);
                if (ASCII.isDigit(gname.charAt(0)))
                    throw new IllegalArgumentException(
                            "capturing group name {" + gname +
                                    "} starts with digit character");
                Integer number = mr.namedGroups().get(gname);
                if (number == null)
                    throw new IllegalArgumentException(
                            "No group with name {" + gname + "}");
                refNum = number;
                cursor++;
            } else {
                // The first number is always a group
                refNum = nextChar - '0';
                if ((refNum < 0) || (refNum > 9))
                    throw new IllegalArgumentException(
                            "Illegal group reference");
                cursor++;
                // Capture the largest legal group string
                boolean done = false;
                while (!done) {
                    if (cursor >= len) {
                        break;
                    }
                    int nextDigit = replacement.charAt(cursor) - '0';
                    if ((nextDigit < 0) || (nextDigit > 9)) { // not a number
                        break;
                    }
                    int newRefNum = (refNum * 10) + nextDigit;
                    if (mr.groupCount() < newRefNum) {
                        done = true;
                    } else {
                        refNum = newRefNum;
                        cursor++;
                    }
                }
            }
            return (((long)~refNum) << 32) + cursor;
        } else {
            int start = cursor;  // start of literal text to copy
            if (nextChar == '\\') {
                cursor++;  // skip \ in \xyz but return xyz
                if (cursor == len)
                    throw new IllegalArgumentException(
                            "character to be escaped is missing");
                start = cursor++;  // be sure to include literal \ or $
            }
            while (cursor < len) {
                nextChar = replacement.charAt(cursor);
                // skip to end of literal text
                if (nextChar == '\\' || nextChar == '$')  break;
                cursor++;
            }
            // Return i:j meaning r.substring(i,j).
            return ((long)start << 32) + cursor;
        }
    }

    /**
     * Implements a terminal append-and-replace step.
     *
     * <p> This method reads characters from the input sequence, starting at
     * the append position, and appends them to the given string buffer.  It is
     * intended to be invoked after one or more invocations of the {@link
     * #appendReplacement(StringBuffer, String) appendReplacement} method in
     * order to copy the remainder of the input sequence.  </p>
     *
     * @param  sb
     *         The target string buffer
     *
     * @return  The target string buffer
     */
    public StringBuffer appendTail(StringBuffer sb) {
        sb.append(text, lastAppendPosition, getTextLength());
        return sb;
    }

    /**
     * Implements a terminal append-and-replace step.
     *
     * <p> This method reads characters from the input sequence, starting at
     * the append position, and appends them to the given string builder.  It is
     * intended to be invoked after one or more invocations of the {@link
     * #appendReplacement(StringBuilder, String)
     * appendReplacement} method in order to copy the remainder of the input
     * sequence.  </p>
     *
     * @param  sb
     *         The target string builder
     *
     * @return  The target string builder
     *
     * @since 9
     */
    public StringBuilder appendTail(StringBuilder sb) {
        sb.append(text, lastAppendPosition, getTextLength());
        return sb;
    }

    /**
     * Replaces every subsequence of the input sequence that matches the
     * pattern with the given replacement string.
     *
     * <p> This method first resets this matcher.  It then scans the input
     * sequence looking for matches of the pattern.  Characters that are not
     * part of any match are appended directly to the result string; each match
     * is replaced in the result by the replacement string.  The replacement
     * string may contain references to captured subsequences as in the {@link
     * #appendReplacement appendReplacement} and {@link #replacement
     * replacement} methods.
     *
     * <p> Note that backslashes ({@code \}) and dollar signs ({@code $}) in
     * the replacement string may cause the results to be different than if it
     * were being treated as a literal replacement string. Dollar signs may be
     * treated as references to captured subsequences as described above, and
     * backslashes are used to escape literal characters in the replacement
     * string.
     *
     * <p> Given the regular expression {@code a*b}, the input
     * {@code "aabfooaabfooabfoob"}, and the replacement string
     * {@code "-"}, an invocation of this method on a matcher for that
     * expression would yield the string {@code "-foo-foo-foo-"}.
     *
     * <p> An invocation of this method on an argument <i>repl</i> has
     * the same behavior as this stream-based expression:
     *
     * <blockquote>
     * {@link #resultsWithNegatives()
     * resultsWithNegatives}{@code ()
     * }<br>&nbsp;&nbsp;&nbsp;&nbsp;{@code
     *     .map(mr -> mr.}{@link
     * MatchResult#replacement replacement}{@code (mr.isNegative() ?
     * "$0" : }<i>repl</i>{@code ))
     * }<br>&nbsp;&nbsp;&nbsp;&nbsp;{@code
     *     .collect(}{@link
     * java.util.stream.Collectors#joining()}{@code ())}
     * </blockquote>
     *
     * <p> Invoking this method changes this matcher's state.  If the matcher
     * is to be used in further matching operations then it should first be
     * reset.  </p>
     *
     * @param  replacement
     *         The replacement string
     *
     * @return  The string constructed by replacing each matching subsequence
     *          by the replacement string, substituting captured subsequences
     *          as needed
     *
     * @see #replaceFirst(String)
     * @see #replacement(String)
     * @see #replacements(String)
     */
    public String replaceAll(String replacement) {
        reset();
        boolean result = find();
        if (result) {
            StringBuilder sb = new StringBuilder();
            do {
                appendReplacement(sb, replacement);
                result = find();
            } while (result);
            appendTail(sb);
            return sb.toString();
        }
        return text.toString();
    }

    /**
     * Replaces every subsequence of the input sequence that matches the
     * pattern with the result of applying the given replacer function to the
     * match result of this matcher corresponding to that subsequence.
     * Exceptions thrown by the function are relayed to the caller.
     *
     * <p> This method first resets this matcher.  It then scans the input
     * sequence looking for matches of the pattern.  Characters that are not
     * part of any match are appended directly to the result string; each match
     * is replaced in the result by the applying the replacer function that
     * returns a replacement string.  Each replacement string may contain
     * references to captured subsequences as in the {@link #appendReplacement
     * appendReplacement} and {@link #replacement replacement} methods.
     *
     * <p> Note that backslashes ({@code \}) and dollar signs ({@code $}) in
     * a replacement string may cause the results to be different than if it
     * were being treated as a literal replacement string. Dollar signs may be
     * treated as references to captured subsequences as described above, and
     * backslashes are used to escape literal characters in the replacement
     * string.
     *
     * <p> Given the regular expression {@code dog}, the input
     * {@code "zzzdogzzzdogzzz"}, and the function
     * {@code mr -> mr.group().toUpperCase()}, an invocation of this method on
     * a matcher for that expression would yield the string
     * {@code "zzzDOGzzzDOGzzz"}.
     *
     * <p> Invoking this method changes this matcher's state.  If the matcher
     * is to be used in further matching operations then it should first be
     * reset.  </p>
     *
     * <p> The replacer function should not modify this matcher's state during
     * replacement.  This method will, on a best-effort basis, throw a
     * {@link java.util.ConcurrentModificationException} if such modification is
     * detected.
     *
     * <p> The state of each match result passed to the replacer function is
     * guaranteed to be constant only for the duration of the replacer function
     * call and only if the replacer function does not modify this matcher's
     * state.
     *
     * @implNote
     * This implementation applies the replacer function to this matcher, which
     * is an instance of {@code MatchResult}.
     *
     * @param  replacer
     *         The function to be applied to the match result of this matcher
     *         that returns a replacement string.
     * @return  The string constructed by replacing each matching subsequence
     *          with the result of applying the replacer function to that
     *          matched subsequence, substituting captured subsequences as
     *          needed.
     * @throws NullPointerException if the replacer function is null
     * @throws ConcurrentModificationException if it is detected, on a
     *         best-effort basis, that the replacer function modified this
     *         matcher's state
     *
     * @see #replacement(String)
     * @see #replacements(String)
     *
     * @since 9
     */
    public String replaceAll(Function<MatchResult, String> replacer) {
        Objects.requireNonNull(replacer);
        reset();
        boolean result = find();
        if (result) {
            StringBuilder sb = new StringBuilder();
            do {
                int ec = modCount;
                String replacement =  replacer.apply(this);
                if (ec != modCount)
                    throw new ConcurrentModificationException();
                appendReplacement(sb, replacement);
                result = find();
            } while (result);
            appendTail(sb);
            return sb.toString();
        }
        return text.toString();
    }

    // helper class for implementing streams that do the find-loop
    private abstract
    class MatchStreamIterator<T> implements Iterator<T> {
        // -ve for call to find, 0 for not found, >0 for found
        int state = -1;
        // State for concurrent modification checking
        // -1 for uninitialized
        int expectedCount = -1;
        // how many match attempts to allow, or <0 for no limit
        int remainingMatches = -1;

        // Hook for producing the next result for the given state (>0).
        abstract T result();

        @Override
        public T next() {
            if (expectedCount >= 0 && expectedCount != modCount)
                throw new ConcurrentModificationException();

            if (!hasNext())
                throw new NoSuchElementException();

            state = -1;
            return result();  // might set state > 0 to report 2nd result
        }

        // Hook for controlling how find is called.
        boolean doFind() {
            int rm = remainingMatches;
            if (rm == 0) {
                return false;
            } else if (rm > 0) {
                remainingMatches = rm - 1;
            }
            return find();
        }

        @Override
        public boolean hasNext() {
            if (state >= 0)
                return state > 0;

            // Defer throwing ConcurrentModificationException to when next
            // or forEachRemaining is called.  The is consistent with other
            // fail-fast implementations.
            if (expectedCount >= 0 && expectedCount != modCount)
                return true;

            boolean found = doFind();
            state = found ? 1 : 0;
            expectedCount = modCount;
            return found;
        }

        @Override
        public void forEachRemaining(Consumer<? super T> action) {
            if (expectedCount >= 0 && expectedCount != modCount)
                throw new ConcurrentModificationException();

            int s = state;
            if (s == 0)
                return;

            expectedCount = -1;

            // Perform a first find if required
            if (s < 0 && !doFind())
                return;

            do {
                
                state = -1;
                T res = result();  // might set state > 0 to report 2nd result
                int ec = modCount;
                action.accept(res);
                if (ec != modCount)
                    throw new ConcurrentModificationException();
            } while (state > 0 || doFind());
        }
    }

    // helper subclass for that inserts negative results as well
    private abstract
    class MatchWithNegativesIterator extends MatchStreamIterator<MatchResult> {
        MatchResult nextNegative;
        MatchResult nextPositive;
        boolean generatedLastNegative;

        // doFind derives up to two results from one search
        @Override
        boolean doFind() {
            if (!super.doFind())  return false;
            
            // we just had a successful find() call; now we set up our state
            // to record both negative and positive matches, as appropriate
            nextNegative = toNegativeMatchResult(false);
            nextPositive = nextPositiveOrNull();
            if (nextNegative == null && nextPositive == null)  throw new AssertionError();
            return true;
        }

        final MatchResult peekResult() {
            return nextNegative != null ? nextNegative : nextPositive;
        }

        @Override
        final MatchResult result() {
            MatchResult res;
            if ((res = nextNegative) != null) {
                nextNegative = null;  // consumed
                if (nextPositive != null) {
                    super.state = 1;  // force hasNext to remain true
                }
            } else {
                res = nextPositive;
                if (res == null)  throw new AssertionError();
                nextPositive = null;  // consumed
            }
            return res;
        }

        @Override
        public boolean hasNext() {
            if (!super.hasNext()) {
                // this is our chance to return a trailing negative match
                return checkFinalNegative();
            }
            return true;
        }

        @Override
        public void forEachRemaining(Consumer<? super MatchResult> action) {
            super.forEachRemaining(action);
            // this is our chance to return a trailing negative match
            if (checkFinalNegative()) {
                action.accept(result());
                // Do not bother to check modCount, because there
                // will be no more queries to this matcher.
            }
        }

        // can override to modify behavior
        MatchResult nextPositiveOrNull() {
            return toMatchResult();
        }

        // addendum to hasNext and forEachRemaining:
        private boolean checkFinalNegative() {
            if (generatedLastNegative)  return false;
            generatedLastNegative = true;
            nextNegative = toNegativeMatchResult(true);
            return true;
        }
    }

    /**
     * Returns a stream of match results for each subsequence of the input
     * sequence that matches the pattern.  The match results occur in the
     * same order as the matching subsequences in the input sequence.
     *
     * <p> Each match result is produced as if by {@link #toMatchResult()}.
     *
     * <p> If the {@code matchCount} parameter is non-negative, it
     * limits the number of matches that are attempted.  If the match
     * count is zero the stream will have no elements.  Thus, the
     * number of elements in the stream will never be more than the
     * match count, unless that parameter is negative.
     *
     * <p> The {@code matchCount} parameter is optional in the sense
     * that there is {@linkplain #results() another
     * overloading of this method} which omits the parameter, and
     * which behaves as if this method had been passed a negative
     * match count, removing any limit on matches.
     *
     * <p> This method does not reset this matcher.  Matching starts on
     * initiation of the terminal stream operation either at the beginning of
     * this matcher's region, or, if the matcher has not since been reset, at
     * the first character not matched by a previous match.
     *
     * <p> If the matcher is to be used for further matching operations after
     * the terminal stream operation completes then it should be first reset.
     *
     * <p> This matcher's state should not be modified during execution of the
     * returned stream's pipeline.  The returned stream's source
     * {@code Spliterator} is <em>fail-fast</em> and will, on a best-effort
     * basis, throw a {@link java.util.ConcurrentModificationException} if such
     * modification is detected.
     *
     * @param matchCount the maximum number of matches attempted,
     *        or a negative number directing that matches are unlimited
     *
     * @return a sequential stream of match results
     *
     * @see #results()
     *
     * @since NN
     */
    public Stream<MatchResult> results(int matchCount) {
        class Results extends MatchStreamIterator<MatchResult> {
            { if (matchCount >= 0)  super.remainingMatches = matchCount; }
            @Override
            MatchResult result() {
                return toMatchResult();
            }
        }
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(
                new Results(), Spliterator.ORDERED | Spliterator.NONNULL), false);
    }

    /**
     * Returns a stream of match results for each subsequence of the input
     * sequence that matches the pattern.  The match results occur in the
     * same order as the matching subsequences in the input sequence.
     *
     * <p> Each match result is produced as if by {@link #toMatchResult()}.
     *
     * <p> This method accepts an optional {@code matchCount}
     * parameter, in the sense that there is {@linkplain #results(int)
     * another overloading of this method} which accepts an extra
     * {@code int} parameter that (if non-negative) imposes a limit on
     * the number of match attempts.  A call to this method is
     * equivalent to <code>{@link #results(int) results}(-1)</code>,
     * which means that, in the absence of a limit argument, no limit
     * is imposed on the number of match attempts.
     *
     * <p> Refer to {@link results(int)} for all additional details
     * about the behavior of both overloadings of this method.
     *
     * <!-- Normally such documentation is duplicated across
     * overloadings, but that seems excessive in this case. -->
     *
     * @return a sequential stream of match results.
     *
     * @see #results(int)
     * @see #nextResult()
     * @see #replacements(String)
     *
     * @since 9
     */
    public Stream<MatchResult> results() {
        return results(-1);
    }

    /**
     * Attempts to find the next subsequence of the input sequence that matches
     * the pattern.  Returns an {@link Optional} object which wraps this
     * matcher, if there is a match, or else is empty.  This method does
     * not reset this matcher; therefore, it can be used in a loop to
     * process multiple matches sequentially.
     *
     * <p> This method called on a matcher <i>m</i> has the same
     * behavior as the following expression:
     * <i>m</i>{@code .find() ?  Optional.of(}<i>m</i>{@code) :
     * Optional.empty()}.
     *
     * <p> Underneath the {@code Optional} wrapper, a stable copy of
     * the match can be obtained with {@code
     * nextResult().map(Matcher::toMatchResult)}, the actual matching
     * string can be obtained with {@code
     * nextResult().map(Matcher::group)}, and an expansion of a
     * replacement string <i>repl</i> can be obtained with {@code
     * nextResult().map(m -> m.replacement(}<i>repl</i>{@code ))}.
     *
     * @return this object, if there is a next match, else the empty value
     * @since NN
     * @see String#firstMatch(String)
     * @see #results(int)
     */
    public Optional<Matcher> nextResult() {
        return find() ? Optional.of(this) : Optional.empty();
    }

    /**
     * Replaces the first subsequence of the input sequence that matches the
     * pattern with the given replacement string.
     *
     * <p> This method first resets this matcher.  It then scans the input
     * sequence looking for a match of the pattern.  Characters that are not
     * part of the match are appended directly to the result string; the match
     * is replaced in the result by the replacement string.  The replacement
     * string may contain references to captured subsequences as in
     * the {@link #appendReplacement appendReplacement} and {@link
     * #replacement replacement} methods.
     *
     * <p>Note that backslashes ({@code \}) and dollar signs ({@code $}) in
     * the replacement string may cause the results to be different than if it
     * were being treated as a literal replacement string. Dollar signs may be
     * treated as references to captured subsequences as described above, and
     * backslashes are used to escape literal characters in the replacement
     * string.
     *
     * <p> Given the regular expression {@code dog}, the input
     * {@code "zzzdogzzzdogzzz"}, and the replacement string
     * {@code "cat"}, an invocation of this method on a matcher for that
     * expression would yield the string {@code "zzzcatzzzdogzzz"}.  </p>
     *
     * <p> An invocation of this method on an argument <i>repl</i> has
     * the same behavior as this stream-based expression:
     *
     * <blockquote>
     * {@link #resultsWithNegatives(int)
     * resultsWithNegatives}{@code (1)
     * }<br>&nbsp;&nbsp;&nbsp;&nbsp;{@code
     *     .map(mr -> mr.}{@link
     * MatchResult#replacement
     * replacement}{@code (mr.isNegative() ? "$0" :
     * }<i>repl</i>{@code )
     * }<br>&nbsp;&nbsp;&nbsp;&nbsp;{@code
     *     .collect(}{@link
     * java.util.stream.Collectors#joining()}{@code )}
     * </blockquote>
     *
     * <p> Invoking this method changes this matcher's state.  If the matcher
     * is to be used in further matching operations then it should first be
     * reset.  </p>
     *
     * @param  replacement
     *         The replacement string
     * @return  The string constructed by replacing the first matching
     *          subsequence by the replacement string, substituting captured
     *          subsequences as needed
     *
     * @see #replaceAll(String)
     * @see #replacement(String)
     * @see #replacements(String,int)
     */
    public String replaceFirst(String replacement) {
        if (replacement == null)
            throw new NullPointerException("replacement");
        reset();
        if (!find())
            return text.toString();
        StringBuilder sb = new StringBuilder();
        appendReplacement(sb, replacement);
        appendTail(sb);
        return sb.toString();
    }

    /**
     * Replaces the first subsequence of the input sequence that matches the
     * pattern with the result of applying the given replacer function to the
     * match result of this matcher corresponding to that subsequence.
     * Exceptions thrown by the replace function are relayed to the caller.
     *
     * <p> This method first resets this matcher.  It then scans the input
     * sequence looking for a match of the pattern.  Characters that are not
     * part of the match are appended directly to the result string; the match
     * is replaced in the result by the applying the replacer function that
     * returns a replacement string.  The replacement string may contain
     * references to captured subsequences as in the {@link #appendReplacement
     * appendReplacement} and {@link #replacement replacement}
     * methods.
     *
     * <p>Note that backslashes ({@code \}) and dollar signs ({@code $}) in
     * the replacement string may cause the results to be different than if it
     * were being treated as a literal replacement string. Dollar signs may be
     * treated as references to captured subsequences as described above, and
     * backslashes are used to escape literal characters in the replacement
     * string.
     *
     * <p> Given the regular expression {@code dog}, the input
     * {@code "zzzdogzzzdogzzz"}, and the function
     * {@code mr -> mr.group().toUpperCase()}, an invocation of this method on
     * a matcher for that expression would yield the string
     * {@code "zzzDOGzzzdogzzz"}.
     *
     * <p> Invoking this method changes this matcher's state.  If the matcher
     * is to be used in further matching operations then it should first be
     * reset.
     *
     * <p> The replacer function should not modify this matcher's state during
     * replacement.  This method will, on a best-effort basis, throw a
     * {@link java.util.ConcurrentModificationException} if such modification is
     * detected.
     *
     * <p> The state of the match result passed to the replacer function is
     * guaranteed to be constant only for the duration of the replacer function
     * call and only if the replacer function does not modify this matcher's
     * state.
     *
     * @implNote
     * This implementation applies the replacer function to this matcher, which
     * is an instance of {@code MatchResult}.
     *
     * @param  replacer
     *         The function to be applied to the match result of this matcher
     *         that returns a replacement string.
     * @return  The string constructed by replacing the first matching
     *          subsequence with the result of applying the replacer function to
     *          the matched subsequence, substituting captured subsequences as
     *          needed.
     * @throws NullPointerException if the replacer function is null
     * @throws ConcurrentModificationException if it is detected, on a
     *         best-effort basis, that the replacer function modified this
     *         matcher's state
     * @see #replacements(String,int)
     * @see #results(int)
     * @since 9
     */
    public String replaceFirst(Function<MatchResult, String> replacer) {
        Objects.requireNonNull(replacer);
        reset();
        if (!find())
            return text.toString();
        StringBuilder sb = new StringBuilder();
        int ec = modCount;
        String replacement = replacer.apply(this);
        if (ec != modCount)
            throw new ConcurrentModificationException();
        appendReplacement(sb, replacement);
        appendTail(sb);
        return sb.toString();
    }

    /**
     * {@inheritDoc}
     *
     * @see #appendReplacement
     * @see #replaceFirst(String)
     * @see #replaceAll(String)
     * @see #replacements(String,int)
     */
    public String replacement(String replacement) {
        return expandReplacement(this, replacement);
    }

    // not public; used locally in a few places including MatchResult
    static String expandReplacement(MatchResult mr, String replacement) {
        int len = replacement.length();
        if (len == 0)  return replacement;
        int cursor = 0;
        StringBuilder sb = null;
        while (cursor < len) {
            long ij = scanReplacement(replacement, cursor, mr);
            int start = (int)(ij >> 32);
            cursor = (int)ij;  // low 32 bits is end of scanned token
            if (sb == null) {
                if (cursor < len) {
                    sb = new StringBuilder();
                } else if (start >= 0) {
                    // common case: constant replacement string
                    return replacement.substring(start);
                } else {
                    // common case: single group reference
                    int refNum = ~start;
                    String g = mr.group(refNum);
                    return g == null ? "" : g;
                }
            }
            if (start >= 0) {
                // Append one or more chars of literal replacement
                sb.append(replacement, start, cursor);
            } else {
                // Append group
                int refNum = ~start;
                String g = mr.group(refNum);
                sb.append(g == null ? "" : g);
            }
        }
        return sb.toString();
    }

    /**
     * Returns a stream of replacements for each subsequence of the input
     * sequence that matches the pattern.  The replacements occur in the
     * same order as the matching subsequences in the input sequence.
     * The stream is not reset before this operation, so matches that
     * have already been found do not contribute to the resulting stream.
     *
     * <p> The replacements are produced as if by repeated calls to
     * {@link #find() the find method}.  Each match is used to expand
     * the replacement string, and the expansion is added to the
     * stream.  For example, if the replacement string is {@code "$0"}
     * then the matching substring itself would be added to the
     * stream.  Likewise, if the replacement string is {@code "hello"}
     * then the stream contains a reference to that constant string
     * for each match, and a call to {@link Stream#count()} would
     * return the number of matches.
     * 
     * <p>Note that backslashes ({@code \}) and dollar signs ({@code $}) in
     * the replacement string may cause the results to be different than if it
     * were being treated as a literal replacement string. Dollar signs may be
     * treated as references to captured subsequences as described above, and
     * backslashes are used to escape literal characters in the replacement
     * string.
     *
     * <p> The resulting stream has the same behavior as this stream,
     * which inspects the match results as intermediate values and
     * repeatedly expands the given replacement string:
     *
     * <blockquote><pre>{@code
     * // these two streams have the same behavior:
     * this.replacements(repl) ...
     * this.results().map(mr -> mr.replacement(repl)) ...
     * }</pre></blockquote>
     *
     * <p> A stream based on this method may be more efficient than a
     * more general-purpose stream based on {@code results()}.  If the
     * replacement string is a simple group reference (or a simple
     * constant string), a stream which directly extracts the group
     * (or repeats the constant string) has the same behavior as well,
     * and the performance is likely to be comparable or better:
     *
     * <blockquote><pre>{@code
     * // these two streams have the same behavior:
     * this.replacements("${42}") ...
     * this.results().map(mr -> mr.group(42)) ...
     * // these two streams have the same behavior:
     * this.replacements("$0") ...
     * this.results().map(MatchResult::group) ...
     * // these two streams have the same behavior:
     * this.replacements("free = \\$0") ...
     * this.results().map(mr -> "free = $0") ...
     * }</pre></blockquote>
     *
     * <p> If the {@code matchCount} parameter is non-negative, it
     * limits the number of matches that are attempted.  If the match
     * count is zero the stream will have no elements.  Thus, the
     * number of elements in the stream will never be more than the
     * match count, unless that parameter is negative.
     *
     * <p> The {@code matchCount} parameter is optional in the sense
     * that there is {@linkplain #replacements(String) another overloading
     * of this method} which omits that parameter, and which behaves as
     * if this method had been passed a negative match count, removing
     * any limit on matches.
     *
     * <p> This method does not reset this matcher.  Matching starts on
     * initiation of the terminal stream operation either at the beginning of
     * this matcher's region, or, if the matcher has not since been reset, at
     * the first character not matched by a previous match.
     *
     * <p> If the matcher is to be used for further matching operations after
     * the terminal stream operation completes then it should be first reset.
     *
     * <p> This matcher's state should not be modified during execution of the
     * returned stream's pipeline.  The returned stream's source
     * {@code Spliterator} is <em>fail-fast</em> and will, on a best-effort
     * basis, throw a {@link java.util.ConcurrentModificationException} if such
     * modification is detected.
     *
     * @param  replacement
     *         the replacement string to expand for each match encountered
     * @param matchCount the maximum number of matches attempted,
     *        or a negative number directing that matches are unlimited
     * @return a sequential stream of match replacements
     * @see #replacements(String)
     * @see #replacement(String)
     * @see #replaceFirst(String)
     * @see #replaceAll(String)
     * @see #results(int)
     * @since NN
     */
    public Stream<String> replacements(String replacement, int matchCount) {
        long ij = scanReplacement(replacement, 0, this);
        boolean isSimple = (int)ij == replacement.length();
        MatchStreamIterator<String> iter;
        if (isSimple && ij < 0) {
            // common case: whole replacement is single group "$0", "${1}", "${foo}", etc.
            int refNum = ~(int)(ij >> 32);
            class Groups extends MatchStreamIterator<String> {
                @Override
                String result() {
                    String g = group(refNum);
                    return g == null ? "" : g;
                }
            }
            iter = new Groups();
        } else if (isSimple || replacement.indexOf('$') < 0) {
            // replacement is a constant string, perhaps with embedded '\' escapes
            // (The null argument proves that we do not peek at groups; it is just literal text.)
            String constantRepl = expandReplacement(null, replacement);
            class Constants extends MatchStreamIterator<String> {
                @Override
                String result() {
                    return constantRepl;  // all matches replaced with constant string
                }
            }
            iter = new Constants();
        } else {
            // general case; just use replacement to interpret the string
            // (There might be a group reference and we don't bother
            // to completely pre-parse the string to find out if it's in there.)
            class Replacements extends MatchStreamIterator<String> {
                @Override
                String result() {
                    // expand it every time, for every match
                    return replacement(replacement);
                }
            }
            iter = new Replacements();
        }
        // enforce the match count, if one is present
        if (matchCount >= 0) {
            iter.remainingMatches = matchCount;
        }
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(
                iter, Spliterator.ORDERED | Spliterator.NONNULL), false);
    }

    /**
     * Returns a stream of replacements for each subsequence of the input
     * sequence that matches the pattern.  The replacements occur in the
     * same order as the matching subsequences in the input sequence.
     * The stream is not reset before this operation, so matches that
     * have already been found do not contribute to the resulting stream.
     *
     * <p> This method accepts an optional {@code matchCount}
     * parameter, in the sense that there is {@linkplain
     * #replacements(String,int) another overloading of this method} which
     * accepts an extra {@code int} parameter that (if non-negative)
     * imposes a limit on the number of match attempts.  A call to
     * this method is equivalent to <code>{@link #replacements(String,int)
     * replacements}(repl,-1)</code>, which means that, in the absence of a
     * limit argument, no limit is imposed on the number of match
     * attempts.
     *
     * <p> Refer to {@link replacements(String,int)} for all additional
     * details about the behavior of both overloadings of this method.
     *
     * <!-- Normally such documentation is duplicated across
     * overloadings, but that seems excessive in this case. -->
     *
     * @param  replacement
     *         the replacement string to expand for each match encountered
     * @return a sequential stream of match replacements
     * @see #replacements(String,int)
     * @see #results()
     * @see #replaceFirst(String)
     * @see #replaceAll(String)
     * @see #replacement(String)
     * @see #quoteReplacement
     * @since NN
     */
    public Stream<String> replacements(String replacement) {
        return replacements(replacement, -1);
    }

    /**
     * Returns a stream of match results for each subsequence of the
     * input sequence that matches the pattern, with intervening
     * subsequences that do <i>not</i> match also reported as {@link
     * #toNegativeMatchResult negative match results}.  Subsequences
     * that <i>do</i> match are reported as regular match results,
     * which (as distinguished from negative results) are also called
     * <i>positive</i> match results.
     *
     * <p> The match results occur in the same order as the matching
     * subsequences in the input sequence.  The match results (both
     * positive and negative) exhaustively partition the input
     * sequence.  Thus, each character of the input sequence falls in
     * the span of exactly one match result, either positive or
     * negative.
     *
     * <p> The sequence of positive elements of this stream are
     * identical to the whole sequence of elements in {@link
     * #results(int) results}, when both methods are provided with the
     * same match count option.
     *
     * <p> Each match result is produced as if by {@link #toMatchResult()}
     * or (for negative results) by {@link #toNegativeMatchResult}.
     *
     * <p> If the first match occurs after the position at which
     * matching starts, a negative match is reported as the first
     * element of the stream, from that beginning position through the
     * start of the first match.  If the last match does not end with
     * the input sequence itself, a negative match is reported between
     * the end of the last match and the end of the input sequence.
     * If there are no matches, the stream contains a single negative
     * match of the input sequence as a whole, unless the input
     * sequence as a whole is empty, in which case there are no stream
     * elements at all.
     *
     * <p> If the {@code matchCount} parameter is non-negative, it
     * limits the number of matches are attempted, and the last
     * negative match result may possibly contain additional matches
     * for the pattern, not attempted because of the limit.  If the
     * match count is zero, no matches are attempted, and the result
     * stream will consist of one negative match result for the whole
     * input sequence, unless it is empty, in which case the result
     * stream will have no elements at all.  Thus, the number of
     * regular (non-negative) elements in the stream will never be
     * more than the match count, unless that parameter is negative.
     *
     * <p> The {@code matchCount} parameter is optional in the sense
     * that there is {@linkplain #resultsWithNegatives() another
     * overloading of this method} which omits the parameter, and
     * which behaves as if this method had been passed a negative
     * match count, removing any limit on matches.
     *
     * <p> This method can be used to gain finer control over the
     * replacement of matches within the input sequence, beyond the
     * capabilities of {@link #replaceFirst} and {@link #replaceAll}.
     * The following example code shows how to use this stream to replace the first
     * <i>N</i> matches with a given replacement <i>R</i>:
     *
     * <blockquote>{@code
     * match.resultsWithNegatives(N)
     * }<br>&nbsp;&nbsp;&nbsp;&nbsp;{@code
     *     .map(m -> m.isNegative() ? m.group() : m.replacement(repl))
     * }<br>&nbsp;&nbsp;&nbsp;&nbsp;{@code
     *     .collect(Collectors.joining())
     * }</blockquote>
     *
     * <p> This method does not reset this matcher.  Matching starts on
     * initiation of the terminal stream operation either at the beginning of
     * this matcher's region, or, if the matcher has not since been reset, at
     * the first character not matched by a previous match.
     *
     * <p> If the matcher is to be used for further matching operations after
     * the terminal stream operation completes then it should be first reset.
     *
     * <p> This matcher's state should not be modified during execution of the
     * returned stream's pipeline.  The returned stream's source
     * {@code Spliterator} is <em>fail-fast</em> and will, on a best-effort
     * basis, throw a {@link java.util.ConcurrentModificationException} if such
     * modification is detected.
     *
     * <p> The results from this method are similar to those produced
     * by <a href="Pattern.html#split">splitting methods</a> where the
     * {@code withDelimiters} option is {@code true}, in that they
     * consist of a mix of positive and negative matches.  Like the
     * splitting methods, there is always an odd number of results
     * containing a regular odd/even alternation between positive and
     * negative matches.  Below the optional match count limit,
     * positive matches are never discarded, not even a leading
     * zero-length match.  One negative match always precedes and
     * follows every positive match.
     *
     * <p> Both methods support an optional match count limit.  For a
     * split method, the {@code limit} parameter corresponds to the
     * {@code matchCount} parameter of this method, except that a
     * positive {@code limit} of <i>N</i><code>&nbsp;+&nbsp;1</code>
     * has the same limiting effect as a non-negative {@code
     * matchCount} of <i>N</i>.  For both kinds of methods, a negative
     * {@code limit} or {@code matchCount} requests unlimited match
     * attempts.  A {@code limit} of zero has a special meaning
     * specific to split methods, but for this method simply specifies
     * that no matches are to be attempted.
     *
     * @param matchCount the maximum number of matches attempted,
     *        or a negative number directing that matches are unlimited
     *
     * @return a sequential stream of alternating negative and positive match results
     *
     * @see MatchResult#isNegative()
     * @see #resultsWithNegatives(int)
     * @see #results(int)
     * @see #splits(int,boolean)
     * @see Pattern#splitWithDelimiters(CharSequence,int)
     *
     * @since NN
     */
    public Stream<MatchResult> resultsWithNegatives(int matchCount) {
        class ResultsWithNegatives extends MatchWithNegativesIterator {
            { if (matchCount >= 0)  super.remainingMatches = matchCount; }
        }
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(
                new ResultsWithNegatives(), Spliterator.ORDERED | Spliterator.NONNULL), false);
    }

    /**
     * Returns a stream of match results for each subsequence of the
     * input sequence that matches the pattern, with intervening
     * subsequences that do <i>not</i> match also reported as
     * {@link #toNegativeMatchResult negative match results}.
     *
     * <p> This method accepts an optional {@code matchCount}
     * parameter, in the sense that there is {@linkplain
     * #resultsWithNegatives(int) another overloading of this method}
     * which accepts an extra {@code int} parameter that (if
     * non-negative) imposes a limit on the number of match attempts.
     * A call to this method is equivalent to <code>{@link
     * #resultsWithNegatives(int) resultsWithNegatives}(-1)</code>,
     * which means that, in the absence of a limit argument, no limit
     * is imposed on the number of match attempts.
     *
     * <p> Refer to {@link resultsWithNegatives(int)} for all
     * additional details about the behavior of both overloadings of
     * this method.
     *
     * <!-- Normally such documentation is duplicated across
     * overloadings, but that seems excessive in this case. -->
     *
     * @return a sequential stream of match results, both regular and negative
     *
     * @see MatchResult#isNegative()
     * @see #resultsWithNegatives(int)
     * @see #results()
     * @see #splits(int,boolean)
     * @see Pattern#splitWithDelimiters(CharSequence,int)
     *
     * @since NN
     */
    public Stream<MatchResult> resultsWithNegatives() {
        return resultsWithNegatives(-1);
    }

    /**
     * Splits the input sequence around matches of the given regular
     * expression and returns the unmatched strings and, optionally,
     * the matching delimiters.  Subsequences containing matched
     * delimiters, if present, are represented as regular match
     * results, as if from {@link #toMatchResult()}.  Non-matching
     * subsequences of delimited text are represented as negative
     * match results, as if from {@link #toNegativeMatchResult}.
     *
     * <p> The two kinds of match results can be distinguished by
     * {@link MatchResult#isNegative isNegative}.  The strings and
     * locations of all of the match results can be obtained using
     * {@link MatchResult#group() the group method} or other methods
     * of {@link MatchResult}.
     *
     * <p> If the {@code withDelimiters} parameter is {@code false},
     * when creating the stream, all regular match results are
     * removed, leaving only the negative results.  This is the
     * classic "split" behavior, which reports only runs of
     * non-matching input text.
     *
     * <p> This method does not reset this matcher.  Matching starts on
     * initiation of the terminal stream operation either at the beginning of
     * this matcher's region, or, if the matcher has not since been reset, at
     * the first character not matched by a previous match.
     *
     * <p> If the matcher is to be used for further matching operations after
     * the terminal stream operation completes then it should be first reset.
     *
     * <p> This matcher's state should not be modified during execution of the
     * returned stream's pipeline.  The returned stream's source
     * {@code Spliterator} is <em>fail-fast</em> and will, on a best-effort
     * basis, throw a {@link java.util.ConcurrentModificationException} if such
     * modification is detected.
     *
     * <p> The {@code limit} parameter controls the number of times the
     * pattern is applied and therefore affects the length of the resulting
     * stream.
     * <ul>
     *    <li> If the <i>limit</i> is positive then the pattern will
     *    be applied at most <i>limit</i>&nbsp;-&nbsp;1 times and
     *    streams's last element will contain all input beyond the
     *    last matched delimiter.</li>
     *
     *    <li> If the <i>limit</i> is zero or negative then the
     *    pattern will be applied as many times as possible and the
     *    stream's length is not limited.</li>
     *
     *    <li> If the <i>limit</i> is exactly zero then trailing empty
     *    match results, whether negative or regular (if present),
     *    will be discarded.</li>
     *
     *    <li> If {@code withDelimiters} is {@code false} and
     *    <i>limit</i> is positive, the streams's length will be no
     *    greater <i>limit</i>.</li>
     *
     *    <li> If {@code withDelimiters} is {@code true} and
     *    <i>limit</i> is non-zero, the streams's length will be an
     *    odd number, because the matching and non-matching results
     *    alternate, with one extra non-match.  If <i>limit</i> is
     *    positive, the length will be no greater than
     *    2&nbsp;&times;&nbsp;<i>limit</i>&nbsp;-&nbsp;1.</li>
     *
     * </ul>
     *
     * <p> This is a <a href="Pattern.html#split">splitting method</a>
     * that follows a set of historically determined rules in common
     * with other splitting methods.  A full discussion of those rules
     * is <a href="Pattern.html#split">available elsewhere</a>.
     *
     * @param  limit
     *         one more than the maximum number of delimiters matched,
     *         if positive, or an indication to discard trailing empty
     *         results, if zero
     * @param  withDelimiters
     *         if {@code true} include results for delimiters
     *
     * @return a sequential stream of negative match results,
     *         possibly alternating with regular match results as well
     *
     * @see #results(int)
     * @see MatchResult#isNegative()
     * @see Pattern#splitAsStream(CharSequence)
     * @see Pattern#splitWithDelimiters(CharSequence,int)
     * @see <a href="Pattern.html#split">rules for methods that split using a pattern</a>
     *
     * @since NN
     */
    public Stream<MatchResult> splits(int limit, boolean withDelimiters) {
        class Splits extends MatchWithNegativesIterator {
            @Override MatchResult nextPositiveOrNull() {
                // maybe drop positives
                if (!withDelimiters)  return null;
                return super.nextPositiveOrNull();
            }
            @Override
            boolean doFind() {
                if (last == previousMatchLast) {
                    int rm = super.remainingMatches;  // checkpoint match limit
                    if (!super.doFind())  return false;
                    if (last > previousMatchLast)  return true;
                    // skip first two elements that are -empty, +empty
                    super.remainingMatches = rm;  // this one did not count
                    // and fall through to a second match
                }
                return super.doFind();
            }
        }
        MatchWithNegativesIterator iter;
        if (limit != 0) {
            iter = new Splits();
        } else {
            // We need extra logic to strip trailing empty match results.
            class ElideEmptiesIterator extends Splits {
                // worst case is withDelimiters=false and many consecutive delims
                // e.g., "::::::::::::::::::::".split(":",-1)
                final ArrayList<MatchResult> empties = new ArrayList<>();

                @Override
                public MatchResult next() {
                    if (!empties.isEmpty()) {
                        return empties.remove(0);
                    }
                    return super.next();
                }

                @Override
                public boolean hasNext() {
                    if (!empties.isEmpty()) {
                        return true;
                    }
                    if (!super.hasNext()) {
                        return false;
                    }
                    // search for non-empty result to justify returning empties
                    for (;;) {
                        MatchResult mr = peekResult();
                        int start = mr.start();
                        if (start != mr.end()) {
                            // We found a justification for all our empties.
                            // The caller will call next() and see them.
                            return true;
                        }
                        if (start == previousMatchLast && start == to) {
                            // If the input is an empty string then
                            // the result can only be a stream of the
                            // input.  Induce that by reporting an
                            // empty trailing negative.
                            return true;  // this is the only trailing empty possible
                        }
                        empties.add(mr);
                        MatchResult nmr = next();  // advance past the empty
                        if (mr != nmr)  throw new AssertionError();
                        if (!super.hasNext()) {
                            empties.clear();
                            return false;
                        }
                    }
                }

                @Override
                public void forEachRemaining(Consumer<? super MatchResult> action) {
                    super.forEachRemaining(mr -> {
                            if (mr.start() == mr.end()) {
                                empties.add(mr);
                                return;
                            }
                            // found a non-empty item; first clear previous empties
                            for (int s = empties.size(); s > 0; ) {
                                action.accept(empties.remove(--s));
                            }
                            action.accept(mr);
                        });
                }
            }
            iter = new ElideEmptiesIterator();
        }
        if (limit > 0) {
            iter.remainingMatches = limit - 1;
        }
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(
                iter, Spliterator.ORDERED | Spliterator.NONNULL), false);
    }

    /**
     * Sets the limits of this matcher's region. The region is the part of the
     * input sequence that will be searched to find a match. Invoking this
     * method resets the matcher, and then sets the region to start at the
     * index specified by the {@code start} parameter and end at the
     * index specified by the {@code end} parameter.
     *
     * <p>Depending on the transparency and anchoring being used (see
     * {@link #useTransparentBounds(boolean) useTransparentBounds} and
     * {@link #useAnchoringBounds(boolean) useAnchoringBounds}), certain
     * constructs such as anchors may behave differently at or around the
     * boundaries of the region.
     *
     * @param  start
     *         The index to start searching at (inclusive)
     * @param  end
     *         The index to end searching at (exclusive)
     * @throws  IndexOutOfBoundsException
     *          If start or end is less than zero, if
     *          start is greater than the length of the input sequence, if
     *          end is greater than the length of the input sequence, or if
     *          start is greater than end.
     * @return  this matcher
     * @since 1.5
     */
    public Matcher region(int start, int end) {
        regionChecks(start, end);
        reset();
        from = start;
        to = end;
        previousMatchLast = start;
        return this;
    }

    /**
     * Sets the lower limit of this matcher's region. The region is the part of the
     * input sequence that will be searched to find a match. Invoking this
     * method resets the matcher, and then sets the region to start at the
     * index specified by the {@code start} parameter.  The region end is
     * unchanged.
     *
     * The expression {@code m.region(start)} equivalent to the
     * expression {@code m.}{@link #region(int,int)
     * region}{@code (start, m.}{@link #regionEnd()}{@code )}
     *
     * @param  start
     *         The index to start searching at (inclusive)
     * @throws  IndexOutOfBoundsException
     *          If start is less than zero, if
     *          start is greater than the end of the region.
     * @return  this matcher
     * @since NN
     */
    public Matcher region(int start) {
        return region(start, to);
    }

    private void regionChecks(int start, int end) {
        if ((start < 0) || (start > getTextLength()))
            throw new IndexOutOfBoundsException("start");
        if ((end < 0) || (end > getTextLength()))
            throw new IndexOutOfBoundsException("end");
        if (start > end)
            throw new IndexOutOfBoundsException("start > end");
    }

    /**
     * Reports the start index of this matcher's region. The
     * searches this matcher conducts are limited to finding matches
     * within {@link #regionStart() regionStart} (inclusive) and
     * {@link #regionEnd() regionEnd} (exclusive).
     *
     * @return  The starting point of this matcher's region
     * @since 1.5
     */
    public int regionStart() {
        return from;
    }

    /**
     * Reports the end index (exclusive) of this matcher's region.
     * The searches this matcher conducts are limited to finding matches
     * within {@link #regionStart() regionStart} (inclusive) and
     * {@link #regionEnd() regionEnd} (exclusive).
     *
     * @return  the ending point of this matcher's region
     * @since 1.5
     */
    public int regionEnd() {
        return to;
    }

    /**
     * Queries the transparency of region bounds for this matcher.
     *
     * <p> This method returns {@code true} if this matcher uses
     * <i>transparent</i> bounds, {@code false} if it uses <i>opaque</i>
     * bounds.
     *
     * <p> See {@link #useTransparentBounds(boolean) useTransparentBounds} for a
     * description of transparent and opaque bounds.
     *
     * <p> By default, a matcher uses opaque region boundaries.
     *
     * @return {@code true} iff this matcher is using transparent bounds,
     *         {@code false} otherwise.
     * @see java.util.regex.Matcher#useTransparentBounds(boolean)
     * @since 1.5
     */
    public boolean hasTransparentBounds() {
        return transparentBounds;
    }

    /**
     * Sets the transparency of region bounds for this matcher.
     *
     * <p> Invoking this method with an argument of {@code true} will set this
     * matcher to use <i>transparent</i> bounds. If the boolean
     * argument is {@code false}, then <i>opaque</i> bounds will be used.
     *
     * <p> Using transparent bounds, the boundaries of this
     * matcher's region are transparent to lookahead, lookbehind,
     * and boundary matching constructs. Those constructs can see beyond the
     * boundaries of the region to see if a match is appropriate.
     *
     * <p> Using opaque bounds, the boundaries of this matcher's
     * region are opaque to lookahead, lookbehind, and boundary matching
     * constructs that may try to see beyond them. Those constructs cannot
     * look past the boundaries so they will fail to match anything outside
     * of the region.
     *
     * <p> By default, a matcher uses opaque bounds.
     *
     * @param  b a boolean indicating whether to use opaque or transparent
     *         regions
     * @return this matcher
     * @see java.util.regex.Matcher#hasTransparentBounds
     * @since 1.5
     */
    public Matcher useTransparentBounds(boolean b) {
        transparentBounds = b;
        return this;
    }

    /**
     * Queries the anchoring of region bounds for this matcher.
     *
     * <p> This method returns {@code true} if this matcher uses
     * <i>anchoring</i> bounds, {@code false} otherwise.
     *
     * <p> See {@link #useAnchoringBounds(boolean) useAnchoringBounds} for a
     * description of anchoring bounds.
     *
     * <p> By default, a matcher uses anchoring region boundaries.
     *
     * @return {@code true} iff this matcher is using anchoring bounds,
     *         {@code false} otherwise.
     * @see java.util.regex.Matcher#useAnchoringBounds(boolean)
     * @since 1.5
     */
    public boolean hasAnchoringBounds() {
        return anchoringBounds;
    }

    /**
     * Sets the anchoring of region bounds for this matcher.
     *
     * <p> Invoking this method with an argument of {@code true} will set this
     * matcher to use <i>anchoring</i> bounds. If the boolean
     * argument is {@code false}, then <i>non-anchoring</i> bounds will be
     * used.
     *
     * <p> Using anchoring bounds, the boundaries of this
     * matcher's region match anchors such as ^ and $.
     *
     * <p> Without anchoring bounds, the boundaries of this
     * matcher's region will not match anchors such as ^ and $.
     *
     * <p> By default, a matcher uses anchoring region boundaries.
     *
     * @param  b a boolean indicating whether or not to use anchoring bounds.
     * @return this matcher
     * @see java.util.regex.Matcher#hasAnchoringBounds
     * @since 1.5
     */
    public Matcher useAnchoringBounds(boolean b) {
        anchoringBounds = b;
        return this;
    }

    /**
     * <p>Returns the string representation of this matcher. The
     * string representation of a {@code Matcher} contains information
     * that may be useful for debugging. The exact format is unspecified.
     *
     * @return  The string representation of this matcher
     * @since 1.5
     */
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("java.util.regex.Matcher")
                .append("[pattern=").append(pattern())
                .append(" region=")
                .append(regionStart()).append(',').append(regionEnd())
                .append(" lastmatch=");
        if ((first >= 0) && (group() != null)) {
            sb.append(group());
        }
        sb.append(']');
        return sb.toString();
    }

    // non-public default code for MatchResult
    static String matchResultToString(MatchResult mr) {
        boolean isneg = mr.isNegative();
        if (!isneg && !mr.hasMatch()) {
            return "MatchResult[none]";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("MatchResult[")
            .append("start=").append(mr.start())
            .append(isneg ? " skip=" : " match=");
        if (mr.group() != null) {
            sb.append(mr.group());
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * <p>Returns true if the end of input was hit by the search engine in
     * the last match operation performed by this matcher.
     *
     * <p>When this method returns true, then it is possible that more input
     * would have changed the result of the last search.
     *
     * @return  true iff the end of input was hit in the last match; false
     *          otherwise
     * @since 1.5
     */
    public boolean hitEnd() {
        return hitEnd;
    }

    /**
     * <p>Returns true if more input could change a positive match into a
     * negative one.
     *
     * <p>If this method returns true, and a match was found, then more
     * input could cause the match to be lost. If this method returns false
     * and a match was found, then more input might change the match but the
     * match won't be lost. If a match was not found, then requireEnd has no
     * meaning.
     *
     * @return  true iff more input could change a positive match into a
     *          negative one.
     * @since 1.5
     */
    public boolean requireEnd() {
        return requireEnd;
    }

    /**
     * Initiates a search to find a Pattern within the given bounds.
     * The groups are filled with default values and the match of the root
     * of the state machine is called. The state machine will hold the state
     * of the match as it proceeds in this matcher.
     *
     * Matcher.from is not set here, because it is the "hard" boundary
     * of the start of the search which anchors will set to. The from param
     * is the "soft" boundary of the start of the search, meaning that the
     * regex tries to match at that index but ^ won't match there. Subsequent
     * calls to the search methods start at a new "soft" boundary which is
     * the end of the previous match, or one character beyond that if the
     * previous match was to the empty string.
     */
    boolean search(int from) {
        this.hitEnd = false;
        this.requireEnd = false;
        from        = from < 0 ? 0 : from;
        this.first  = from;
        this.oldLast = oldLast < 0 ? from : oldLast;
        for (int i = 0; i < groups.length; i++)
            groups[i] = -1;
        for (int i = 0; i < localsPos.length; i++) {
            if (localsPos[i] != null)
                localsPos[i].clear();
        }
        acceptMode = NOANCHOR;
        boolean result = parentPattern.root.match(this, from, text);
        if (!result)
            this.first = -1;
        this.oldLast = this.last;
        this.modCount++;
        return result;
    }

    /**
     * Initiates a search for an anchored match to a Pattern within the given
     * bounds. The groups are filled with default values and the match of the
     * root of the state machine is called. The state machine will hold the
     * state of the match as it proceeds in this matcher.
     */
    boolean match(int from, int anchor) {
        this.hitEnd = false;
        this.requireEnd = false;
        from        = from < 0 ? 0 : from;
        this.first  = from;
        this.oldLast = oldLast < 0 ? from : oldLast;
        for (int i = 0; i < groups.length; i++)
            groups[i] = -1;
        for (int i = 0; i < localsPos.length; i++) {
            if (localsPos[i] != null)
                localsPos[i].clear();
        }
        acceptMode = anchor;
        boolean result = parentPattern.matchRoot.match(this, from, text);
        if (!result)
            this.first = -1;
        this.oldLast = this.last;
        this.modCount++;
        return result;
    }

    /**
     * Returns the end index of the text.
     *
     * @return the index after the last character in the text
     */
    int getTextLength() {
        return text.length();
    }

    /**
     * Generates a String from this matcher's input in the specified range.
     *
     * @param  beginIndex   the beginning index, inclusive
     * @param  endIndex     the ending index, exclusive
     * @return A String generated from this matcher's input
     */
    CharSequence getSubSequence(int beginIndex, int endIndex) {
        return text.subSequence(beginIndex, endIndex);
    }

    /**
     * Returns this matcher's input character at index i.
     *
     * @return A char from the specified index
     */
    char charAt(int i) {
        return text.charAt(i);
    }

    /**
     * Returns the group index of the matched capturing group.
     *
     * @return the index of the named-capturing group
     */
    int getMatchedGroupIndex(String name) {
        Objects.requireNonNull(name, "Group name");
        checkMatch();
        Integer number = namedGroups().get(name);
        if (number == null)
            throw new IllegalArgumentException("No group with name <" + name + ">");
        return number;
    }

    private void checkGroup(int group) {
        if (group < 0 || group > groupCount())
            throw new IndexOutOfBoundsException("No group " + group);
    }

    private void checkMatch() {
        if (!hasMatch())
            throw new IllegalStateException("No match found");
    }

    /**
     * {@inheritDoc}
     *
     * @return {@inheritDoc}
     *
     * @since 20
     */
    @Override
    public Map<String, Integer> namedGroups() {
        if (namedGroups == null) {
            return namedGroups = parentPattern.namedGroups();
        }
        return namedGroups;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@inheritDoc}
     *
     * @since 20
     */
    @Override
    public boolean hasMatch() {
        return first >= 0;
    }

}
