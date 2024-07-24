/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

/*
 * @test
 * @bug 8305486
 * @summary Tests to exercise the split functionality added in the issue.
 * @run junit SplitWithDelimitersTest
 */

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.MatchResult;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

public class SplitWithDelimitersTest {

    private static String[] dropOddIndexed(String[] a, int limit) {
        String[] r = new String[(a.length + 1) / 2];
        for (int i = 0; i < a.length; i += 2) {
            r[i / 2] = a[i];
        }
        if (limit == 0) {
            /* Also drop trailing empty strings */
            r = dropTrailingEmpties(r);
        }
        return r;
    }

    private static String[] dropTrailingEmpties(String[] a) {
        int len = a.length;
        for (; len > 0 && a[len - 1].isEmpty(); --len);  // empty body
        return len < a.length ? Arrays.copyOf(a, len) : a;
    }

    private static String[] dropEvenIndexed(String[] a) {
        String[] r = new String[a.length / 2];
        for (int i = 1; i < a.length; i += 2) {
            r[i / 2] = a[i];
        }
        return r;
    }

    static Arguments[] testSplit() {
        return new Arguments[] {
                arguments(new String[] {"b", "o", "", "o", ":::and::f", "o", "", "o", ""},
                        "boo:::and::foo", "o", 5),
                arguments(new String[] {"b", "o", "", "o", ":::and::f", "o", "o"},
                        "boo:::and::foo", "o", 4),
                arguments(new String[] {"b", "o", "", "o", ":::and::foo"},
                        "boo:::and::foo", "o", 3),
                arguments(new String[] {"b", "o", "o:::and::foo"},
                        "boo:::and::foo", "o", 2),
                arguments(new String[] {"boo:::and::foo"},
                        "boo:::and::foo", "o", 1),
                arguments(new String[] {"b", "o", "", "o", ":::and::f", "o", "", "o"},
                        "boo:::and::foo", "o", 0),
                arguments(new String[] {"b", "o", "", "o", ":::and::f", "o", "", "o", ""},
                        "boo:::and::foo", "o", -1),

                arguments(new String[] {"boo", ":::", "and", "::", "foo"},
                        "boo:::and::foo", ":+", 3),
                arguments(new String[] {"boo", ":::", "and::foo"},
                        "boo:::and::foo", ":+", 2),
                arguments(new String[] {"boo:::and::foo"},
                        "boo:::and::foo", ":+", 1),
                arguments(new String[] {"boo", ":::", "and", "::", "foo"},
                        "boo:::and::foo", ":+", 0),
                arguments(new String[] {"boo", ":::", "and", "::", "foo"},
                        "boo:::and::foo", ":+", -1),

                arguments(new String[] {"b", "", "b", "", ""},
                        "bb", "a*|b*", 3),
                arguments(new String[] {"b", "", "b"},
                        "bb", "a*|b*", 2),
                arguments(new String[] {"bb"},
                        "bb", "a*|b*", 1),
                arguments(new String[] {"b", "", "b"},
                        "bb", "a*|b*", 0),
                arguments(new String[] {"b", "", "b", "", ""},
                        "bb", "a*|b*", -1),

                arguments(new String[] {"", "bb", "", "", ""},
                        "bb", "b*|a*", 3),
                arguments(new String[] {"", "bb", ""},
                        "bb", "b*|a*", 2),
                arguments(new String[] {"bb"},
                        "bb", "b*|a*", 1),
                arguments(new String[] {"", "bb"},
                        "bb", "b*|a*", 0),
                arguments(new String[] {"", "bb", "", "", ""},
                        "bb", "b*|a*", -1),

                arguments(new String[] {"A", ":", "B", ":", "", ":", "C"},
                          "A:B::C", ":", -1),
                arguments(new String[] {"A", ":", "B", ":", ":C"},
                          "A:B::C", ":", 3),
                arguments(new String[] {"A", ":", "B::C"},
                          "A:B::C", ":", 2),
                arguments(new String[] {"A:B::C"},
                          "A:B::C", ":", 1),
                arguments(new String[] {"A"},
                          "A", ":", -1),
                arguments(new String[] {"", ":", "A", ":"},
                          ":A:", ":", 0),
                arguments(new String[] {"", ":", "A", ":", ""},
                          ":A:", ":", -1),
                arguments(new String[] {"", ":", "A:"},
                          ":A:", ":", 2),
                arguments(new String[] {"", ":"},
                          ":", ":", 0),
                arguments(new String[] {"", ":", ""},
                          ":", ":", -1),
                arguments(new String[] {""},
                          "", ":", -1),
                arguments(new String[] {"A"},
                          "A", ":+", -1),
                arguments(new String[] {"", ":", "A", ":"},
                          ":A:", ":+", 0),
                arguments(new String[] {"", ":", "A", ":", ""},
                          ":A:", ":+", -1),
                arguments(new String[] {"", ":", "A:"},
                          ":A:", ":+", 2),
                arguments(new String[] {"A"},
                          "A", "(?=:)", -1),
                arguments(new String[] {":A", "", ":"},
                          ":A:", "(?=:)", -1),
                arguments(new String[] {"A"},
                          "A", ":*", 0),
                arguments(new String[] {"A", "", ""},
                          "A", ":*", -1),
                arguments(new String[] {"", ":", "", "", "A", ":"},
                          ":A:", ":*", 0),
                arguments(new String[] {"", ":", "", "", "A", ":", "", "", ""},
                          ":A:", ":*", -1),
                arguments(new String[] {"", ":", "", "", "A", ":", ""},
                          ":A:", ":*", 4),
                arguments(new String[] {"", ":", "", "", "A:"},
                          ":A:", ":*", 3),
                arguments(new String[] {"", ":", "A:"},
                          ":A:", ":*", 2),
                arguments(new String[] {":A:"},
                          ":A:", ":*", 1),
                arguments(new String[] {"A"},
                          "A", "\\b", 0),
                arguments(new String[] {"A", "", ""},
                          "A", "\\b", -1),
                arguments(new String[] {":", "", "A", "", ":"},
                          ":A:", "\\b", -1),
                arguments(new String[] {":", "", "A:"},
                          ":A:", "\\b", 2),
                arguments(new String[] {"A", "", "B", "", "C"},
                          "ABC", "", 0),
                arguments(new String[] {"A", "", "B", "", "C", "", ""},
                          "ABC", "", -1),
                arguments(new String[] {"A", "", "B", "", "C"},
                          "ABC", "", 3),
                arguments(new String[] {"A", "", "BC"},
                          "ABC", "", 2),
                arguments(new String[] {"ABC"},
                          "ABC", "", 1),
                arguments(new String[] {"A"},
                          "A", "", 0),
                arguments(new String[] {"A", "", ""},
                          "A", "", -1),
                arguments(new String[] {""},
                          "", "", -1),
        };
    }

    @ParameterizedTest
    @MethodSource
    void testSplit(String[] expected, String target, String regex, int limit) {
        // First split with delimiters included.
        String[] computedWith = target.splitWithDelimiters(regex, limit);
        assertArrayEquals(expected, computedWith);
        String[] patComputedWith = Pattern.compile(regex).splitWithDelimiters(target, limit);
        assertArrayEquals(computedWith, patComputedWith);

        // Then split without delimiters.
        String[] computedWithout = target.split(regex, limit);
        assertArrayEquals(dropOddIndexed(expected, limit), computedWithout);
        String[] patComputedWithout = Pattern.compile(regex).split(target, limit);
        assertArrayEquals(computedWithout, patComputedWithout);

        // Demonstrate the relation between the delimiter substream and Matcher::results.
        if (limit != 0) {
            String[] streamDelim = target.matching(regex).results()
                .filter(m -> m.end() > 0)  // drop leading zero-length match
                .limit(limit <= 0 ? Long.MAX_VALUE : limit - 1)
                .map(MatchResult::group).toArray(String[]::new);
            assertArrayEquals(dropEvenIndexed(expected), streamDelim);
        }

        // Make sure the stream-based splitter gives the correct sequence of results.
        String[] streamWith = target.matching(regex).splits(limit, true).map(MatchResult::group).toArray(String[]::new);
        assertArrayEquals(computedWith, streamWith);
        String[] streamWithout = target.matching(regex).splits(limit, false).map(MatchResult::group).toArray(String[]::new);
        assertArrayEquals(computedWithout, streamWithout);

        // Simulate splitting with the more regular resultsWithNegatives streams.
        // Oddity #1: split pretends a leading empty delimiter (LED) never happened.
        boolean dropFirst = (target.firstMatch(regex).map(m -> m.end() == 0).orElse(false));
        // Oddity #2: split uses an exclusive limit, which does not apply to an LED if present.
        int matchC = limit > 0 ? limit - 1 + (dropFirst ? 1 : 0) : -1;
        String[] fromNStreamWith =
            target.matching(regex).resultsWithNegatives(matchC)
                .dropWhile(m -> dropFirst && m.end() == 0)
                .map(MatchResult::group).toArray(String[]::new);
        String[] fromNStreamWithout =
            target.matching(regex).resultsWithNegatives(matchC)
                .dropWhile(m -> dropFirst && m.end() == 0)
                .filter(MatchResult::isNegative)
                .map(MatchResult::group).toArray(String[]::new);
        if (target.isEmpty()) {
            // Oddity #3: An empty string splits to a single copy of itself, regardless of other rules.
            // We stripped all leading empty matches, but we must put back exactly one.
            fromNStreamWith = fromNStreamWithout = new String[] { "" };
        } else if (limit == 0) {
            // Oddity #4: A limit of zero is much like -1 but it cleans up trailing empties.
            fromNStreamWith = dropTrailingEmpties(fromNStreamWith);
            fromNStreamWithout = dropTrailingEmpties(fromNStreamWithout);
        }
        // Other than those oddities, splitWD produces a regular series of -/+/- matches.
        assertArrayEquals(computedWith, fromNStreamWith);
        assertArrayEquals(computedWithout, fromNStreamWithout);
    }

}
