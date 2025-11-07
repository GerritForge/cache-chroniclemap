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

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.metrics.MetricMaker;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.openhft.chronicle.bytes.BytesStore;
import net.openhft.chronicle.core.io.Closeable;
import net.openhft.chronicle.core.util.SerializableFunction;
import net.openhft.chronicle.hash.Data;
import net.openhft.chronicle.map.ChronicleMap;
import net.openhft.chronicle.map.ExternalMapQueryContext;
import net.openhft.chronicle.map.MapEntry;
import net.openhft.chronicle.map.MapSegmentContext;
import net.openhft.chronicle.map.VanillaChronicleMap;

class ChronicleMapStore<K, V> implements ChronicleMap<KeyWrapper<K>, TimedValue<V>> {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final ChronicleMap<KeyWrapper<K>, TimedValue<V>> store;
  private final ChronicleMapCacheConfig config;
  private final ChronicleMapStoreMetrics metrics;

  ChronicleMapStore(
      ChronicleMap<KeyWrapper<K>, TimedValue<V>> store,
      ChronicleMapCacheConfig config,
      MetricMaker metricMaker) {

    this.store = store;
    this.config = config;
    this.metrics = new ChronicleMapStoreMetrics(store.name(), metricMaker);
    metrics.registerCallBackMetrics(this);
  }

  /**
   * Attempt to put the key/value pair into the chronicle-map store. Also catches and warns on disk
   * allocation errors, so that such failures result in non-cached entries rather than throwing.
   *
   * @param wrappedKey the wrapped key value
   * @param timedVal the timed value
   * @return true when the value was successfully inserted in chronicle-map, false otherwise
   */
  public boolean tryPut(KeyWrapper<K> wrappedKey, TimedValue<V> timedVal) {
    try {
      store.put(wrappedKey, timedVal);
    } catch (IllegalArgumentException | IllegalStateException e) {
      metrics.incrementPutFailures();
      logger.atWarning().withCause(e).log(
          "[cache %s] Caught exception when inserting entry '%s' in chronicle-map",
          store.name(), wrappedKey.getValue());
      return false;
    }
    return true;
  }

  @SuppressWarnings("rawtypes")
  public double percentageUsedAutoResizes() {
    /*
     * Chronicle-map already exposes the number of _remaining_ auto-resizes, but
     * this is an absolute value, and it is not enough to understand the
     * percentage of auto-resizes that have been utilized.
     *
     * For that, we fist need to understand the _maximum_ number of possible
     * resizes (inclusive of the resizes allowed by the max-bloat factor).
     * This information is exposed at low level, by the VanillaChronicleMap,
     * which has access to the number of allocated segments.
     *
     * So we proceed as follows:
     *
     * Calculate the maximum number of segments by multiplying the allocated
     * segments (`actualSegments`) by the configured max-bloat-factor.
     *
     * The ratio between this value and the _current_ segment utilization
     * (`getExtraTiersInUse`) shows the overall percentage.
     */
    VanillaChronicleMap vanillaStore = (VanillaChronicleMap) store;
    long usedResizes = vanillaStore.globalMutableState().getExtraTiersInUse();
    return usedResizes * 100 / maxAutoResizes();
  }

  @SuppressWarnings("rawtypes")
  public double maxAutoResizes() {
    return config.getMaxBloatFactor() * ((VanillaChronicleMap) store).actualSegments;
  }

  @Override
  public int size() {
    return store.size();
  }

  @Override
  public boolean isEmpty() {
    return store.isEmpty();
  }

  @Override
  public boolean containsKey(Object key) {
    return store.containsKey(key);
  }

  @Override
  public boolean containsValue(Object value) {
    return store.containsValue(value);
  }

  @Override
  public TimedValue<V> get(Object key) {
    return store.get(key);
  }

  @Override
  public TimedValue<V> put(KeyWrapper<K> key, TimedValue<V> value) {
    return store.put(key, value);
  }

  @Override
  public TimedValue<V> remove(Object key) {
    return store.remove(key);
  }

  @Override
  public void putAll(Map<? extends KeyWrapper<K>, ? extends TimedValue<V>> m) {
    store.putAll(m);
  }

  @Override
  public void clear() {
    store.clear();
  }

  @Override
  public Set<KeyWrapper<K>> keySet() {
    return store.keySet();
  }

  @Override
  public Collection<TimedValue<V>> values() {
    return store.values();
  }

  @Override
  public Set<Entry<KeyWrapper<K>, TimedValue<V>>> entrySet() {
    return store.entrySet();
  }

  @Override
  public boolean equals(Object o) {
    return store.equals(o);
  }

  @Override
  public int hashCode() {
    return store.hashCode();
  }

  @Override
  public TimedValue<V> getUsing(KeyWrapper<K> key, TimedValue<V> usingValue) {
    return store.getUsing(key, usingValue);
  }

  @Override
  public TimedValue<V> acquireUsing(KeyWrapper<K> key, TimedValue<V> usingValue) {
    return store.acquireUsing(key, usingValue);
  }

  @Override
  public Closeable acquireContext(KeyWrapper<K> key, TimedValue<V> usingValue) {
    return store.acquireContext(key, usingValue);
  }

  @Override
  public <R> R getMapped(
      KeyWrapper<K> key, SerializableFunction<? super TimedValue<V>, R> function) {
    return store.getMapped(key, function);
  }

  @Override
  public void getAll(File toFile) throws IOException {
    store.getAll(toFile);
  }

  @Override
  public void putAll(File fromFile) throws IOException {
    store.putAll(fromFile);
  }

  @Override
  public Class<TimedValue<V>> valueClass() {
    return store.valueClass();
  }

  @Override
  public Type valueType() {
    return store.valueType();
  }

  @Override
  public short percentageFreeSpace() {
    return store.percentageFreeSpace();
  }

  @Override
  public int remainingAutoResizes() {
    return store.remainingAutoResizes();
  }

  @Override
  public TimedValue<V> putIfAbsent(KeyWrapper<K> key, TimedValue<V> value) {
    return store.putIfAbsent(key, value);
  }

  @Override
  public boolean remove(Object key, Object value) {
    return store.remove(key, value);
  }

  @Override
  public boolean replace(KeyWrapper<K> key, TimedValue<V> oldValue, TimedValue<V> newValue) {
    return store.replace(key, oldValue, newValue);
  }

  @Override
  public TimedValue<V> replace(KeyWrapper<K> key, TimedValue<V> value) {
    return store.replace(key, value);
  }

  @Override
  public File file() {
    return store.file();
  }

  @Override
  public String name() {
    return store.name();
  }

  @Override
  public String toIdentityString() {
    return store.toIdentityString();
  }

  @Override
  public long longSize() {
    return store.longSize();
  }

  @Override
  public long offHeapMemoryUsed() {
    return store.offHeapMemoryUsed();
  }

  @Override
  public Class<KeyWrapper<K>> keyClass() {
    return store.keyClass();
  }

  @Override
  public Type keyType() {
    return store.keyType();
  }

  @Override
  public ExternalMapQueryContext<KeyWrapper<K>, TimedValue<V>, ?> queryContext(KeyWrapper<K> key) {
    return store.queryContext(key);
  }

  @Override
  public ExternalMapQueryContext<KeyWrapper<K>, TimedValue<V>, ?> queryContext(
      Data<KeyWrapper<K>> key) {
    return store.queryContext(key);
  }

  @Override
  public ExternalMapQueryContext<KeyWrapper<K>, TimedValue<V>, ?> queryContext(
      @SuppressWarnings("rawtypes") BytesStore keyBytes, long offset, long size) {
    return store.queryContext(keyBytes, offset, size);
  }

  @Override
  public MapSegmentContext<KeyWrapper<K>, TimedValue<V>, ?> segmentContext(int segmentIndex) {
    return store.segmentContext(segmentIndex);
  }

  @Override
  public int segments() {
    return store.segments();
  }

  @Override
  public boolean forEachEntryWhile(
      Predicate<? super MapEntry<KeyWrapper<K>, TimedValue<V>>> predicate) {
    return store.forEachEntryWhile(predicate);
  }

  @Override
  public void forEachEntry(Consumer<? super MapEntry<KeyWrapper<K>, TimedValue<V>>> action) {
    store.forEachEntry(action);
  }

  @Override
  public void close() {
    store.close();
    metrics.close();
  }

  @Override
  public boolean isOpen() {
    return store.isOpen();
  }
}
