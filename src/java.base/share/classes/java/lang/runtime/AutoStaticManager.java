/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

package java.lang.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.Properties;
import sun.security.action.GetPropertyAction;

//import jdk.internal.javac.PreviewFeature;

import static java.util.Objects.requireNonNull;

/**
 * Bootstrap methods for managing the initialization of autonomous
 * static fields.
 *
 * @since 99
 */
//@PreviewFeature(feature=PreviewFeature.Feature.AUTONOMOUS_STATICS)
public class AutoStaticManager {
    private final MethodHandles.Lookup lookup;
    private final MethodHandle autoinit;
    private final Object initializationLock;

    // @@@ FIXME: VM should supply this bookkeeping:
    private final HashMap<String,ResultOrError> state = new HashMap<>();
    private static class ResultOrError {  //not a record, to simplify boot-up
        final Object result; final Error error;
        private ResultOrError(Object result, Error error) {
            this.result = result; this.error = error;
        }
    }
    private static final ResultOrError PENDING =
        new ResultOrError(null, new Error("PENDING",null,false,false){});

    private AutoStaticManager(MethodHandles.Lookup lookup,
                              MethodHandle autoinit) {
        if (!lookup.hasFullPrivilegeAccess())
            throw new IllegalArgumentException("Illegal unprivileged lookup " + lookup);
        if (!AUTOINIT_TYPE.equals(autoinit.type()))
            throw new IllegalArgumentException("Illegal initializer method " + autoinit);
        this.lookup = lookup;
        this.autoinit = autoinit;
        // @@@ FIXME: VM should supply this bookkeeping:
        this.initializationLock = lookup.lookupClass();
        if (TRACE_AUTO_STATICS)
            System.out.println(new StringBuilder("AutoStaticManager::new ")
                               .append(lookup.lookupClass().getName()));

    }
    private static final MethodType AUTOINIT_TYPE = // (String,Object)->Object
        MethodType.methodType(Object.class, String.class, Object.class);

    private Class<?> lookupClass() {
        return lookup.lookupClass();
    }
    private void check(MethodHandles.Lookup lookupToCheck) {
        if (!lookupToCheck.hasFullPrivilegeAccess() ||
            lookupToCheck.lookupClass() != lookupClass())
            throw new IllegalArgumentException("Illegal unprivileged lookup " + lookupToCheck);
    }
    private String errm(String message, String name) {
        return message + lookupClass().getName() + "." + name;
    }

    private Object initialize(String name) {
        StringBuilder traceStr
            = (TRACE_AUTO_STATICS ?
               new StringBuilder("AutoStaticManager::initialize ")
               .append(lookup.lookupClass().getName())
               .append(" ").append(name) : null);
        int traceStrLen = (traceStr == null ? 0 : traceStr.length());
        if (TRACE_AUTO_STATICS)  System.out.println(traceStr);
        // Pick an object to serve as an error sentinel.  It can be
        // any object that will never be the actual value of some
        // auto-static.  The error field of PENDING will do.
        final Object SENTINEL = PENDING.error;

        synchronized (initializationLock) {
            ResultOrError done = state.get(name);
            if (done == PENDING) {
                if (TRACE_AUTO_STATICS)  System.out.println(traceStr.append(" circularity"));
                throw new StackOverflowError(errm("dependency cycle on ", name));
            }
            if (TRACE_AUTO_STATICS && done != null) {
                System.out.println(traceStr.append(" (already executed)"));
                traceStr.setLength(traceStrLen);
            }
            if (done == null) {
                state.put(name, PENDING);
                Object result = null;
                Error error = null;
                try {
                    // Call back to the method baked into the class file.
                    for (;;) {
                        result = autoinit.invokeExact(name, SENTINEL);
                        if (result != SENTINEL)  break;
                        String name2 = name.intern();
                        if (name2 == name)  break;  // hard error
                        name = name2;
                        if (TRACE_AUTO_STATICS) {
                            System.out.println(traceStr.append(" (retry with interned string)"));
                            traceStr.setLength(traceStrLen);
                        }
                    }
                } catch (Throwable ex) {
                    error = (ex instanceof Error)
                        ? (Error) ex
                        : new ExceptionInInitializerError(ex);
                }
                if (result == SENTINEL) {
                    throw new IllegalArgumentException(errm("Unrecognized static ", name));
                }
                done = new ResultOrError(result, error);
                state.put(name, done);
            }
            if (done.error != null) {
                if (TRACE_AUTO_STATICS)
                    System.out.println(new StringBuilder("AutoStaticManager::initialize ")
                                       .append(lookup.lookupClass().getName())
                                       .append(" ").append(name)
                                       .append(" throws ").append(done.error.getClass().getName()));
                throw done.error;
            } else {
                if (TRACE_AUTO_STATICS)
                    System.out.println(new StringBuilder("AutoStaticManager::initialize ")
                                       .append(lookup.lookupClass().getName())
                                       .append(" ").append(name)
                                       .append(" returns ").append(done.result));
                return done.result;
            }
        }
    }

    /**
     * Bootstrap method for creating the {@code AutoStaticManager}
     * object that manages initialization of the autonomous statics of
     * a particular class.
     *
     * The method handle {@code autoinit} must take an interned string
     * and a second sentinel value (which can be any reference).
     *
     * If the string is interned and recognized as the name of an
     * autonomous static field with an initializer, then the method
     * must execute the initializer code for that field, either
     * returning the value (converted to an {@code Object}) or
     * throwing any kind of exception.
     *
     * If the string is not recognized or not interned, the method
     * must return the sentinel value, so that the caller can know
     * something went wrong.
     *
     * @param lookup privileged lookup context for the class
     *               containing some autonomous statics
     * @param constantName unused
     * @param constantType unused
     * @param autoinit a method handle to execute auto-static initializers
     * @return a {@code AutoStaticManager} object that will coordinate execution of auto-static initializers
     *
     * @throws NullPointerException if any argument is {@code null}
     * @jvms 4.4.10 The CONSTANT_Dynamic_info and CONSTANT_InvokeDynamic_info Structures
     */
    public static AutoStaticManager make(MethodHandles.Lookup lookup,
                                         String constantName,
                                         Class<?> constantType,
                                         MethodHandle autoinit) {
        return new AutoStaticManager(lookup, autoinit);
    }

    /**
     * Bootstrap method for initializing a single autonomous static,
     * using a previously created {@code AutoStaticManager}.
     *
     * @param lookup unused
     * @param constantName the name of the static to initialize
     * @param constantType unused
     * @param manager the {@code AutoStaticManager} for this static's class
     * @return the initialized value of the static
     * @throws NullPointerException if any argument is {@code null}
     * @jvms 4.4.10 The CONSTANT_Dynamic_info and CONSTANT_InvokeDynamic_info Structures
     */
    public static Object initialize(MethodHandles.Lookup lookup,
                                    String constantName,
                                    Class<?> constantType,
                                    AutoStaticManager manager) {
        manager.check(lookup);
        return manager.initialize(constantName);
    }

    static final boolean TRACE_AUTO_STATICS;
    static {
        Properties props = GetPropertyAction.privilegedGetProperties();
        TRACE_AUTO_STATICS = Boolean.parseBoolean(
                props.getProperty("java.lang.runtime.AutoStaticManager.TRACE_AUTO_STATICS"));
    }
}
