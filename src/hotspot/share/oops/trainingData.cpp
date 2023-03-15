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
#include "ci/ciEnv.hpp"
#include "ci/ciMetadata.hpp"
#include "ci/ciObject.hpp"
#include "classfile/javaClasses.hpp"
#include "compiler/compileTask.hpp"
#include "memory/metadataFactory.hpp"
#include "oops/fieldStreams.inline.hpp"
#include "oops/trainingData.hpp"
#include "runtime/jniHandles.inline.hpp"
#include "runtime/fieldDescriptor.inline.hpp"
#include "runtime/javaThread.inline.hpp"
#include "utilities/xmlstream.hpp"

int TrainingData::_clinit_count;

bool TrainingData::add_initialization_touch(Klass* requester) {
  int new_count = Atomic::add(&_initialization_touch_count, 1);
  if (new_count != 1)  return false;
  if (requester && requester->is_instance_klass()) {
    // To keep the requester alive during the training run, use an oop.
    assert(_first_requester.is_empty(), "no double set");
    Handle mirror(JavaThread::current(), requester->java_mirror());
    _first_requester = class_loader_data()->add_handle(mirror);
  }
  return true;
}

// Returns the first initializer, if it was an instance klass, else null.
InstanceKlass* TrainingData::first_requester() {
  if (!has_initialization_touch())  return nullptr;
  oop mirror = _first_requester.resolve();
  if (!java_lang_Class::is_instance(mirror))
    return nullptr;
  else
    return InstanceKlass::cast(java_lang_Class::as_Klass(mirror));
}

// Note:  Racers may do this more than once.
// So, make no externally visible side effects.
void TrainingData::setup_field_array(TRAPS) {
  int num_statics = 0;
  for (int pass = 0; pass <= 1; pass++) {
    int i = 0;
    for (JavaFieldStream fs(holder()); !fs.done(); fs.next()) {
      if (!fs.access_flags().is_static())
        continue;  // only tracking static fields
      if (fs.access_flags().is_final() && fs.initval_index() != 0)
        continue;  // skip constants initialized directly by the JVM
      if (pass == 1) {
        // set up tracking data for the field
        FieldData& data = _static_fields->adr_at(i)[0];
        data.init_from(fs.field_descriptor());
        if (!field_state_is_clean(&data))
          data._fieldinit_sequence_index = ++_fieldinit_count;
      }
      ++i;
    }
    if (pass == 1)  break;
    num_statics = i;
    if (!num_statics)  return;
    _static_fields = MetadataFactory::new_array<FieldData>(class_loader_data(), num_statics, CHECK);
  }
}

// Combined linear search pass to find the name, and also
// note missed field updates.  It could be a fancy binary search,
// except we want to do a linear walk anyway to look for updates.
// It is possible we missed an initial `putstatic`, or maybe it never happened.
// Work around the leaky detection by periodic checks for evidence of inits.
TrainingData::FieldData*
TrainingData::check_field_states_and_find_field(Symbol* name) {
  int len;
  if (_static_fields == nullptr || (len = _static_fields->length()) == 0)
    return nullptr;
  FieldData* result = nullptr;
  for (int i = 0; i < len; i++) {
    FieldData* fdata = _static_fields->adr_at(i);
    if (fdata->_name == name)  result = fdata;
    if (fdata->_fieldinit_sequence_index == 0 &&
        !field_state_is_clean(fdata)) {
      // Oops, a missed update.  Track it after the fact.
      assert(!all_field_states_done(), "");
      record_static_field_init(fdata, "unknown");
    }
  }
  return result;
}

bool TrainingData::record_static_field_init(FieldData* fdata,
                                            const char* reason) {
  int& seq = fdata->_fieldinit_sequence_index;
  int PENDING = -1;
  int found = Atomic::cmpxchg(&seq, 0, PENDING, memory_order_conservative);
  if (found != 0)  return false;  // racer beat us to it
  Atomic::store(&seq, next_fieldinit_count());
  {
    ttyLocker ttyl;
    xtty->begin_elem("initialize_static_field");
    xtty->klass(holder());
    print_iclock_attr(holder(), xtty, seq);
    xtty->name(fdata->_name);
    xtty->print(" reason='%s'", reason);
    xtty->thread();
    xtty->stamp();
    xtty->end_elem();
  }
  return true;
}

void TrainingData::print_klass_attrs(xmlStream* xtty,
                                     Klass* klass, const char* prefix) {
  if (!klass)  return;
  xtty->klass(klass, prefix);
  if (!klass->is_instance_klass())  return;

  // print a little more information in case it is useful
  InstanceKlass* ik = InstanceKlass::cast(klass);
  int ikf = ik->access_flags().as_int() & (u2)-1;
  ikf &= ~JVM_ACC_SUPER;  // this is strictly noise
  char ikf2[20];
  char* ikf2p = &ikf2[0];
  if (ik->is_sealed()) { *ikf2p++ = 's'; }
  *ikf2p = 0;
  // no need for is_hidden since the name makes it obvious
  xtty->print(" %skflags='%d%s'", prefix, ikf, &ikf2[0]);
  print_iclock_attr(ik, xtty, -1, prefix);
}

void TrainingData::print_iclock_attr(InstanceKlass* klass,
                                     xmlStream* xtty,
                                     int fieldinit_index,
                                     const char* prefix) {
  TrainingData* tdata = klass->training_data_or_null();
  const int all_fields_done = 9999;
  int clinit_index = 0;
  if (tdata != nullptr) {
    if (fieldinit_index < 0) {
      if (tdata->_clinit_is_done)
        fieldinit_index = all_fields_done;
      else {
        fieldinit_index = tdata->_fieldinit_count;
        if (fieldinit_index > 900) {
          // ... 42.899, 42.900, 42.900901, 42.900902, ... 42.930000
          fieldinit_index += 900000;
        }
      }
    }
    clinit_index = tdata->clinit_sequence_index_or_zero();
  }
  const char* istate = "";
  if (klass->is_initialized()) {
    if (tdata != nullptr)
      tdata->_clinit_is_done = true;  // notice this, just in case
    fieldinit_index = all_fields_done;
  } else if (klass->is_not_initialized()) {
    if (tdata == nullptr || clinit_index != 0)
      istate = "U";
  } else if (klass->is_being_initialized()) {
    // check for intermediate states:  R = recursive, O = other thread
    istate = klass->is_init_thread(JavaThread::current()) ? "R" : "O";
  } else {
    istate = "E";  // initialization error, which is very rare
  }
  if (fieldinit_index < 0)
    fieldinit_index = 0;
  if (fieldinit_index < 100000)
    xtty->print(" %siclock='%d.%03d%s'", prefix,
                clinit_index, fieldinit_index, istate);
  else
    // avoid clock wrap for ridiculous field counts
    xtty->print(" %siclock='%d.%06d%s'", prefix,
                clinit_index, fieldinit_index, istate);
}


// Decide if the field state looks clean.
// Without further effort we cannot tell if someone has just stored
// the default value, so this query can return false positives,
// claims that a field is "clean" even if it has been subject to updates.
bool TrainingData::field_state_is_clean(FieldData* fdata) {
  oop mirror = holder()->java_mirror();
  int fo = fdata->_offset;
  switch (fdata->_type) {
  case T_OBJECT:
  case T_ARRAY:
    return (mirror->obj_field(fo) == nullptr);
  case T_BYTE:
    return (mirror->byte_field(fo) == 0);
  case T_BOOLEAN:
    return (mirror->bool_field(fo) == 0);
  case T_CHAR:
    return (mirror->char_field(fo) == 0);
  case T_SHORT:
    return (mirror->short_field(fo) == 0);
  case T_INT:
  case T_FLOAT:
    // use int field format to test for zero because of -0.0f
    return (mirror->int_field(fo) == 0);
  case T_LONG:
  case T_DOUBLE:
    // use long field format to test for zero because of -0.0d
    return (mirror->long_field(fo) == 0);
  default:
    break;
  }
  return true;
}

// called externally
bool TrainingData::record_static_field_init(fieldDescriptor* fd,
                                            const char* reason) {
  if (!_static_fields)  return false;  // should not happen unless OOM
  if (fd->field_holder() != holder())  return false;  // should not happen...
  FieldData* fdp = check_field_states_and_find_field(fd->name());
  if (fdp == nullptr)  return false;
  return record_static_field_init(fdp, reason);
}

void TrainingData::record_touch_common(xmlStream* xtty,
                                       const char* reason,
                                       CompileTask* jit_task,
                                       Klass* init_klass,
                                       Klass* requesting_klass,
                                       Symbol* name,
                                       Symbol* sig,
                                       const char* context) {  
  xtty->begin_elem("initialization_touch reason='%s'", reason);
  if (context)  xtty->print(" context='%s'", context);
  print_klass_attrs(xtty, holder());
  if (name)  xtty->name(name);
  if (sig)   xtty->signature(sig);
  // report up to two requesting parties
  for (int pass = 0; pass <= 1; pass++) {
    Klass* k = !pass ? init_klass : requesting_klass;
    if (!k)  continue;
    if (pass && k == init_klass)  break;
    const char* prefix = !pass ? "init_" : "requesting_";
    if (k == holder()) {
      xtty->print(" %sklass='//self'", prefix); continue;
    }
    print_klass_attrs(xtty, k, prefix);
  }
  if (!init_klass && !requesting_klass) {
    xtty->print_raw(" requesting_klass=''");
  }
  if (jit_task != nullptr) {
    xtty->print(" compile_id='%d'", jit_task->compile_id());
  }
  xtty->thread();
  xtty->stamp();
  xtty->end_elem();
}

void TrainingData::record_initialization_touch(const char* reason,
                                               Symbol* name,
                                               Symbol* sig,
                                               Klass* requesting_klass,
                                               const char* context,
                                               TRAPS) {
  Klass* init_klass = THREAD->class_being_initialized();
  if (!strcmp(reason, "super")) {
    // Extra-special touch during class initialization per JVMS Step 7.
    // We track this touch as if from RK.<clinit>, even if RK doesn't have one.
    init_klass = requesting_klass;
    requesting_klass = nullptr;  // ignore any real <clinit> on stack
  }
  add_initialization_touch(init_klass ? init_klass : requesting_klass);
  ttyLocker ttyl;
  record_touch_common(xtty, reason, /*jit_env*/ nullptr,
                      init_klass, requesting_klass,
                      name, sig, context);
}

void TrainingData::record_jit_observation(ciEnv* env, ciBaseObject* what) {
  // A JIT is starting to look at class k.
  // We could follow the queries that it is making, but it is
  // simpler to assume, conservatively, that the JIT will
  // eventually depend on the initialization state of k.
  CompileTask* task = env->task();
  CompileLog*  xtty = env->log();
  if (task == nullptr || xtty == nullptr)  return;
  Method* method = task->method();
  InstanceKlass* compiling_klass = method->method_holder();
  if (what->is_metadata()) {
    ciMetadata* md = what->as_metadata();
    if (md->is_instance_klass()) {
      InstanceKlass* ik = md->as_instance_klass()->get_instanceKlass();
      TrainingData* tdata = ik->training_data_or_null();
      if (tdata == nullptr)  return;
      tdata->record_touch_common(env->log(), "jit", task,
                                 compiling_klass, nullptr,
                                 method->name(), method->signature(),
                                 nullptr);
    }
  }
}

void TrainingData::record_initialization_start() {
  ttyLocker ttyl;
  assert(_clinit_sequence_index == 0, "set this under mutex");
  _clinit_sequence_index = next_clinit_count();
  xtty->begin_elem("initialization");
  print_klass_attrs(xtty, holder());
  xtty->thread();
  xtty->stamp();
  xtty->end_elem();
} 

void TrainingData::record_initialization_end() {
  // Note:  The XML records might not nest properly.
  // This is why we use <init/> and <init_done/>.  Buyer beware!
  ttyLocker ttyl;
  xtty->begin_elem("initialization_done");
  print_klass_attrs(xtty, holder());
  xtty->thread();
  xtty->stamp();
  xtty->end_elem();
  _clinit_is_done = true;  // we know this now
}
