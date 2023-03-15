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

#ifndef SHARE_OOPS_TRAININGDATA_HPP
#define SHARE_OOPS_TRAININGDATA_HPP

#include "oops/instanceKlass.hpp"
#include "runtime/fieldDescriptor.inline.hpp"

class ciEnv;
class ciBaseObject;
class CompileTask;
class xmlStream;

class TrainingData: public CHeapObj<mtClass> {
 public:
  struct FieldData {
    Symbol*   _name;    // name of field, for making reports (no refcount)
    int      _index;    // index in the field stream (a unique id)
    BasicType _type;    // what kind of field is it?
    int     _offset;    // offset of field storage, within mirror
    int _fieldinit_sequence_index;  // 1-based local initialization order
    void init_from(fieldDescriptor& fd) {
      _name = fd.name();
      _index = fd.index();
      _offset = fd.offset(); 
      _type = fd.field_type();
      _fieldinit_sequence_index = 0;
    }
  };
 private:
  InstanceKlass* const _holder;
  OopHandle _first_requester;       // who triggered my <clinit>, the first time
  int _initialization_touch_count;  // a count of all such events
  int _clinit_sequence_index;       // 1-based global initialization order
  Array<FieldData>* _static_fields;
  int _fieldinit_count;  // count <= _static_fields.length()
  bool _clinit_is_done;

  static int _clinit_count;  // global count (so far) of clinit events
  static int next_clinit_count() {
    return Atomic::add(&_clinit_count, 1);
  }
  int next_fieldinit_count() {
    return Atomic::add(&_fieldinit_count, 1);
  }

  ClassLoaderData* class_loader_data() { return holder()->class_loader_data(); }
  void setup_field_array(TRAPS);
  bool field_state_is_clean(FieldData* fdata);
  FieldData* check_field_states_and_find_field(Symbol* name);
  bool all_field_states_done() {
    return _static_fields != nullptr && _static_fields->length() == _fieldinit_count;
  }
  static void print_klass_attrs(xmlStream* xtty,
                                Klass* klass, const char* prefix = "");
  static void print_iclock_attr(InstanceKlass* klass,
                                xmlStream* xtty,
                                int fieldinit_index = -1,
                                const char* prefix = "");

  void record_touch_common(xmlStream* xtty,
                           const char* reason,
                           CompileTask* jit_task,
                           Klass* init_klass,
                           Klass* requesting_klass,
                           Symbol* name,
                           Symbol* sig,
                           const char* context);

public:
  TrainingData(InstanceKlass* holder, TRAPS)
    : _holder(holder)
  {
    _first_requester = OopHandle();
    _initialization_touch_count = 0;
    _clinit_sequence_index = 0;
    _static_fields = nullptr;
    _fieldinit_count = 0;
    _clinit_is_done = false;
    setup_field_array(THREAD);
  }

  InstanceKlass* holder() { return _holder; }

  InstanceKlass* first_requester();

  // A 1-based global order in which <clinit> was called, or zero if
  // that never did happen, or has not yet happened.
  int clinit_sequence_index_or_zero() {
    return _clinit_sequence_index;
  }

  // How many "touches" have been recorded for this one?
  int initialization_touch_count() {
    return Atomic::load_acquire(&_initialization_touch_count);
  }
  bool has_initialization_touch() {
    return initialization_touch_count() > 0;
  }
  bool add_initialization_touch(Klass* requester);

  // For some reason, somebody is touching my class (this->holder())
  // and that might be relevant to my class's initialization state.
  // We collect these events even after my class is fully initialized.
  //
  // The requesting class, if not null, is the class which is causing
  // the event, somehow (depending on the reason).
  //
  // The name and signature, if not null, are somehow relevant to
  // the event; depending on the reason, they might refer to a
  // member of my class, or else to a member of the requesting class.
  //
  // The context is a little extra information.
  //
  // The record that will be emitted records all this information,
  // plus extra stuff, notably whether there is a <clinit> execution
  // on stack, and if so, who that is.  Often, the class running its
  // <clinit> is even more interesting than the requesting class.
  void record_initialization_touch(const char* reason,
                                   Symbol* name,
                                   Symbol* sig,
                                   Klass* requesting_klass,
                                   const char* context,
                                   TRAPS);

  // The JIT looks at classes and objects too and can depend on their state.
  // These simple calls just report the *possibility* of an observation.
  static void record_jit_observation(ciEnv* env, ciBaseObject* what);

  void record_initialization_start();
  void record_initialization_end();

  // Record that we have witnessed the initialization of the name.
  // This is called when we know we are doing a `putstatic` or equivalent.
  // It can be called either just before or just after.  It is only
  // safe to call this inside the initializing thread.
  bool record_static_field_init(fieldDescriptor* fd, const char* reason);

  bool record_static_field_init(FieldData* fdata, const char* reason);
};

#endif // SHARE_OOPS_TRAININGDATA_HPP
