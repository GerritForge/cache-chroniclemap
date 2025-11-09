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
import static com.gerritforge.gerrit.modules.cache.chroniclemap.AutoAdjustCaches.MAX_ENTRIES_MULTIPLIER;
import static com.gerritforge.gerrit.modules.cache.chroniclemap.AutoAdjustCaches.PERCENTAGE_SIZE_INCREASE_THRESHOLD;
import static com.gerritforge.gerrit.modules.cache.chroniclemap.AutoAdjustCaches.serializedKeyLength;
import static com.gerritforge.gerrit.modules.cache.chroniclemap.AutoAdjustCaches.serializedValueLength;
import static com.gerritforge.gerrit.modules.cache.chroniclemap.AutoAdjustCachesCommand.CONFIG_HEADER;
import static com.gerritforge.gerrit.modules.cache.chroniclemap.AutoAdjustCachesCommand.TUNED_INFIX;
import static com.gerritforge.gerrit.modules.cache.chroniclemap.ChronicleMapCacheConfig.Defaults.maxBloatFactorFor;
import static com.gerritforge.gerrit.modules.cache.chroniclemap.ChronicleMapCacheConfig.Defaults.maxEntriesFor;

import com.google.common.base.Joiner;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.Sandboxed;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.acceptance.UseSsh;
import com.google.gerrit.acceptance.config.GerritConfig;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.server.ModuleImpl;
import com.google.gerrit.server.cache.CacheModule;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import java.io.File;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.junit.Test;

@Sandboxed
@UseLocalDisk
@UseSsh
@TestPlugin(
    name = "cache-chroniclemap",
    sshModule = "com.gerritforge.gerrit.modules.cache.chroniclemap.SSHCommandModule",
    httpModule = "com.gerritforge.gerrit.modules.cache.chroniclemap.HttpModule")
public class AutoAdjustCachesIT extends LightweightPluginDaemonTest {
  private static final String SSH_CMD = "cache-chroniclemap auto-adjust-caches";
  private static final String REST_CMD = "/plugins/cache-chroniclemap/auto-adjust-caches";
  private static final String GROUPS_BYUUID_PERSISTED = "groups_byuuid_persisted";
  private static final String GERRIT_FILE_DIFF = "gerrit_file_diff";
  private static final String GIT_FILE_DIFF = "git_file_diff";
  private static final String DIFF_SUMMARY = "diff_summary";
  private static final String ACCOUNTS = "accounts";
  private static final String PERSISTED_PROJECTS = "persisted_projects";
  private static final String TEST_CACHE_NAME = "test_cache";
  private static final int TEST_CACHE_VERSION = 1;
  private static final String TEST_CACHE_FILENAME_TUNED =
      TEST_CACHE_NAME + "_" + TEST_CACHE_VERSION + AutoAdjustCaches.TUNED_INFIX;
  private static final String TEST_CACHE_KEY_100_CHARS = new String(new char[100]);
  private static final Function<String, Boolean> MATCH_ALL = (n) -> true;

  private static final ImmutableList<String> EXPECTED_CACHES =
      ImmutableList.of(
          GROUPS_BYUUID_PERSISTED,
          GERRIT_FILE_DIFF,
          GIT_FILE_DIFF,
          DIFF_SUMMARY,
          ACCOUNTS,
          PERSISTED_PROJECTS);

  @Inject
  @Named(TEST_CACHE_NAME)
  LoadingCache<String, String> testCache;

  @ModuleImpl(name = CacheModule.PERSISTENT_MODULE)
  public static class TestPersistentCacheModule extends CacheModule {

    @Override
    protected void configure() {
      persist(TEST_CACHE_NAME, String.class, String.class)
          .loader(TestCacheLoader.class)
          .version(TEST_CACHE_VERSION);
      install(new ChronicleMapCacheModule());
    }
  }

  public static class TestCacheLoader extends CacheLoader<String, String> {

    @Override
    public String load(String key) throws Exception {
      return key;
    }
  }

  @Override
  public com.google.inject.Module createModule() {
    return new TestPersistentCacheModule();
  }

  @Test
  public void shouldUseDefaultsWhenCachesAreNotConfigured() throws Exception {
    createChange();

    String result = adminSshSession.exec(SSH_CMD);

    adminSshSession.assertSuccess();
    Config configResult = configResult(result, CONFIG_HEADER);

    for (String cache : EXPECTED_CACHES) {
      assertThat(configResult.getLong("cache", cache, "maxEntries", 0))
          .isEqualTo(maxEntriesFor(cache));
      assertThat(configResult.getLong("cache", cache, "maxBloatFactor", 0))
          .isEqualTo(maxBloatFactorFor(cache));
    }
  }

  @Test
  @GerritConfig(name = "cache.test_cache.maxEntries", value = "10")
  @GerritConfig(name = "cache.test_cache.maxBloatFactor", value = "1")
  public void shouldCorrectlyIncreaseCacheSizeWhenIsGettingFull() throws Exception {
    ChronicleMapCacheImpl<String, String> chronicleMapCache =
        (ChronicleMapCacheImpl<String, String>) testCache;

    long elemsAdded = 0;
    long totalKeySize = 0;
    long totalValueSize = 0;
    while (chronicleMapCache.percentageUsedAutoResizes() < PERCENTAGE_SIZE_INCREASE_THRESHOLD) {
      String key = UUID.randomUUID() + "someExtraValue";
      String value = UUID.randomUUID().toString();
      elemsAdded += 1;
      totalKeySize += serializedKeyLength(TEST_CACHE_NAME, new KeyWrapper<>(key));
      totalValueSize += serializedValueLength(TEST_CACHE_NAME, new TimedValue<>(value));
      testCache.put(key, value);
    }

    String tuneResult = adminSshSession.exec(SSH_CMD + " " + TEST_CACHE_NAME);
    adminSshSession.assertSuccess();

    Config tunedConfig = configResult(tuneResult, CONFIG_HEADER);
    assertThat(tunedConfig.getSubsections("cache")).contains(TEST_CACHE_NAME);
    assertThat(tunedConfig.getLong("cache", TEST_CACHE_NAME, "maxEntries", 0))
        .isEqualTo(chronicleMapCache.getConfig().getMaxEntries() * MAX_ENTRIES_MULTIPLIER);
    assertThat(tunedConfig.getLong("cache", TEST_CACHE_NAME, "avgKeySize", 0))
        .isEqualTo(totalKeySize / elemsAdded);
    assertThat(tunedConfig.getLong("cache", TEST_CACHE_NAME, "avgValueSize", 0))
        .isEqualTo(totalValueSize / elemsAdded);
  }

  @Test
  public void shouldHonourMaxEntriesParameter() throws Exception {
    createChange();
    Long wantedMaxEntries = 100L;

    String result =
        adminSshSession.exec(String.format("%s --max-entries %s", SSH_CMD, wantedMaxEntries));

    adminSshSession.assertSuccess();
    Config configResult = configResult(result, CONFIG_HEADER);

    for (String cache : EXPECTED_CACHES) {
      assertThat(configResult.getLong("cache", cache, "maxEntries", 0)).isEqualTo(wantedMaxEntries);
    }
  }

  @Test
  public void shouldHonourAvgKeySizeParameter() throws Exception {
    createChange();
    Long wantedAvgKeySize = 50L;

    String result =
        adminSshSession.exec(String.format("%s --avg-key-size %s", SSH_CMD, wantedAvgKeySize));

    adminSshSession.assertSuccess();
    Config configResult = configResult(result, CONFIG_HEADER);

    for (String cache : EXPECTED_CACHES) {
      assertThat(configResult.getLong("cache", cache, "avgKeySize", 0)).isEqualTo(wantedAvgKeySize);
    }
  }

  @Test
  public void shouldHonourAvgValueSizeParameter() throws Exception {
    createChange();
    Long wantedAvgValueSize = 100L;

    String result =
        adminSshSession.exec(String.format("%s --avg-value-size %s", SSH_CMD, wantedAvgValueSize));

    adminSshSession.assertSuccess();
    Config configResult = configResult(result, CONFIG_HEADER);

    for (String cache : EXPECTED_CACHES) {
      assertThat(configResult.getLong("cache", cache, "avgValueSize", 0))
          .isEqualTo(wantedAvgValueSize);
    }
  }

  @Test
  public void shouldCreateNewCacheFiles() throws Exception {
    createChange();

    adminSshSession.exec(SSH_CMD);

    adminSshSession.assertSuccess();
    Set<String> tunedCaches =
        tunedFileNamesSet(n -> n.matches(".*(" + String.join("|", EXPECTED_CACHES) + ").*"));

    assertThat(tunedCaches.size()).isEqualTo(EXPECTED_CACHES.size());
  }

  @Test
  public void shouldCreateNewCacheFileForSingleGitFileDiffCache() throws Exception {
    createChange();

    adminSshSession.exec(SSH_CMD + " " + GIT_FILE_DIFF);

    adminSshSession.assertSuccess();
    Set<String> tunedCaches =
        tunedFileNamesSet(n -> n.matches(".*" + AutoAdjustCaches.TUNED_INFIX + ".*"));

    assertThat(tunedCaches.size()).isEqualTo(1);
  }

  @Test
  public void shouldCreateNewCacheFileForSingleGerritFileDiffCache() throws Exception {
    createChange();

    adminSshSession.exec(SSH_CMD + " " + GERRIT_FILE_DIFF);

    adminSshSession.assertSuccess();
    Set<String> tunedCaches =
        tunedFileNamesSet(n -> n.matches(".*" + AutoAdjustCaches.TUNED_INFIX + ".*"));

    assertThat(tunedCaches.size()).isEqualTo(1);
  }

  @Test
  @GerritConfig(name = "cache.test_cache.avgKeySize", value = "207")
  @GerritConfig(name = "cache.test_cache.avgValueSize", value = "207")
  public void shouldNotRecreateTestCacheFileWhenAlreadyTuned() throws Exception {
    testCache.get(TEST_CACHE_KEY_100_CHARS);

    String tuneResult =
        adminSshSession.exec(
            String.format(
                "%s --max-entries %s",
                SSH_CMD, ChronicleMapCacheConfig.Defaults.maxEntriesFor(TEST_CACHE_KEY_100_CHARS)));
    adminSshSession.assertSuccess();

    assertThat(configResult(tuneResult, CONFIG_HEADER).getSubsections("cache"))
        .doesNotContain(TEST_CACHE_NAME);
    assertThat(Joiner.on('\n').join(tunedFileNamesSet(MATCH_ALL)))
        .doesNotContain(TEST_CACHE_FILENAME_TUNED);
  }

  @Test
  public void shouldCreateTestCacheTuned() throws Exception {
    testCache.get(TEST_CACHE_KEY_100_CHARS);
    String tuneResult = adminSshSession.exec(SSH_CMD);
    adminSshSession.assertSuccess();

    assertThat(configResult(tuneResult, CONFIG_HEADER).getSubsections("cache"))
        .contains(TEST_CACHE_NAME);
    assertThat(
            Joiner.on('\n').join(tunedFileNamesSet((n) -> n.contains(TEST_CACHE_FILENAME_TUNED))))
        .isNotEmpty();
  }

  @Test
  public void shouldDenyAccessOverSshToCreateNewCacheFiles() throws Exception {
    userSshSession.exec(SSH_CMD);
    userSshSession.assertFailure("not permitted");
  }

  @Test
  public void shouldDenyAccessOverRestToCreateNewCacheFiles() throws Exception {
    userRestSession.put(REST_CMD).assertForbidden();
  }

  @Test
  public void shouldAllowTuningOverRestForAdmin() throws Exception {
    RestResponse resp = adminRestSession.put(REST_CMD);

    resp.assertCreated();

    assertThat(configResult(resp.getEntityContent(), null).getSubsections("cache")).isNotEmpty();
    assertThat(tunedFileNamesSet(MATCH_ALL)).isNotEmpty();
  }

  @Test
  public void shouldHonourMaxEntriesOverRestForAdmin() throws Exception {
    Long wantedMaxEntries = 100L;

    RestResponse resp =
        adminRestSession.put(String.format("%s?max-entries=%s", REST_CMD, wantedMaxEntries));

    resp.assertCreated();

    assertThat(
            configResult(resp.getEntityContent(), null)
                .getLong("cache", ACCOUNTS, "maxEntries", 0L))
        .isEqualTo(wantedMaxEntries);
  }

  @Test
  public void shouldAllowTuningOfSingleGitFileDiffCacheOverRestForAdmin() throws Exception {
    createChange();

    RestResponse resp = adminRestSession.put(REST_CMD + "?CACHE_NAME=" + GIT_FILE_DIFF);

    resp.assertCreated();

    assertThat(configResult(resp.getEntityContent(), null).getSubsections("cache")).isNotEmpty();
    assertThat(tunedFileNamesSet(n -> n.matches(".*" + AutoAdjustCaches.TUNED_INFIX + ".*")))
        .hasSize(1);
  }

  @Test
  public void shouldAllowTuningOfSingleGerritFileDiffCacheOverRestForAdmin() throws Exception {
    createChange();

    RestResponse resp = adminRestSession.put(REST_CMD + "?CACHE_NAME=" + GERRIT_FILE_DIFF);

    resp.assertCreated();

    assertThat(configResult(resp.getEntityContent(), null).getSubsections("cache")).isNotEmpty();
    assertThat(tunedFileNamesSet(n -> n.matches(".*" + AutoAdjustCaches.TUNED_INFIX + ".*")))
        .hasSize(1);
  }

  @Test
  public void shouldAdjustCachesOnDefaultsWhenSelected() throws Exception {
    assertThat(
            Joiner.on('\n').join(tunedFileNamesSet((n) -> n.contains(TEST_CACHE_FILENAME_TUNED))))
        .isEmpty();

    testCache.get(TEST_CACHE_KEY_100_CHARS);
    String tuneResult = adminSshSession.exec(SSH_CMD + " --adjust-caches-on-defaults");
    adminSshSession.assertSuccess();

    assertThat(configResult(tuneResult, CONFIG_HEADER).getSubsections("cache"))
        .contains(TEST_CACHE_NAME);
    assertThat(
            Joiner.on('\n').join(tunedFileNamesSet((n) -> n.contains(TEST_CACHE_FILENAME_TUNED))))
        .isNotEmpty();
  }

  private Config configResult(String result, @Nullable String configHeader)
      throws ConfigInvalidException {
    Config configResult = new Config();
    configResult.fromText(configHeader == null ? result : result.split(configHeader)[1]);
    return configResult;
  }

  private Set<String> tunedFileNamesSet(Function<String, Boolean> fileNameFilter) {
    Path cachePath = sitePaths.resolve(cfg.getString("cache", null, "directory"));
    return Stream.of(Objects.requireNonNull(cachePath.toFile().listFiles()))
        .filter(file -> !file.isDirectory())
        .map(File::getName)
        .filter(n -> n.contains(TUNED_INFIX) && fileNameFilter.apply(n))
        .collect(Collectors.toSet());
  }
}
