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
import com.google.gerrit.server.cache.serialize.StringCacheSerializer;
import com.google.inject.TypeLiteral;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public class TestPersistentCacheDef implements PersistentCacheDef<String, String> {

  private static final Duration ONE_YEAR = Duration.ofDays(365);

  private static final Integer DEFAULT_DISK_LIMIT = 1024;
  private static final Integer DEFAULT_MEMORY_LIMIT = 1024;

  private static final Duration DEFAULT_EXPIRY_AFTER_MEMORY_ACCESS = ONE_YEAR;

  private final String name;
  private final String loadedValue;
  private final Duration expireAfterWrite;
  private final Duration refreshAfterWrite;
  private final Duration expireFromMemoryAfterAccess;
  private final Integer maximumWeight;
  private final Integer diskLimit;
  private final CacheSerializer<String> keySerializer;
  private final CacheSerializer<String> valueSerializer;

  public TestPersistentCacheDef(
      String name,
      @Nullable String loadedValue,
      @Nullable Duration expireAfterWrite,
      @Nullable Duration refreshAfterWrite) {

    this.name = name;
    this.loadedValue = loadedValue;
    this.expireAfterWrite = expireAfterWrite;
    this.refreshAfterWrite = refreshAfterWrite;
    this.expireFromMemoryAfterAccess = DEFAULT_EXPIRY_AFTER_MEMORY_ACCESS;
    this.diskLimit = DEFAULT_DISK_LIMIT;
    this.maximumWeight = DEFAULT_MEMORY_LIMIT;
    this.keySerializer = StringCacheSerializer.INSTANCE;
    this.valueSerializer = StringCacheSerializer.INSTANCE;
  }

  public TestPersistentCacheDef(
      String name, @Nullable String loadedValue, Integer diskLimit, Integer memoryLimit) {

    this.name = name;
    this.loadedValue = loadedValue;
    this.expireAfterWrite = null;
    this.refreshAfterWrite = null;
    this.expireFromMemoryAfterAccess = DEFAULT_EXPIRY_AFTER_MEMORY_ACCESS;
    this.diskLimit = diskLimit;
    this.keySerializer = StringCacheSerializer.INSTANCE;
    this.valueSerializer = StringCacheSerializer.INSTANCE;
    this.maximumWeight = memoryLimit;
  }

  public TestPersistentCacheDef(
      String name,
      @Nullable String loadedValue,
      @Nullable CacheSerializer<String> keySerializer,
      @Nullable CacheSerializer<String> valueSerializer,
      boolean withLoader,
      @Nullable Integer memoryLimit) {
    this(name, loadedValue, keySerializer, valueSerializer, withLoader, null, memoryLimit);
  }

  public TestPersistentCacheDef(
      String name,
      @Nullable String loadedValue,
      @Nullable CacheSerializer<String> keySerializer,
      @Nullable CacheSerializer<String> valueSerializer,
      boolean withLoader,
      @Nullable Duration maxAge,
      @Nullable Integer memoryLimit) {

    this.name = name;
    this.loadedValue = loadedValue;
    this.expireAfterWrite = withLoader ? ONE_YEAR : null;
    this.refreshAfterWrite = withLoader ? ONE_YEAR : null;
    this.expireFromMemoryAfterAccess = maxAge == null ? DEFAULT_EXPIRY_AFTER_MEMORY_ACCESS : maxAge;
    this.diskLimit = DEFAULT_DISK_LIMIT;
    this.maximumWeight = memoryLimit == null ? DEFAULT_MEMORY_LIMIT : memoryLimit;
    this.keySerializer = Optional.ofNullable(keySerializer).orElse(StringCacheSerializer.INSTANCE);
    this.valueSerializer =
        Optional.ofNullable(valueSerializer).orElse(StringCacheSerializer.INSTANCE);
  }

  @Override
  public long diskLimit() {
    return diskLimit;
  }

  @Override
  public int version() {
    return 0;
  }

  @Override
  public CacheSerializer<String> keySerializer() {
    return keySerializer;
  }

  @Override
  public CacheSerializer<String> valueSerializer() {
    return valueSerializer;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public String configKey() {
    return name();
  }

  @Override
  public TypeLiteral<String> keyType() {
    return new TypeLiteral<>() {};
  }

  @Override
  public TypeLiteral<String> valueType() {
    return new TypeLiteral<>() {};
  }

  @Override
  public long maximumWeight() {
    return maximumWeight;
  }

  @Override
  public Duration expireAfterWrite() {
    return expireAfterWrite;
  }

  @Override
  public Duration expireFromMemoryAfterAccess() {
    return expireFromMemoryAfterAccess;
  }

  @Override
  public Duration refreshAfterWrite() {
    return refreshAfterWrite;
  }

  @Override
  public Weigher<String, String> weigher() {
    return (s, s2) -> 0;
  }

  @Override
  public CacheLoader<String, String> loader() {
    return new CacheLoader<>() {
      @Override
      public String load(String s) {
        return loadedValue != null ? loadedValue : UUID.randomUUID().toString();
      }
    };
  }
}
