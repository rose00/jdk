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

#ifndef SHARE_UTILITIES_XMLINPUT_HPP
#define SHARE_UTILITIES_XMLINPUT_HPP

#include "utilities/istream.hpp"
#include "utilities/xmlstream.hpp"

// Input streams for reading XML-flavored data
//
class xmlInput : public CHeapObjBase {
 private:
  NONCOPYABLE(xmlInput);

 public:
  enum LineKind {
    TEXT = 0,
    HEAD = 1,
    TAIL = 2,
    ELEM = HEAD+TAIL,
  };

 protected:
  static constexpr int _kind_mask = 3, _kind_valid = 4, _lineno_shift = 3;
  static uint status_code(int lineno, int kind) {
    return (lineno << _lineno_shift) | _kind_valid | (kind & _kind_mask);
  }

  struct AVS {
    size_t _name_size;
    char*  _name_str;
    size_t _value_size;
    char*  _value_str;
  };

  inputStream _input;
  uint  _line_status;
  uint  _line_length;
  short _tag_offset;
  short _tag_end;
  uint  _error_offset;
  int   _attr_count;        // -1 means not scanned yet
  AVS*  _attr_index;        // growable, expands into C heap
  uint  _attr_index_len;    // allocated length of index
  AVS   _small_attr_index[5 DEBUG_ONLY(*0 + 2)];

  bool need_scan() const {
    // Make sure the line number has not changed since the last do_scan.
    // Also, make sure the valid bit is set.  Don't try to predict the kind,
    // but rather force it to all-one-bits (s|kind_mask).
    return status_code(lineno(), _kind_mask) != (_line_status | _kind_mask);
  }

  void do_scan();

  int definite_kind() const {
    return need_scan() ? -1 : _line_status & _kind_mask;
  }

  void parse_attrs();  // parse a bunch of / name='val'/ to end of line 
  bool grow_attrs();   // increase size of _attr_index
  
  void reset_line_pointers() {
    _line_status = _line_length = _error_offset = _attr_count = 0;
    _tag_offset = _tag_end = 0;
  }

public:
  // just forward all the constructor arguments to the wrapped line-input class
  template<typename... Arg>
  xmlInput(Arg... arg)
    : _input(arg...)
  {
    reset_line_pointers();
    _attr_index = &_small_attr_index[0];
    _attr_index_len = sizeof(_small_attr_index) / sizeof(_small_attr_index[0]);
  }

  ~xmlInput();

  // Return the current line if it has not yet been scanned, or else null.
  char* raw_current_line() const {
    return has_raw_current_line() ? input().current_line() : nullptr;
  }

  // Return true if the current line is "raw": it has not yet been
  // scanned for XML markup.
  bool has_raw_current_line() const {
    return need_scan();
  }

  // The raw line to a resource or C-heap array as requested.
  char* save_raw_current_line(bool c_heap = false) const {
    assert(has_raw_current_line(), "must call this before scanning");
    return input().save_line(c_heap);
  }

  int lineno() const {
    return input().lineno();
  }

  const inputStream& input() const { return _input; }
  inputStream&       input()       { return _input; }

  bool next() { _line_status = 0; return input().next(); }
  bool done() { _line_status = 0; return input().done(); }

  LineKind scan() const {
    if (need_scan()) {
      xmlInput* mu = const_cast<xmlInput*>(this);
      mu->do_scan();
    }
    return static_cast<LineKind>(_line_status & _kind_mask);
  }

  // discrimination of various markup (non-TEXT) flavors:
  static bool is_text(LineKind lk)   { return lk == TEXT; }
  static bool is_markup(LineKind lk) { return lk != TEXT; }
  static bool does_push(LineKind lk) { return lk == HEAD; }
  static bool does_pop(LineKind lk)  { return lk == TAIL; }
  static bool has_attrs(LineKind lk) { return (lk & HEAD) != 0; }

  bool is_text() const   { return is_text(scan()); }
  bool is_markup() const { return is_markup(scan()); }
  bool does_push() const { return does_push(scan()); }
  bool does_pop() const  { return does_pop(scan()); }
  bool has_attrs() const { return attr_count() != 0; }

  char* text_line() const {
    assert(definite_kind() == TEXT, "");
    return input().current_line();
  }
  size_t text_length() const {
    assert(definite_kind() == TEXT, "");
    return _line_length;
  }
  char* text_line(size_t &length) const {
    length = text_length();
    return text_line();
  }

  const char* tag() {
    return _tag_offset == 0 ? nullptr : input().current_line() + _tag_offset;
  }
  bool has_tag(const char* tag) {
    return is_markup() && !strcmp(this->tag(), tag);
  }

  // report the number of attributes on the current line
  int attr_count() const {
    if (_attr_count < 0) {
      xmlInput* mu = const_cast<xmlInput*>(this);
      mu->parse_attrs();
      assert(_attr_count >= 0, "");
    }
    return _attr_count;
  }
  // determine if an attribute of the given name exists
  bool has_attr(const char* name) {
    return attr_index(name) >= 0;
  }
  // return the name of the nth attr, or nullptr if none
  // optional second parameter is a place to store the string size
  const char* attr_name(int n, size_t* lenp = nullptr) const {
    return attr_indexer(n, 0, lenp);
  }
  // return the value of the nth attr, or nullptr if none
  // optional second parameter is a place to store the string size
  const char* attr_value(int n, size_t* lenp = nullptr) const {
    return attr_indexer(n, 1, lenp);
  }
  // return the value of the named attr, or nullptr if none
  // second parameter is place to store the length, or nullptr
  // same as attr_value(attr_index(name), lenp)
  const char* attr_value(const char* name, size_t* lenp = nullptr) const {
    return attr_indexer(attr_index(name), 1, lenp);
  }
  // return the length of the attribute's value or zero if none
  size_t attr_length(int n) const {
    size_t length = 0;
    attr_indexer(n, 1, &length);
    return length;
  }
  // return the index of the attribute with the given name or -1 if none
  int attr_index(const char* name) const {
    if (name == nullptr)  return -1;
    return attr_index(name, strlen(name));
  }
  // optional second parameter gives the name length
  int attr_index(const char* name, size_t name_len) const {
    if (name == nullptr || name_len == 0)  return -1;
    for (int n = 0; n < attr_count(); n++) {
      const AVS& avs = _attr_index[n];
      if (avs._name_size == name_len &&
          !strncmp(avs._name_str, name, name_len)) {
        return n;
      }
    }
    return -1;
  }
 private:
  const char* attr_indexer(int n, int is_value, size_t* lenp = nullptr) const {
    if (n < 0 || n >= attr_count())  return nullptr;
    const AVS& avs = _attr_index[n];
    if (lenp != nullptr) {
      *lenp = is_value ? avs._value_size : avs._name_size;
    }
    return    is_value ? avs._value_str  : avs._name_str;
  }

 public:
  // scanf-like interface for parsing XML elements
  //
  // scan_elem("t a='x' b='y'") will match "<t b='y' c='z' a='x'/>"
  //
  // scan_elem("%p a='%n' b='%p'", &p0, &n1, &p2) will store p0=tag(),
  // n1=attr_index("a"), and p2="y", p2 a null-terminated reference
  // into the line.
  //
  // Scan patterns apply uniformly to tag names, attribute names, and
  // attribute values.  They may be of any of the following formats:
  //
  //   "%n"     at the start, stores an attribute number, else an int length
  //   "%ln"    at the start, stores an attribute number, else a long length
  //   "%p"     points at all the remaining text
  //   "%0p"    points at all the remaining text, also writes '\0' at end
  //   "%p%n"   points at all the remaining text, and stores its int length
  //   "%p%ln"  points at all the remaining text, and stores its long length
  //   "%d"     scans an int decimal literal, as by scanf or strtol
  //   "%ld"    scans a long decimal literal, as by scanf or strtol
  //   "%lld"   scans a long long decimal literal, as by scanf or strtoll
  //   "%x"     scans an int hex literal, as by scanf or strtol
  //   "%lx"    scans a long hex literal, as by scanf or strtol
  //   "%llx"   scans a long long hex literal, as by scanf or strtoll
  //   "%i"     scans an int literal, as by scanf or strtol
  //   "%li"    scans a long literal, as by scanf or strtol
  //   "%lli"   scans a long long literal, as by scanf or strtoll
  //   "%f"     scans a float literal, as by scanf or strtod
  //   "%lf"    scans a double literal, as by scanf or strtod
  //   "%%"     matches a single literal '%' character, as in scanf
  //   " "      greedy match of /[[:space:]]*/, as in scanf
  //   "*"      greedy match of all remaining chars (in name or value)
  //   "&apos;" matches a literal '\'' character (same as "&#10;", etc.)
  //   (char)   anything other character is a literal to be matched exactly
  //
  // Any of the Special Six escapes literally matches the unescaped
  // character.  This gives a way to match "'" (as "&apos;").  The
  // other five escapes are optional, since their characters cannot
  // disturb the parsing of a scan format.
  //
  // The %n pattern is contextual.  If it occurs before any other
  // pattern, it reports the ambient attribute number (so it cannot be
  // used this way on the tag name).  Otherwise, it reports the number
  // of chars since the last %n.  A %*n discards any pending count.
  // An %n that is alone in a value pattern always succeeds (without
  // inspecting the value) and stores the attribute number.
  //
  // The %p pattern stores a pointer into the current line where the
  // current scan occurs.  Unlike %0p, %p does not store a null '\0'
  // or change the line in any other way, so multiple %p results in a
  // single name or value will share overlap.  The scanner ensure a
  // null '\0' at the end of all tag and attribute names and attribute
  // values, overwriting whatever XML punctuation was natively present
  // at first.  Therefore, a lone %p, a final %p, and an intermediate
  // %0p will always report a properly null terminated string.
  //
  // The %p is contextual as well.  If it appears last (except for a
  // possible %n) it will match all remaining chars.  Otherwise the %p
  // will try to stop matching (stop before consuming all the chars)
  // in a way that leaves open a possibility for the following pattern
  // (not counting an immediately following %n also).  Specifically,
  // if %p is followed by a non-space literal character, it will stop
  // before matching any such character.  Otherwise, it will stop
  // before matching any whitespace.  This mildly useful behavior is
  // like that of %s in scanf, except that with scanf %s never matches
  // a whitespace character.  Thus, if an attribute value is "a,b,c,d"
  // and the scan pattern is "%p,%p,%p", the three pointer will all
  // point into the same string, at "a,b,c,d", then "b,c,d", and
  // finally "c,d".  To get useful lengths for the first two results,
  // scan with "%p%n,%p%n,%p".  (A final %n would be harmless.)
  //
  // There are two ways to match tag and attribute names, either
  // literally or sequentially.  A literal name pattern is one which
  // contains only regular textual characters, with absolutely no
  // occurrence of "*" or "%" or "&" or whitespace.  It may contain
  // %n at the beginning and/or the end, but not the middle.  That
  // name is exactly matched to the tag or attribute name; this
  // obviously selects the required attribute, and the attribute
  // match will fail if the attribute is not present.
  //
  // A sequential name is a pattern matched against a particular
  // string, with no attribute lookup.  The string is either the tag
  // name, or the name of the next attribute in textual sequence,
  // starting with attribute zero (the leftmost on the input line).
  // If a name attribute is not literal, it must match any possible
  // string.  That means it must be of a single form (%p, %n, or *),
  // with no literal characters at all.  Parital matching forms (like
  // %d and mixes like foo*) are never allowed for attribute names.
  //
  // Positional and sequential attribute patterns may not be mixed.
  // The optional attr_start argument provides a way for sequential
  // matching to start at a different attribute.  If there are not
  // enough attributes on the line, the sequential match fails.
  //
  // An individual name/attribute pattern can be made "total" (that
  // is, failure-proof) by adding a question mark '?' to the name
  // pattern.  If there is no matching attribute, the scan will
  // continue as if the indicated attribute had been present and
  // matched the pattern, but -1 or null will be stored for %n and %p.
  // (Besides *, a total pattern can take no other form.)  Thus
  // "foo='%n'" and "foo?='%n'" both store the attribute index of
  // "foo" if there is such an attribute, but if there is no such
  // attribute, the first pattern fails and the second stores -1 and
  // continues.  Likewise, "foo?='%p'" and "foo?='%n%p'" are total and
  // stores either the pointer to the "foo" attribute value, or a null
  // pointer, and also an attribute index for "foo" or -1.
  //
  // The optional argument next_attr is advanced by side effect of the
  // number of attempted (not only successful) attribute matches, and
  // it also gives the number used for the first positional pattern,
  // if any are present.  This feature allows repeated calls to
  // scan_elem to cycle in sequence through all attributes.
  //

  bool    scan_elem(const char* format, ...)        ATTRIBUTE_SCANF(2, 3);
  bool va_scan_elem(const char* format, va_list ap) ATTRIBUTE_SCANF(2, 0);
  bool    scan_elem(int &next_attr,
                    const char* format, ...)        ATTRIBUTE_SCANF(3, 4);
  bool va_scan_elem(int &next_attr,
                    const char* format, va_list ap) ATTRIBUTE_SCANF(3, 0);

  // print an XML-flavored representation of the current line (no newline)
  void print_on(outputStream* out);
};

template<typename BlockClass>
class xmlBlockInputStream : public xmlStream {
  NONCOPYABLE(xmlBlockInputStream);
  BlockClass _input;
 public:
  template<typename... Arg>
  xmlBlockInputStream(Arg... arg)
    : _input(arg...) {
    set_input(&_input);
  }
};

#endif // SHARE_UTILITIES_XMLINPUT_HPP
