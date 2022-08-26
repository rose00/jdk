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

template <typename T>
struct ReverseBitsImpl {
 private:
  static const int NB = sizeof(T) * BitsPerByte;
  static_assert((NB == 8) || (NB == 16) || (NB == 32) || (NB == 64),
                "unsupported bit width");

  // These masks are specified in 64 bits but are down-cast to size for use.
  static const T rep_5555 = (T) CONST64(0x5555555555555555);
  static const T rep_3333 = (T) CONST64(0x3333333333333333);
  static const T rep_0F0F = (T) CONST64(0x0F0F0F0F0F0F0F0F);
  static const T rep_00FF = (T) CONST64(0x00FF00FF00FF00FF);
  static const T rep_FFFF = (T) CONST64(0x0000FFFF0000FFFF);

 public:

  static constexpr T reverse_bits_in_bytes(uint64_t x) {
    // Based on Hacker's Delight Section 7-1
    // Note that the cast (T) is always done after `>>` in case T is signed.
    // Since x is always unsigned, we get the effect of Java's `>>>` operator.
    x = (((T)x & rep_5555) << 1) | ((T)(x >> 1) & rep_5555);
    x = (((T)x & rep_3333) << 2) | ((T)(x >> 2) & rep_3333);
    x = (((T)x & rep_0F0F) << 4) | ((T)(x >> 4) & rep_0F0F);
    // The vigourous re-casting to T here means that all of the
    // logical operations can be strength reduced to the width of T.
    // For example, on x86, when T is 64 bits, 64-bit instructions
    // (like andq, shrq) are selected for all this logic, but for
    // smaller T, narrow instructions (like andl, shrl) are selected.
    return (T)x;
  }

  // At this point one might consider calling intrinsics like
  // __builtin_bswap{16,32,64}, but in fact this generic code is
  // routinely recognized by the compiler as a byte-swap operation and
  // the single instruction is selected anyway.
  static constexpr T reverse_bytes(uint64_t x) {
    // Based on Hacker's Delight Section 7-1
    // Note that the cast (T) is always done after `>>` in case T is signed.
    // Since x is always unsigned, we get the effect of Java's `>>>` operator.
    if (NB <= 8)  return (T)x;
    x = (((T)x & rep_00FF) << 8)  | ((T)(x >> 8)  & rep_00FF);
    if (NB <= 16)  return (T)x;
    x = (((T)x & rep_FFFF) << 16) | ((T)(x >> 16) & rep_FFFF);
    if (NB <= 32)  return (T)x;
    // Using NB/2 instead of 32 avoids a warning in dead code when T=int32_t.
    // The code is never reached, but it is also warn-worthy since shifting
    // a 32-bit value by 32 is an undefined operation.  NB/2 always works.
    // We don't need similar hack above (NB/4, etc.) because auto-promotion
    // to 32 bits means that shifts below 32 bits are always acceptable.
    // What a wonderful world.
    x = ((T)x << (NB/2)) | (T)(x >> (NB/2));
    return (T)x;
  }
};

// Performs byte reversal of an integral type up to 64 bits.
template <typename T, ENABLE_IF(std::is_integral<T>::value)>
constexpr T reverse_bytes(T x) {
  return ReverseBitsImpl<T>::reverse_bytes(x);
}

// Performs bytewise bit reversal of each byte of an integral
// type up to 64 bits.
template <typename T, ENABLE_IF(std::is_integral<T>::value)>
constexpr T reverse_bits_in_bytes(T x) {
  return ReverseBitsImpl<T>::reverse_bits_in_bytes(x);
}

// Performs full bit reversal an integral type up to 64 bits.
template <typename T, ENABLE_IF(std::is_integral<T>::value)>
constexpr T reverse_bits(T x) {
  return reverse_bytes(reverse_bits_in_bytes(x));
}

#endif // SHARE_UTILITIES_MOVE_BITS_HPP
