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
 *
 */

#include "precompiled.hpp"
#include "memory/resourceArea.hpp"
#include "utilities/xmlinput.hpp"

// Pick apart the edges of the line.  Also, if it is text, replace the
// Special Six escapes with their chars.  Do not parse attributes yet.
// That only happens if someone "likes" the tag enough to start asking
// for attributes.
void xmlInput::do_scan() {
  size_t ll = 0;
  char* lp = input().current_line(ll);
  LineKind lk = TEXT;
  int toff = 0, tend = 0;
  int acount = 0;
  if (ll >= 2 && lp[0] == '<' && lp[ll-1] == '>') {
    toff = 1;
    ll -= 1;
    if (lp[1] == '/')         { lk = TAIL; toff = 2; }
    else if (lp[ll-1] == '/') { lk = ELEM; --ll; }
    else if (lp[ll-1] == '?') { lk = ELEM; --ll; }  // simulate PIs as elems
    else                        lk = HEAD;
    lp[ll] = '\0';
    tend = ll;
    char* etag;
    if (lk != TAIL && (etag = strchr(lp, ' '))) {
      tend = (int)(etag - lp);
      lp[tend] = '\0';
      for (int i = tend; ++i < (int)ll; ) {
        if (lp[i] != ' ') {
          acount = -1;  // not scanned yet: ( a='v')*
          break;
        }
      }
    }
  } else {
    ll = xmlStream::unescape_in_place(lp, ll);
  }
  assert(ll == (size_t)(int)ll, "no overflow");
  xmlInput* mu = const_cast<xmlInput*>(this);
  mu->reset_line_pointers();
  mu->_line_status = status_code(lineno(), (int) lk);
  mu->_line_length = ll;
  mu->_tag_offset = toff;
  mu->_tag_end = tend;
  mu->_attr_count = acount;
  assert((_line_status >> _lineno_shift) == lineno(), "");
  assert((_line_status &     _kind_mask)  == (int)lk, "");
}

static bool is_sane_xml_name_start(char c) {
  if (c >= 'a' && c <= 'z')  return true;
  if (c >= 'A' && c <= 'Z')  return true;
  if (c == '_')              return true;
  return false;  // very strict XML subset
}
#define SANE_XML_NAME_EXCLUSIONS (XML_SPECIAL_SIX "=?/")

void xmlInput::parse_attrs() {
  assert(_attr_count < 0, "not parsed yet");
  _attr_count = 0;
  char* lp = input().current_line();
  int scan = _tag_end + 1, limit = _line_length;
  //tty->print_cr("[%d..%d]%.*s", scan, limit, int(limit-scan), &lp[scan]);
  assert(lp[scan-1] == '\0', "");
  assert(lp[limit] == '\0', "");
  uint aindex = 0;
  #define ACCEPT_ATTR   { _attr_count = ++aindex; }
  #define FAIL_RETURN   { _error_offset = scan; return; }
  #define REQUIRE(p)    { if (!(p))  FAIL_RETURN; }
  while (scan < limit) {
    if (isspace(lp[scan])) { scan++; continue; }

    // make sure we have a place to put the attribute data
    if (aindex >= _attr_index_len) {  // grow it
      if (!grow_attrs())  FAIL_RETURN;
    }
    AVS& attr = _attr_index[aindex];

    // parse at | in <tag... |name='value'...>
    REQUIRE(is_sane_xml_name_start(lp[scan]));
    size_t name_off = scan;
    char* endp = strchr(&lp[name_off], '=');
    REQUIRE(endp != nullptr);
    *endp = '\0';  // overwrite the '='
    scan = endp - lp;
    attr._name_str = &lp[name_off];
    attr._name_size = scan - name_off;
    scan++;

    // parse at | in <tag... name=|'value'...>
    char endq = '\'';
    if (lp[scan] == '\'')  scan++;
    else                   endq = ' ';  // cheesy fallback
    size_t value_off = scan;
    endp = strchr(&lp[value_off], endq);
    size_t value_len;
    if (endq == ' ' && endp == nullptr) {
      lp[limit] = '\0';
      scan = limit;
      value_len = scan - value_off;
    } else {
      REQUIRE(endp != nullptr);
      scan = endp - lp;
      value_len = scan - value_off;
      lp[scan++] = '\0';  // overwrite the '\'' (ending of value)
    }
    char* value_str = &lp[value_off];
    value_len = xmlStream::unescape_in_place(value_str, value_len);
    attr._value_str = value_str;
    attr._value_size = value_len;

    ACCEPT_ATTR;
  }
  #undef ACCEPT_ATTR
  #undef FAIL_RETURN
  #undef REQUIRE
}

void xmlInput::print_on(outputStream* out) {
  const char* s0 = "<";
  const char* s1 = ">";
  const char* tag = this->tag();
  switch (scan()) {
  case TEXT:
    xmlStream::write_escaped(text_line(), text_length(), out);
    return;
  case ELEM:  s1 = *tag == '?' ? "?>" : "/>"; break;
  case TAIL:  s0 = "</"; break;
  case HEAD:  break;
  }
  out->print_raw(s0);
  out->print_raw(tag);
  for (int n = 0; n < attr_count(); n++) {
    out->print_raw(" ");
    out->print_raw(attr_name(n));
    out->print_raw("='");
    xmlStream::write_escaped(attr_value(n), attr_length(n), out);
    out->print_raw("'");
  }
  out->print_raw(s1);
}

bool xmlInput::grow_attrs() {
  size_t newlen = _attr_index_len * 2;
  assert(_attr_index_len < (uint)newlen, "");
  assert(newlen > sizeof(_small_attr_index)/sizeof(_small_attr_index[0]), "");
  AVS* new_index;
  if (_attr_index == &_small_attr_index[0]) {
    new_index = NEW_C_HEAP_ARRAY(AVS, newlen, mtInternal);
    if (new_index != nullptr) {
      ::memcpy(new_index, &_small_attr_index[0], sizeof(_small_attr_index));
    }
  } else {
    new_index = REALLOC_C_HEAP_ARRAY(AVS, _attr_index, newlen, mtInternal);
  }
  if (new_index == nullptr) {
    return false;
  }
  _attr_index = new_index;
  _attr_index_len = newlen;
  return true;
}

xmlInput::~xmlInput() {
  if (_attr_index != &_small_attr_index[0]) {
    void* to_free = _attr_index;
    _attr_index = nullptr;
    FreeHeap(to_free);
  }
}

static const char* find_char(char ch, const char* beg, const char* end) {
  assert(beg != nullptr && end != nullptr && beg <= end, "");
  assert(strlen(beg) >= (size_t)(end - beg), "");
  const char* p = strchr(beg, ch);
  return (p == nullptr || p >= end) ? end : p;
}
static bool starts_with_char(char ch, const char* beg, const char* end) {
  assert(beg != nullptr && end != nullptr && beg <= end, "");
  return beg < end && *beg == ch;
}
static bool starts_with_str(const char* str, const char* beg, const char* end) {
  size_t len = strlen(str);
  if (len <= 1)  return len == 0 || starts_with_char(*str, beg, end);
  assert(beg != nullptr && end != nullptr && beg <= end, "");
  assert(str != nullptr, "");
  if (beg + len > end)  return false;
  return !strncmp(str, beg, len);
}
static const char* find_str(const char* str, const char* beg, const char* end) {
  assert(str != nullptr && *str != '\0', "");
  size_t len = strlen(str);
  if (len == 1)  return find_char(*str, beg, end);
  while (beg + len <= end) {
    beg = find_char(*str, beg, end);
    if (beg == end)  break;
    if (starts_with_str(str, beg, end)) {
      return beg;
    }
  }
  return end;
}
static const char* find_char_class(const char* chars, const char* beg, const char* end) {
  for (const char* cp = chars; *cp != '\0'; cp++) {
    const char* found = find_char(*cp, beg, end);
    if (found != end)  return found;
  }
  return end;
}
static bool is_char_in(char ch, const char* beg, const char* end) {
  return find_char(ch, beg, end) != end;
}
static bool is_char_class_in(const char* chars, const char* beg, const char* end) {
  return find_char_class(chars, beg, end) != end;
}

// Here are some of the scanf option combinations supported.
//        set name     variable name for attribute
//  *    name='*'     *='str'   match all (or all remaining, in a value)
//  %n   name='%n'    %n='...'  store attribute number, on either side of =
//  %p   name='%p'    %p='...'  store attribute or value string, as char*
//  %p%n name='%p%n'            value string as char* then length as int
//  %p+  name='%p %p'           store partial value char*, skip space or punct
//  %d+  name='%d' or '%lld'    partial parse using strtoll (e.g., id='%d')
//  %f+  name='%f' or '%lf'     partial parse using strtod (e.g., stamp='%lf')
//  xyz  name='xyz%p'  xyz=''   parse of literal string, can be mixed with */%p
//
// %n is overloaded, at front it gets the attribute number, otherwise the offset.
// when an attribute name is a wildcard, an arbitrary next attribute is chosen
// See header file for a fuller description.
//
#define DO_SCANF_OPTIONS(FN)                    \
  FN("*",       none,           match_all)      \
  FN(" ",       none,           match_spaces)   \
  FN("%n",      int,            match_position) \
  FN("%ln",     long int,       match_position) \
  FN("%*n",     none,           match_position) \
  FN("%p",      char*,          match_strptr)   \
  FN("%0p",     char*,          match_strptr)   \
  FN("%d",      int,            match_strtol)   \
  FN("%ld",     long int,       match_strtol)   \
  FN("%lld",    long long int,  match_strtol)   \
  FN("%x",      int,            match_strtol)   \
  FN("%lx",     long int,       match_strtol)   \
  FN("%llx",    long long int,  match_strtol)   \
  FN("%i",      int,            match_strtol)   \
  FN("%li",     long int,       match_strtol)   \
  FN("%lli",    long long int,  match_strtol)   \
  FN("%f",      float,          match_strtod)   \
  FN("%lf",     double,         match_strtod)   \
  FN("%%",      none,           match_literal)  \
  FN("%",       none,     bad_percent_pattern)  \
  FN(""/*xyz*/, none,           match_literal)  \
  /**/

// Big old state machine to implement crunchy scanf goodness.
class XMLPartialScanner {
  // constant configuration
  xmlInput&     _in;            // stream being queried by _in.scan(...)
  const char* const _fmt_base;   // base pointer into scanf format string
  const char* const _fmt_limit;  // limit in scanf format string, points at '\0'

  // advancing state through the T/A/V segments in /T( A='V')*/
  const char*   _fp_base;       // base of current segment (tag, name, or value)
  const char*   _fp_limit;      // limit of current segment
  const char*   _fp_next_base;  // base of next segment
  bool          _total_match;   // pattern like a?='%n' instead of a='%n'
  const char*   _prematch0;     // pattern part which was pre-matched
  const char*   _prematch1;     // end of pre-matched pattern part

  // matching state machine:
  const char*   _fp;            // pointer into scanf format
  const char*   _fp0;           // previous pointer into scanf format
  char          _which;         // <T A='V'> or E for end or F for failure
  int           _attr_num;      // -1 for T
  const char*   _base;          // tag name, attr name, attr value to match
  size_t        _limit;         // number of chars after _base
  size_t        _scan;          // offset we are scanning at
  size_t        _last_n;        // value of _scan at more recent previous %n

  // a pattern with one of these is a total match
 public:
  XMLPartialScanner(xmlInput& in, const char* fmt, size_t fmt_len)
    : _in(in), _fmt_base(fmt), _fmt_limit(&fmt[fmt_len])
  {
#ifdef ASSERT
    // these are set up by next_segment:
    _which = '\0';
    _fp_next_base = _fp_base = _fp_limit = nullptr;
    _total_match = false;
    // data scan is set up by load_tag/attr/value:
    load_common(-1, nullptr, 0);
    // these set up by match:
    _prematch0 = _prematch1 = _fp = _fp0 = nullptr;
#endif
    // get ready for an immediate call to load_tag:
    next_segment('T');
  }

  bool is_done() { return _which == 'E'; }
  bool is_failed() { return _which == 'F'; }

  bool total_match() { return _total_match; }

  // This logic breaks a format string like "tag n1='v1' n2='v2'"
  // into successive segments T/N/V... in the pattern "T (N='V')*".
  // Each of the T/N/V segments is a scanf-like format pattern.
  // The function set up _fp_base and _fp_limit for one T/N/V,
  // which prepares for the match function to do its work on
  // a tag name, attribute name, or attribute value.
  bool next_segment(char which) {
    if (_which == 'F')  return false;  // sticky failure
    _fp = _fp0 = nullptr;  // force bad_scanf_syntax to look at _fp_base
    switch (which) {
    case 'T':
      assert(_which == '\0', "");          // do this just once
      _which == 'F';
      _fp_base = _fmt_base;
      _fp_limit = find_char(' ', _fp_base, _fmt_limit);
      _fp_next_base = _fp_limit;
      if (_fp_next_base < _fmt_limit) {
        ++_fp_next_base;  // skip the terminating space
      }
      // If a tag is marked as total match, then TEXT lines can match,
      // as long every attribute pattern is also a total match.
      _total_match = (_fp_limit[-1] == '?');
      if (_total_match)  --_fp_limit;
      if (_fp_base == _fp_limit ||    // empty tag
          (!is_sane_xml_name_start(*_fp_base) &&  // insane tag
           !strchr("%*", *_fp_base)) ||           // and not %p etc.
          is_char_class_in(SANE_XML_NAME_EXCLUSIONS, _fp_base, _fp_limit)) {
        return bad_scanf_syntax("bad tag");
      }
      _which = 'T';
      return true;

    case 'A':
      assert(_which == 'T' || _which == 'V', "");
      _which = 'F';
      _fp_base = _fp_next_base;
      while (starts_with_char(' ', _fp_base, _fmt_limit)) {
        ++_fp_base;
      }
      if (_fp_base == _fmt_limit) {
        _fp_limit = _fmt_limit;
        _which = 'E';   // mark done with success
        return false;   // no more attributes
      }
      // tag name|='value'
      _fp_next_base = _fp_limit = find_str("='", _fp_base, _fmt_limit);
      _total_match = (_fp_limit[-1] == '?');
      if (_total_match)  --_fp_limit;
      if ((!_total_match && _fp_limit == _fp_base) ||
          _fp_next_base == _fmt_limit) {
        return bad_scanf_syntax("missing attribute name");
      }
      if (!(is_sane_xml_name_start(*_fp_base) ||  // insane attr atrt
            strchr("%*", *_fp_base) ||            // and not %p= etc
            _fp_limit == _fp_base) ||             // and not plain ?=
          is_char_class_in(SANE_XML_NAME_EXCLUSIONS, _fp_base, _fp_limit)) {
        return bad_scanf_syntax("bad attribute name");
      }
      if (_fp_next_base < _fmt_limit) {
        _fp_next_base += 2;  // skip "='"
      }
      _which = 'A';
      return true;

    case 'V':
      assert(_which == 'A', "");
      _which = 'F';
      _fp_base = _fp_next_base;   // set up to be just after the open-quote
      // there must be a close-quote to match it
      _fp_limit = find_char('\'', _fp_base, _fmt_limit);
      // tag name='value|'
      if (_fp_limit == _fmt_limit) {
        return bad_scanf_syntax("no closing ' for attribute");
      }
      _fp_next_base = _fp_limit + 1;
      _which = 'V';
      return true;
    }
    _which = 'F';  // mark failure
    return false;
  }

  void load_tag() {
    assert(_which == 'T', "");
    const char* tag = _in.tag();
    if (tag == nullptr)  tag = "";
    load_common(-1, tag, strlen(tag));
  }
  void load_attr(int attr_num) {
    assert(_which == 'A', "");
    if (attr_num < 0) {
      load_for_missing_attr();
    } else {
      const char* name = _in.attr_name(attr_num);
      load_common(attr_num, name, strlen(name));
    }
  }
  void load_for_missing_attr() {
    assert(_total_match, "");
    load_common(-1, nullptr, 0);
  }
  void load_value(int attr_num) {
    assert(_which == 'V', "");
    if (attr_num < 0) {
      load_for_missing_attr();
    } else {
      const char* value = _in.attr_value(attr_num);
      load_common(attr_num, value, strlen(value));
    }
  }

  bool finish_segment(va_list& ap) {
    if (_which == 'F')  return false;
    bool status = match(ap);
    // we just used up ap; avoid looking at the corresponding formats
    DEBUG_ONLY(_fp = _fp_base = _fp_limit = nullptr);
    if (_which == 'T' && !_total_match && _limit == 0) {
      status = false;  // empty tag from a text line
    }
    return status;
  }

  // Return the literal name if it exists.
  const char* literal_name(size_t& length) {
    const char* fp = _fp_base;
    const char* next_fp = fp;
    // a literal name can begin with %n escapes
    while ((next_fp = skip_conv(fp, 'n')) > fp) {
      fp = next_fp;
    }
    const char* result = fp;
    fp = skip_plain_chars(fp);
    size_t result_len = fp - result;
    // a literal name can also end with %n escapes
    while ((next_fp = skip_conv(fp, 'n')) > fp) {
      fp = next_fp;
    }
    // Any other escapes or wildcards will spoil a literal name.
    // (Thankfully, there is no special case for "%%", since the
    // percent sign is not a name character for us.)
    if (fp == _fp_limit && result_len > 0) {
      length = result_len;
      return result;
    } else {
      return nullptr;
    }
  }

  bool bad_scanf_syntax(const char* what = nullptr) {
#ifdef ASSERT
    if (_fp0 == nullptr)  _fp0 = _fp_base;
    // fix your code, O Hotspot developer
    stringStream st(200);
    st.print("[xmlInput::scan_elem]"
             " bad scan format \"%s\" (position=%d)",
             _fmt_base, (int)(_fp0 - _fmt_base));
    if (what != nullptr)  st.print(": %s", what);
    //fprintf(stderr, "*** %s\n", st.base());
    fatal("%s", st.base());
#endif
    return false;
  }

 private:
  template<typename RES>
  void store_result(va_list& ap, RES result) {
    if (is_empty_result(result))  return;
    RES* destination = va_arg(ap, RES*);
    *destination = result;
    // This only supports scalars, not arrays like "%.10s" or "%c".
    // To get separate words, try "%d %p", wth %p collecting the tail.
    // Note that "%p %p" works but the first %p will lack a final '\0'.
  }

  bool is_first_format()  { return _fp0 == _fp_base; }
  bool is_last_format()   { return _fp  == _fp_limit; }

  bool consume_format(const char* what) {
    int what_len = strlen(what);
    const char* fp = _fp;
    if (fp + what_len > _fp_limit)        return false;
    if (memcmp(fp, what, what_len) != 0)  return false;
    _fp = fp + what_len;
    return true;
  }

  typedef void*** no_conversion;
  template<typename RES>
  bool is_empty_result(RES result)           { return false; }
  bool is_empty_result(no_conversion result) { return true; }

  /* void pm() {
    tty->print("match[%.*s|%.*s|%.*s|%.*s] ",
               int(_fp_base-_fmt_base), _fmt_base,
               int(_fp-_fp_base), _fp_base,
               int(_fp_limit-_fp), _fp,
               int(_fmt_limit-_fp_limit), _fp_limit);
  } */

  // this call assumes caller has already loaded a tag/value/attr
  bool match(va_list& ap) {
    _fp = _fp_base;     // scan pointer in format specifiers
    _scan = 0;          // scan cursor within tag/attr/value
    _last_n = 0;        // start counting bytes for %n here
    while (_fp < _fp_limit) {
      _fp0 = _fp;       // remember current scan format
      typedef no_conversion none;
      #define SCANF_OPTION(what, type, matcher)        \
        if (what[0] == keyc && consume_format(what)) { \
          if (!matcher(ap, (type*)0))  return false;   \
          continue;                                    \
        }
      char keyc = *_fp;
      switch (keyc) {
      case '%':
        DO_SCANF_OPTIONS(SCANF_OPTION); break;
      case '*': case ' ':
        DO_SCANF_OPTIONS(SCANF_OPTION); break;
      default:
        keyc = '\0';  // enable default "plain literal" matcher
        DO_SCANF_OPTIONS(SCANF_OPTION);
      }
      #undef SCANF_OPTION
      return bad_scanf_syntax();
    }
    if (_scan == _limit ||      // should have consumed all input
        _total_match) {         // but total match allows empty input
      return true;
    }
    return false;
  }
  bool match_all(va_list& ap, no_conversion* TYP) {
    if (!must_be_last("* must be last"))  return false;
    _scan = _limit;
    return true;
  }
  bool match_spaces(va_list& ap, no_conversion* TYP) {
    if (!must_be_simple("no spaces in names"))  return false;
    while (_scan < _limit && isspace(_base[_scan])) {
      ++_scan;
    }
    return true;
  }
  template<typename RES>
  bool match_position(va_list& ap, RES* TYP) {
    if (is_first_format()) {   //%n in first position report attr number
      if (_which == 'T') {
        return bad_scanf_syntax("initial %n cannot apply to tag; use %p or %p%n");
      }
      // at this point _scan==0, which would be a useless fact to report
      size_t result = _attr_num;  // ambient attribute
      store_result(ap, (RES)result);
      if (is_last_format()) {
        _scan = _limit;  // implicit wildcard after lone %n
      }
      return true;
    }
    // non-initial %n counts characters
    if (_total_match && !must_be_simple("no %n counts in total patterns")) {
      return false;
    }
    size_t result = _scan - _last_n;
    _last_n = _scan;
    store_result(ap, (RES)result);
    return true;
  }
  bool match_strptr(va_list& ap, char** TYP) {
    bool null_terminate = starts_with_str("%0p", _fp0, _fp) && _which == 'V';
    char limitc = prematch_char();
    if (limitc || _scan > 0) {   // this is part of a larger pattern
      if (!must_be_simple("no partial matches in names"))  return false;
    }
    if (_base == nullptr) {
      assert(!limitc && _total_match, "");
      store_result(ap, nullptr);
      return true;
    }
    const char* result = &_base[_scan];
    if (!limitc) {
      _scan = _limit;   // if there is nothing to stop us, take it all
    } else if (limitc == ' ') {
      // If %d or * or ' ' is lookahead, it will consume spaces for
      // us, so we can stop at a space.  This can get us into trouble,
      // of course, since it might not consume enough, but that's how
      // scanf("%s") works, and why scanf("%[asdf]") is a thing.
      while (_scan < _limit && !isspace(_base[_scan])) {
        ++_scan;
      }
      _prematch0 = nullptr;  // cancel prematch but consume a space anyway
      if (null_terminate && _scan < _limit) {
        const_cast<char*>(_base)[_scan++] = '\0';  // overwrite the first space
      }
    } else {
      // lame one-character lookahead, such as "%p%n,%p" or "%0p,%0p"
      while (_scan < _limit) {
        if (_base[_scan] == limitc)  break;
        ++_scan;
      }
      if (_scan >= _limit) {
        _prematch0 = nullptr;  // cancel prematch
      } else if (null_terminate) {
        const_cast<char*>(_base)[_scan++] = '\0';  // overwrite the prematch char
      }
    }
    store_result(ap, result);
    return true;
  }
  template<typename RES>
  bool match_strtol(va_list& ap, RES* TYP) {
    if (!must_be_simple("no numerals in names"))  return false;
    char cc = _fp[-1];
    assert(strchr("dxi", cc), "");
    int base = (cc == 'd' ? 10 : cc == 'x' ? 0x10 : 0);
    char* p = (char*) &_base[_scan];
    char* q = p;
    long long result = strtoll(p, &q, base);
    if (p == q)   return false;  // did not match any digits
    _scan += (q - p);
    store_result(ap, (RES)result);
    return true;
  }
  template<typename RES>
  bool match_strtod(va_list& ap, RES* TYP) {
    if (!must_be_simple("no numerals in names"))  return false;
    char* p = (char*) &_base[_scan];
    char* q = p;
    long long result = strtod(p, &q);
    if (p == q)   return false;  // did not match any digits
    _scan += (q - p);
    store_result(ap, (RES)result);
    return true;
  }
  bool match_literal(va_list& ap, no_conversion* TYP) {
    if (_fp0 == _prematch0) {
      _fp = _prematch1;
      _prematch0 = nullptr;
      return true;  // this one literal was already matched
    }
    const char* p = _fp0;
    const char* q = _fp;
    assert(p < _fp_limit, "");
    if (*p == '%') {
      assert(q == p+2, "%% sequence");
      ++p;  // disregard the first of the two
    } else {
      assert(p == q, "");
    }
    if (_which != 'V' && !is_sane_xml_name_start(*p)) {
      return bad_scanf_syntax("no special characters in names");
    }
    q = skip_plain_chars(q);
    _fp = q;  // update pattern pointer; usually consume_format does this
    size_t len = q - p;
    int esc_len; char unesc;
    if (p == q && looking_at_escape(p, esc_len, unesc)) {
      p = &unesc;
      len = 1;
      _fp += esc_len;
    } else {
      assert(p < q, "");   // must make progress
    }
    //tty->print("literal[%.*s]:[%.*s] ", int(len), p, int(_limit-_scan), &_base[_scan]);
    if (_base == nullptr) {
      assert(_total_match, "");
      if (_which == 'A')  {
        ++_scan; return true;  // pretend we consumed something
      }
      return must_be_simple();
    }
    if (_scan + len > _limit)  return false;
    if (memcmp(&_base[_scan], p, len) != 0)  return false;
    _scan += len;
    return true;
  }

  bool bad_percent_pattern(va_list& ap, no_conversion* TYP) {
    return bad_scanf_syntax("unknown % pattern");
  }

  bool must_be_simple(const char* what = nullptr) {
    if (_which == 'V') {
      if (!_total_match)  return true;
      what = "pattern must be total after ?=";
    }
    // only simple stuff Y in Y='Z' or X?='Y'
    return bad_scanf_syntax(what);
  }
  bool must_be_last(const char* what) {
    if (is_last_format())  return true;
    bad_scanf_syntax(what);
    return false;
  }

  void load_common(int attr_num, const char* base, size_t limit) {
    _attr_num = attr_num;
    _base = base;
    _limit = limit;
    _scan = 0;
  }

  bool looking_at_escape(const char* fp, int& esc_len, char& unesc) {
    if (*fp != '&')  return false;
    int len = _fp_limit - fp;
    if (len > xmlStream::MAX_ESCAPE_LEN) {
      len = xmlStream::MAX_ESCAPE_LEN;
    }
    return 0 == xmlStream::find_escape(fp, len, esc_len, unesc);
  }

  const char* skip_plain_chars(const char* fp) {
    for (; fp < _fp_limit; fp++) {
      switch (*fp) {
      case '*': case ' ': case '%':
        return fp;
      case '&':   // &amp; is not plain if it is an escape
        char ignore_ch;
        int ignore_len;
        if (looking_at_escape(fp, ignore_len, ignore_ch)) {
          return fp;
        }
      }
    }
    return fp; 
  }

  const char* skip_conv(const char* fp, char skipc) {
    const char* fp0 = fp;
    if (fp < _fp_limit && *fp == '%') {
      ++fp;
      while (fp < _fp_limit && (*fp == 'l' || *fp == '*')) {
        ++fp;   // skip the stuff in the middle, as in %*n or %lld
      }
      if (fp < _fp_limit && *fp == skipc) {
        return fp+1;
      }
    }
    return fp0;
  }

  // Skipping %n, look ahead for a literal character match.
  // If one is found, set _prematch[01] to bracket it.
  // Return null '\0' if none is found.
  char prematch_char() {
    const char* lafp = skip_conv(_fp, 'n');  // skip %n %ln etc.
    if (lafp >= _fp_limit) {
      return '\0';
    }
    char limitc = *lafp;
    _prematch0 = _prematch1 = lafp;
    switch (limitc) {
    case '%':
      if (lafp < _fp_limit && lafp[1] == '%') {
        _prematch1 += 2;
        return limitc;
      }
      // else treat like space, so "%p%d" scans like "%p %d"
      // fall through...
    case '*':
    case ' ':
      _prematch1 += 1;
      return ' ';

    case '&':
      // whether this is true or false, limitc is what we want
      int esc_len;
      if (!looking_at_escape(lafp, esc_len, limitc))
        esc_len = 1;
      _prematch1 += esc_len;
      return limitc;
    }
    _prematch1 += 1;
    return limitc;
  }
};

bool xmlInput::va_scan_elem(const char* format, va_list ap) {
  int ignore = 0;
  return va_scan_elem(ignore, format, ap);
}

bool xmlInput::va_scan_elem(int &next_attr,
                            const char* format,
                            va_list ap) {
  if (!is_markup() && !strchr(format, '?'))  return false;  // optimization
  const bool VERBOSE = false;
  XMLPartialScanner scan(*this, format, strlen(format));
  if (VERBOSE) {
    tty->print("scan[%d;%s]", next_attr, format);
    print_on(tty); tty->print(" -- ");
  }
  scan.load_tag();
  if (!scan.finish_segment(ap)) {
    if (VERBOSE)  tty->print_cr("(wrong tag)\n");
    return false;
  }
  bool saw_literal_name = false;
  bool saw_sequential_name = (next_attr != 0);
  for (;;) {
    // The first thing after the tag format must be either null or
    // space, and likewise the first format after every /foo='bar'/.
    if (!scan.next_segment('A'))  break;
    size_t fn_len;
    const char* fn = scan.literal_name(fn_len);
    int this_attr = -1;
    if (fn != nullptr) {  // there is at most one candidate attribute
      this_attr = attr_index(fn, fn_len);  // might be -1
      saw_literal_name = true;
    } else {
      this_attr = next_attr++;  // something like %n='*' or %p='%p'
      if (this_attr >= attr_count())  this_attr = -1;
      saw_sequential_name = true;
    }
    if (saw_literal_name && saw_sequential_name) {
      scan.bad_scanf_syntax("bad mix of sequential and literal names");
      break;
    }
    if (VERBOSE)  tty->print("A[%s%d]=%s%.*s ",
                             fn ? "" : "#",
                             fn ? this_attr : next_attr-1,
                             this_attr < 0 ? "?" : "",
                             (int)
                             (this_attr >= 0 ? strlen(attr_name(this_attr))
                              : fn ? fn_len : 1),
                             (this_attr >= 0 ? attr_name(this_attr)
                              : fn ? fn : "?")); 
    if (this_attr < 0 && !scan.total_match())  break;
    scan.load_attr(this_attr);
    if (!scan.finish_segment(ap))  break;

    // Now look at the value
    if (!scan.next_segment('V'))  break;
    if (VERBOSE) tty->print("V=%s ", this_attr < 0 ? "" : attr_value(this_attr));
    scan.load_value(this_attr);
    if (!scan.finish_segment(ap))  break;
  }
  if (VERBOSE)  tty->print_cr("(%s)", scan.is_done() ? "success" : "failure");
  return scan.is_done();  // did we get all the way to the end?
}

bool xmlInput::scan_elem(const char* format, ...) {
  if (!is_markup() && !strchr(format, '?'))  return false;  // optimization
  bool status = false;
  va_list ap;
  va_start(ap, format);
  status = va_scan_elem(format, ap);
  va_end(ap);
  return status;
}

bool xmlInput::scan_elem(int &next_attr, const char* format, ...) {
  if (!is_markup() && !strchr(format, '?'))  return false;  // optimization
  bool status = false;
  va_list ap;
  va_start(ap, format);
  status = va_scan_elem(next_attr, format, ap);
  va_end(ap);
  return status;
}

