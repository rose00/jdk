/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
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
 * @bug 0000000
 * @summary Tests to exercise methods in class String which target regular expressions
 * @run junit StringConnectionTest
 */

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.regex.MatchResult;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

public class StringConnectionTest {

    @org.junit.Test
    public void smokeTest() {
        System.err.println("huzzah".matching("z").results().toList());
        System.err.println("huzzah".matching("z").results().map(m->m.group().toUpperCase()).toList());
        System.err.println("huzzah".matching("z").resultsWithNegatives().toList());
        System.err.println("huzzah".matching("z").resultsWithNegatives().map(m->m.group().toUpperCase()).toList());
        System.err.println("huzzah".splits("huzzah", -1, true).toList());
    }

    static Arguments[] testMatcher() {
        return new Arguments[] {
            arguments("football", "football"),   // one exact match
            arguments("football", "soccer"),     // zero matches
            arguments("football", "[oa]"),
            arguments("football", "(?<z>[oa]+)"),
            arguments("football", "(?<=(?<z>[fb]))."),
        };
    }

    static boolean VERBOSE = false;

    static final String[] REPLACEMENTS = {
        "esc=\\\\", "$0", "$0z", "${z}"
    };

    @ParameterizedTest
    @MethodSource
    void testMatcher(String target, String regex) {
        final var pcr = Pattern.compile(regex);     // compiled regex
        final var fregex = ".*(" + regex + ").*";   // floating version of regex
        final var pcfr = Pattern.compile(fregex);   // compiled floating version

        // s.matches(r) := s.matching(r).matches() := pcr.matcher(s).matches()
        final var matches = pcr.matcher(target).matches();
        final var fmatches = pcr.matcher(target).find();
        final var repl = regex.indexOf("(?<z>") >= 0 ? "$0z${z}" : "$0z";
        assertEquals(matches, target.matches(regex));
        assertEquals(matches, target.matching(pcr).matches());
        assertEquals(fmatches, target.matches(fregex));
        assertEquals(fmatches, target.matching(pcfr).matches());
        if (VERBOSE && matches == fmatches)
            System.err.println(matches ? "whole match" : "no matches at all");

        // s.firstMatch(r) := s.matching(r).nextResult()
        final var res1opt = target.matching(regex).nextResult();
        assertEquals(res1opt.map(m->m.group()).orElse("none"),
                     target.firstMatch(regex).map(m->m.group()).orElse("none"));
        assertEquals(res1opt.map(m->m.group()).orElse("none"),
                     target.firstMatch(pcr).map(m->m.group()).orElse("none"));
        if (fmatches) {  // unless there is no match, dig deeper into the first match
            final var resm = res1opt.get();  // this is a mutable matcher
            assertEquals(resm.replacement(repl),
                         target.firstMatch(regex).map(m->m.replacement(repl)).get());
            assertEquals(resm.replacement(repl),
                         target.firstMatch(pcr).map(m->m.replacement(repl)).get());
            // fancy example from documentation of String::firstMatch(String)
            final var res1 = resm.group();  // capture before next use of martcher
            final var res2 = resm.find() ? resm.group() : null;
            Function<Matcher,List> ls2maker = m -> {
                var m1 = m.group();
                return m.find() ? List.of(m1, m.group()) : List.of(m1);
            };
            final var ls2 = res2 != null ? List.of(res1, res2) : List.of(res1);
            assertEquals(ls2, target.firstMatch(regex).map(ls2maker).get());
            assertEquals(ls2, target.firstMatch(pcr).map(ls2maker).get());
            if (VERBOSE)  System.err.println("ls2 = "+ls2);
        }

        // s.replaceFirst(r,q) := s.matching(r).replaceFirst(q)
        // s.replaceAll(r,q) := s.matching(r).replaceAll(q)
        final var rfirst = target.replaceFirst(regex, repl);
        final var rall = target.replaceAll(regex, repl);
        assertEquals(rfirst, target.matching(regex).replaceFirst(repl));
        assertEquals(rfirst, target.matching(pcr).replaceFirst(repl));
        assertEquals(rall, target.matching(regex).replaceAll(repl));
        assertEquals(rall, target.matching(pcr).replaceAll(repl));

        // Use stream-based equivalent for replaceX.  The stream-based
        // forms are flexible enough to replace the first N matches,
        // only odd matches, and so on.  They can also use varying
        // replacement strings.
        assertEquals(rfirst, target.matching(pcr)
                     .resultsWithNegatives(1)
                     .map(m -> m.isNegative() ? m.group() : m.replacement(repl))
                     .collect(Collectors.joining()));
        assertEquals(rall, target.matching(pcr)
                     .resultsWithNegatives(-1)
                     .map(m -> m.isNegative() ? m.group() : m.replacement(repl))
                     .collect(Collectors.joining()));
        final var rsomes = new ArrayList<String>();
        for (int reps = 0; reps <= 3; reps++) {
            var mrep = target.matching(pcr);
            var sb = new StringBuilder();
            for (int i = 0; i < reps && mrep.find(); i++) {
                mrep.appendReplacement(sb, repl);
            }
            mrep.appendTail(sb);
            var rsome = sb.toString();
            var withStream = target.matching(pcr)
                .resultsWithNegatives(reps)
                .map(m -> m.isNegative() ? m.group() : m.replacement(repl))
                .collect(Collectors.joining());
            assertEquals(rsome, withStream);
            if (rall.equals(rsome))  break;
            rsomes.add(rsome);
        }
        if (VERBOSE || target.contains("foo")) {
            System.err.println("repl: "+repl+"; first: "+rfirst+"; all: "+rall+"; some: "+rsomes);
        }

        // test Matcher::replacements (useful for extracting tokens from string)
        for (var r : REPLACEMENTS) {
            if (r.indexOf("${z}") >= 0 && regex.indexOf("(?<z>") < 0)
                continue;
            final var sb = new StringBuilder();
            final var reps = new ArrayList<String>();
            for (var m = pcr.matcher(target); m.find(); ) {
                reps.add(m.replacement(r));
            }
            assertEquals(reps, target.matching(regex).replacements(r).toList(),
                         "r="+r);
        }

        // s.splits(r,l,d) := s.matching(r).splits(l,d)
        // (there are fuller tests elsewhere...)
        if (true)  return;//@@
        if (fmatches && regex.indexOf("(?<z>") < 0) {
            for (int d = 0; d <= 1; d++) {
                boolean withD = (d != 0);
                for (int limit = 0; limit <= 2; limit++) {
                    final var tokens = (withD
                                        ? target.splitWithDelimiters(regex, limit)
                                        : target.split(regex, limit));
                    if (VERBOSE)
                        System.err.printf("split %d, %s = %s\n",
                                          limit, withD, Arrays.toString(tokens));
                    final var fromStream =
                        target.splits(regex, limit, withD).toArray(String[]::new);
                    assertArrayEquals(tokens, fromStream);
                    final var fromMStream =
                        target.matching(pcr).splits(limit, withD).toArray(String[]::new);
                    assertArrayEquals(tokens, fromMStream);
                    if (target.firstMatch(pcr).map(m -> m.end() > 0).orElse(true)) {
                        // this is approximate; can fail with limit=0 or leading empty delimiter
                        final int matchC = limit > 0 ? limit - 1 : -1;
                        List<String> fromNStream =
                            target.matching(pcr).resultsWithNegatives(matchC)
                                .filter(m -> withD || m.isNegative())
                                .map(MatchResult::group).toList();
                        if (limit == 0 && !target.isEmpty()) {
                            fromNStream = new ArrayList<>(fromNStream);
                            while (!fromNStream.isEmpty() && fromNStream.getLast().isEmpty())
                                fromNStream.removeLast();
                        }                        
                        assertEquals(List.of(tokens), fromNStream);
                    }
                }
            }
        }
    }

}
