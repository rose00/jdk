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

#include "precompiled.hpp"
#include "jvm.h"
#include "memory/allocation.inline.hpp"
#include "memory/resourceArea.hpp"
#include "runtime/os.hpp"
#include "utilities/istream.hpp"
#include "utilities/xmlinput.hpp"
#include "unittest.hpp"

static int firstdiff(char* b1, char* b2, int blen) {
  for (int i = 0; i < blen; i++) {
    if (b1[i] != b2[i])  return i;
  }
  return -1;
}

static char* get_temp_file(const char* filename) {
  const char* tmp_dir = os::get_temp_directory();
  const char* file_sep = os::file_separator();
  size_t temp_file_len = strlen(tmp_dir) + strlen(file_sep) + strlen(filename) + 28;
  char* temp_file = NEW_RESOURCE_ARRAY(char, temp_file_len);
  int ret = jio_snprintf(temp_file, temp_file_len, "%s%spid%d.%s",
                         tmp_dir, file_sep,
                         os::current_process_id(), filename);
  return temp_file;
}

#define EIGHTY 80
#define LC0(x)     ('/' + ((unsigned)(x) % EIGHTY))
#define LC(line,col)  LC0((col) * (line))

#define COLS 30

static const bool VERBOSE = false;

static int cases, total, zeroes;
#ifdef ASSERT
#define istream_coverage_mode(mode, a,b,c) \
  inputStream::coverage_mode(mode, a,b,c)
#else
#define istream_coverage_mode(mode, a,b,c)
#endif

static void fill_pattern(char* pat, int patlen, int ncols,
                         int& full_lines, int& partial_line) {
  full_lines = partial_line = 0;
  for (int i = 0; i < patlen; i++) {
    int line = (i / (ncols+1)) + 1;  // 1-based line number
    int col  = (i % (ncols+1)) + 1;  // 1-based column number
    if (col <= ncols) {
      pat[i] = LC(line, col);
      partial_line = 1;
    } else {
      pat[i] = '!';
      full_lines++;
      partial_line = 0;
    }
  }
  pat[patlen] = '\0';
  if (VERBOSE)  tty->print_cr("PATTERN=%d+%d[%s]",
                              full_lines, partial_line, pat);
  for (int i = 0; i < patlen; i++) {
    if (pat[i] == '!')  pat[i] = '\n';
  }
}

TEST_VM(istream, basic) {
  DEBUG_ONLY( istream_coverage_mode(VERBOSE ? 2 : 1, cases, total, zeroes) );
  ResourceMark rm;
  const char* temp_file = get_temp_file("test_istream");
  if (VERBOSE)  tty->print_cr("temp_file = %s", temp_file);
  unlink(temp_file);
  char pat[COLS * (COLS-1)];
  int patlen = (int)sizeof(pat) - 1;
  for (int ncols = 0; ncols <= patlen; ncols++) {
    if (ncols > COLS) {
      ncols += ncols / 7;
      if (ncols > patlen)  ncols = patlen;
    }
    //if (ncols > 0) ncols = (ncols == 1) ? (2*patlen)/3 : patlen;
    int full_lines, partial_line;
    fill_pattern(pat, patlen, ncols, full_lines, partial_line);
    char pat2[sizeof(pat)];  // copy of pat to help detect scribbling
    memcpy(pat2, pat, sizeof(pat));
    inputStream sin(pat2, patlen);
    if (VERBOSE) {
      tty->print("at %llx ", (unsigned long long)(intptr_t)&sin);
      sin.dump("sin");
    }
    {
      fileStream tfs(temp_file);
      guarantee(tfs.is_open(), "cannot open temp file");
      tfs.write(pat, patlen);
      //tfs.print_cr("googly");  //this fault would get caught
    }
    FileInput fin_block(temp_file);
    inputStream fin(&fin_block);
    if (VERBOSE) {
      tty->print("at %llx ", (unsigned long long)(intptr_t)&fin);
      fin.dump("fin");
    }
    BlockInputStream<MemoryInput> min(&pat2[0], &pat2[patlen]);
    if (VERBOSE) {
      tty->print("at %llx ", (unsigned long long)(intptr_t)&min);
      sin.dump("min");
    }
    inputStream* ins[] = { &sin, &fin, &min };
    const char* in_names[] = { "sin", "fin", "min" };
    for (int which = 0; which <= 2; which++) {
      inputStream& in = *ins[which];
      const char* in_name = in_names[which];
      int lineno;
      char* lp = (char*)"--";
      #define LPEQ \
        in_name << " ncols=" << ncols << " lineno=" << lineno \
        << " [" << lp << "]"
      if (VERBOSE)
        tty->print_cr("testing %s patlen=%d ncols=%d full_lines=%d partial_line=%d",
                      in_name, patlen, ncols, full_lines, partial_line);
      for (lineno = 1; lineno <= full_lines + partial_line; lineno++) {
        EXPECT_EQ(-1, firstdiff(pat, pat2, patlen + 1));
        if (VERBOSE)  in.dump("done");
        bool done = in.done();
        EXPECT_TRUE(!done)  <<LPEQ;
        if (done)  break;
        lp = in.current_line();
        if (lineno % 3 == 0) {  // add tests later
          const char* copy = in.save_line();
          int oldcll = (int) in.current_line_length();
          EXPECT_STREQ(lp, copy)  <<LPEQ;
          const char* endl = in.current_line_ending();
          bool overwrite = (lineno % 6 == 0);
          if (overwrite) {
            in.pushback_input(endl, strlen(endl), true);
            in.pushback_input(copy);
          } else {
            bool saw_next = in.next();
            in.pushback_input(endl);
            in.pushback_input(copy);
            // we ate two newlines, unless there is no next line
            in.add_to_lineno(saw_next ? -1 : 0);
          }
          lp = in.current_line();
          if (VERBOSE)
            tty->print_cr("pushback %s %d: [%s], [%s]",
                          overwrite ? "overwriting" : "before next",
                          lineno, copy, lp);
          EXPECT_STREQ(lp, copy)  <<LPEQ;
          int newcll = (int) in.current_line_length();
          EXPECT_EQ(newcll, oldcll)  <<LPEQ << " newcll:" << newcll;
          if (lineno == full_lines+partial_line)
            in.set_lineno(lineno);  //FIXME: paper over minor bug
        }
        int actual_lineno = in.lineno();
        if (VERBOSE)  in.dump("CL");
        EXPECT_EQ(actual_lineno, lineno)  <<LPEQ;
        int len = (int) in.current_line_length();
        EXPECT_EQ(len, (int) strlen(lp))  <<LPEQ;
        int expect_len = (lineno <= full_lines) ? ncols : patlen % (ncols+1);
        EXPECT_EQ(len, expect_len)  <<LPEQ;
        for (int j = 0; j < len; j++) {
          int lc = LC(lineno, j+1);   // 1-based column
          EXPECT_EQ(lc, lp[j])  <<LPEQ;
        }
        if (len != expect_len || len != (int)strlen(lp)) {
          return;  // no error cascades please
        }
        const char* expect_endl = (lineno <= full_lines) ? "\n" : "";
        const char* endl = in.current_line_ending();
        EXPECT_EQ(strlen(expect_endl), strlen(endl))  <<LPEQ << " endl=" << endl;
        EXPECT_STREQ(expect_endl, endl);
        if (VERBOSE)  in.dump("next");
        in.next();
      }

      for (int done_test = 0; done_test <= 3; done_test++) {
        if (done_test == 2)  in.set_done();
        lp = in.current_line();  // should be empty line
        if (VERBOSE)  in.dump("done");
        EXPECT_TRUE(lp != nullptr);
        EXPECT_TRUE(in.done())  <<LPEQ;
        if (!in.done())  break;
        EXPECT_EQ(in.current_line_length(), (size_t)0)   <<LPEQ;
        EXPECT_EQ(strlen(lp), in.current_line_length())  <<LPEQ;
        const char* endl = in.current_line_ending();
        EXPECT_EQ(0, (int)strlen(endl))  <<LPEQ << " endl=" << endl;
        bool extra_next = in.next();
        EXPECT_TRUE(!extra_next)  <<LPEQ;
      }

      // no memory side effects
      EXPECT_EQ(-1, firstdiff(pat, pat2, patlen + 1));
    }
  }
  unlink(temp_file);
}

TEST_VM(istream, coverage) {
#ifdef ASSERT
  istream_coverage_mode(0, cases, total, zeroes);
  if (cases == 0)  return;
  if (VERBOSE || zeroes != 0)
    istream_coverage_mode(-1, cases, total, zeroes);
  EXPECT_EQ(zeroes, 0) << "zeroes: " << zeroes << "/" << cases;
#endif //ASSERT
}

static const char xmlfile[] = {
  "<?xml version='1.0' encoding='UTF-8'?>\n"
  "\n"  // empty line
  " plain text \n"
  "<zeroattrs>\n"
  "<zeroattrs/>\n"
  "<one attr=''/>\n"
  "<two attr1='' attr2=''/>\n"
  "<three attr1='' attr2='' attr3=''/>\n"
  "<our attr1='' attr2='' attr3='' attr4=''/>\n"
  "have some kibbles &amp; bits\n"
  "special escapes for &quot;&amp;&lt;&gt;&apos;\\n&quot;"
     " are &quot;&amp;amp;&amp;lt;&amp;gt;&amp;apos;&amp;#10;&quot;\n"
  "<task level='high &amp; mighty' name='&lt;init&gt;'>\n"
  "<type id='1207' name='void'/>\n"
  "<klass id='1384' name='[Ljava.util.concurrent.ConcurrentHashMap$Node;' flags='1040'/>\n"
  "<squeeze_these_spaces     />\n"
  "<squeeze_these_spaces   a=''    b=''  >\n"
  "\n"  // empty line
  "<has_newlines attr=' &#10;&#10;  &#10;'/>\n"
  "<method id='1385' holder='1314' name='setTabAt' return='1207' arguments='1384 1205 1383' flags='24' bytes='20' code_compile_id='422' code_compiler='c1' code_compile_level='3' iicount='6816'/>\n"
  "</task>\n"
  "not markup \"here\"\n"
  "not markup >here>\n"
  "<not markup> here\n"
  "&not markup here\n"
  "not markup in any of these: &nbsp; &newline; &GT; &#60;\n"
  "this partial line ends with dollar sign $"
};

TEST_VM(istream, xmlinput) {
  ResourceMark rm;
  const bool VERBOSE = false;
  xmlInput in(xmlfile);
  for (; !in.done(); in.next()) {
    assert(in.has_raw_current_line(), "");
    const char* saved = in.save_raw_current_line();
    if (VERBOSE) {
       tty->print_cr("%d: %d[%d]: %s",
                     in.lineno(), in.scan(), in.attr_count(), saved);
       tty->print("XML%d = ", in.scan()); in.print_on(tty); tty->cr();
       if (in.is_text()) tty->print_cr("TEXT = %s", in.text_line());
    }
    EXPECT_EQ(in.is_markup(), (saved[0] == '<' &&
                               saved[strlen(saved)-1] == '>')) << saved;
    EXPECT_EQ(in.has_attrs(),
              xmlInput::has_attrs(in.scan()) && strstr(saved, "='")) << saved;
    stringStream ss;
    in.print_on(&ss);
    if (strstr(saved, "not markup")) {
      // Broken XML is passed as plain text; this is a feature not a bug.
      // It allows config files to behave as if XML is auto-detected.
      EXPECT_TRUE(in.is_text());
      EXPECT_STREQ(saved, in.text_line());
      EXPECT_STRNE(ss.base(), saved);  // escapes get added!
    } else if (strstr(saved, "squeeze_these_spaces")) {
      EXPECT_STRNE(ss.base(), saved);
      EXPECT_TRUE(!strstr(ss.base(), "  ")) <<ss.base();
    } else {
      EXPECT_STREQ(ss.base(), saved);
    }
    EXPECT_TRUE(!strstr(ss.base(), " />")) <<ss.base();
    if (strstr(saved, "kibbles")) {
      EXPECT_TRUE(in.is_text()) << saved;
      EXPECT_STREQ(in.text_line(), "have some kibbles & bits") << saved;
    }
    if (strstr(saved, "escapes")) {
      EXPECT_TRUE(strstr(in.text_line(), "\"&<>'\\n\""));
      EXPECT_TRUE(strstr(in.text_line(), "\"&amp;&lt;&gt;&apos;&#10;\""));
    }

    bool got_scan = false;
    int n1; const char* p2; const char* p3;
    #define RESET_NP123 { n1 = -1; p2 = "p2"; p3 = "p2"; }

    bool has_task = strstr(saved, "<task");
    bool has_task2 = has_task || strstr(saved, "</task");
    got_scan = in.has_tag("task");
    EXPECT_EQ(got_scan, has_task2) << saved;
    got_scan = in.scan_elem("task");
    EXPECT_EQ(got_scan, has_task2) << saved;
    got_scan = in.scan_elem("* ");
    EXPECT_EQ(got_scan, !in.is_text()) << saved;
    got_scan = in.scan_elem("* *='*'");
    EXPECT_EQ(got_scan, in.has_attrs()) << saved;
    got_scan = in.scan_elem("* %p%n='*'", &p2, &n1);
    EXPECT_EQ(got_scan, in.has_attrs()) << saved;
    if (got_scan)  EXPECT_EQ(n1, (int)strlen(p2));
    got_scan = in.scan_elem("* *='%p%n'", &p2, &n1);
    EXPECT_EQ(got_scan, in.has_attrs()) << saved;
    EXPECT_EQ(has_task, in.scan_elem("task *='*'"));
    if (has_task) {
      // <task level='high &amp; mighty' name='&lt;init&gt;'>
      EXPECT_TRUE(!in.scan_elem("tas *='*'")) << saved;
      EXPECT_TRUE(in.scan_elem("task %n='*' %p='*'", &n1, &p2)) << saved;
      if (VERBOSE)  tty->print_cr("n1=%d p2=%s", n1, p2);
      EXPECT_TRUE(n1 >= 0) << saved;
      EXPECT_EQ(n1, in.attr_index(in.attr_name(n1))) << saved;
      EXPECT_TRUE(n1 != in.attr_index(p2)) << saved;
      got_scan = in.scan_elem("task %nname='%p' level='high %p'", &n1, &p2, &p3);
      if (VERBOSE)  tty->print_cr("n1=%d p2=%s p3=%s", n1, p2, p3);
      EXPECT_TRUE(got_scan) << saved;
      if (VERBOSE)  tty->print_cr("n1=%d p2=%s p3=%s", n1, p2, p3);
      EXPECT_STREQ("name", in.attr_name(n1));
      EXPECT_STREQ("<init>", in.attr_value(n1));
      EXPECT_STREQ(p2, in.attr_value(n1));
      EXPECT_STREQ(p3, "& mighty");
      EXPECT_TRUE(!in.scan_elem("* kibble='*'"));
    }
    got_scan = in.scan_elem("has_newlines *='%p'", &p2);
    EXPECT_EQ(got_scan, strstr(saved, "newlines") != nullptr);
    if (got_scan)  EXPECT_STREQ(p2, " \n\n  \n");
    if (strstr(saved, "ends with dollar sign")) {
      EXPECT_EQ('$', in.text_line()[in.text_length()-1]);
    }

    for (int total = 0; total <= 1; total++) {
      n1 = -2; p2 = "-";
      if (total)
        got_scan = in.scan_elem("*? name?='%n%p'", &n1, &p2);
      else
        got_scan = in.scan_elem("* name='%n%p'", &n1, &p2);
      EXPECT_EQ(got_scan, total || in.has_attr("name")) << saved;
      EXPECT_EQ(n1,    !got_scan ? -2  : in.attr_index("name")) << saved;
      EXPECT_STREQ(p2, !got_scan ? "-" : in.attr_value("name")) << saved;
    }
    RESET_NP123;

    const int skip = 1;
    int scan_count = skip;
    n1 = -2; p2 = "-";
    got_scan = in.scan_elem(scan_count, "* %p='*'" // tag + 1 required attr
                            " *?='' ?=''"          // 2 optional attrs
                            " ?='%n'",
                            &p2, &n1);  // n1=fourth, optional
    EXPECT_EQ(got_scan, in.attr_count() >= 2) << saved;
    int expect_scan_count = skip + (!got_scan ? (in.is_text() ? 0 : 1) : (skip < in.attr_count()) ? 4 : 1);
    EXPECT_EQ(scan_count, expect_scan_count) << saved;
    int expect_end_attr = !got_scan ? -2 : (skip+3 < in.attr_count()) ? skip+3 : -1;
    EXPECT_EQ(n1, expect_end_attr) << saved;
    EXPECT_NE(p2, in.attr_name(0)) << saved;
    EXPECT_STREQ(p2, !got_scan ? "-" : in.attr_name(skip)) << saved;
  }
}

