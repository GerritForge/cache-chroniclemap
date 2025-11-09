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

import static com.gerritforge.gerrit.modules.cache.chroniclemap.ChronicleMapCacheFactory.getCacheDir;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.Cache;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.registration.DynamicMap;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ProgressMonitor;

public class AutoAdjustCaches {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  protected static final String CONFIG_HEADER = "__CONFIG__";
  protected static final String TUNED_INFIX = "_tuned_";

  protected static final Integer MAX_ENTRIES_MULTIPLIER = 2;
  protected static final Integer PERCENTAGE_SIZE_INCREASE_THRESHOLD = 50;

  private final DynamicMap<Cache<?, ?>> cacheMap;
  private final ChronicleMapCacheConfig.Factory configFactory;
  private final Path cacheDir;
  private final AdministerCachePermission adminCachePermission;
  private final CachesWithoutChronicleMapConfigMetric metric;

  private boolean dryRun;
  private boolean adjustCachesOnDefaults;
  private Optional<Long> optionalAvgKeySize = Optional.empty();
  private Optional<Long> optionalAvgValueSize = Optional.empty();
  private Optional<Long> optionalMaxEntries = Optional.empty();
  private Set<String> cacheNames = new HashSet<>();

  @Inject
  AutoAdjustCaches(
      @GerritServerConfig Config cfg,
      SitePaths site,
      DynamicMap<Cache<?, ?>> cacheMap,
      ChronicleMapCacheConfig.Factory configFactory,
      AdministerCachePermission adminCachePermission,
      CachesWithoutChronicleMapConfigMetric metric) {
    this.cacheMap = cacheMap;
    this.configFactory = configFactory;
    this.cacheDir = getCacheDir(site, cfg.getString("cache", null, "directory"));
    this.adminCachePermission = adminCachePermission;
    this.metric = metric;
  }

  public boolean isDryRun() {
    return dryRun;
  }

  public void setDryRun(boolean dryRun) {
    this.dryRun = dryRun;
  }

  public boolean isAdjustCachesOnDefaults() {
    return adjustCachesOnDefaults;
  }

  public void setAdjustCachesOnDefaults(boolean adjustCachesOnDefaults) {
    this.adjustCachesOnDefaults = adjustCachesOnDefaults;
  }

  public Optional<Long> getOptionalMaxEntries() {
    return optionalMaxEntries;
  }

  public Optional<Long> getOptionalAvgKeySize() {
    return optionalAvgKeySize;
  }

  public Optional<Long> getOptionalAvgValueSize() {
    return optionalAvgValueSize;
  }

  public void setOptionalMaxEntries(Optional<Long> maxEntries) {
    this.optionalMaxEntries = maxEntries;
  }

  public void setAvgKeySize(Optional<Long> avgKeySize) {
    this.optionalAvgKeySize = avgKeySize;
  }

  public void setAvgValueSize(Optional<Long> avgValueSize) {
    this.optionalAvgValueSize = avgValueSize;
  }

  public void addCacheNames(List<String> cacheNames) {
    this.cacheNames.addAll(cacheNames);
  }

  protected Config run(@Nullable ProgressMonitor optionalProgressMonitor)
      throws AuthException, PermissionBackendException, IOException {
    ProgressMonitor progressMonitor =
        optionalProgressMonitor == null ? NullProgressMonitor.INSTANCE : optionalProgressMonitor;
    adminCachePermission.checkCurrentUserAllowed(null);

    Config outputChronicleMapConfig = new Config();

    Map<String, ChronicleMapCacheImpl<Object, Object>> chronicleMapCaches = getChronicleMapCaches();

    for (Map.Entry<String, ChronicleMapCacheImpl<Object, Object>> cache :
        chronicleMapCaches.entrySet()) {
      String cacheName = cache.getKey();
      ChronicleMapCacheImpl<Object, Object> currCache = cache.getValue();

      {
        long newKeySize;
        long newValueSize;

        if (optionalAvgKeySize.isPresent() && optionalAvgValueSize.isPresent()) {
          newKeySize = optionalAvgKeySize.get();
          newValueSize = optionalAvgValueSize.get();
        } else {
          ImmutablePair<Long, Long> avgSizes =
              averageSizes(cacheName, currCache.getStore(), progressMonitor);
          if (!(avgSizes.getKey() > 0) || !(avgSizes.getValue() > 0)) {
            logger.atWarning().log(
                "Cache [%s] has %s entries, but average of (key: %d, value: %d). Skipping.",
                cacheName, currCache.diskStats().size(), avgSizes.getKey(), avgSizes.getValue());
            continue;
          }

          newKeySize = getOptionalAvgKeySize().orElseGet(avgSizes::getKey);
          newValueSize = getOptionalAvgValueSize().orElseGet(avgSizes::getValue);
        }

        ChronicleMapCacheConfig currCacheConfig = currCache.getConfig();
        long newMaxEntries = newMaxEntries(currCache);

        if (currCacheConfig.getAverageKeySize() == newKeySize
            && currCacheConfig.getAverageValueSize() == newValueSize
            && currCacheConfig.getMaxEntries() == newMaxEntries) {
          continue;
        }

        ChronicleMapCacheConfig newChronicleMapCacheConfig =
            makeChronicleMapConfig(currCache.getConfig(), newMaxEntries, newKeySize, newValueSize);

        updateOutputConfig(
            outputChronicleMapConfig,
            cacheName,
            newKeySize,
            newValueSize,
            newMaxEntries,
            currCache.getConfig().getMaxBloatFactor());

        if (!dryRun) {
          ChronicleMapCacheImpl<Object, Object> newCache =
              new ChronicleMapCacheImpl<>(
                  currCache.getCacheDefinition(), newChronicleMapCacheConfig);

          progressMonitor.beginTask(
              String.format("[%s] migrate content", cacheName), (int) currCache.size());

          currCache
              .getStore()
              .forEach(
                  (k, v) -> {
                    try {
                      newCache.putUnchecked(k, v);

                      progressMonitor.update(1);
                    } catch (Exception e) {
                      logger.atWarning().withCause(e).log(
                          "[%s] Could not migrate entry %s -> %s",
                          cacheName, k.getValue(), v.getValue());
                    }
                  });
        }
      }
    }

    return outputChronicleMapConfig;
  }

  private ImmutablePair<Long, Long> averageSizes(
      String cacheName,
      ConcurrentMap<KeyWrapper<Object>, TimedValue<Object>> store,
      ProgressMonitor progressMonitor) {
    long kTotal = 0;
    long vTotal = 0;

    if (store.isEmpty()) return ImmutablePair.of(kTotal, vTotal);

    progressMonitor.beginTask(
        String.format("[%s] calculate average key/value size", cacheName), store.size());

    for (Map.Entry<KeyWrapper<Object>, TimedValue<Object>> entry : store.entrySet()) {
      kTotal += serializedKeyLength(cacheName, entry.getKey());
      vTotal += serializedValueLength(cacheName, entry.getValue());
      progressMonitor.update(1);
    }
    progressMonitor.endTask();
    long numCacheEntries = store.entrySet().size();
    return ImmutablePair.of(kTotal / numCacheEntries, vTotal / numCacheEntries);
  }

  @VisibleForTesting
  static int serializedKeyLength(String cacheName, KeyWrapper<Object> keyWrapper) {
    return CacheSerializers.getKeySerializer(cacheName).serialize(keyWrapper.getValue()).length;
  }

  @VisibleForTesting
  static int serializedValueLength(String cacheName, TimedValue<Object> timedValue) {
    return CacheSerializers.getValueSerializer(cacheName).serialize(timedValue.getValue()).length;
  }

  private ChronicleMapCacheConfig makeChronicleMapConfig(
      ChronicleMapCacheConfig currentChronicleMapConfig,
      long newMaxEntries,
      long averageKeySize,
      long averageValueSize) {

    return configFactory.createWithValues(
        currentChronicleMapConfig.getConfigKey(),
        resolveNewFile(currentChronicleMapConfig.getCacheFile().getName()),
        currentChronicleMapConfig.getExpireAfterWrite(),
        currentChronicleMapConfig.getRefreshAfterWrite(),
        newMaxEntries,
        averageKeySize,
        averageValueSize,
        currentChronicleMapConfig.getMaxBloatFactor(),
        currentChronicleMapConfig.getPersistIndexEvery());
  }

  private long newMaxEntries(ChronicleMapCacheImpl<Object, Object> currentCache) {
    return getOptionalMaxEntries()
        .orElseGet(
            () -> {
              double percentageUsedAutoResizes = currentCache.percentageUsedAutoResizes();
              long currMaxEntries = currentCache.getConfig().getMaxEntries();

              long newMaxEntries = currMaxEntries;
              if (percentageUsedAutoResizes > PERCENTAGE_SIZE_INCREASE_THRESHOLD) {
                newMaxEntries = currMaxEntries * MAX_ENTRIES_MULTIPLIER;
              }
              logger.atInfo().log(
                  "Cache '%s' (maxEntries: %s) used %s%% of available space. new maxEntries will"
                      + " be: %s",
                  currentCache.name(), currMaxEntries, percentageUsedAutoResizes, newMaxEntries);
              return newMaxEntries;
            });
  }

  private File resolveNewFile(String currentFileName) {
    String newFileName =
        String.format(
            "%s%s%s.%s",
            FilenameUtils.getBaseName(currentFileName),
            TUNED_INFIX,
            System.currentTimeMillis(),
            FilenameUtils.getExtension(currentFileName));

    return cacheDir.resolve(newFileName).toFile();
  }

  private static void updateOutputConfig(
      Config config,
      String cacheName,
      long averageKeySize,
      long averageValueSize,
      long maxEntries,
      int maxBloatFactor) {

    config.setLong("cache", cacheName, "avgKeySize", averageKeySize);
    config.setLong("cache", cacheName, "avgValueSize", averageValueSize);
    config.setLong("cache", cacheName, "maxEntries", maxEntries);
    config.setLong("cache", cacheName, "maxBloatFactor", maxBloatFactor);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private Map<String, ChronicleMapCacheImpl<Object, Object>> getChronicleMapCaches() {
    if (isAdjustCachesOnDefaults()) {
      if (metric.cachesOnDefaults().isEmpty()) {
        return Collections.emptyMap();
      }
      cacheNames.addAll(metric.cachesOnDefaults());
    }

    return cacheMap.plugins().stream()
        .map(cacheMap::byPlugin)
        .flatMap(
            pluginCaches ->
                pluginCaches.entrySet().stream()
                    .map(entry -> ImmutablePair.of(entry.getKey(), entry.getValue().get())))
        .filter(
            pair ->
                pair.getValue() instanceof ChronicleMapCacheImpl
                    && ((ChronicleMapCacheImpl) pair.getValue()).diskStats().size() > 0)
        .filter(pair -> cacheNames.isEmpty() ? true : cacheNames.contains(pair.getKey()))
        .collect(
            Collectors.toMap(
                ImmutablePair::getKey, p -> (ChronicleMapCacheImpl<Object, Object>) p.getValue()));
  }
}
