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

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.allowCapability;
import static com.gerritforge.gerrit.modules.cache.chroniclemap.H2CacheCommand.H2_SUFFIX;
import static com.gerritforge.gerrit.modules.cache.chroniclemap.H2MigrationServlet.DEFAULT_MAX_BLOAT_FACTOR;
import static com.gerritforge.gerrit.modules.cache.chroniclemap.H2MigrationServlet.DEFAULT_SIZE_MULTIPLIER;
import static com.gerritforge.gerrit.modules.cache.chroniclemap.H2MigrationServlet.MAX_BLOAT_FACTOR_PARAM;
import static com.gerritforge.gerrit.modules.cache.chroniclemap.H2MigrationServlet.SIZE_MULTIPLIER_PARAM;
import static org.apache.http.HttpHeaders.ACCEPT;
import static org.eclipse.jgit.util.HttpSupport.TEXT_PLAIN;

import com.google.common.cache.LoadingCache;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.RestSession;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.acceptance.WaitUtil;
import com.google.gerrit.acceptance.config.GerritConfig;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.entities.CachedProjectConfig;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.server.account.CachedAccountDetails;
import com.google.gerrit.server.cache.PersistentCacheDef;
import com.google.gerrit.server.cache.h2.H2CacheImpl;
import com.google.gerrit.server.cache.proto.Cache;
import com.google.gerrit.server.cache.proto.Cache.ProjectCacheKeyProto.Builder;
import com.google.gerrit.server.cache.serialize.ObjectIdConverter;
import com.google.gerrit.server.group.SystemGroupBackend;
import com.google.inject.Binding;
import com.google.inject.Inject;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.name.Named;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.apache.http.message.BasicHeader;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Repository;
import org.junit.Before;
import org.junit.Test;

@TestPlugin(
    name = "cache-chroniclemap",
    httpModule = "com.gerritforge.gerrit.modules.cache.chroniclemap.HttpModule")
@UseLocalDisk
public class MigrateH2CachesLocalDiskIT extends LightweightPluginDaemonTest {
  private final Duration LOAD_CACHE_WAIT_TIMEOUT = Duration.ofSeconds(60);
  private String ACCOUNTS_CACHE_NAME = "accounts";
  private String PERSISTED_PROJECTS_CACHE_NAME = "persisted_projects";
  private String MIGRATION_ENDPOINT = "/plugins/cache-chroniclemap/migrate";

  @Inject private ProjectOperations projectOperations;

  private ChronicleMapCacheConfig.Factory chronicleMapCacheConfigFactory;

  @Before
  public void setUp() {
    chronicleMapCacheConfigFactory =
        plugin.getHttpInjector().getInstance(ChronicleMapCacheConfig.Factory.class);
  }

  /** Override to bind an additional Guice module */
  @Override
  public Module createModule() {
    return new CapabilityModule();
  }

  @Test
  public void shouldRunAndCompleteSuccessfullyWhenCacheDirectoryIsDefined() throws Exception {
    runMigration(adminRestSession).assertOK();
  }

  @Test
  public void shouldReturnSuccessWhenAllTextContentsAreAccepted() throws Exception {
    runMigrationWithAcceptHeader(adminRestSession, "text/*").assertOK();
  }

  @Test
  public void shouldReturnSuccessWhenAllContentsAreAccepted() throws Exception {
    runMigrationWithAcceptHeader(adminRestSession, "*/*").assertOK();
  }

  @Test
  public void shouldOutputChronicleMapBloatedDefaultConfiguration() throws Exception {
    waitForCacheToLoad(ACCOUNTS_CACHE_NAME);
    waitForCacheToLoad(PERSISTED_PROJECTS_CACHE_NAME);

    RestResponse result = runMigration(adminRestSession);
    result.assertOK();

    Config configResult = new Config();
    configResult.fromText(result.getEntityContent());

    assertThat(configResult.getInt("cache", ACCOUNTS_CACHE_NAME, "maxEntries", 0))
        .isEqualTo(H2CacheFor(ACCOUNTS_CACHE_NAME).diskStats().size() * DEFAULT_SIZE_MULTIPLIER);

    assertThat(configResult.getInt("cache", ACCOUNTS_CACHE_NAME, "maxBloatFactor", 0))
        .isEqualTo(DEFAULT_MAX_BLOAT_FACTOR);

    assertThat(configResult.getInt("cache", PERSISTED_PROJECTS_CACHE_NAME, "maxEntries", 0))
        .isEqualTo(
            H2CacheFor(PERSISTED_PROJECTS_CACHE_NAME).diskStats().size() * DEFAULT_SIZE_MULTIPLIER);

    assertThat(configResult.getInt("cache", PERSISTED_PROJECTS_CACHE_NAME, "maxBloatFactor", 0))
        .isEqualTo(DEFAULT_MAX_BLOAT_FACTOR);
  }

  @Test
  public void shouldDenyH2MigrationForNonAdminsAndUsersWithoutAdministerCachePermission()
      throws Exception {
    waitForCacheToLoad(ACCOUNTS_CACHE_NAME);
    waitForCacheToLoad(PERSISTED_PROJECTS_CACHE_NAME);

    runMigration(userRestSession).assertForbidden();

    projectOperations
        .project(allProjects)
        .forUpdate()
        .add(
            allowCapability("cache-chroniclemap-" + AdministerCachesCapability.ID)
                .group(SystemGroupBackend.REGISTERED_USERS))
        .update();

    runMigration(userRestSession).assertOK();
  }

  @Test
  public void shouldOutputChronicleMapBloatedProvidedConfiguration() throws Exception {
    waitForCacheToLoad(ACCOUNTS_CACHE_NAME);
    waitForCacheToLoad(PERSISTED_PROJECTS_CACHE_NAME);

    int sizeMultiplier = 2;
    int maxBloatFactor = 3;
    RestResponse result = runMigration(sizeMultiplier, maxBloatFactor);
    result.assertOK();

    Config configResult = new Config();
    configResult.fromText(result.getEntityContent());

    assertThat(configResult.getInt("cache", ACCOUNTS_CACHE_NAME, "maxEntries", 0))
        .isEqualTo(H2CacheFor(ACCOUNTS_CACHE_NAME).diskStats().size() * sizeMultiplier);

    assertThat(configResult.getInt("cache", ACCOUNTS_CACHE_NAME, "maxBloatFactor", 0))
        .isEqualTo(maxBloatFactor);

    assertThat(configResult.getInt("cache", PERSISTED_PROJECTS_CACHE_NAME, "maxEntries", 0))
        .isEqualTo(H2CacheFor(PERSISTED_PROJECTS_CACHE_NAME).diskStats().size() * sizeMultiplier);

    assertThat(configResult.getInt("cache", PERSISTED_PROJECTS_CACHE_NAME, "maxBloatFactor", 0))
        .isEqualTo(maxBloatFactor);
  }

  @Test
  @GerritConfig(name = "cache.accounts.maxBloatFactor", value = "1")
  @GerritConfig(name = "cache.accounts.maxEntries", value = "10")
  @GerritConfig(name = "cache.accounts.avgKeySize", value = "100")
  @GerritConfig(name = "cache.accounts.avgValueSize", value = "1000")
  public void shouldKeepExistingChronicleMapConfiguration() throws Exception {
    waitForCacheToLoad(ACCOUNTS_CACHE_NAME);

    int sizeMultiplier = 2;
    int maxBloatFactor = 3;
    RestResponse result = runMigration(sizeMultiplier, maxBloatFactor);
    result.assertOK();

    Config configResult = new Config();
    String entityContent = result.getEntityContent();
    configResult.fromText(entityContent);

    assertThat(configResult.getInt("cache", ACCOUNTS_CACHE_NAME, "maxBloatFactor", 0)).isEqualTo(1);
    assertThat(configResult.getInt("cache", ACCOUNTS_CACHE_NAME, "maxEntries", 0)).isEqualTo(10);
    assertThat(configResult.getInt("cache", ACCOUNTS_CACHE_NAME, "avgKeySize", 0)).isEqualTo(100);
    assertThat(configResult.getInt("cache", ACCOUNTS_CACHE_NAME, "avgValueSize", 0))
        .isEqualTo(1000);
  }

  @Test
  @GerritConfig(name = "cache.accounts.maxBloatFactor", value = "1")
  @GerritConfig(name = "cache.accounts.maxEntries", value = "10")
  @GerritConfig(name = "cache.accounts.avgValueSize", value = "1000")
  public void shouldIgnoreIncompleteChronicleMapConfiguration() throws Exception {
    waitForCacheToLoad(ACCOUNTS_CACHE_NAME);

    int sizeMultiplier = 2;
    int maxBloatFactor = 3;
    RestResponse result = runMigration(sizeMultiplier, maxBloatFactor);
    result.assertOK();

    Config configResult = new Config();
    String entityContent = result.getEntityContent();
    configResult.fromText(entityContent);

    assertThat(configResult.getInt("cache", ACCOUNTS_CACHE_NAME, "maxBloatFactor", 0))
        .isEqualTo(maxBloatFactor);
    assertThat(configResult.getInt("cache", ACCOUNTS_CACHE_NAME, "maxEntries", 0)).isNotEqualTo(10);
    assertThat(configResult.getInt("cache", ACCOUNTS_CACHE_NAME, "avgValueSize", 0))
        .isNotEqualTo(1000);
  }

  @Test
  public void shouldMigrateAccountsCache() throws Exception {
    waitForCacheToLoad(ACCOUNTS_CACHE_NAME);

    runMigration(adminRestSession).assertOK();

    ChronicleMapCacheImpl<CachedAccountDetails.Key, CachedAccountDetails> chronicleMapCache =
        chronicleCacheFor(ACCOUNTS_CACHE_NAME);
    H2CacheImpl<CachedAccountDetails.Key, CachedAccountDetails> h2Cache =
        H2CacheFor(ACCOUNTS_CACHE_NAME);

    assertThat(chronicleMapCache.diskStats().size()).isEqualTo(h2Cache.diskStats().size());
  }

  @Test
  public void shouldMigratePersistentProjects() throws Exception {
    waitForCacheToLoad(PERSISTED_PROJECTS_CACHE_NAME);

    runMigration(adminRestSession).assertOK();

    H2CacheImpl<Cache.ProjectCacheKeyProto, CachedProjectConfig> h2Cache =
        H2CacheFor(PERSISTED_PROJECTS_CACHE_NAME);
    ChronicleMapCacheImpl<Cache.ProjectCacheKeyProto, CachedProjectConfig> chronicleMapCache =
        chronicleCacheFor(PERSISTED_PROJECTS_CACHE_NAME);

    Cache.ProjectCacheKeyProto allUsersProto = projectCacheKey(allUsers);

    assertThat(chronicleMapCache.get(allUsersProto)).isEqualTo(h2Cache.get(allUsersProto));
  }

  private Cache.ProjectCacheKeyProto projectCacheKey(Project.NameKey key) throws IOException {
    try (Repository git = repoManager.openRepository(key)) {
      Builder builder =
          Cache.ProjectCacheKeyProto.newBuilder()
              .setProject(key.get())
              .setRevision(
                  ObjectIdConverter.create()
                      .toByteString(git.exactRef(RefNames.REFS_CONFIG).getObjectId()));

      return builder.build();
    }
  }

  @SuppressWarnings("unchecked")
  private <K, V> PersistentCacheDef<K, V> getPersistentCacheDef(String named) {
    return findClassBoundWithName(PersistentCacheDef.class, named);
  }

  @SuppressWarnings("unchecked")
  private <K, V> H2CacheImpl<K, V> H2CacheFor(String named) {
    return (H2CacheImpl<K, V>) findClassBoundWithName(LoadingCache.class, named);
  }

  private RestResponse runMigration(int sizeMultiplier, int maxBloatFactor) throws Exception {
    return adminRestSession.put(
        String.format(
            "%s?%s=%d&%s=%d",
            MIGRATION_ENDPOINT,
            MAX_BLOAT_FACTOR_PARAM,
            maxBloatFactor,
            SIZE_MULTIPLIER_PARAM,
            sizeMultiplier));
  }

  private RestResponse runMigration(RestSession restSession) throws Exception {
    return runMigrationWithAcceptHeader(restSession, TEXT_PLAIN);
  }

  private RestResponse runMigrationWithAcceptHeader(RestSession restSession, String acceptHeader)
      throws Exception {
    return restSession.putWithHeaders(MIGRATION_ENDPOINT, new BasicHeader(ACCEPT, acceptHeader));
  }

  private <T> T findClassBoundWithName(Class<T> clazz, String named) {
    return plugin.getSysInjector().getAllBindings().entrySet().stream()
        .filter(entry -> isClassBoundWithName(entry, clazz.getSimpleName(), named))
        .findFirst()
        .map(entry -> clazz.cast(entry.getValue().getProvider().get()))
        .get();
  }

  private boolean isClassBoundWithName(
      Map.Entry<Key<?>, Binding<?>> entry, String classNameMatch, String named) {
    String className = entry.getKey().getTypeLiteral().getRawType().getSimpleName();
    Annotation annotation = entry.getKey().getAnnotation();
    return className.equals(classNameMatch)
        && annotation != null
        && annotation instanceof Named
        && annotation.toString().contains(String.format("\"%s\"", named));
  }

  private <K, V> ChronicleMapCacheImpl<K, V> chronicleCacheFor(String cacheName) throws Exception {
    Path cacheDirectory = sitePaths.resolve(cfg.getString("cache", null, "directory"));

    PersistentCacheDef<K, V> persistentDef = getPersistentCacheDef(cacheName);
    ChronicleMapCacheConfig config =
        H2MigrationServlet.makeChronicleMapConfig(
            chronicleMapCacheConfigFactory,
            cacheDirectory,
            persistentDef,
            H2CacheCommand.getStats(
                cacheDirectory.resolve(String.format("%s%s", cacheName, H2_SUFFIX))),
            DEFAULT_SIZE_MULTIPLIER,
            DEFAULT_MAX_BLOAT_FACTOR);

    return new ChronicleMapCacheImpl<>(persistentDef, config);
  }

  private void waitForCacheToLoad(String cacheName) throws InterruptedException {
    WaitUtil.waitUntil(() -> H2CacheFor(cacheName).diskStats().size() > 0, LOAD_CACHE_WAIT_TIMEOUT);
  }
}
