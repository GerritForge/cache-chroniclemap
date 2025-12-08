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

import com.google.common.cache.AbstractLoadingCache;
import com.google.common.cache.CacheStats;
import com.google.common.flogger.FluentLogger;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.gerrit.metrics.DisabledMetricMaker;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.server.cache.PersistentCache;
import com.google.gerrit.server.cache.PersistentCacheDef;
import com.google.gerrit.server.util.time.TimeUtil;
import com.googlesource.gerrit.modules.cache.chroniclemap.KeyWrapper;
import com.googlesource.gerrit.modules.cache.chroniclemap.TimedValue;
import com.googlesource.gerrit.modules.cache.chroniclemap.TimedValueMarshaller;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.LongAdder;
import net.openhft.chronicle.map.ChronicleMap;
import net.openhft.chronicle.map.ChronicleMapBuilder;

public class ChronicleMapCacheImpl<K, V> extends AbstractLoadingCache<K, V>
    implements PersistentCache {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final long DONT_TRACK_CACHE_INVALIDATIONS = 0L;

  private final ChronicleMapCacheConfig config;
  private final ChronicleMapStore<K, V> store;
  private final LongAdder hitCount = new LongAdder();
  private final LongAdder missCount = new LongAdder();
  private final LongAdder loadSuccessCount = new LongAdder();
  private final LongAdder loadExceptionCount = new LongAdder();
  private final LongAdder totalLoadTime = new LongAdder();
  private final CacheKeysIndex<K> keysIndex;
  private final PersistentCacheDef<K, V> cacheDefinition;
  private final ChronicleMapCacheLoader<K, V> memLoader;
  private final InMemoryCache<K, V> mem;
  private final Executor indexPersistenceExecutor;
  private long pruneCount;

  ChronicleMapCacheImpl(PersistentCacheDef<K, V> def, ChronicleMapCacheConfig config)
      throws IOException {
    DisabledMetricMaker metricMaker = new DisabledMetricMaker();

    this.cacheDefinition = def;
    this.config = config;
    this.keysIndex =
        new CacheKeysIndex<>(
            metricMaker, def.name(), config.getIndexFile(), config.getCacheFileExists());
    this.store = createOrRecoverStore(def, config, metricMaker);
    this.memLoader =
        new ChronicleMapCacheLoader<>(
            MoreExecutors.directExecutor(), store, config.getExpireAfterWrite());
    this.mem = memLoader.asInMemoryCacheBypass();
    this.indexPersistenceExecutor = MoreExecutors.directExecutor();
  }

  ChronicleMapCacheImpl(
      PersistentCacheDef<K, V> def,
      ChronicleMapCacheConfig config,
      MetricMaker metricMaker,
      ChronicleMapCacheLoader<K, V> memLoader,
      InMemoryCache<K, V> mem,
      Executor indexPersistenceExecutor) {

    this.cacheDefinition = def;
    this.config = config;
    this.keysIndex =
        new CacheKeysIndex<>(
            metricMaker, def.name(), config.getIndexFile(), config.getCacheFileExists());
    this.memLoader = memLoader;
    this.mem = mem;
    this.store = memLoader.getStore();
    this.indexPersistenceExecutor = indexPersistenceExecutor;
  }

  @SuppressWarnings({"unchecked", "cast", "rawtypes"})
  static <K, V> ChronicleMapStore<K, V> createOrRecoverStore(
      PersistentCacheDef<K, V> def, ChronicleMapCacheConfig config, MetricMaker metricMaker)
      throws IOException {
    CacheSerializers.registerCacheDef(def);

    final Class<KeyWrapper<K>> keyWrapperClass = (Class<KeyWrapper<K>>) (Class) KeyWrapper.class;
    final Class<TimedValue<V>> valueWrapperClass = (Class<TimedValue<V>>) (Class) TimedValue.class;

    final ChronicleMapBuilder<KeyWrapper<K>, TimedValue<V>> mapBuilder =
        ChronicleMap.of(keyWrapperClass, valueWrapperClass).name(def.name());

    // Chronicle-map does not allow to custom-serialize boxed primitives
    // such as Boolean, Integer, for which size is statically determined.
    // This means that even though a custom serializer was provided for a primitive
    // it cannot be used.
    if (!mapBuilder.constantlySizedKeys()) {
      mapBuilder.averageKeySize(config.getAverageKeySize());
      mapBuilder.keyMarshaller(new KeyWrapperMarshaller<>(def.name()));
    }

    mapBuilder.averageValueSize(config.getAverageValueSize());

    TimedValueMarshaller<V> valueMarshaller = new TimedValueMarshaller<>(metricMaker, def.name());
    mapBuilder.valueMarshaller(valueMarshaller);

    mapBuilder.entries(config.getMaxEntries());

    mapBuilder.maxBloatFactor(config.getMaxBloatFactor());

    logger.atWarning().log(
        "chronicle-map cannot honour the diskLimit of %s bytes for the %s "
            + "cache, since the file size is pre-allocated rather than being "
            + "a function of the number of entries in the cache",
        def.diskLimit(), def.name());
    ChronicleMap<KeyWrapper<K>, TimedValue<V>> store =
        mapBuilder.createOrRecoverPersistedTo(config.getCacheFile());

    logger.atInfo().log(
        "Initialized '%s'|version: %s|avgKeySize: %s bytes|avgValueSize:"
            + " %s bytes|entries: %s|maxBloatFactor: %s|remainingAutoResizes:"
            + " %s|percentageFreeSpace: %s|persistIndexEvery: %s",
        def.name(),
        def.version(),
        mapBuilder.constantlySizedKeys() ? "CONSTANT" : config.getAverageKeySize(),
        config.getAverageValueSize(),
        config.getMaxEntries(),
        config.getMaxBloatFactor(),
        store.remainingAutoResizes(),
        store.percentageFreeSpace(),
        config.getPersistIndexEvery());

    return new ChronicleMapStore<>(store, config, metricMaker) {
      @Override
      public void close() {
        super.close();
        valueMarshaller.close();
      }
    };
  }

  protected PersistentCacheDef<K, V> getCacheDefinition() {
    return cacheDefinition;
  }

  public ChronicleMapCacheConfig getConfig() {
    return config;
  }

  @SuppressWarnings("unchecked")
  @Override
  public V getIfPresent(Object objKey) {
    K key = (K) objKey;

    TimedValue<V> timedValue =
        Optional.ofNullable(mem.getIfPresent(key)).orElse(memLoader.loadIfPresent(key));
    if (timedValue == null) {
      missCount.increment();
      return null;
    }

    mem.put(key, timedValue);
    keysIndex.add(objKey, timedValue.getCreated());
    return timedValue.getValue();
  }

  @Override
  public V get(K key) throws ExecutionException {
    KeyWrapper<K> keyWrapper = new KeyWrapper<>(key);

    if (mem.isLoadingCache()) {
      TimedValue<V> valueHolder = mem.get(key);
      if (needsRefresh(valueHolder.getCreated())) {
        store.remove(keyWrapper);
        mem.refresh(key);
        keysIndex.refresh(key);
      } else {
        keysIndex.add(key, valueHolder.getCreated());
      }
      return valueHolder.getValue();
    }

    loadExceptionCount.increment();
    throw new UnsupportedOperationException(
        String.format("Could not load value for %s without any loader", key));
  }

  @Override
  public V get(K key, Callable<? extends V> valueLoader) throws ExecutionException {
    try {
      TimedValue<V> value = mem.get(key, () -> getFromStore(key, valueLoader));
      keysIndex.add(key, value.getCreated());
      return value.getValue();
    } catch (ExecutionException e) {
      throw e;
    } catch (Exception e) {
      throw new ExecutionException(e);
    }
  }

  private TimedValue<V> getFromStore(K key, Callable<? extends V> valueLoader)
      throws ExecutionException {

    TimedValue<V> valueFromCache = memLoader.loadIfPresent(key);
    if (valueFromCache != null) {
      return valueFromCache;
    }

    V v = null;
    try {
      long start = System.nanoTime();
      v = valueLoader.call();
      totalLoadTime.add(System.nanoTime() - start);
      loadSuccessCount.increment();
    } catch (Exception e) {
      loadExceptionCount.increment();
      throw new ExecutionException(String.format("Could not load key %s", key), e);
    }
    TimedValue<V> timedValue = new TimedValue<>(v);
    putTimedToStore(key, timedValue);
    return timedValue;
  }

  /**
   * Associates the specified value with the specified key. This method should be used when the
   * creation time of the value needs to be preserved, rather than computed at insertion time
   * ({@link #put}. This is typically the case when migrating from an existing cache where the
   * creation timestamp needs to be preserved. See ({@link H2MigrationServlet} for an example.
   *
   * @param key
   * @param value
   * @param created
   */
  @SuppressWarnings("unchecked")
  public void putUnchecked(Object key, Object value, Timestamp created) {
    TimedValue<?> wrappedValue = new TimedValue<>(value, created.toInstant().toEpochMilli());
    KeyWrapper<?> wrappedKey = new KeyWrapper<>(key);
    if (store.tryPut((KeyWrapper<K>) wrappedKey, (TimedValue<V>) wrappedValue)) {
      mem.put((K) key, (TimedValue<V>) wrappedValue);
      keysIndex.add(key, wrappedValue.getCreated());
    }
  }

  /**
   * Associates the specified value with the specified key. This method should be used when the
   * {@link TimedValue} and the {@link KeyWrapper} have already been constructed elsewhere rather
   * than delegate their construction to this cache ({@link #put}. This is typically the case when
   * the key/value are extracted from another chronicle-map cache see ({@link
   * AutoAdjustCachesCommand} for an example.
   *
   * @param wrappedKey The wrapper for the key object
   * @param wrappedValue the wrapper for the value object
   */
  @SuppressWarnings("unchecked")
  public void putUnchecked(KeyWrapper<Object> wrappedKey, TimedValue<Object> wrappedValue) {
    if (store.tryPut((KeyWrapper<K>) wrappedKey, (TimedValue<V>) wrappedValue)) {
      mem.put((K) wrappedKey.getValue(), (TimedValue<V>) wrappedValue);
      keysIndex.add(wrappedKey.getValue(), wrappedValue.getCreated());
    }
  }

  @Override
  public void put(K key, V val) {
    TimedValue<V> timedVal = new TimedValue<>(val);
    if (putTimedToStore(key, timedVal)) {
      mem.put(key, timedVal);
      keysIndex.add(key, timedVal.getCreated());
    }
  }

  boolean putTimedToStore(K key, TimedValue<V> timedVal) {
    KeyWrapper<K> wrappedKey = new KeyWrapper<>(key);
    return store.tryPut(wrappedKey, timedVal);
  }

  public void prune() {
    if (!config.getExpireAfterWrite().isZero()) {
      long expirationTime = System.currentTimeMillis() - config.getExpireAfterWrite().toMillis();
      keysIndex.removeAndConsumeKeysOlderThan(
          expirationTime, key -> store.remove(new KeyWrapper<>(key)));
    }

    if (runningOutOfFreeSpace()) {
      evictColdEntries();
    }

    if (++pruneCount % config.getPersistIndexEveryNthPrune() == 0L) {
      indexPersistenceExecutor.execute(keysIndex::persist);
    }
  }

  private boolean needsRefresh(long created) {
    final Duration refreshAfterWrite = config.getRefreshAfterWrite();
    Duration age = Duration.between(Instant.ofEpochMilli(created), TimeUtil.now());
    return !refreshAfterWrite.isZero() && age.compareTo(refreshAfterWrite) > 0;
  }

  protected boolean runningOutOfFreeSpace() {
    return store.remainingAutoResizes() == 0
        && store.percentageFreeSpace() <= config.getPercentageFreeSpaceEvictionThreshold();
  }

  private void evictColdEntries() {
    while (runningOutOfFreeSpace()
        && keysIndex.removeAndConsumeLruKey(key -> store.remove(new KeyWrapper<>(key))))
      ;
  }

  @SuppressWarnings("unchecked")
  @Override
  public void invalidate(Object key) {
    KeyWrapper<K> wrappedKey = (KeyWrapper<K>) new KeyWrapper<>(key);
    store.remove(wrappedKey);
    mem.invalidate(key);
    keysIndex.invalidate(key);
  }

  @Override
  public void invalidateAll() {
    store.clear();
    mem.invalidateAll();
    keysIndex.clear();
  }

  ChronicleMap<KeyWrapper<K>, TimedValue<V>> getStore() {
    return store;
  }

  @Override
  public long size() {
    return mem.size();
  }

  @Override
  public CacheStats stats() {
    return mem.stats();
  }

  @Override
  public DiskStats diskStats() {
    return new DiskStats(
        store.longSize(),
        config.getCacheFile().length(),
        hitCount.longValue(),
        missCount.longValue(),
        DONT_TRACK_CACHE_INVALIDATIONS);
  }

  public CacheStats memStats() {
    return mem.stats();
  }

  public void close() {
    store.close();
    keysIndex.close();
  }

  public double percentageUsedAutoResizes() {
    return store.percentageUsedAutoResizes();
  }

  public String name() {
    return store.name();
  }
}
