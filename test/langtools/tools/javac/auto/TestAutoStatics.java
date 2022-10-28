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
 * @compile -XDautonomousFields TestAutoStatics.java
 * @run main TestAutoStatics
 */
public class TestAutoStatics {
    static class Nexter {
        private static int COUNT;
        private static java.util.ArrayList<String> CALLERS
          = new java.util.ArrayList<>();
        private synchronized static String next(String who) {
            CALLERS.add(who);
            return new StringBuilder(who).append('#').append(++COUNT).toString();
        }
        private synchronized static String loser(RuntimeException ex) {
            CALLERS.add(ex.toString());
            throw ex;
        }
    }
    static class Test1 {
        __Auto static final String CONST = "CONST" + "#000";
        __Auto static final String AUTO1 = Nexter.next("AUTO1");
        __Auto static final String BADONE = Nexter.loser(new RuntimeException());
        __Auto static final String AUTO2 = Nexter.next("AUTO2");
    }
    static void doTest1() {
        String prev = null;
        Throwable prevThrow = null;
        int callerCount = Nexter.CALLERS.size();
        Test1.AUTO2.getClass();  // trigger the second one first!
        Test1.CONST.getClass();  // this one should NOT trigger
        for (int i = 0; i < 4; i++) {
            try {
                System.out.println(Test1.BADONE);
            } catch (Throwable ex) {
                System.out.println("caught EIIE "+ex);
                ex.printStackTrace(System.out);
                // JVMS says "the exception must be the same",
                // but HotSpot only memoizes selected information,
                // to encourage GC-ing of failed classes.
                assert(prevThrow == null ||
                       prevThrow.getClass() == ex.getClass()) : prevThrow;
                prevThrow = ex;
            }
            String a1, a2;
            if ((i & 1) == 0) { a1 = Test1.AUTO1; a2 = Test1.AUTO2; }
            else              { a2 = Test1.AUTO2; a1 = Test1.AUTO1; }
            if (i == 0) { callerCount += 3; }
            String next = new StringBuilder(a1).append("; ").append(a2).toString();
            System.out.println(next);
            assert(prev == null || prev.equals(next)) : prev;
            assert(Nexter.CALLERS.size() == callerCount) : Nexter.CALLERS;
            switch ("CONST#000") {
            case Test1.CONST: break;
            default: assert(false) : Test1.CONST;
            }
        }
        System.out.println(Nexter.CALLERS);
    }
    /* sample output:
    AutoStaticManager::new TestAutoStatics$Test1
    AutoStaticManager::initialize TestAutoStatics$Test1 AUTO2
    AutoStaticManager::initialize TestAutoStatics$Test1 AUTO2 returns AUTO2#1
    AutoStaticManager::initialize TestAutoStatics$Test1 AUTO1
    AutoStaticManager::initialize TestAutoStatics$Test1 AUTO1 returns AUTO1#2
    AUTO1#2; AUTO2#1
    AUTO1#2; AUTO2#1
    AUTO1#2; AUTO2#1
    AUTO1#2; AUTO2#1
    [AUTO2, AUTO1]
    */

    public static void main(String... av) {
        doTest1();
    }
}

