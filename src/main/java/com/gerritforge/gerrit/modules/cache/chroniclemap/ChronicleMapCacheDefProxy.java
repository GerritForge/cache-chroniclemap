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
import com.google.common.cache.Weigher;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.server.cache.PersistentCacheDef;
import com.google.gerrit.server.cache.serialize.CacheSerializer;
import com.google.inject.TypeLiteral;
import java.time.Duration;

class ChronicleMapCacheDefProxy<K, V> implements PersistentCacheDef<K, V> {
  private final PersistentCacheDef<K, V> source;

  ChronicleMapCacheDefProxy(PersistentCacheDef<K, V> source) {
    this.source = source;
  }

  @Override
  @Nullable
  public Duration expireAfterWrite() {
    return source.expireAfterWrite();
  }

  @Override
  @Nullable
  public Duration expireFromMemoryAfterAccess() {
    return source.expireFromMemoryAfterAccess();
  }

  @Override
  public Duration refreshAfterWrite() {
    return source.refreshAfterWrite();
  }

  @Override
  public Weigher<K, V> weigher() {
    Weigher<K, V> weigher = source.weigher();
    if (weigher == null) {
      return null;
    }

    // introduce weigher that performs calculations
    // on value that is being stored not on TimedValue
    Weigher<K, TimedValue<V>> holderWeigher = (k, v) -> weigher.weigh(k, v.getValue());
    @SuppressWarnings("unchecked")
    Weigher<K, V> ret = (Weigher<K, V>) holderWeigher;
    return ret;
  }

  @Override
  public String name() {
    return source.name();
  }

  @Override
  public String configKey() {
    return source.configKey();
  }

  @Override
  public TypeLiteral<K> keyType() {
    return source.keyType();
  }

  @Override
  public TypeLiteral<V> valueType() {
    return source.valueType();
  }

  @Override
  public long maximumWeight() {
    return source.maximumWeight();
  }

  @Override
  public long diskLimit() {
    return source.diskLimit();
  }

  @Override
  public CacheLoader<K, V> loader() {
    return source.loader();
  }

  @Override
  public int version() {
    return source.version();
  }

  @Override
  public CacheSerializer<K> keySerializer() {
    return source.keySerializer();
  }

  @Override
  public CacheSerializer<V> valueSerializer() {
    return source.valueSerializer();
  }
}
