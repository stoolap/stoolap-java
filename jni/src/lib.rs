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
//! `Box`-ed Rust object stored as a `jlong` pointer; the Java side
//! serialises access to each handle and never frees one in use. Results are
//! packed in Rust into one `byte[]` per call (see `BulkDecoder.java`), and
//! parameters arrive packed the same way (see `ParamEncoder.java`).

use std::cell::RefCell;
use std::panic::{catch_unwind, AssertUnwindSafe};

use chrono::{DateTime, Utc};
use jni::objects::{JByteArray, JClass, JLongArray, JObject, JString, JThrowable, JValue};
use jni::sys::{jboolean, jint, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use stoolap::api::{
    Database, NamedParams, ParamVec, Rows, Statement, Transaction as ApiTransaction,
};
use stoolap::parser::ast::{RollbackStatement, Statement as Ast};
use stoolap::parser::Parser;
use stoolap::{DataType, Error, IsolationLevel, Value};

/// A result read in chunks. `rows` is `None` once the result is exhausted.
pub struct Cursor {
    rows: Option<Rows>,
    columns: Vec<String>,
    affected: i64,
}

// ============================================================
// Errors
// ============================================================

/// An error for Java: its message and, when known, its SQLState.
#[derive(Debug)]
struct Fail {
    msg: String,
    state: Option<&'static str>,
}

type R<T> = Result<T, Fail>;

impl From<String> for Fail {
    fn from(msg: String) -> Self {
        Fail { msg, state: None }
    }
}

impl From<&str> for Fail {
    fn from(msg: &str) -> Self {
        msg.to_string().into()
    }
}

impl From<Error> for Fail {
    fn from(e: Error) -> Self {
        let state = sqlstate(&e);
        Fail {
            msg: e.to_string(),
            state,
        }
    }
}

impl From<jni::errors::Error> for Fail {
    fn from(e: jni::errors::Error) -> Self {
        e.to_string().into()
    }
}

/// The SQLState of an engine error, so callers can tell a constraint
/// violation from a conflict worth retrying.
fn sqlstate(e: &Error) -> Option<&'static str> {
    Some(match e {
        Error::Parse(_) => "42601",
        Error::TableNotFound(_) | Error::TableOrViewNotFound(_) | Error::ViewNotFound(_) => "42P01",
        Error::ColumnNotFound(_) => "42703",
        Error::IndexNotFound(_) => "42704",
        Error::TableAlreadyExists(_)
        | Error::ViewAlreadyExists(_)
        | Error::IndexAlreadyExists(_)
        | Error::DuplicateColumn => "42P07",
        Error::NotNullConstraint { .. } => "23502",
        Error::PrimaryKeyConstraint { .. } | Error::UniqueConstraint { .. } => "23505",
        Error::ForeignKeyViolation { .. } => "23503",
        Error::CheckConstraintViolation { .. } | Error::TableCheckViolation { .. } => "23514",
        Error::DivisionByZero => "22012",
        Error::ValueTooLong { .. } => "22001",
        Error::TypeConversion { .. } => "22018",
        Error::Type(_) | Error::InvalidValue | Error::VectorDimensionMismatch { .. } => "22000",
        Error::InvalidArgument(_) => "22023",
        Error::TransactionNotStarted
        | Error::TransactionAlreadyStarted
        | Error::TransactionEnded
        | Error::TransactionCommitted
        | Error::TransactionClosed => "25000",
        Error::TransactionAborted => "40000",
        Error::QueryCancelled => "57014",
        Error::DatabaseLocked => "55P03",
        Error::NotSupported(_) => "0A000",
        Error::Internal { message } if message.starts_with("write conflict") => "40001",
        // The insert path reports a NOT NULL violation as an internal error
        Error::Internal { message } if message.starts_with("NULL value in non-nullable column") => {
            "23502"
        }
        _ => return None,
    })
}

/// Throws `fail` as a `java.sql.SQLException` carrying its SQLState.
fn throw(env: &mut JNIEnv, fail: &Fail) {
    let thrown = (|| -> jni::errors::Result<()> {
        let msg = JObject::from(env.new_string(&fail.msg)?);
        let state = match fail.state {
            Some(s) => JObject::from(env.new_string(s)?),
            None => JObject::null(),
        };
        let ex = env.new_object(
            "java/sql/SQLException",
            "(Ljava/lang/String;Ljava/lang/String;)V",
            &[JValue::Object(&msg), JValue::Object(&state)],
        )?;
        env.throw(JThrowable::from(ex))
    })();
    if thrown.is_err() && !env.exception_check().unwrap_or(false) {
        let _ = env.throw_new("java/sql/SQLException", &fail.msg);
    }
}

/// Runs `f`, turning an `Err` or a panic into a Java `SQLException` and
/// returning `default` in its place. A panic must never unwind into the JVM.
fn run<'a, T>(env: &mut JNIEnv<'a>, default: T, f: impl FnOnce(&mut JNIEnv<'a>) -> R<T>) -> T {
    let fail = match catch_unwind(AssertUnwindSafe(|| f(env))) {
        Ok(Ok(v)) => return v,
        Ok(Err(fail)) => fail,
        Err(_) => "internal error: the native driver panicked".into(),
    };
    if !env.exception_check().unwrap_or(false) {
        throw(env, &fail);
    }
    default
}

// ============================================================
// Handles
// ============================================================

/// Borrows the object behind a handle, refusing a closed (zero) handle.
unsafe fn handle<'h, T>(ptr: jlong, what: &str) -> R<&'h T> {
    if ptr == 0 {
        return Err(format!("{what} is closed").into());
    }
    Ok(unsafe { &*(ptr as *const T) })
}

/// Mutably borrows the object behind a handle, refusing a closed handle.
unsafe fn handle_mut<'h, T>(ptr: jlong, what: &str) -> R<&'h mut T> {
    if ptr == 0 {
        return Err(format!("{what} is closed").into());
    }
    Ok(unsafe { &mut *(ptr as *mut T) })
}

fn into_handle<T>(v: T) -> jlong {
    Box::into_raw(Box::new(v)) as jlong
}

/// Drops the object behind a handle; a zero handle is ignored. A panic in
/// a destructor is swallowed, since it must not unwind into the JVM.
unsafe fn drop_handle<T>(ptr: jlong) {
    if ptr != 0 {
        let boxed = unsafe { Box::from_raw(ptr as *mut T) };
        let _ = catch_unwind(AssertUnwindSafe(move || drop(boxed)));
    }
}

fn jstring(env: &mut JNIEnv, s: &JString) -> R<String> {
    if s.is_null() {
        return Err("SQL must not be null".into());
    }
    Ok(env.get_string(s)?.into())
}

// ============================================================
// Parameters
// ============================================================

/// Set in the leading count when the parameters are named.
const NAMED_FLAG: u32 = 1 << 31;

enum Params {
    Positional(ParamVec),
    Named(NamedParams),
}

impl Params {
    fn none() -> Self {
        Params::Positional(ParamVec::new())
    }
}

/// Reads the packed parameter format of `ParamEncoder.java`.
struct Reader<'b> {
    data: &'b [u8],
    pos: usize,
}

impl<'b> Reader<'b> {
    fn new(data: &'b [u8]) -> Self {
        Reader { data, pos: 0 }
    }

    fn take(&mut self, n: usize) -> R<&'b [u8]> {
        let end = self
            .pos
            .checked_add(n)
            .filter(|&end| end <= self.data.len())
            .ok_or("truncated parameter buffer")?;
        let bytes = &self.data[self.pos..end];
        self.pos = end;
        Ok(bytes)
    }

    /// An announced element count, capped for allocation at the bytes left,
    /// since every element takes at least one
    fn count(&mut self, announced: usize) -> usize {
        announced.min(self.data.len() - self.pos)
    }

    fn u8(&mut self) -> R<u8> {
        Ok(self.take(1)?[0])
    }

    fn u32(&mut self) -> R<u32> {
        Ok(u32::from_le_bytes(self.take(4)?.try_into().unwrap()))
    }

    fn i64(&mut self) -> R<i64> {
        Ok(i64::from_le_bytes(self.take(8)?.try_into().unwrap()))
    }

    fn str(&mut self) -> R<&'b str> {
        let len = self.u32()? as usize;
        std::str::from_utf8(self.take(len)?)
            .map_err(|_| Fail::from("parameter text is not valid UTF-8"))
    }

    fn value(&mut self) -> R<Value> {
        Ok(match self.u8()? {
            0 => Value::Null(DataType::Null),
            1 => Value::Integer(self.i64()?),
            2 => Value::Float(f64::from_bits(self.i64()? as u64)),
            3 => Value::text(self.str()?),
            4 => Value::Boolean(self.u8()? != 0),
            5 => Value::Timestamp(DateTime::<Utc>::from_timestamp_nanos(self.i64()?)),
            6 => Value::json(self.str()?),
            7 => {
                let count = self.u32()? as usize;
                let bytes = self.take(count.checked_mul(4).ok_or("vector too large")?)?;
                Value::vector(
                    bytes
                        .as_chunks::<4>()
                        .0
                        .iter()
                        .map(|c| f32::from_le_bytes(*c))
                        .collect(),
                )
            }
            tag => return Err(format!("unknown parameter type tag {tag}").into()),
        })
    }

    fn params(&mut self) -> R<Params> {
        let head = self.u32()?;
        let count = (head & !NAMED_FLAG) as usize;
        let capacity = self.count(count);
        if head & NAMED_FLAG == 0 {
            let mut values = ParamVec::with_capacity(capacity);
            for _ in 0..count {
                values.push(self.value()?);
            }
            return Ok(Params::Positional(values));
        }
        let mut named = NamedParams::with_capacity(capacity);
        for _ in 0..count {
            let name = self.str()?.trim_start_matches([':', '@', '$']).to_string();
            let value = self.value()?;
            named.insert(name, value);
        }
        Ok(Params::Named(named))
    }

    fn finish(&self) -> R<()> {
        match self.pos == self.data.len() {
            true => Ok(()),
            false => Err("trailing bytes after parameters".into()),
        }
    }
}

/// Decodes one parameter set; a null array means no parameters.
fn params(env: &mut JNIEnv, bin: &JByteArray) -> R<Params> {
    if bin.is_null() {
        return Ok(Params::none());
    }
    let bytes = env.convert_byte_array(bin)?;
    let mut r = Reader::new(&bytes);
    let p = r.params()?;
    r.finish()?;
    Ok(p)
}

/// Decodes a batch: `[row_count:u32]` followed by one parameter set per row.
fn batch(env: &mut JNIEnv, bin: &JByteArray) -> R<Vec<Params>> {
    if bin.is_null() {
        return Err("batch must not be null".into());
    }
    let bytes = env.convert_byte_array(bin)?;
    let mut r = Reader::new(&bytes);
    let rows = r.u32()? as usize;
    let mut all = Vec::with_capacity(r.count(rows));
    for _ in 0..rows {
        all.push(r.params()?);
    }
    r.finish()?;
    Ok(all)
}

// ============================================================
// Running statements
// ============================================================

fn db_exec(db: &Database, sql: &str, p: Params, timeout_ms: u64) -> stoolap::Result<i64> {
    match p {
        Params::Positional(v) if timeout_ms > 0 => db.execute_with_timeout(sql, v, timeout_ms),
        Params::Positional(v) => db.execute(sql, v),
        Params::Named(n) => db.execute_named(sql, n),
    }
}

fn db_query(db: &Database, sql: &str, p: Params, timeout_ms: u64) -> stoolap::Result<Rows> {
    match p {
        Params::Positional(v) if timeout_ms > 0 => db.query_with_timeout(sql, v, timeout_ms),
        Params::Positional(v) => db.query(sql, v),
        Params::Named(n) => db.query_named(sql, n),
    }
}

fn tx_exec(tx: &mut ApiTransaction, sql: &str, p: Params) -> stoolap::Result<i64> {
    match p {
        Params::Positional(v) => tx.execute(sql, v),
        Params::Named(n) => tx.execute_named(sql, n),
    }
}

fn tx_query(tx: &mut ApiTransaction, sql: &str, p: Params) -> stoolap::Result<Rows> {
    match p {
        Params::Positional(v) => tx.query(sql, v),
        Params::Named(n) => tx.query_named(sql, n),
    }
}

fn stmt_exec(s: &Statement, tx: Option<&mut ApiTransaction>, p: Params) -> stoolap::Result<i64> {
    match (tx, p) {
        (None, Params::Positional(v)) => s.execute(v),
        (None, Params::Named(n)) => s.execute_named(n),
        (Some(tx), Params::Positional(v)) => s.execute_in(tx, v),
        (Some(tx), Params::Named(n)) => s.execute_named_in(tx, n),
    }
}

fn stmt_query(s: &Statement, tx: Option<&mut ApiTransaction>, p: Params) -> stoolap::Result<Rows> {
    match (tx, p) {
        (None, Params::Positional(v)) => s.query(v),
        (None, Params::Named(n)) => s.query_named(n),
        (Some(tx), Params::Positional(v)) => s.query_in(tx, v),
        (Some(tx), Params::Named(n)) => s.query_named_in(tx, n),
    }
}

/// Runs every parameter set of a batch through `s` inside `tx`, naming the
/// failing row in the error.
fn stmt_batch(s: &Statement, tx: &mut ApiTransaction, all: Vec<Params>) -> R<Vec<i64>> {
    let mut counts = Vec::with_capacity(all.len());
    for (i, p) in all.into_iter().enumerate() {
        match stmt_exec(s, Some(tx), p) {
            Ok(n) => counts.push(n),
            Err(e) => {
                let mut fail = Fail::from(e);
                fail.msg = format!("batch row {i}: {}", fail.msg);
                return Err(fail);
            }
        }
    }
    Ok(counts)
}

fn long_array<'a>(env: &mut JNIEnv<'a>, values: &[i64]) -> R<JLongArray<'a>> {
    let arr = env.new_long_array(values.len() as i32)?;
    env.set_long_array_region(&arr, 0, values)?;
    Ok(arr)
}

/// Where a manual-commit (JDBC) connection runs `sql`, as the code
/// `NativeBridge.placement` returns:
/// - `IN_TX` (0): inside the open transaction: DML and savepoints, which
///   is all an engine transaction accepts.
/// - `COMMIT_FIRST` (1): outside, after committing the open transaction:
///   DDL and anything else that changes the database outside a transaction.
/// - `OUTSIDE` (2): outside, leaving the transaction open: statements that
///   only read the catalog or a plan (SHOW, DESCRIBE, EXPLAIN without
///   ANALYZE of a write).
/// - `COMMIT` (3): BEGIN or COMMIT, which commit the open transaction.
/// - `ROLLBACK` (4): ROLLBACK, which rolls the open transaction back.
///
/// SQL that does not parse is placed in the transaction, so running it
/// reports the parse error. A script of several statements runs in the
/// transaction only when all of them may, and outside without committing
/// only when all of them only read.
fn placement(sql: &str) -> i32 {
    const IN_TX: i32 = 0;
    const COMMIT_FIRST: i32 = 1;
    const OUTSIDE: i32 = 2;
    const COMMIT: i32 = 3;
    const ROLLBACK: i32 = 4;

    fn one(s: &Ast) -> i32 {
        match s {
            Ast::Select(_)
            | Ast::Insert(_)
            | Ast::Update(_)
            | Ast::Delete(_)
            | Ast::Savepoint(_)
            | Ast::ReleaseSavepoint(_)
            | Ast::Rollback(RollbackStatement {
                savepoint_name: Some(_),
                ..
            }) => IN_TX,
            Ast::ShowTables(_)
            | Ast::ShowViews(_)
            | Ast::ShowCreateTable(_)
            | Ast::ShowCreateView(_)
            | Ast::ShowIndexes(_)
            | Ast::Describe(_) => OUTSIDE,
            Ast::Explain(e) if !e.analyze || matches!(*e.statement, Ast::Select(_)) => OUTSIDE,
            Ast::Begin(_) | Ast::Commit(_) => COMMIT,
            Ast::Rollback(_) => ROLLBACK,
            _ => COMMIT_FIRST,
        }
    }

    let Ok(program) = Parser::new(sql).parse_program() else {
        return IN_TX;
    };
    match program.statements.as_slice() {
        [] => IN_TX,
        [s] => one(s),
        all => {
            let each: Vec<i32> = all.iter().map(one).collect();
            if each.iter().all(|&p| p == IN_TX) {
                IN_TX
            } else if each.iter().all(|&p| p == OUTSIDE) {
                OUTSIDE
            } else {
                COMMIT_FIRST
            }
        }
    }
}

// ============================================================
// Packed results
// ============================================================

/// Largest packed buffer a thread keeps between calls.
const PACKED_KEEP_MAX: usize = 1 << 20;

thread_local! {
    static PACKED: RefCell<Vec<u8>> = const { RefCell::new(Vec::new()) };
}

/// Writes the column count and names and a zero row count, returning the
/// offset of the row count for `write_rows` to patch.
fn write_header(columns: &[String], buf: &mut Vec<u8>) -> R<usize> {
    buf.extend_from_slice(&(columns.len() as u32).to_le_bytes());
    for name in columns {
        let len = u16::try_from(name.len()).map_err(|_| {
            Fail::from(format!(
                "a result column name takes {} bytes, more than the {} the packed format \
                 carries; give the column an alias",
                name.len(),
                u16::MAX
            ))
        })?;
        buf.extend_from_slice(&len.to_le_bytes());
        buf.extend_from_slice(name.as_bytes());
    }
    let count_at = buf.len();
    buf.extend_from_slice(&0u32.to_le_bytes());
    Ok(count_at)
}

fn write_bytes(tag: u8, bytes: &[u8], buf: &mut Vec<u8>) {
    buf.push(tag);
    buf.extend_from_slice(&(bytes.len() as u32).to_le_bytes());
    buf.extend_from_slice(bytes);
}

fn write_value(val: &Value, buf: &mut Vec<u8>) -> R<()> {
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
        Value::Text(s) => write_bytes(3, s.as_bytes(), buf),
        Value::Boolean(v) => {
            buf.push(4);
            buf.push(*v as u8);
        }
        Value::Timestamp(ts) => {
            let nanos = ts.timestamp_nanos_opt().ok_or_else(|| {
                Fail::from(format!(
                    "TIMESTAMP {ts} is outside the range 1677-09-21 00:12:43.145224192 to \
                     2262-04-11 23:47:16.854775807"
                ))
            })?;
            buf.push(5);
            buf.extend_from_slice(&nanos.to_le_bytes());
        }
        Value::Extension(data) => match data.first() {
            None => buf.push(0),
            Some(&t) if t == DataType::Json as u8 => write_bytes(6, &data[1..], buf),
            Some(&t) if t == DataType::Vector as u8 => write_bytes(7, &data[1..], buf),
            Some(&t) => return Err(format!("unsupported result value type {t}").into()),
        },
    }
    Ok(())
}

/// Packs up to `max_rows` rows and patches the row count. Returns the
/// number of rows packed. A runtime error that ends the result early is
/// returned, never a partial result.
fn write_rows(rows: &mut Rows, max_rows: usize, count_at: usize, buf: &mut Vec<u8>) -> R<usize> {
    let col_count = rows.columns().len();
    let mut n = 0usize;
    while n < max_rows && rows.advance() {
        let row = rows.current_row();
        for i in 0..col_count {
            match row.get(i) {
                Some(v) => write_value(v, buf)?,
                None => buf.push(0),
            }
        }
        n += 1;
    }
    if n < max_rows {
        if let Some(e) = rows.error() {
            return Err(e.into());
        }
    }
    let count = u32::try_from(n).map_err(|_| "result has more than 4294967295 rows")?;
    buf[count_at..count_at + 4].copy_from_slice(&count.to_le_bytes());
    Ok(n)
}

/// Packs into this thread's reusable buffer and copies it into a `byte[]`.
fn packed<'a>(env: &mut JNIEnv<'a>, fill: impl FnOnce(&mut Vec<u8>) -> R<()>) -> R<JByteArray<'a>> {
    PACKED.with(|cell| {
        let mut buf = cell.borrow_mut();
        if buf.capacity() > PACKED_KEEP_MAX {
            *buf = Vec::new();
        }
        buf.clear();
        fill(&mut buf)?;
        Ok(env.byte_array_from_slice(&buf)?)
    })
}

/// Packs a whole query result, followed by its rows affected.
fn pack_all<'a>(env: &mut JNIEnv<'a>, rows: stoolap::Result<Rows>) -> R<JByteArray<'a>> {
    let mut rows = rows?;
    packed(env, |buf| {
        let count_at = write_header(rows.columns(), buf)?;
        write_rows(&mut rows, usize::MAX, count_at, buf)?;
        buf.extend_from_slice(&rows.rows_affected().to_le_bytes());
        Ok(())
    })
}

/// Packs the next chunk of `c` into `buf`, followed by the rows affected,
/// dropping the result once a chunk comes back short.
fn write_chunk(c: &mut Cursor, max: usize, buf: &mut Vec<u8>) -> R<()> {
    let count_at = write_header(&c.columns, buf)?;
    if let Some(r) = &mut c.rows {
        match write_rows(r, max, count_at, buf) {
            Ok(n) if n == max => {}
            Ok(_) => c.rows = None,
            Err(e) => {
                c.rows = None;
                return Err(e);
            }
        }
    }
    buf.extend_from_slice(&c.affected.to_le_bytes());
    Ok(())
}

/// Opens a cursor over `rows` and packs its first chunk behind the cursor
/// handle: `[handle:i64][chunk]`. When the first chunk holds the whole
/// result the handle is 0 and nothing stays open.
fn open_cursor<'a>(
    env: &mut JNIEnv<'a>,
    rows: stoolap::Result<Rows>,
    max_rows: jint,
) -> R<JByteArray<'a>> {
    if max_rows <= 0 {
        return Err("max_rows must be positive".into());
    }
    let mut rows = rows?;
    let max = max_rows as usize;
    let mut handle = 0;
    let out = packed(env, |buf| {
        buf.extend_from_slice(&0i64.to_le_bytes());
        let count_at = write_header(rows.columns(), buf)?;
        let n = write_rows(&mut rows, max, count_at, buf)?;
        let affected = rows.rows_affected();
        buf.extend_from_slice(&affected.to_le_bytes());
        // Only a result with rows left keeps a cursor, and its column names
        if n == max {
            handle = into_handle(Cursor {
                columns: rows.columns().to_vec(),
                affected,
                rows: Some(rows),
            });
            buf[..8].copy_from_slice(&handle.to_le_bytes());
        }
        Ok(())
    });
    if out.is_err() {
        unsafe { drop_handle::<Cursor>(handle) };
    }
    out
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
    run(&mut env, 0, |env| {
        let dsn = jstring(env, &dsn)?;
        Ok(into_handle(Database::open(&dsn)?))
    })
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_openInMemory(
    mut env: JNIEnv,
    _class: JClass,
) -> jlong {
    run(&mut env, 0, |_| {
        Ok(into_handle(Database::open_in_memory()?))
    })
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_closeDb(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    unsafe { drop_handle::<Database>(ptr) }
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_cloneDb(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jlong {
    run(&mut env, 0, |_| {
        let db = unsafe { handle::<Database>(ptr, "Database")? };
        Ok(into_handle(db.clone()))
    })
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_version<'a>(
    env: JNIEnv<'a>,
    _class: JClass<'a>,
) -> JObject<'a> {
    match env.new_string(env!("CARGO_PKG_VERSION")) {
        Ok(s) => s.into(),
        Err(_) => JObject::null(),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_placement(
    mut env: JNIEnv,
    _class: JClass,
    sql: JString,
) -> jint {
    run(&mut env, 0, |env| {
        let sql = jstring(env, &sql)?;
        Ok(placement(&sql))
    })
}

// ============================================================
// Database: execute and query
// ============================================================

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_execParams(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    sql: JString,
    bin: JByteArray,
    timeout_ms: jint,
) -> jlong {
    run(&mut env, -1, |env| {
        let db = unsafe { handle::<Database>(ptr, "Database")? };
        let sql = jstring(env, &sql)?;
        let p = params(env, &bin)?;
        Ok(db_exec(db, &sql, p, timeout_ms.max(0) as u64)?)
    })
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_queryParams<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    ptr: jlong,
    sql: JString,
    bin: JByteArray,
) -> JByteArray<'a> {
    run(&mut env, JByteArray::default(), |env| {
        let db = unsafe { handle::<Database>(ptr, "Database")? };
        let sql = jstring(env, &sql)?;
        let p = params(env, &bin)?;
        pack_all(env, db_query(db, &sql, p, 0))
    })
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_queryCursor<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    ptr: jlong,
    sql: JString,
    bin: JByteArray,
    max_rows: jint,
    timeout_ms: jint,
) -> JByteArray<'a> {
    run(&mut env, JByteArray::default(), |env| {
        let db = unsafe { handle::<Database>(ptr, "Database")? };
        let sql = jstring(env, &sql)?;
        let p = params(env, &bin)?;
        let rows = db_query(db, &sql, p, timeout_ms.max(0) as u64);
        open_cursor(env, rows, max_rows)
    })
}

// ============================================================
// Prepared statements
// ============================================================

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_prepare(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    sql: JString,
) -> jlong {
    run(&mut env, 0, |env| {
        let db = unsafe { handle::<Database>(ptr, "Database")? };
        let sql = jstring(env, &sql)?;
        Ok(into_handle(db.prepare(&sql)?))
    })
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_stmtClose(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    unsafe { drop_handle::<Statement>(ptr) }
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_stmtExec(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    bin: JByteArray,
) -> jlong {
    run(&mut env, -1, |env| {
        let s = unsafe { handle::<Statement>(ptr, "Statement")? };
        let p = params(env, &bin)?;
        Ok(stmt_exec(s, None, p)?)
    })
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_stmtQuery<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    ptr: jlong,
    bin: JByteArray,
) -> JByteArray<'a> {
    run(&mut env, JByteArray::default(), |env| {
        let s = unsafe { handle::<Statement>(ptr, "Statement")? };
        let p = params(env, &bin)?;
        pack_all(env, stmt_query(s, None, p))
    })
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_stmtQueryCursor<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    ptr: jlong,
    bin: JByteArray,
    max_rows: jint,
) -> JByteArray<'a> {
    run(&mut env, JByteArray::default(), |env| {
        let s = unsafe { handle::<Statement>(ptr, "Statement")? };
        let p = params(env, &bin)?;
        let rows = stmt_query(s, None, p);
        open_cursor(env, rows, max_rows)
    })
}

/// Runs every row of a batch in one transaction of its own on `db`: all
/// rows commit together, or none do.
#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_stmtExecBatch<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    db_ptr: jlong,
    stmt_ptr: jlong,
    bin: JByteArray,
) -> JLongArray<'a> {
    run(&mut env, JLongArray::default(), |env| {
        let db = unsafe { handle::<Database>(db_ptr, "Database")? };
        let s = unsafe { handle::<Statement>(stmt_ptr, "Statement")? };
        let all = batch(env, &bin)?;
        let mut tx = db.begin()?;
        let counts = stmt_batch(s, &mut tx, all)?; // dropping tx rolls back
        tx.commit()?;
        long_array(env, &counts)
    })
}

// ============================================================
// Transactions
// ============================================================

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_begin(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    isolation: jint,
) -> jlong {
    run(&mut env, 0, |_| {
        let db = unsafe { handle::<Database>(ptr, "Database")? };
        let level = match isolation {
            1 => IsolationLevel::SnapshotIsolation,
            _ => IsolationLevel::ReadCommitted,
        };
        Ok(into_handle(db.begin_with_isolation(level)?))
    })
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_txCommit(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jboolean {
    run(&mut env, JNI_FALSE, |_| {
        let tx = unsafe { handle_mut::<ApiTransaction>(ptr, "Transaction")? };
        tx.commit()?;
        Ok(JNI_TRUE)
    })
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_txRollback(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jboolean {
    run(&mut env, JNI_FALSE, |_| {
        let tx = unsafe { handle_mut::<ApiTransaction>(ptr, "Transaction")? };
        tx.rollback()?;
        Ok(JNI_TRUE)
    })
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_txClose(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    unsafe { drop_handle::<ApiTransaction>(ptr) }
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_txExecParams(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    sql: JString,
    bin: JByteArray,
) -> jlong {
    run(&mut env, -1, |env| {
        let tx = unsafe { handle_mut::<ApiTransaction>(ptr, "Transaction")? };
        let sql = jstring(env, &sql)?;
        let p = params(env, &bin)?;
        Ok(tx_exec(tx, &sql, p)?)
    })
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_txQueryParams<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    ptr: jlong,
    sql: JString,
    bin: JByteArray,
) -> JByteArray<'a> {
    run(&mut env, JByteArray::default(), |env| {
        let tx = unsafe { handle_mut::<ApiTransaction>(ptr, "Transaction")? };
        let sql = jstring(env, &sql)?;
        let p = params(env, &bin)?;
        pack_all(env, tx_query(tx, &sql, p))
    })
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_txQueryCursor<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    ptr: jlong,
    sql: JString,
    bin: JByteArray,
    max_rows: jint,
) -> JByteArray<'a> {
    run(&mut env, JByteArray::default(), |env| {
        let tx = unsafe { handle_mut::<ApiTransaction>(ptr, "Transaction")? };
        let sql = jstring(env, &sql)?;
        let p = params(env, &bin)?;
        let rows = tx_query(tx, &sql, p);
        open_cursor(env, rows, max_rows)
    })
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_txStmtExec(
    mut env: JNIEnv,
    _class: JClass,
    tx_ptr: jlong,
    stmt_ptr: jlong,
    bin: JByteArray,
) -> jlong {
    run(&mut env, -1, |env| {
        let tx = unsafe { handle_mut::<ApiTransaction>(tx_ptr, "Transaction")? };
        let s = unsafe { handle::<Statement>(stmt_ptr, "Statement")? };
        let p = params(env, &bin)?;
        Ok(stmt_exec(s, Some(tx), p)?)
    })
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_txStmtQuery<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    tx_ptr: jlong,
    stmt_ptr: jlong,
    bin: JByteArray,
) -> JByteArray<'a> {
    run(&mut env, JByteArray::default(), |env| {
        let tx = unsafe { handle_mut::<ApiTransaction>(tx_ptr, "Transaction")? };
        let s = unsafe { handle::<Statement>(stmt_ptr, "Statement")? };
        let p = params(env, &bin)?;
        pack_all(env, stmt_query(s, Some(tx), p))
    })
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_txStmtQueryCursor<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    tx_ptr: jlong,
    stmt_ptr: jlong,
    bin: JByteArray,
    max_rows: jint,
) -> JByteArray<'a> {
    run(&mut env, JByteArray::default(), |env| {
        let tx = unsafe { handle_mut::<ApiTransaction>(tx_ptr, "Transaction")? };
        let s = unsafe { handle::<Statement>(stmt_ptr, "Statement")? };
        let p = params(env, &bin)?;
        let rows = stmt_query(s, Some(tx), p);
        open_cursor(env, rows, max_rows)
    })
}

/// Runs every row of a batch inside the caller's transaction.
#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_txStmtExecBatch<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    tx_ptr: jlong,
    stmt_ptr: jlong,
    bin: JByteArray,
) -> JLongArray<'a> {
    run(&mut env, JLongArray::default(), |env| {
        let tx = unsafe { handle_mut::<ApiTransaction>(tx_ptr, "Transaction")? };
        let s = unsafe { handle::<Statement>(stmt_ptr, "Statement")? };
        let all = batch(env, &bin)?;
        let counts = stmt_batch(s, tx, all)?;
        long_array(env, &counts)
    })
}

// ============================================================
// Cursors
// ============================================================

/// Packs up to `max_rows` more rows of a cursor. A chunk with fewer rows
/// than asked for is the last; the cursor then returns empty chunks.
#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_cursorFetch<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    ptr: jlong,
    max_rows: jint,
) -> JByteArray<'a> {
    run(&mut env, JByteArray::default(), |env| {
        let c = unsafe { handle_mut::<Cursor>(ptr, "Cursor")? };
        if max_rows <= 0 {
            return Err("max_rows must be positive".into());
        }
        packed(env, |buf| write_chunk(c, max_rows as usize, buf))
    })
}

#[no_mangle]
pub extern "system" fn Java_io_stoolap_internal_NativeBridge_cursorClose(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    unsafe { drop_handle::<Cursor>(ptr) }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn decode(bytes: &[u8]) -> R<Params> {
        let mut r = Reader::new(bytes);
        let p = r.params()?;
        r.finish()?;
        Ok(p)
    }

    #[test]
    fn refuses_truncated_and_invalid_parameters() {
        // One INTEGER announced, three of its eight bytes present
        assert!(decode(&[1, 0, 0, 0, 1, 1, 2, 3]).is_err());
        // TEXT that is not UTF-8
        assert!(decode(&[1, 0, 0, 0, 3, 1, 0, 0, 0, 0xff]).is_err());
        // An unknown tag
        assert!(decode(&[1, 0, 0, 0, 42]).is_err());
        // Bytes after the last parameter
        assert!(decode(&[0, 0, 0, 0, 9]).is_err());
        // A length that would overflow the buffer position
        assert!(decode(&[1, 0, 0, 0, 3, 0xff, 0xff, 0xff, 0xff]).is_err());
        // A count far beyond the bytes present must not allocate for it
        assert!(decode(&[0xff, 0xff, 0xff, 0x7f]).is_err());
    }

    #[test]
    fn decodes_named_parameters_without_their_prefix() {
        let mut b = vec![];
        b.extend_from_slice(&(1u32 | NAMED_FLAG).to_le_bytes());
        b.extend_from_slice(&3u32.to_le_bytes());
        b.extend_from_slice(b":id");
        b.push(1);
        b.extend_from_slice(&7i64.to_le_bytes());
        match decode(&b).unwrap() {
            Params::Named(n) => assert_eq!(n.as_map().get("id"), Some(&Value::Integer(7))),
            Params::Positional(_) => panic!("expected named parameters"),
        }
    }

    #[test]
    fn places_statements_for_a_manual_commit_connection() {
        for (sql, want) in [
            ("SELECT 1", 0),
            ("INSERT INTO t VALUES (1)", 0),
            ("UPDATE t SET a = 1", 0),
            ("DELETE FROM t", 0),
            ("SAVEPOINT s", 0),
            ("RELEASE SAVEPOINT s", 0),
            ("ROLLBACK TO SAVEPOINT s", 0),
            ("SELEC broken", 0),
            ("SELECT 1; INSERT INTO t VALUES (1)", 0),
            ("CREATE TABLE t (a INTEGER)", 1),
            ("DROP TABLE t", 1),
            ("ALTER TABLE t ADD COLUMN b TEXT", 1),
            ("CREATE INDEX i ON t(a)", 1),
            ("TRUNCATE TABLE t", 1),
            ("PRAGMA snapshot", 1),
            ("EXPLAIN ANALYZE DELETE FROM t", 1),
            ("SELECT 1; CREATE TABLE u (a INTEGER)", 1),
            ("SHOW TABLES", 2),
            ("SHOW INDEXES FROM t", 2),
            ("DESCRIBE t", 2),
            ("EXPLAIN SELECT 1", 2),
            ("EXPLAIN ANALYZE SELECT 1", 2),
            ("SHOW TABLES; DESCRIBE t", 2),
            ("BEGIN", 3),
            ("COMMIT", 3),
            ("ROLLBACK", 4),
        ] {
            assert_eq!(placement(sql), want, "{sql}");
        }
    }
}
