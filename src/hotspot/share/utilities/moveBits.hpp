/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_UTILITIES_MOVE_BITS_HPP
#define SHARE_UTILITIES_MOVE_BITS_HPP

#include "metaprogramming/enableIf.hpp"
#include "utilities/debug.hpp"
#include "utilities/globalDefinitions.hpp"
#include <type_traits>

template <typename T, int S>
struct ReverseImpl {
 private:
  static const uint64_t rep_5555 = CONST64(0x5555555555555555);
  static const uint64_t rep_3333 = CONST64(0x3333333333333333);
  static const uint64_t rep_0F0F = CONST64(0x0F0F0F0F0F0F0F0F);
  static const uint64_t rep_00FF = CONST64(0x00FF00FF00FF00FF);
  static const uint64_t rep_FFFF = CONST64(0x0000FFFF0000FFFF);

 public:

  static constexpr T reverse_bits_in_bytes_template(uint64_t x) {
    // Based on Hacker's Delight Section 7-1
    // Note that the cast (T) is always done after `>>` in case T is signed.
    // This gives the effect of Java's `>>>` operator.
    x = (((T)x & (T)rep_5555) << 1) | ((T)(x >> 1) & (T)rep_5555);
    x = (((T)x & (T)rep_3333) << 2) | ((T)(x >> 2) & (T)rep_3333);
    x = (((T)x & (T)rep_0F0F) << 4) | ((T)(x >> 4) & (T)rep_0F0F);
    return (T)x;
  }

  static constexpr T reverse_bytes_template(uint64_t x) {
    // GCC and compatible (including Clang)
    #if defined(TARGET_COMPILER_gcc)
    switch (S) {
    case 2: return (T) __builtin_bswap16((T) x);
    case 4: return (T) __builtin_bswap32((T) x);
    case 8: return (T) __builtin_bswap64((T) x);
    default:  break;  // fall through to generic code
    }
    #endif
    // Based on Hacker's Delight Section 7-1
    // Note that the cast (T) is always done after `>>` in case T is signed.
    // This gives the effect of Java's `>>>` operator.
    if (S <= 1)  return (T)x;
    x = (((T)x & (T)rep_00FF) << 8)  | ((T)(x >> 8)  & (T)rep_00FF);
    if (S <= 2)  return (T)x;
    x = (((T)x & (T)rep_FFFF) << 16) | ((T)(x >> 16) & (T)rep_FFFF);
    if (S <= 4)  return (T)x;
    x = ((T)x << 32) | (T)(x >> 32);
    return (T)x;
  }

  static constexpr T reverse_bits_template(uint64_t x) {
    return reverse_bytes_template(reverse_bits_in_bytes_template(x));
  }
};

// Performs byte reversal of an integral type up to 64 bits.
template <typename T, ENABLE_IF(std::is_integral<T>::value)>
inline constexpr T reverse_bytes(T x) {
  return ReverseImpl<T, sizeof(T)>::reverse_bytes_template(x);
}

// Performs bytewise bit reversal of each byte of an integral
// type up to 64 bits.
template <typename T, ENABLE_IF(std::is_integral<T>::value)>
inline constexpr T reverse_bits_in_bytes(T x) {
  return ReverseImpl<T, sizeof(T)>::reverse_bits_in_bytes_template(x);
}

// Performs full bit reversal an integral type up to 64 bits.
template <typename T, ENABLE_IF(std::is_integral<T>::value)>
inline constexpr T reverse_bits(T x) {
  return ReverseImpl<T, sizeof(T)>::reverse_bits_template(x);
}

#endif // SHARE_UTILITIES_MOVE_BITS_HPP
