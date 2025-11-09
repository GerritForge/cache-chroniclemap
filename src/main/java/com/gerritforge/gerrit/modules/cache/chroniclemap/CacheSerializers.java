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

import com.google.common.annotations.VisibleForTesting;
import com.google.gerrit.server.cache.PersistentCacheDef;
import com.google.gerrit.server.cache.serialize.CacheSerializer;
import com.google.inject.Singleton;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class CacheSerializers {
  private static final Map<String, CacheSerializer<?>> keySerializers = new ConcurrentHashMap<>();
  private static final Map<String, CacheSerializer<?>> valueSerializers = new ConcurrentHashMap<>();

  static <K, V> void registerCacheDef(PersistentCacheDef<K, V> def) {
    String cacheName = def.name();
    registerCacheKeySerializer(cacheName, def.keySerializer());
    registerCacheValueSerializer(cacheName, def.valueSerializer());
  }

  @SuppressWarnings("unchecked")
  public static <K> CacheSerializer<K> getKeySerializer(String name) {
    if (keySerializers.containsKey(name)) {
      return (CacheSerializer<K>) keySerializers.get(name);
    }
    throw new IllegalStateException("Could not find key serializer for " + name);
  }

  @SuppressWarnings("unchecked")
  public static <V> CacheSerializer<V> getValueSerializer(String name) {
    if (valueSerializers.containsKey(name)) {
      return (CacheSerializer<V>) valueSerializers.get(name);
    }
    throw new IllegalStateException("Could not find value serializer for " + name);
  }

  @VisibleForTesting
  static <K, V> void registerCacheKeySerializer(
      String cacheName, CacheSerializer<K> keySerializer) {
    keySerializers.computeIfAbsent(cacheName, (name) -> keySerializer);
  }

  @VisibleForTesting
  static <K, V> void registerCacheValueSerializer(
      String cacheName, CacheSerializer<V> valueSerializer) {
    valueSerializers.computeIfAbsent(cacheName, (name) -> valueSerializer);
  }

  @VisibleForTesting
  static Set<String> getSerializersNames() {
    // caches registration during Gerrit's start is performed through registerCacheDef hence there
    // is no need to check both maps for all serializers names
    return keySerializers.keySet();
  }

  @VisibleForTesting
  static void clear() {
    keySerializers.clear();
    valueSerializers.clear();
  }
}
