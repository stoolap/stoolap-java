/*
 * Copyright 2025 Stoolap Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.stoolap.internal;

import io.stoolap.StoolapException;
import java.lang.ref.Cleaner;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.LongConsumer;

/**
 * Owns one native handle and guards every use of it.
 *
 * <p>A shared use may run on several threads at once; an exclusive use runs alone. Closing waits
 * until no use is in flight, so a handle is never freed while native code holds it. If the owner
 * becomes unreachable without being closed, a {@link Cleaner} frees the handle.
 *
 * <p>Internal: used by the core API and the JDBC driver, not part of the public API.
 */
public final class NativeHandle {

  private static final Cleaner CLEANER = Cleaner.create();

  /** A native call made with the handle's pointer. */
  @FunctionalInterface
  public interface Call<T> {
    T apply(long ptr) throws Exception;
  }

  /** The state the cleaner frees; it must not reference the owner. */
  private static final class State implements Runnable {
    final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    final LongConsumer free;
    volatile long ptr;

    State(long ptr, LongConsumer free) {
      this.ptr = ptr;
      this.free = free;
    }

    @Override
    public void run() {
      lock.writeLock().lock();
      try {
        long p = ptr;
        if (p != 0) {
          ptr = 0;
          free.accept(p);
        }
      } finally {
        lock.writeLock().unlock();
      }
    }
  }

  private final State state;
  private final Cleaner.Cleanable cleanable;
  private final String what;

  /**
   * @param owner the object whose reachability keeps the handle alive
   * @param ptr the native handle
   * @param free the native function that frees it
   * @param what the name used in "... is closed" errors
   */
  public NativeHandle(Object owner, long ptr, LongConsumer free, String what) {
    this.state = new State(ptr, free);
    this.cleanable = CLEANER.register(owner, state);
    this.what = what;
  }

  /** Runs {@code call} alongside other shared uses. */
  public <T> T shared(Call<T> call) throws StoolapException {
    ReentrantReadWriteLock.ReadLock l = state.lock.readLock();
    l.lock();
    try {
      return call.apply(live());
    } catch (Exception e) {
      throw StoolapException.of(e);
    } finally {
      l.unlock();
    }
  }

  /** Runs {@code call} with no other use of the handle in flight. */
  public <T> T exclusive(Call<T> call) throws StoolapException {
    ReentrantReadWriteLock.WriteLock l = state.lock.writeLock();
    l.lock();
    try {
      return call.apply(live());
    } catch (Exception e) {
      throw StoolapException.of(e);
    } finally {
      l.unlock();
    }
  }

  private long live() throws StoolapException {
    long p = state.ptr;
    if (p == 0) throw new StoolapException(what + " is closed", "08003");
    return p;
  }

  /**
   * Frees the handle once no use is in flight; later uses fail. Must not be called by a thread in a
   * shared use of this handle.
   */
  public void close() {
    cleanable.clean();
  }

  public boolean isClosed() {
    return state.ptr == 0;
  }
}
