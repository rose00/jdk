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
#include "memory/allocation.inline.hpp"
#include "runtime/orderAccess.hpp"
#include "utilities/istream.hpp"
#include "utilities/ostream.hpp"
#include "utilities/xmlstream.hpp"

#ifdef ASSERT
// Coverage monitoring, used by the gtest.
static int current_coverage_mode = 0;

// $ sed -n < istream.cpp '/^.* COV(\([A-Z][^)]*\)).*$/s//  COV_FN(\1) \\/p'
#define DO_COV_CASES(COV_FN) \
  COV_FN(NXT_N) \
  COV_FN(NXT_L) \
  COV_FN(FIB_P) \
  COV_FN(FIB_E) \
  COV_FN(FIB_N) \
  COV_FN(FIB_L) \
  COV_FN(PFB_X) \
  COV_FN(PFB_C) \
  COV_FN(PFB_P) \
  COV_FN(PFB_A) \
  COV_FN(PFB_G) \
  COV_FN(PFB_H) \
  COV_FN(SBC_C) \
  COV_FN(SBC_B) \
  COV_FN(SBC_N) \
  COV_FN(SBC_L) \
  COV_FN(EXB_S) \
  COV_FN(EXB_R) \
  COV_FN(EXB_A) \
  /**/

#define COV_COUNT(casename) coverage_case_##casename

#define DECLARE_COV_CASE(casename) \
  static int COV_COUNT(casename);
DO_COV_CASES(DECLARE_COV_CASE)
#undef DECLARE_COV_CASE

#define COV(casename) {                                 \
    if (current_coverage_mode != 0) {                   \
      COV_COUNT(casename)++;                            \
      if (current_coverage_mode > 1)  dump(#casename);  \
    }                                                  }

int inputStream::coverage_mode(int start,
                               int& cases, int& total, int& zeroes) {
  int old_mode = current_coverage_mode;
  current_coverage_mode = start;
  int num_cases = 0, zero_count = 0, case_count = 0;
#define COUNT_COV_CASE(casename) {              \
    int tem = COV_COUNT(casename);              \
    case_count += tem;                          \
    if (tem == 0)  ++zero_count;                \
    num_cases++;                                \
  }
  DO_COV_CASES(COUNT_COV_CASE)
#undef COUNT_COV_CASE
  if (start < 0) {
    tty->print("istream coverage:");
    #define PRINT_COV_CASE(casename) \
      tty->print(" %s:%d", #casename, COV_COUNT(casename));
    DO_COV_CASES(PRINT_COV_CASE)
    tty->cr();
    #undef PRINT_COV_CASE
    if (zero_count != 0) {
      case_count = -case_count;
      #define ZERO_COV_CASE(casename)                  \
        if (COV_COUNT(casename) == 0)                  \
          tty->print_cr("%s: no coverage for %s",      \
                        __FILE__, #casename);          \
      DO_COV_CASES(ZERO_COV_CASE)
      #undef ZERO_COV_CASE
    }
  }
  if (start >= 2 || start < 0) {
    #define CLEAR_COV_CASE(casename) \
       COV_COUNT(casename) = 0;
    DO_COV_CASES(CLEAR_COV_CASE)
    #undef CLEAR_COV_CASE
  }
  cases  = num_cases;
  total  = case_count;
  zeroes = zero_count;
  return old_mode;
}

#else //ASSERT
#define COV(casen) {}
#endif //ASSERT

bool inputStream::next() {
  // We have to look at the current line first, just in case nobody
  // actually called current_line() or done().
  preload_buffer();
  if (definitely_done()) {
    return false;         // it is OK to call this->next() after done is true
  }
  // current line is at buffer[beg..end]; now skip past its '\0'
  assert(have_current_line(), "");
  size_t new_beg = _end + 1;  // == _beg + current_line_length + 1
  _position += new_beg - _beg;
  set_buffer_content(new_beg, _content_end);
  if (!need_to_read()) {  // any next line was already in the buffer
    COV(NXT_L);
    assert(have_current_line(), "");
    return true;
  } else {                // go back to the source for more
    COV(NXT_N);
    return fill_buffer();
  }
}

void inputStream::set_done() {
  if (!definitely_done()) {  // if not already done or in error
    _content_end = _buffer_size;
    _beg = _end = 1 + _buffer_size;
    _line_ending = 0;
    assert(definitely_done(), "");
  }
}

bool inputStream::fill_buffer() {
  assert(!definitely_done(), "");  // caller responsibility
  while (need_to_read()) {
    size_t fill_offset, fill_length;
    prepare_to_fill_buffer(fill_offset, fill_length);
    if (error())  return false;
    assert(fill_length > 0 && fill_offset >= 0
           && fill_offset + fill_length <= _buffer_size, "");
    size_t nr = 0;
    if (_input != nullptr) {
      nr = _input->read_input(&_buffer[fill_offset], fill_length);
    }
    int last_partial = 0;
    if (nr == 0) {
      // we hit the end of the file (or there was never anything there)
      if (_beg == _end) {  // no partial line, so end it now
        COV(FIB_P);
        assert(!definitely_done(), "");
        set_done();
        assert(definitely_done(), "");
        return false;
      }
      COV(FIB_E);
      // pretend to read a newline, to complete the last partial line
      _buffer[fill_offset] = '\n';
      last_partial = 1;
      // note:  we will probably read one more time after this
    }
    set_buffer_content(_beg, fill_offset + nr + last_partial);
    assert(!definitely_done(), "");
    #ifdef ASSERT
    if (need_to_read()) { COV(FIB_N); }
    else                { COV(FIB_L); }
    #endif //ASSERT
    if (last_partial) {
      _line_ending = 0;  // cancel effect of supplied \n
      break;             // stop looking for an absent \n
    }
  }
  return true;
}

// Find some space in the buffer for reading.  If there is already a
// partial line in the buffer, the space must follow it immediately.
void inputStream::prepare_to_fill_buffer(size_t& fill_offset,
                                         size_t& fill_length) {
  assert(_end == _content_end, "");  // need_to_read() == true
  if (_buffer_size == 0) {
    COV(PFB_X);
    expand_buffer(sizeof(_small_buffer));
    assert(_buffer_size > 0, "");
    // and continue with at least a little buffer
  }
  if (_beg == _end) {
    COV(PFB_C);
    clear_buffer();
    fill_offset = 0;
    fill_length = _buffer_size;
    return;   // use the whole buffer
  }
  assert(need_to_read(), "");
  // at this point we have a pending line that needs more input
  if (_beg > 0) {
    COV(PFB_P);
    // compact the buffer by overwriting characters from previous lines
    size_t content_len = _content_end - _beg;
    ::memmove(_buffer, _buffer + _beg, content_len);
    _content_end = _end = content_len;
    _beg = 0;
  }
  if (_end < _buffer_size) {
    COV(PFB_A);
    fill_offset = _end;
    fill_length = _buffer_size - fill_offset;
    return;   // use the whole buffer except partial line at the beginning
  }
  // the whole buffer contains a partial line, which means we must expand
  COV(PFB_G);
  size_t new_size = (_buffer_size < BIG_SIZE ? BIG_SIZE
                     : _buffer_size + _buffer_size / 2);
  assert(new_size > _buffer_size, "");
  if (expand_buffer(new_size)) {
    COV(PFB_H);
    fill_offset = _end;
    fill_length = _buffer_size - fill_offset;
    return;   // use the expanded buffer, except the partial line
  }
  // no recovery from failed allocation; just set the error state and bail
  set_error();
}

void inputStream::set_buffer_content(size_t content_start,
                                     size_t content_end) {
  assert(content_start >= 0 && content_end <= _buffer_size, "");
  if (content_start >= content_end) {
    COV(SBC_C);
    clear_buffer();
    return;
  }
  COV(SBC_B);
  _beg = content_start;
  _content_end = content_end;
  _line_ending = 0;  // change if we encounter newline char

  // this is where we scan for newlines
  size_t end = content_start;
  for (; end < content_end; end++) {
    if (_buffer[end] == '\n') {
      _buffer[end] = '\0';  // so that this->current_line() will work
      ++_lineno;
      _line_ending = 1;
      if (end > content_start && _buffer[end-1] == '\r') { // yuck
        // again, for this->current_line(), remove '\r' before '\n'
        _buffer[end-1] = '\0';
        _line_ending = 2;
      }
      // Note: we could treat '\r' alone as a line ending on some
      // platforms, but that is way too much work.  Newline '\n' is
      // supported everywhere, and some tools insist on accompanying
      // it with return as well, so we remove that.  But return '\r'
      // by itself is an obsolete format, and also inconsistent with
      // outputStream, which standarizes on '\n' and never emits '\r'.
      // Postel's law suggests that we write '\n' only and grudgingly
      // accept '\r' before '\n'.
      break;
    }
  }
  _end = end;  // now this->current_line() points to buf[beg..end]
#ifdef ASSERT
  assert(need_to_read() || current_line() == &_buffer[_beg], "");
  assert(need_to_read() || current_line_length() == _end - _beg, "");
  if (need_to_read()) { COV(SBC_N); }
  else                { COV(SBC_L); }
#endif //ASSERT
}

// Return true iff we expanded the buffer to the given length.
bool inputStream::expand_buffer(size_t new_length) {
  assert(new_length > _buffer_size, "");
  char* new_buf = nullptr;
  if (new_length <= sizeof(_small_buffer)) {
    COV(EXB_S);
    new_buf = &_small_buffer[0];
    new_length = sizeof(_small_buffer);
  } else if (_buffer != nullptr && _buffer == _must_free) {
    COV(EXB_R);
    new_buf = REALLOC_C_HEAP_ARRAY(char, _buffer, new_length, mtInternal);
    if (new_buf != nullptr) {
      _must_free = new_buf;
    }
  } else {  // fresh allocation
    COV(EXB_A);
    new_buf = NEW_C_HEAP_ARRAY(char, new_length, mtInternal);
    if (new_buf != nullptr) {
      assert(_must_free == nullptr, "dropped free");
      _must_free = new_buf;
      if (_content_end > 0) {
        assert(_content_end <= _buffer_size, "");
        ::memcpy(new_buf, _buffer, _content_end);  // copy only the active content
      }
    }
  }
  if (new_buf == nullptr) {
    return false;   // do not further update _buffer etc.
  }
  _buffer = new_buf;
  _buffer_size = new_length;
  return true;
}

void inputStream::handle_free() {
  void* to_free = _must_free;
  if (to_free == nullptr)  return;
  _must_free = nullptr;
  FreeHeap(to_free);
}

#ifdef ASSERT
void inputStream::dump(const char* what) {
  int diff = (int)(_end - _beg);
  if (!_buffer || _beg > _buffer_size || _end > _buffer_size)
    diff = 0;
  
  tty->print_cr("%s%sistream %s%s%s%s [%d<%.*s>%d/%d/%d]"
                " B=%llx%s, MF=%llx, LN=%d",
                what ? what : "", what ? ": " : "",
                unstarted() ? "U" : "",
                need_to_read() ? "N" : "",
                have_current_line() ? "L" : "",
                definitely_done() ? "D" : "",
                (int)_beg,
                diff < 0 ? 0 : diff > 10 ? 10 : diff,
                _buffer ? &_buffer[_beg] : "",
                (int)_end, (int)_content_end, (int)_buffer_size,
                (unsigned long long)(intptr_t)_buffer,
                _buffer == _small_buffer ? "(SB)" : "",
                (unsigned long long)(intptr_t)_must_free, _lineno);
}
#endif

char* inputStream::save_line(bool c_heap) const {
  size_t len;
  char* line = current_line(len);
  size_t alloc_len = len + 1;
  char* copy = (c_heap
                ? NEW_C_HEAP_ARRAY(char, alloc_len, mtInternal)
                : NEW_RESOURCE_ARRAY(char, alloc_len));
  if (copy == nullptr) {
    return (char*)"";  // recover by returning a valid string
  }
  ::memcpy(copy, line, len);
  copy[len] = '\0';  // terminating null
  // Note: There may also be embedded nulls in the line.  The caller
  // must deal with this by saving a count as well, or else previously
  // testing for nulls.
  if (c_heap) {
    // Need to ensure our content is written to memory before we return
    // the pointer to it.
    OrderAccess::storestore();
  }
  return copy;
}

const char* inputStream::current_line_ending() const {
  preload_buffer();
  switch (_line_ending) {
  case 1: return "\n";
  case 2: return "\r\n";
    // If we were to support more kinds of newline, such as '\r' or
    // Unicode line ends, we could add more cases here.
    // If we were to support null line endings (as a special mode),
  default: return "";
  }
}

// Forces the given data into the buffer, before the current line
// or overwriting the current line, depending on the flag.
void inputStream::pushback_input(const char* chars, size_t length,
                                 bool overwrite_current_line) {
  bool partial_line = (chars[length-1] != '\n');
  if (overwrite_current_line) {
    preload_buffer();   // we need to know how much to overwrite...
  }
  if (!have_current_line()) {
    overwrite_current_line = false;  // nothing to overwrite
  }
  size_t pending = 0;
  size_t pending_beg = 0;
  if (!definitely_done()) {
    pending_beg = (overwrite_current_line ? _end + 1 : _beg);
    pending = _content_end - pending_beg;
  }
  if (have_current_line()) {
    add_to_lineno(-1);     // we will see its \n again, or delete it
    if (pending_beg <= _end) {
      // prepare to recognize the current line ending a second time
      assert(_buffer[_end] == '\0', "");
      _buffer[_end] = '\n';  // set_buffer_content will see it again
      if (_line_ending == 2) {
        assert(pending_beg < _end-1 && _buffer[_end-1] == '\0', "");
        _buffer[_end-1] = '\r';  // set_buffer_content will see it again
      } else if (_line_ending == 0) {
        assert(_end+1 == _content_end, "");
        --pending;  // kill final synthetic newline
      }
    }
  }
  size_t buflen = length + (pending != 0 ? pending : 1);
  if (_buffer_size < buflen && !expand_buffer(buflen)) {
    set_error();
    return;
  }
  assert(length + pending <= _buffer_size, "");
  size_t fillp = _buffer_size;
  if (pending > 0) {
    fillp -= pending;
    if (fillp != pending_beg)
      ::memmove(&_buffer[fillp], &_buffer[pending_beg], pending);
  } else if (partial_line) {
    --fillp;  // welcome a terminating \0, if we are going to need one
  }
  fillp -= length;
  ::memcpy(&_buffer[fillp], chars, length);
  set_buffer_content(fillp, fillp + length + pending);
  assert(!unstarted(), "");
}
