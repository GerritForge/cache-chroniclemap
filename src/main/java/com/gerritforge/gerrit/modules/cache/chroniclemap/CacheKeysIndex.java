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

import static java.util.stream.Collectors.toUnmodifiableSet;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.flogger.FluentLogger;
import com.google.errorprone.annotations.CompatibleWith;
import com.google.gerrit.extensions.registration.RegistrationHandle;
import com.google.gerrit.metrics.Counter0;
import com.google.gerrit.metrics.Description;
import com.google.gerrit.metrics.Description.Units;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.metrics.Timer0;
import com.google.gerrit.server.cache.serialize.CacheSerializer;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

class CacheKeysIndex<T> {
  /** As opposed to TimedValue keys are equal when their key equals. */
  class TimedKey {
    private final T key;
    private final long created;

    private TimedKey(T key, long created) {
      this.key = key;
      this.created = created;
    }

    T getKey() {
      return key;
    }

    long getCreated() {
      return created;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o instanceof CacheKeysIndex.TimedKey) {
        @SuppressWarnings("unchecked")
        TimedKey other = (TimedKey) o;
        return Objects.equals(key, other.key);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(key);
    }
  }

  private class Metrics {
    private final RegistrationHandle indexSize;
    private final Timer0 addLatency;
    private final Timer0 removeAndConsumeOlderThanLatency;
    private final Timer0 removeAndConsumeLruKeyLatency;
    private final Timer0 restoreLatency;
    private final Timer0 persistLatency;
    private final Counter0 restoreFailures;
    private final Counter0 persistFailures;

    private Metrics(MetricMaker metricMaker, String name) {
      String sanitizedName = CacheNameSanitizer.sanitize(metricMaker, name);

      indexSize =
          metricMaker.newCallbackMetric(
              "cache/chroniclemap/keys_index_size_" + sanitizedName,
              Integer.class,
              new Description(
                  String.format(
                      "The number of cache index keys for %s cache that are currently in memory",
                      name)),
              keys::size);

      addLatency =
          metricMaker.newTimer(
              "cache/chroniclemap/keys_index_add_latency_" + sanitizedName,
              new Description(
                      String.format("The latency of adding key to the index for %s cache", name))
                  .setCumulative()
                  .setUnit(Units.NANOSECONDS));

      removeAndConsumeOlderThanLatency =
          metricMaker.newTimer(
              "cache/chroniclemap/keys_index_remove_and_consume_older_than_latency_"
                  + sanitizedName,
              new Description(
                      String.format(
                          "The latency of removing and consuming all keys older than expiration"
                              + " time for the index for %s cache",
                          name))
                  .setCumulative()
                  .setUnit(Units.NANOSECONDS));

      removeAndConsumeLruKeyLatency =
          metricMaker.newTimer(
              "cache/chroniclemap/keys_index_remove_lru_key_latency_" + sanitizedName,
              new Description(
                      String.format(
                          "The latency of removing and consuming LRU key from the index for %s"
                              + " cache",
                          name))
                  .setCumulative()
                  .setUnit(Units.NANOSECONDS));

      restoreLatency =
          metricMaker.newTimer(
              "cache/chroniclemap/keys_index_restore_latency_" + sanitizedName,
              new Description(
                      String.format("The latency of restoring %s cache's index from file", name))
                  .setCumulative()
                  .setUnit(Units.MICROSECONDS));

      persistLatency =
          metricMaker.newTimer(
              "cache/chroniclemap/keys_index_persist_latency_" + sanitizedName,
              new Description(
                      String.format("The latency of perststing %s cache's index to file", name))
                  .setCumulative()
                  .setUnit(Units.MICROSECONDS));

      restoreFailures =
          metricMaker.newCounter(
              "cache/chroniclemap/keys_index_restore_failures_" + sanitizedName,
              new Description(
                      String.format(
                          "The number of errors caught when restore %s cache index from file"
                              + " operation was performed: ",
                          name))
                  .setCumulative()
                  .setUnit("errors"));

      persistFailures =
          metricMaker.newCounter(
              "cache/chroniclemap/keys_index_persist_failures_" + sanitizedName,
              new Description(
                      String.format(
                          "The number of errors caught when persist %s cache index to file"
                              + " operation was performed: ",
                          name))
                  .setCumulative()
                  .setUnit("errors"));
    }

    private void close() {
      indexSize.remove();
      addLatency.remove();
      removeAndConsumeOlderThanLatency.remove();
      removeAndConsumeLruKeyLatency.remove();
      restoreLatency.remove();
      persistLatency.remove();
      restoreFailures.remove();
      persistFailures.remove();
    }
  }

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final Set<TimedKey> keys;
  private final Metrics metrics;
  private final String name;
  private final File indexFile;
  private final File tempIndexFile;
  private final AtomicBoolean persistInProgress;

  CacheKeysIndex(MetricMaker metricMaker, String name, File indexFile, boolean cacheFileExists) {
    this.keys = Collections.synchronizedSet(new LinkedHashSet<>());
    this.metrics = new Metrics(metricMaker, name);
    this.name = name;
    this.indexFile = indexFile;
    this.tempIndexFile = tempIndexFile(indexFile);
    this.persistInProgress = new AtomicBoolean(false);
    restore(cacheFileExists);
  }

  @SuppressWarnings("unchecked")
  void add(@CompatibleWith("T") Object key, long created) {
    Objects.requireNonNull(key, "Key value cannot be [null].");
    TimedKey timedKey = new TimedKey((T) key, created);

    // bubble up MRU key by re-adding it to a set
    try (Timer0.Context timer = metrics.addLatency.start()) {
      keys.remove(timedKey);
      keys.add(timedKey);
    }
  }

  void refresh(T key) {
    add(key, System.currentTimeMillis());
  }

  void removeAndConsumeKeysOlderThan(long time, Consumer<T> consumer) {
    try (Timer0.Context timer = metrics.removeAndConsumeOlderThanLatency.start()) {
      Set<TimedKey> toRemoveAndConsume;
      synchronized (keys) {
        toRemoveAndConsume =
            keys.stream().filter(key -> key.created < time).collect(toUnmodifiableSet());
      }
      toRemoveAndConsume.forEach(
          key -> {
            keys.remove(key);
            consumer.accept(key.getKey());
          });
    }
  }

  boolean removeAndConsumeLruKey(Consumer<T> consumer) {
    try (Timer0.Context timer = metrics.removeAndConsumeLruKeyLatency.start()) {
      Optional<TimedKey> lruKey;
      synchronized (keys) {
        lruKey = keys.stream().findFirst();
      }

      return lruKey
          .map(
              key -> {
                keys.remove(key);
                consumer.accept(key.getKey());
                return true;
              })
          .orElse(false);
    }
  }

  void invalidate(@CompatibleWith("T") Object key) {
    keys.remove(key);
  }

  void clear() {
    keys.clear();
  }

  @VisibleForTesting
  Set<TimedKey> keys() {
    return keys;
  }

  void persist() {
    if (!persistInProgress.compareAndSet(false, true)) {
      logger.atWarning().log(
          "Persist cache keys index %s to %s file is already in progress. This persist request was"
              + " skipped.",
          name, indexFile);
      return;
    }

    logger.atInfo().log("Persisting cache keys index %s to %s file", name, indexFile);
    try (Timer0.Context timer = metrics.persistLatency.start()) {
      ArrayList<TimedKey> toPersist;
      synchronized (keys) {
        toPersist = new ArrayList<>(keys);
      }
      CacheSerializer<T> serializer = CacheSerializers.getKeySerializer(name);
      try (DataOutputStream dos =
          new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tempIndexFile)))) {
        for (TimedKey key : toPersist) {
          writeKey(serializer, dos, key);
        }
        dos.flush();
        indexFile.delete();
        if (!tempIndexFile.renameTo(indexFile)) {
          logger.atWarning().log(
              "Renaming temporary index file %s to %s was not successful",
              tempIndexFile, indexFile);
          metrics.persistFailures.increment();
          return;
        }
        logger.atFine().log("Cache keys index %s was persisted to %s file", name, indexFile);
      } catch (Exception e) {
        logger.atSevere().withCause(e).log("Persisting cache keys index %s failed", name);
        metrics.persistFailures.increment();
      }
    } finally {
      persistInProgress.set(false);
    }
  }

  void restore(boolean cacheFileExists) {
    try {
      if (tempIndexFile.isFile()) {
        logger.atWarning().log(
            "Gerrit was not closed properly as index persist operation was not finished: temporary"
                + " index storage file %s exists for %s cache.",
            tempIndexFile, name);
        metrics.restoreFailures.increment();
        if (!tempIndexFile.delete()) {
          logger.atSevere().log(
              "Cannot delete the temporary index storage file %s.", tempIndexFile);
          return;
        }
      }

      if (!indexFile.isFile() || !indexFile.canRead()) {
        if (cacheFileExists) {
          logger.atWarning().log(
              "Restoring cache keys index %s not possible. File %s doesn't exist or cannot be read."
                  + " Existing persisted entries will be pruned only when they are accessed after"
                  + " Gerrit start.",
              name, indexFile.getPath());
          metrics.restoreFailures.increment();
        }
        return;
      }

      logger.atInfo().log("Restoring cache keys index %s from %s file", name, indexFile);
      try (Timer0.Context timer = metrics.restoreLatency.start()) {
        CacheSerializer<T> serializer = CacheSerializers.getKeySerializer(name);
        try (DataInputStream dis =
            new DataInputStream(new BufferedInputStream(new FileInputStream(indexFile)))) {
          while (dis.available() > 0) {
            keys.add(readKey(serializer, dis));
          }
          logger.atInfo().log("Cache keys index %s was restored from %s file", name, indexFile);
        }
      }
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("Restoring cache keys index %s failed", name);
      metrics.restoreFailures.increment();
    }
  }

  void close() {
    persist();
    metrics.close();
  }

  static final File tempIndexFile(File indexFile) {
    return new File(String.format("%s.tmp", indexFile.getPath()));
  }

  private void writeKey(CacheSerializer<T> serializer, DataOutput out, TimedKey key)
      throws IOException {
    byte[] serializeKey = serializer.serialize(key.getKey());
    out.writeLong(key.getCreated());
    out.writeInt(serializeKey.length);
    out.write(serializeKey);
  }

  private TimedKey readKey(CacheSerializer<T> serializer, DataInput in) throws IOException {
    long created = in.readLong();
    int keySize = in.readInt();
    byte[] serializedKey = new byte[keySize];
    in.readFully(serializedKey);
    T key = serializer.deserialize(serializedKey);
    return new TimedKey(key, created);
  }
}
