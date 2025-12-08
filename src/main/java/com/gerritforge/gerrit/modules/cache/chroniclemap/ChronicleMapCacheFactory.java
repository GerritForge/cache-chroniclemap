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
import com.google.common.cache.Cache;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.registration.DynamicMap;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.server.cache.MemoryCacheFactory;
import com.google.gerrit.server.cache.PersistentCacheBaseFactory;
import com.google.gerrit.server.cache.PersistentCacheDef;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.logging.LoggingContextAwareExecutorService;
import com.google.gerrit.server.logging.LoggingContextAwareScheduledExecutorService;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.googlesource.gerrit.modules.cache.chroniclemap.TimedValue;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.lib.Config;

@Singleton
class ChronicleMapCacheFactory extends PersistentCacheBaseFactory implements LifecycleListener {
  static final long PRUNE_DELAY = 30;

  private final ChronicleMapCacheConfig.Factory configFactory;
  private final MetricMaker metricMaker;
  private final DynamicMap<Cache<?, ?>> cacheMap;
  private final List<ChronicleMapCacheImpl<?, ?>> caches;
  private final ScheduledExecutorService cleanup;

  private final LoggingContextAwareExecutorService storePersistenceExecutor;
  private final LoggingContextAwareExecutorService indexPersistenceExecutor;

  @Inject
  ChronicleMapCacheFactory(
      MemoryCacheFactory memCacheFactory,
      @GerritServerConfig Config cfg,
      @Nullable @ChronicleMapDir Path cacheDir,
      ChronicleMapCacheConfig.Factory configFactory,
      DynamicMap<Cache<?, ?>> cacheMap,
      MetricMaker metricMaker) {
    super(memCacheFactory, cfg, cacheDir);
    this.configFactory = configFactory;
    this.metricMaker = metricMaker;
    this.caches = new LinkedList<>();
    this.cacheMap = cacheMap;
    this.cleanup =
        new LoggingContextAwareScheduledExecutorService(
            Executors.newScheduledThreadPool(
                1,
                new ThreadFactoryBuilder()
                    .setNameFormat("ChronicleMap-Prune-%d")
                    .setDaemon(true)
                    .build()));
    this.storePersistenceExecutor =
        new LoggingContextAwareExecutorService(
            Executors.newFixedThreadPool(
                1, new ThreadFactoryBuilder().setNameFormat("ChronicleMap-Store-%d").build()));
    this.indexPersistenceExecutor =
        new LoggingContextAwareExecutorService(
            Executors.newFixedThreadPool(
                1, new ThreadFactoryBuilder().setNameFormat("ChronicleMap-Index-%d").build()));
  }

  @Override
  public <K, V> Cache<K, V> buildImpl(PersistentCacheDef<K, V> in, long limit) {
    ChronicleMapCacheConfig config =
        configFactory.create(
            in.configKey(),
            fileName(cacheDir, in.name(), in.version()),
            in.expireAfterWrite(),
            in.refreshAfterWrite());
    return build(in, config, metricMaker);
  }

  @SuppressWarnings("unchecked")
  @VisibleForTesting
  <K, V> Cache<K, V> build(
      PersistentCacheDef<K, V> in, ChronicleMapCacheConfig config, MetricMaker metricMaker) {
    ChronicleMapCacheDefProxy<K, V> def = new ChronicleMapCacheDefProxy<>(in);

    ChronicleMapCacheImpl<K, V> cache;
    try {
      ChronicleMapStore<K, V> store =
          ChronicleMapCacheImpl.createOrRecoverStore(in, config, metricMaker);

      ChronicleMapCacheLoader<K, V> memLoader =
          new ChronicleMapCacheLoader<>(
              storePersistenceExecutor, store, config.getExpireAfterWrite());

      LoadingCache<K, TimedValue<V>> mem =
          (LoadingCache<K, TimedValue<V>>)
              memCacheFactory.build(def, (CacheLoader<K, V>) memLoader);

      cache =
          new ChronicleMapCacheImpl<>(
              in,
              config,
              metricMaker,
              memLoader,
              new InMemoryCacheLoadingFromStoreImpl<>(mem, false),
              indexPersistenceExecutor);

    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    synchronized (caches) {
      caches.add(cache);
    }
    return cache;
  }

  @Override
  public <K, V> LoadingCache<K, V> buildImpl(
      PersistentCacheDef<K, V> in, CacheLoader<K, V> loader, long limit) {
    ChronicleMapCacheConfig config =
        configFactory.create(
            in.configKey(),
            fileName(cacheDir, in.name(), in.version()),
            in.expireAfterWrite(),
            in.refreshAfterWrite());
    return build(in, loader, config, metricMaker);
  }

  @SuppressWarnings("unchecked")
  @VisibleForTesting
  public <K, V> LoadingCache<K, V> build(
      PersistentCacheDef<K, V> in,
      CacheLoader<K, V> loader,
      ChronicleMapCacheConfig config,
      MetricMaker metricMaker) {
    ChronicleMapCacheImpl<K, V> cache;
    ChronicleMapCacheDefProxy<K, V> def = new ChronicleMapCacheDefProxy<>(in);

    try {
      ChronicleMapStore<K, V> store =
          ChronicleMapCacheImpl.createOrRecoverStore(in, config, metricMaker);

      ChronicleMapCacheLoader<K, V> memLoader =
          new ChronicleMapCacheLoader<>(
              storePersistenceExecutor, store, loader, config.getExpireAfterWrite());

      LoadingCache<K, TimedValue<V>> mem =
          (LoadingCache<K, TimedValue<V>>)
              memCacheFactory.build(def, (CacheLoader<K, V>) memLoader);

      cache =
          new ChronicleMapCacheImpl<>(
              in,
              config,
              metricMaker,
              memLoader,
              new InMemoryCacheLoadingFromStoreImpl<>(mem, true),
              indexPersistenceExecutor);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    synchronized (caches) {
      caches.add(cache);
    }
    return cache;
  }

  @Override
  public void onStop(String plugin) {
    synchronized (caches) {
      for (Map.Entry<String, Provider<Cache<?, ?>>> entry : cacheMap.byPlugin(plugin).entrySet()) {
        Cache<?, ?> cache = entry.getValue().get();
        if (caches.remove(cache)) {
          ((ChronicleMapCacheImpl<?, ?>) cache).close();
        }
      }
    }
  }

  @Override
  public void start() {
    for (ChronicleMapCacheImpl<?, ?> cache : caches) {
      cleanup.scheduleWithFixedDelay(cache::prune, PRUNE_DELAY, PRUNE_DELAY, TimeUnit.SECONDS);
    }
  }

  @Override
  public void stop() {
    cleanup.shutdownNow();
    for (ChronicleMapCacheImpl<?, ?> cache : caches) {
      cache.close();
    }
    caches.clear();
    storePersistenceExecutor.shutdown();
    indexPersistenceExecutor.shutdown();
  }

  public static File fileName(Path cacheDir, String name, Integer version) {
    return cacheDir.resolve(String.format("%s_%s.dat", name, version)).toFile();
  }

  protected static Path getCacheDir(SitePaths site, String name) {
    return site.resolve(name);
  }
}
