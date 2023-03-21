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

#ifndef SHARE_UTILITIES_ISTREAM_HPP
#define SHARE_UTILITIES_ISTREAM_HPP

#include "memory/allocation.hpp"
#include "utilities/globalDefinitions.hpp"
#include "utilities/macros.hpp"
#include "utilities/ostream.hpp"

// Block-oriented input, which treats all bytes equally.
class BlockInput : public CHeapObjBase {
 public:
  // Read some characters from an external source into the line buffer.
  // If there are no more, return zero, otherwise return non-zero.
  // It must be OK to call read_input even after it returns zero.
  virtual size_t read_input(char* buf, size_t size) = 0;
  // Example: read_input(b,s) { return fread(b, 1, s, _my_fp); }
  // Example: read_input(b,s) { return 0; } // never more than the initial buffer

  // If it is backed by a resource that needs closing, do so.
  virtual void close() { }
};

// Input streams for reading line-oriented textual data These streams
// treat newline '\n' very differently from all other bytes.  Carriage
// return '\r' is just another bit of whitespace, although it is
// removed just before newline.
//
// Null '\0' is just a data byte, although it also terminates C
// strings; the `current_line` function adds a null after removing any
// line terminator but does not specially process any nulls embedded
// in the line.
//
// There are sizing access functions which allow lines to contain
// null, but the simpler function assumes null termination, and thus
// lines containing null will "look" shorter when viewed as C strings.
// Use the sizing access functions if you care about this.
//
// Formatting guidelines:
//
// Configuration data should be line-oriented.  It should be readable
// by humans (though perhaps with difficulty).  It should be easily
// processed by text editors and by widely available text processing
// tools such as grep, sed, and awk.
//
// Configuration data should not require "compilers" to generate, if
// possible.  It should be editable by hand, if possible.  In cases
// where binary data is strongly required, pick a binary format
// already native to Hotspot, such as classfile, jar, or jmod.
//
// Each line should be separately parseable; the parsing can be ad
// hoc.  For constructs inherently larger than single lines (such as
// complex method configuration information), try to use a structuring
// principle that allows "leaf" data to be line-oriented, and delimits
// that data with markup lines of some sort.  Try to pick a
// line-friendly version of a standard format like XML or Markdown.
// JSON is somewhat problematic because there is no line-friendly leaf
// syntax: everything at the leaves must be a quoted string in JSON.
//
// Use simple parsing via scanf-like formats for simple applications.
// But, keep in mind that these formats may lose data when applied to
// unusual strings, such as class names that contain spaces, or method
// names that contain punctuation.  For more robust transmission of
// potentially unusual names, consider wrapping them in XML-flavored
// lines like <tag attr='pay load'/>.
//
// See xmlstream.hpp for the details of XML flavoring.
//
// Note: Input streams are never MT-safe.
//
class inputStream : public CHeapObjBase {
 private:
  NONCOPYABLE(inputStream);

  static constexpr size_t SMALL_SIZE =  240 DEBUG_ONLY(*0 + 10);
  static constexpr size_t BIG_SIZE   = 2048 DEBUG_ONLY(*0 + 20);

 protected:
  BlockInput* _input;   // where the input comes from or else nullptr
  char*  _buffer;       // scratch buffer holding at least the current line
  size_t _buffer_size;  // allocated size of buffer
  size_t _content_end;  // offset to end of valid contents of buffer
  size_t _beg, _end;    // offset in buffer to start/end of current line
  void*  _must_free;    // unless null, a malloc pointer which we must free
  size_t _position; // total count of bytes *before* the current line
  int    _lineno;       // number of current line (1-based, or 0 if none)
  char   _line_ending;  // which line end did we remove? 0=none, 1=\n, 2=\n\r
  char   _small_buffer[SMALL_SIZE];  // buffer for holding lines

  void handle_free();

  // buffer states:
  //   buffer == nullptr           =>  not yet started (constructor resp.)
  //   beg <= end < content_end    =>  valid current line (*end == '\0')
  //   beg == end == content_end   =>  nothing buffered, need to try more I/O
  //   beg <  end == content_end   =>  partial line, need to try more I/O
  //   beg == end == buffer_size+1 =>  definitely done; no more I/O
  //   beg == end >= buffer_size+2 =>  definitely done, and error seen

  bool unstarted() const {
    return _buffer == nullptr;
  }
  bool need_to_read() const {   // note:  includes unstarted
    return _end == _content_end;
  }
  bool have_current_line() const {
    return _end < _content_end;
  }
  bool definitely_done() const {
    return _end > _buffer_size;
  }

  // Rest indexes within the buffer to point to no content.
  void clear_buffer() {
    _content_end = _beg = _end = 0;
    _line_ending = 0;
  }

  // Reset indexes within the buffer to point to the given content.
  // This is where we scan for newlines as well.
  void set_buffer_content(size_t content_start, size_t content_end);

  // Try to make the buffer bigger.  This may be necessary in order to
  // buffer a very long line.  Returns false if there was an
  // allocation failure.
  //
  // On allocation failure, just make do with whatever buffer there
  // was to start with; the caller must check for this condition and
  // avoid buffering more data in the non-expanded buffer.  However,
  // the buffer will always be non-null, so at least one line can be
  // buffered, if it is of normal size.
  bool expand_buffer(size_t new_length);

  // Make sure there is at least one line in the buffer, and set
  // _beg/_end to indicate where it is.  Any content before _beg can
  // be overwritten to make more room in the buffer.  If there is no
  // more input, set the state up to indicate we are done.
  bool fill_buffer();

  // Find some room in the buffer so we call read_input on it.
  // This might call expand_buffer but will try not to.
  // The assumption is that read_input already buffers slow I/O calls.
  // The purpose for the small buffer managed here is to store whole lines,
  // and perhaps edit them in-place.
  void prepare_to_fill_buffer(size_t& fill_offset, size_t& fill_length);

  // Quick check for an initially incomplete buffer...
  void preload_buffer() const {
    if (need_to_read()) {
      const_cast<inputStream*>(this)->fill_buffer();
    }
  }

 public:
  // Create an empty input stream.
  // Call pushback_input or set_input to configure.
  inputStream() {
    _input = nullptr;
    _buffer = nullptr;
    _buffer_size = _content_end = _beg = _end = 0;
    _must_free = nullptr;
    _lineno = 0;
    _position = 0;
    _line_ending = 0;
  }

  // Take input from the given source.  Buffer only a modest amount.
  inputStream(BlockInput* input) : inputStream() {
    set_input(input);
  }

  // For reading lines directly from strings or other shared memory.
  // This constructor inhales the whole string into its buffer, as if
  // by pushback_input.
  //
  // If you have large shared memory, and don't want to make a large
  // private copy, consider using MemoryInput instead.
  inputStream(const char* chars, size_t length) : inputStream() {
    pushback_input(chars, length);
  }

  inputStream(const char* chars)
    : inputStream(chars, strlen(chars))
  { }

  virtual ~inputStream() {
    if (_must_free)         handle_free();
    if (_input != nullptr)  set_input(nullptr);
  }

  // Discards any previous input and sets the given input source.
  void set_input(BlockInput* input) {
    clear_buffer();
    if (_input != nullptr)  _input->close();
    _input = input;
  }

  // Forces the given data into the buffer, before the current line.
  // If overwrite_current_line is true, the current line is removed.
  // Normally, an input stream tries not to do a "big inhale", but
  // this will cause all of the given data into my buffer.
  // If the current pushback
  void pushback_input(const char* chars, size_t length,
                      bool overwrite_current_line = false);

  void pushback_input(const char* chars) {
    pushback_input(chars, strlen(chars));
  }

  // Returns a pointer to a null terminated mutable copy of the current line.
  // Note that embedded nulls may make the line appear shorter than it really is.
  // This may trigger input activity if there is not enough data buffered.
  // If there are no more lines, return an empty line, statically allocated.
  char* current_line() const {
    preload_buffer();
    if (definitely_done())
      return (char*)"";
    return &_buffer[_beg];
  }

  // Returns a pointer to a null terminated mutable copy of the current line.
  // The size of the line (which may contain nulls) is reported via line_length.
  // This may trigger input activity if there is not enough data buffered.
  char* current_line(size_t& line_length) const {
    char* line = current_line();
    line_length = _end - _beg;
    return line;
  }

  // Return the size of the current line, exclusive of any line terminator.
  // If no lines have been read yet, or there are none remaining, return zero.
  size_t current_line_length() const {
    preload_buffer();
    return _end - _beg;
  }

  // Returns a C string for exactly the line-ending sequence which was
  // stripped from the current line.  This is the sequence, pulled
  // from the underlying block input, that delimited the current line.
  // If there are no more lines, or if we are at a partial final line,
  // return an empty string.  Otherwise return "\n" or "\r\n" as the
  // case may be.
  const char* current_line_ending() const;

  // Reports my current input source, if any, else a null pointer.
  BlockInput* input() const { return _input; }

  // Returns a pointer and count to characters buffered after
  // the current line, but not yet read from my input source.
  // Only useful if you are trying to stack input streams on
  // top of each other somehow.
  //
  // The bytes processed by this stream are composed of the current
  // line, its line ending, the bytes buffered after the current line,
  // and any bytes not yet obtained from the input source, if any.
  const char* buffered_after_current(size_t& buffered_size) const {
    preload_buffer();
    if (definitely_done()) {
      buffered_size = 0;
      return (char*)"";
    }
    size_t endl = _end + _line_ending;  // skip \0 which replaced \n
    assert(endl <= _content_end, "");
    buffered_size = _content_end - endl;
    return &_buffer[endl];
  }

  // Discards the current line, gets ready to report the next line.
  // Returns true if there is one, which is always the opposite of done().
  bool next();

  // Reports if there are no more lines.
  bool done() const  {
    preload_buffer();
    return definitely_done();
  }

  // Discard pending input and do not read any more.
  void set_done();

  bool error() const {
    return _end >= _buffer_size + 2;
  }
  void set_error(bool error_condition = true) {
    if (error_condition) {
      _end = _buffer_size + 2;
      _line_ending = 0;
    } else if (error()) {
      _end = _buffer_size + 1;
      _line_ending = 0;
    }
  }

  // position is the 1-based ordinal of the current line; it starts at one
  int lineno() const                    { return _lineno; }
  void set_lineno(int lineno)           { _lineno = lineno; }
  void add_to_lineno(int amount)        { _lineno += amount; }

  // position is the number of bytes before the current line; it starts at zero
  size_t position() const               { return _position; }
  void set_position(size_t position)    { _position = position; }
  void add_to_position(size_t amount)   { _position += amount; }

  // Copy to a resource or C-heap array as requested.
  // Add a terminating null, and also keep any embedded nulls.
  char* save_line(bool c_heap = false) const;

  // Copy to a resource or C-heap array, doing the actual work with a
  // copy-function which can perform arbitrary operations on this
  // input stream, copying arbitrary data into a temporary
  // string-stream that collects the output.  The copy function is
  // called on two pointers, as if it by the expression
  // `this->print_on(out)`.  Note that multiple lines can be saved, if
  // desired, by calling `this->next()` inside the copy function.
  template<typename WFN>
  char* save_data(WFN copy_in_to_out, bool c_heap = false) {
    stringStream out(current_line_length() + 10);
    copy_in_to_out(this, &out);
    return out.as_string(c_heap);
  }

  // Copy the current line to the given output stream.
  void print_on(outputStream* out);

  // Copy the current line to the given output stream, and also call cr().
  void print_cr_on(outputStream* out) {
    print_on(out); out->cr();
  }

#ifdef ASSERT
  void dump(const char* what = nullptr);
  static int coverage_mode(int mode, int& cases, int& total, int& zeroes);
#else
  void dump(const char* what = nullptr) { }
#endif
};

template<typename BlockClass>
class BlockInputStream : public inputStream {
  BlockClass _input;
 public:
  template<typename... Arg>
  BlockInputStream(Arg... arg)
    : _input(arg...) {
    set_input(&_input);
  }
};

// for reading lines from files
class FileInput : public BlockInput {
  NONCOPYABLE(FileInput);

 protected:
  fileStream& _fs;
  fileStream _private_fs;

  // it does not seem likely there are such file streams around
  FileInput(fileStream& fs)
    : _fs(fs)
  { }

 public:
  FileInput(const char* file_name, const char* opentype = "r")
    : _fs(_private_fs), _private_fs(file_name, opentype)
  { }
  FileInput(FILE* file, bool need_close = false)
    : _fs(_private_fs), _private_fs(file, need_close)
  { }

  bool is_open() const { return _fs.is_open(); }

 protected:
  virtual size_t read_input(char* buf, size_t size) {
    return _fs.read(buf, size);
  }
  virtual void close() {
    if (_fs.is_open())  _fs.close();
  }
};

class MemoryInput : public BlockInput {
  const void* _base;
  size_t      _offset;
  size_t      _limit;
  const void* _must_free;  // unless null, a malloc pointer which we must free

 public:
  MemoryInput(const void* base, size_t offset, size_t limit,
              bool must_free = false)
    : _base(base), _offset(offset), _limit(limit)
  {
    if (must_free)  _must_free = base;
  }

  MemoryInput(const void* start, const void* limit)
    : MemoryInput(start, 0, (const char*)limit - (const char*)start)
  { }

 protected:
  virtual size_t read_input(char* buf, size_t size) {
    size_t nr = size;
    if (nr > _limit - _offset) {
      nr = _limit - _offset;
    }
    if (nr > 0) {
      ::memcpy(buf, (char*)_base + _offset, nr);
      _offset += nr;
    }
    return nr;
  }
};

#endif // SHARE_UTILITIES_ISTREAM_HPP
