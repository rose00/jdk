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
 */

import com.sun.tools.classfile.*;
import com.sun.tools.classfile.BootstrapMethods_attribute.BootstrapMethodSpecifier;
import com.sun.tools.classfile.ConstantPool.CONSTANT_InvokeDynamic_info;
import com.sun.tools.classfile.ConstantPool.CONSTANT_MethodHandle_info;

import java.io.File;

/*
 * @test
 * @bug     9999999
 * @summary basic tests for autonomous statics
 * @modules jdk.jdeps/com.sun.tools.classfile
 *
 * @clean *
 * @compile/fail -XDautonomousFields AutoStaticChecks.java
 */
public class AutoStaticChecks {
    // FIXME: Only one of these failing is enough to pass the test.
    // We need something that will call javac on a bunch of bad
    // code bits, and verify that each one fails.
    static class MissingFinal {
        __Auto static /*final*/ String MISSING_FINAL = "";
    }
    static class MissingStatic {
        __Auto /*static*/ final String MISSING_STATIC = "";
    }
    static class MissingInit {
        __Auto static final String MISSING_INIT;
        static { MISSING_INIT = ""; }
    }
}

