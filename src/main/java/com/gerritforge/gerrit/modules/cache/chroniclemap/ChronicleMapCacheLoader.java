// Copyright (C) 2025 GerritForge, Inc.
//
// Licensed under the BSL 1.1 (the "License");
// you may not use this file except in compliance with the License.
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.gerritforge.gerrit.modules.cache.chroniclemap;

import com.google.common.cache.CacheLoader;
import com.google.common.cache.CacheStats;
import com.google.common.flogger.FluentLogger;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gerrit.server.logging.Metadata;
import com.google.gerrit.server.logging.TraceContext;
import com.google.gerrit.server.logging.TraceContext.TraceTimer;
import com.google.gerrit.server.util.time.TimeUtil;
import com.googlesource.gerrit.modules.cache.chroniclemap.KeyWrapper;
import com.googlesource.gerrit.modules.cache.chroniclemap.TimedValue;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.LongAdder;

class ChronicleMapCacheLoader<K, V> extends CacheLoader<K, TimedValue<V>> {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final Executor storePersistenceExecutor;
  private final Optional<CacheLoader<K, V>> loader;
  private final ChronicleMapStore<K, V> store;
  private final LongAdder loadSuccessCount = new LongAdder();
  private final LongAdder loadExceptionCount = new LongAdder();
  private final LongAdder totalLoadTime = new LongAdder();
  private final LongAdder hitCount = new LongAdder();
  private final LongAdder missCount = new LongAdder();
  private final Duration expireAfterWrite;

  /**
   * Creates a loader for fetching entries from a ChronicleMap store and an external data source.
   *
   * @param storePersistenceExecutor executor for async loading/storage to ChronicleMap
   * @param store the ChronicleMap storage
   * @param loader the data loader from the external source
   * @param expireAfterWrite maximum lifetime of the data loaded into ChronicleMap
   */
  ChronicleMapCacheLoader(
      Executor storePersistenceExecutor,
      ChronicleMapStore<K, V> store,
      CacheLoader<K, V> loader,
      Duration expireAfterWrite) {
    this.storePersistenceExecutor = storePersistenceExecutor;
    this.store = store;
    this.loader = Optional.of(loader);
    this.expireAfterWrite = expireAfterWrite;
  }

  /**
   * Creates a loader for fetching entries from a ChronicleMap store.
   *
   * @param storePersistenceExecutor executor for async loading/storage to ChronicleMap
   * @param store the ChronicleMap storage
   * @param expireAfterWrite maximum lifetime of the data loaded into ChronicleMap
   */
  ChronicleMapCacheLoader(
      Executor storePersistenceExecutor, ChronicleMapStore<K, V> store, Duration expireAfterWrite) {
    this.storePersistenceExecutor = storePersistenceExecutor;
    this.store = store;
    this.loader = Optional.empty();
    this.expireAfterWrite = expireAfterWrite;
  }

  @Override
  public TimedValue<V> load(K key) throws Exception {
    try (TraceTimer timer =
        TraceContext.newTimer(
            "Loading value from cache", Metadata.builder().cacheKey(key.toString()).build())) {
      TimedValue<V> h = loadIfPresent(key);
      if (h != null) {
        return h;
      }

      if (loader.isPresent()) {
        missCount.increment();
        long start = System.nanoTime();
        TimedValue<V> loadedValue = new TimedValue<>(loader.get().load(key));
        totalLoadTime.add(System.nanoTime() - start);
        storePersistenceExecutor.execute(
            () -> {
              // Note that we return a loadedValue, even when we
              // we fail populating the cache with it, to make clients more
              // resilient to storage cache failures
              if (store.tryPut(new KeyWrapper<>(key), loadedValue)) {
                loadSuccessCount.increment();
              }
            });
        return loadedValue;
      }

      throw new UnsupportedOperationException("No loader defined");
    } catch (Exception e) {
      logger.atWarning().withCause(e).log("Unable to load a value for key='%s'", key);
      loadExceptionCount.increment();
      throw e;
    }
  }

  public ChronicleMapStore<K, V> getStore() {
    return store;
  }

  TimedValue<V> loadIfPresent(K key) {
    TimedValue<V> h = store.get(new KeyWrapper<>(key));
    if (h != null && !expired(h.getCreated())) {
      hitCount.increment();
      return h;
    }

    return null;
  }

  @Override
  public ListenableFuture<TimedValue<V>> reload(K key, TimedValue<V> oldValue) throws Exception {
    if (!loader.isPresent()) {
      throw new IllegalStateException("No loader defined");
    }

    final long start = System.nanoTime();
    ListenableFuture<V> reloadedValue = loader.get().reload(key, oldValue.getValue());
    Futures.addCallback(
        reloadedValue,
        new FutureCallback<V>() {
          @Override
          public void onSuccess(V result) {
            if (store.tryPut(new KeyWrapper<>(key), new TimedValue<>(result))) {
              loadSuccessCount.increment();
            }
            totalLoadTime.add(System.nanoTime() - start);
          }

          @Override
          public void onFailure(Throwable t) {
            logger.atWarning().withCause(t).log("Unable to reload cache value for key='%s'", key);
            loadExceptionCount.increment();
          }
        },
        storePersistenceExecutor);

    return Futures.transform(reloadedValue, TimedValue::new, storePersistenceExecutor);
  }

  boolean expired(long created) {
    Duration age = Duration.between(Instant.ofEpochMilli(created), TimeUtil.now());
    return !expireAfterWrite.isZero() && age.compareTo(expireAfterWrite) > 0;
  }

  InMemoryCache<K, V> asInMemoryCacheBypass() {
    return new InMemoryCache<>() {

      @SuppressWarnings("unchecked")
      @Override
      public TimedValue<V> getIfPresent(Object key) {
        try {
          return load((K) key);
        } catch (Exception e) {
          return null;
        }
      }

      @Override
      public TimedValue<V> get(K key, Callable<? extends TimedValue<V>> valueLoader)
          throws Exception {
        return valueLoader.call();
      }

      @Override
      public void put(K key, TimedValue<V> value) {
        store.tryPut(new KeyWrapper<>(key), value);
      }

      @Override
      public boolean isLoadingCache() {
        return true;
      }

      @Override
      public TimedValue<V> get(K key) throws ExecutionException {
        try {
          return load(key);
        } catch (Exception e) {
          throw new ExecutionException(e);
        }
      }

      @Override
      public void refresh(K key) {}

      @Override
      public CacheStats stats() {
        throw new IllegalArgumentException("Cache stats not available for a loader-bypass");
      }

      @Override
      public long size() {
        return 0;
      }

      @Override
      public void invalidate(Object key) {}

      @Override
      public void invalidateAll() {}
    };
  }
}
