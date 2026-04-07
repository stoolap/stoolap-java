// Copyright 2025 Stoolap Contributors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

//! JNI bindings for the Stoolap database engine.
//!
//! Calls `stoolap::api` directly (no C FFI layer). Each Java handle is a
//! `Box`-ed Rust object stored as a `jlong` pointer. Bulk row encoding is done
//! entirely in Rust and returned as a `DirectByteBuffer` (zero-copy).

use jni::objects::{JByteArray, JClass, JLongArray, JObject, JString};
use jni::sys::{jboolean, jint, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use stoolap::api::{Database, Rows, Transaction as ApiTransaction};
use stoolap::executor::CachedPlanRef;
use stoolap::{DataType, IsolationLevel, Value};

/// Our prepared statement: bundles the cached plan with a database clone
/// so we can execute directly without SQL parsing or cache lookups.
pub struct PreparedStmt {
    plan: CachedPlanRef,
    db: Database,
}

// ============================================================
// Helpers
// ============================================================

/// Throw a Java SQLException and return `default`.
fn throw_sql<T>(env: &mut JNIEnv, msg: &str, default: T) -> T {
    let _ = env.throw_new("java/sql/SQLException", msg);
    default
}

/// Convert a jlong back to a Box<T> reference (borrow, not consume).
unsafe fn as_ref<'a, T>(ptr: jlong) -> &'a T {
    unsafe { &*(ptr as *const T) }
}

/// Convert a jlong back to a mutable Box<T> reference (borrow, not consume).
unsafe fn as_mut<'a, T>(ptr: jlong) -> &'a mut T {
    unsafe { &mut *(ptr as *mut T) }
}

/// Decode binary-encoded params from Java ParamEncoder format.
/// Format: [param_count: u32 LE] [for each: type_tag:u8 + payload]
fn decode_binary_params(data: &[u8]) -> Vec<Value> {
    if data.len() < 4 {
        return Vec::new();
    }
    let count = u32::from_le_bytes([data[0], data[1], data[2], data[3]]) as usize;
    let mut values = Vec::with_capacity(count);
    let mut pos = 4;
    for _ in 0..count {
        if pos >= data.len() {
            break;
        }
        let tag = data[pos];
        pos += 1;
        match tag {
            0 => values.push(Value::Null(DataType::Text)),
            1 => {
                let v = i64::from_le_bytes(data[pos..pos + 8].try_into().unwrap());
                pos += 8;
                values.push(Value::Integer(v));
            }
            2 => {
                let v = f64::from_le_bytes(data[pos..pos + 8].try_into().unwrap());
                pos += 8;
                values.push(Value::Float(v));
            }
            3 => {
                let len = u32::from_le_bytes(data[pos..pos + 4].try_into().unwrap()) as usize;
                pos += 4;
                let s = std::str::from_utf8(&data[pos..pos + len])
                    .unwrap_or("")
                    .to_string();
                pos += len;
                values.push(Value::Text(s.into()));
            }
            4 => {
                let v = data[pos] != 0;
                pos += 1;
                values.push(Value::Boolean(v));
            }
            _ => values.push(Value::Null(DataType::Text)),
        }
    }
    values
}

/// Decode batch binary params. Format: [row_count:u32][param_count:u32][rows...]
fn decode_binary_batch(data: &[u8]) -> Vec<Vec<Value>> {
    if data.len() < 8 {
        return Vec::new();
    }
    let row_count = u32::from_le_bytes(data[0..4].try_into().unwrap()) as usize;
    let param_count = u32::from_le_bytes(data[4..8].try_into().unwrap()) as usize;
    let mut rows = Vec::with_capacity(row_count);
    let mut pos = 8;
    for _ in 0..row_count {
        let mut values = Vec::with_capacity(param_count);
        for _ in 0..param_count {
            if pos >= data.len() {
                break;
            }
            let tag = data[pos];
            pos += 1;
            match tag {
                0 => values.push(Value::Null(DataType::Text)),
                1 => {
                    let v = i64::from_le_bytes(data[pos..pos + 8].try_into().unwrap());
                    pos += 8;
                    values.push(Value::Integer(v));
                }
                2 => {
                    let v = f64::from_le_bytes(data[pos..pos + 8].try_into().unwrap());
                    pos += 8;
                    values.push(Value::Float(v));
                }
                3 => {
                    let len = u32::from_le_bytes(data[pos..pos + 4].try_into().unwrap()) as usize;
                    pos += 4;
                    let s = std::str::from_utf8(&data[pos..pos + len])
                        .unwrap_or("")
                        .to_string();
                    pos += len;
                    values.push(Value::Text(s.into()));
                }
                4 => {
                    let v = data[pos] != 0;
                    pos += 1;
                    values.push(Value::Boolean(v));
                }
                _ => values.push(Value::Null(DataType::Text)),
            }
        }
        rows.push(values);
    }
    rows
}

/// Get byte[] from JNI and decode params.
fn get_binary_params(env: &mut JNIEnv, bin: &JByteArray) -> Vec<Value> {
    match env.convert_byte_array(bin) {
        Ok(bytes) => decode_binary_params(&bytes),
        Err(_) => Vec::new(),
    }
}

/// Encode all rows from a Rows iterator into the packed binary format.
/// Same wire format as stoolap_rows_fetch_all (see BulkDecoder.java).
fn encode_rows(rows: &mut Rows) -> Vec<u8> {
    let columns = rows.columns().to_vec();
    let col_count = columns.len();

    // Collect all rows first (we need row count upfront)
    let mut all_rows: Vec<Vec<Value>> = Vec::new();
    while rows.advance() {
        let row = rows.current_row();
        let mut values = Vec::with_capacity(col_count);
        for i in 0..col_count {
            match row.get(i) {
                Some(v) => values.push(v.clone()),
                None => values.push(Value::Null(DataType::Text)),
            }
        }
        all_rows.push(values);
    }

    // Estimate capacity
    let mut buf = Vec::with_capacity(4 + col_count * 32 + 4 + all_rows.len() * col_count * 16);

    // Column count (u32 LE)
    buf.extend_from_slice(&(col_count as u32).to_le_bytes());

    // Column names
    for name in &columns {
        let name_bytes = name.as_bytes();
        buf.extend_from_slice(&(name_bytes.len() as u16).to_le_bytes());
        buf.extend_from_slice(name_bytes);
    }

    // Row count (u32 LE)
    buf.extend_from_slice(&(all_rows.len() as u32).to_le_bytes());

    // Row data
    for row_values in &all_rows {
        for val in row_values {
            match val {
                Value::Null(_) => buf.push(0),
                Value::Integer(v) => {
                    buf.push(1);
                    buf.extend_from_slice(&v.to_le_bytes());
                }
                Value::Float(v) => {
                    buf.push(2);
                    buf.extend_from_slice(&v.to_le_bytes());
                }
                Value::Text(s) => {
                    buf.push(3);
                    let bytes = s.as_bytes();
                    buf.extend_from_slice(&(bytes.len() as u32).to_le_bytes());
                    buf.extend_from_slice(bytes);
                }
                Value::Boolean(v) => {
                    buf.push(4);
                    buf.push(if *v { 1 } else { 0 });
                }
                Value::Timestamp(ts) => {
                    buf.push(5);
                    let nanos = ts.timestamp_nanos_opt().unwrap_or(0);
                    buf.extend_from_slice(&nanos.to_le_bytes());
                }
                Value::Extension(ext) => {
                    let bytes = ext.as_ref();
                    if bytes.is_empty() {
                        buf.push(0); // null
                    } else {
                        let tag = bytes[0];
                        let payload = &bytes[1..];
                        // tag 6 = JSON (DataType::Json = 6), else blob
                        if tag == 6 {
                            buf.push(6); // JSON
                            buf.extend_from_slice(&(payload.len() as u32).to_le_bytes());
                            buf.extend_from_slice(payload);
                        } else {
                            buf.push(7); // BLOB
                            buf.extend_from_slice(&(payload.len() as u32).to_le_bytes());
                            buf.extend_from_slice(payload);
                        }
                    }
                }
            }
        }
    }
    buf
}

// ============================================================
// Database lifecycle
// ============================================================

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_open(
    mut env: JNIEnv,
    _class: JClass,
    dsn: JString,
) -> jlong {
    let dsn: String = match env.get_string(&dsn) {
        Ok(s) => s.into(),
        Err(e) => return throw_sql(&mut env, &e.to_string(), 0),
    };
    match Database::open(&dsn) {
        Ok(db) => Box::into_raw(Box::new(db)) as jlong,
        Err(e) => throw_sql(&mut env, &e.to_string(), 0),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_openInMemory(
    mut env: JNIEnv,
    _class: JClass,
) -> jlong {
    match Database::open_in_memory() {
        Ok(db) => Box::into_raw(Box::new(db)) as jlong,
        Err(e) => throw_sql(&mut env, &e.to_string(), 0),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_closeDb(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    if ptr != 0 {
        unsafe {
            drop(Box::from_raw(ptr as *mut Database));
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_cloneDb(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jlong {
    let db = unsafe { as_ref::<Database>(ptr) };
    Box::into_raw(Box::new(db.clone())) as jlong
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_version<'a>(
    env: JNIEnv<'a>,
    _class: JClass<'a>,
) -> JObject<'a> {
    let v = env!("CARGO_PKG_VERSION");
    match env.new_string(v) {
        Ok(s) => s.into(),
        Err(_) => JObject::null(),
    }
}

// ============================================================
// Execute (DDL/DML)
// ============================================================

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_exec(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    sql: JString,
) -> jlong {
    let db = unsafe { as_ref::<Database>(ptr) };
    let sql: String = match env.get_string(&sql) {
        Ok(s) => s.into(),
        Err(e) => return throw_sql(&mut env, &e.to_string(), -1),
    };
    match db.execute(&sql, ()) {
        Ok(n) => n,
        Err(e) => throw_sql(&mut env, &e.to_string(), -1),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_execParams(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    sql: JString,
    params: JByteArray,
) -> jlong {
    let db = unsafe { as_ref::<Database>(ptr) };
    let sql: String = match env.get_string(&sql) {
        Ok(s) => s.into(),
        Err(e) => return throw_sql(&mut env, &e.to_string(), -1),
    };
    let values = get_binary_params(&mut env, &params);
    match db.execute(&sql, values.as_slice()) {
        Ok(n) => n,
        Err(e) => throw_sql(&mut env, &e.to_string(), -1),
    }
}

// ============================================================
// Query — returns packed binary buffer via byte[]
// ============================================================

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_query<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    ptr: jlong,
    sql: JString,
) -> JByteArray<'a> {
    let db = unsafe { as_ref::<Database>(ptr) };
    let sql: String = match env.get_string(&sql) {
        Ok(s) => s.into(),
        Err(e) => return throw_sql(&mut env, &e.to_string(), JByteArray::default()),
    };
    match db.query(&sql, ()) {
        Ok(mut rows) => {
            let buf = encode_rows(&mut rows);
            match env.byte_array_from_slice(&buf) {
                Ok(arr) => arr,
                Err(e) => throw_sql(&mut env, &e.to_string(), JByteArray::default()),
            }
        }
        Err(e) => throw_sql(&mut env, &e.to_string(), JByteArray::default()),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_queryParams<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    ptr: jlong,
    sql: JString,
    params: JByteArray,
) -> JByteArray<'a> {
    let db = unsafe { as_ref::<Database>(ptr) };
    let sql: String = match env.get_string(&sql) {
        Ok(s) => s.into(),
        Err(e) => return throw_sql(&mut env, &e.to_string(), JByteArray::default()),
    };
    let values = get_binary_params(&mut env, &params);
    match db.query(&sql, values.as_slice()) {
        Ok(mut rows) => {
            let buf = encode_rows(&mut rows);
            match env.byte_array_from_slice(&buf) {
                Ok(arr) => arr,
                Err(e) => throw_sql(&mut env, &e.to_string(), JByteArray::default()),
            }
        }
        Err(e) => throw_sql(&mut env, &e.to_string(), JByteArray::default()),
    }
}

// ============================================================
// Prepared statements
// ============================================================

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_prepare(
    mut env: JNIEnv,
    _class: JClass,
    db_ptr: jlong,
    sql: JString,
) -> jlong {
    let db = unsafe { as_ref::<Database>(db_ptr) };
    let sql: String = match env.get_string(&sql) {
        Ok(s) => s.into(),
        Err(e) => return throw_sql(&mut env, &e.to_string(), 0),
    };
    match db.cached_plan(&sql) {
        Ok(plan) => {
            let stmt = PreparedStmt {
                plan,
                db: db.clone(),
            };
            Box::into_raw(Box::new(stmt)) as jlong
        }
        Err(e) => throw_sql(&mut env, &e.to_string(), 0),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_stmtClose(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    if ptr != 0 {
        unsafe {
            drop(Box::from_raw(ptr as *mut PreparedStmt));
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_stmtExec(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    params: JByteArray,
) -> jlong {
    let stmt = unsafe { as_ref::<PreparedStmt>(ptr) };
    let values = get_binary_params(&mut env, &params);
    match stmt.db.execute_plan(&stmt.plan, values.as_slice()) {
        Ok(n) => n,
        Err(e) => throw_sql(&mut env, &e.to_string(), -1),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_stmtQuery<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    ptr: jlong,
    params: JByteArray,
) -> JByteArray<'a> {
    let stmt = unsafe { as_ref::<PreparedStmt>(ptr) };
    let values = get_binary_params(&mut env, &params);
    match stmt.db.query_plan(&stmt.plan, values.as_slice()) {
        Ok(mut rows) => {
            let buf = encode_rows(&mut rows);
            match env.byte_array_from_slice(&buf) {
                Ok(arr) => arr,
                Err(e) => throw_sql(&mut env, &e.to_string(), JByteArray::default()),
            }
        }
        Err(e) => throw_sql(&mut env, &e.to_string(), JByteArray::default()),
    }
}

/// Batch execute: takes array of param arrays, runs them all in one JNI call.
#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_stmtExecBatch<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    ptr: jlong,
    batch: JByteArray,
) -> JLongArray<'a> {
    let stmt = unsafe { as_ref::<PreparedStmt>(ptr) };
    let bytes = match env.convert_byte_array(&batch) {
        Ok(b) => b,
        Err(e) => return throw_sql(&mut env, &e.to_string(), JLongArray::default()),
    };
    // Auto-wrap in a transaction for atomic batch (same as Python driver).
    let all_rows = decode_binary_batch(&bytes);
    let mut tx = match stmt.db.begin() {
        Ok(tx) => tx,
        Err(e) => return throw_sql(&mut env, &e.to_string(), JLongArray::default()),
    };
    let stmt_ast = stmt.plan.statement.as_ref();
    let mut results = vec![0i64; all_rows.len()];
    for (i, values) in all_rows.iter().enumerate() {
        match tx.execute_prepared(stmt_ast, values.as_slice()) {
            Ok(n) => results[i] = n,
            Err(e) => {
                let _ = tx.rollback();
                return throw_sql(&mut env, &e.to_string(), JLongArray::default());
            }
        }
    }
    if let Err(e) = tx.commit() {
        return throw_sql(&mut env, &e.to_string(), JLongArray::default());
    }
    match env.new_long_array(results.len() as i32) {
        Ok(arr) => {
            let _ = env.set_long_array_region(&arr, 0, &results);
            arr
        }
        Err(e) => throw_sql(&mut env, &e.to_string(), JLongArray::default()),
    }
}

// ============================================================
// ApiTransactions
// ============================================================

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_begin(
    mut env: JNIEnv,
    _class: JClass,
    db_ptr: jlong,
    isolation: jint,
) -> jlong {
    let db = unsafe { as_ref::<Database>(db_ptr) };
    let level = if isolation == 1 {
        IsolationLevel::SnapshotIsolation
    } else {
        IsolationLevel::ReadCommitted
    };
    match db.begin_with_isolation(level) {
        Ok(tx) => Box::into_raw(Box::new(tx)) as jlong,
        Err(e) => throw_sql(&mut env, &e.to_string(), 0),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_txCommit(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jboolean {
    if ptr == 0 {
        return JNI_FALSE;
    }
    let tx = unsafe { as_mut::<ApiTransaction>(ptr) };
    match tx.commit() {
        Ok(()) => JNI_TRUE,
        Err(e) => {
            throw_sql(&mut env, &e.to_string(), ());
            JNI_FALSE
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_txRollback(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jboolean {
    if ptr == 0 {
        return JNI_FALSE;
    }
    let tx = unsafe { as_mut::<ApiTransaction>(ptr) };
    match tx.rollback() {
        Ok(()) => JNI_TRUE,
        Err(e) => {
            throw_sql(&mut env, &e.to_string(), ());
            JNI_FALSE
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_txClose(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    if ptr != 0 {
        unsafe {
            drop(Box::from_raw(ptr as *mut ApiTransaction));
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_txExec(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    sql: JString,
) -> jlong {
    let tx = unsafe { as_mut::<ApiTransaction>(ptr) };
    let sql: String = match env.get_string(&sql) {
        Ok(s) => s.into(),
        Err(e) => return throw_sql(&mut env, &e.to_string(), -1),
    };
    match tx.execute(&sql, ()) {
        Ok(n) => n,
        Err(e) => throw_sql(&mut env, &e.to_string(), -1),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_txExecParams(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    sql: JString,
    params: JByteArray,
) -> jlong {
    let tx = unsafe { as_mut::<ApiTransaction>(ptr) };
    let sql: String = match env.get_string(&sql) {
        Ok(s) => s.into(),
        Err(e) => return throw_sql(&mut env, &e.to_string(), -1),
    };
    let values = get_binary_params(&mut env, &params);
    match tx.execute(&sql, values.as_slice()) {
        Ok(n) => n,
        Err(e) => throw_sql(&mut env, &e.to_string(), -1),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_txQuery<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    ptr: jlong,
    sql: JString,
) -> JByteArray<'a> {
    let tx = unsafe { as_mut::<ApiTransaction>(ptr) };
    let sql: String = match env.get_string(&sql) {
        Ok(s) => s.into(),
        Err(e) => return throw_sql(&mut env, &e.to_string(), JByteArray::default()),
    };
    match tx.query(&sql, ()) {
        Ok(mut rows) => {
            let buf = encode_rows(&mut rows);
            match env.byte_array_from_slice(&buf) {
                Ok(arr) => arr,
                Err(e) => throw_sql(&mut env, &e.to_string(), JByteArray::default()),
            }
        }
        Err(e) => throw_sql(&mut env, &e.to_string(), JByteArray::default()),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_txQueryParams<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    ptr: jlong,
    sql: JString,
    params: JByteArray,
) -> JByteArray<'a> {
    let tx = unsafe { as_mut::<ApiTransaction>(ptr) };
    let sql: String = match env.get_string(&sql) {
        Ok(s) => s.into(),
        Err(e) => return throw_sql(&mut env, &e.to_string(), JByteArray::default()),
    };
    let values = get_binary_params(&mut env, &params);
    match tx.query(&sql, values.as_slice()) {
        Ok(mut rows) => {
            let buf = encode_rows(&mut rows);
            match env.byte_array_from_slice(&buf) {
                Ok(arr) => arr,
                Err(e) => throw_sql(&mut env, &e.to_string(), JByteArray::default()),
            }
        }
        Err(e) => throw_sql(&mut env, &e.to_string(), JByteArray::default()),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_txStmtExec(
    mut env: JNIEnv,
    _class: JClass,
    tx_ptr: jlong,
    stmt_ptr: jlong,
    params: JByteArray,
) -> jlong {
    let tx = unsafe { as_mut::<ApiTransaction>(tx_ptr) };
    let stmt = unsafe { as_ref::<PreparedStmt>(stmt_ptr) };
    let values = get_binary_params(&mut env, &params);
    match tx.execute_prepared(stmt.plan.statement.as_ref(), values.as_slice()) {
        Ok(n) => n,
        Err(e) => throw_sql(&mut env, &e.to_string(), -1),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_txStmtQuery<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    tx_ptr: jlong,
    stmt_ptr: jlong,
    params: JByteArray,
) -> JByteArray<'a> {
    let tx = unsafe { as_mut::<ApiTransaction>(tx_ptr) };
    let stmt = unsafe { as_ref::<PreparedStmt>(stmt_ptr) };
    let values = get_binary_params(&mut env, &params);
    match tx.query_prepared(stmt.plan.statement.as_ref(), values.as_slice()) {
        Ok(mut rows) => {
            let buf = encode_rows(&mut rows);
            match env.byte_array_from_slice(&buf) {
                Ok(arr) => arr,
                Err(e) => throw_sql(&mut env, &e.to_string(), JByteArray::default()),
            }
        }
        Err(e) => throw_sql(&mut env, &e.to_string(), JByteArray::default()),
    }
}

/// Batch execute prepared statement within a transaction.
#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_txStmtExecBatch<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    tx_ptr: jlong,
    stmt_ptr: jlong,
    batch: JByteArray,
) -> JLongArray<'a> {
    let tx = unsafe { as_mut::<ApiTransaction>(tx_ptr) };
    let stmt = unsafe { as_ref::<PreparedStmt>(stmt_ptr) };
    let stmt_ast = stmt.plan.statement.as_ref();
    let bytes = match env.convert_byte_array(&batch) {
        Ok(b) => b,
        Err(e) => return throw_sql(&mut env, &e.to_string(), JLongArray::default()),
    };
    let all_rows = decode_binary_batch(&bytes);
    let mut results = vec![0i64; all_rows.len()];
    for (i, values) in all_rows.iter().enumerate() {
        match tx.execute_prepared(stmt_ast, values.as_slice()) {
            Ok(n) => results[i] = n,
            Err(e) => return throw_sql(&mut env, &e.to_string(), JLongArray::default()),
        }
    }
    match env.new_long_array(results.len() as i32) {
        Ok(arr) => {
            let _ = env.set_long_array_region(&arr, 0, &results);
            arr
        }
        Err(e) => throw_sql(&mut env, &e.to_string(), JLongArray::default()),
    }
}
