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

import static com.gerritforge.gerrit.modules.cache.chroniclemap.ChronicleMapCacheConfig.Defaults.persistIndexEvery;
import static com.gerritforge.gerrit.modules.cache.chroniclemap.H2CacheCommand.H2_SUFFIX;
import static com.gerritforge.gerrit.modules.cache.chroniclemap.H2CacheCommand.getStats;
import static com.gerritforge.gerrit.modules.cache.chroniclemap.H2CacheCommand.jdbcUrl;
import static com.gerritforge.gerrit.modules.cache.chroniclemap.HttpServletOps.checkAcceptHeader;
import static com.gerritforge.gerrit.modules.cache.chroniclemap.HttpServletOps.setResponse;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.CachedProjectConfig;
import com.google.gerrit.extensions.auth.oauth.OAuthToken;
import com.google.gerrit.extensions.client.ChangeKind;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.httpd.WebSessionManager;
import com.google.gerrit.server.account.CachedAccountDetails;
import com.google.gerrit.server.cache.PersistentCacheDef;
import com.google.gerrit.server.cache.proto.Cache;
import com.google.gerrit.server.change.ChangeKindCacheImpl;
import com.google.gerrit.server.change.MergeabilityCacheImpl;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.git.TagSetHolder;
import com.google.gerrit.server.notedb.ChangeNotesCache;
import com.google.gerrit.server.notedb.ChangeNotesState;
import com.google.gerrit.server.patch.DiffSummary;
import com.google.gerrit.server.patch.DiffSummaryKey;
import com.google.gerrit.server.patch.IntraLineDiff;
import com.google.gerrit.server.patch.IntraLineDiffKey;
import com.google.gerrit.server.patch.filediff.FileDiffCacheKey;
import com.google.gerrit.server.patch.filediff.FileDiffOutput;
import com.google.gerrit.server.patch.gitfilediff.GitFileDiff;
import com.google.gerrit.server.patch.gitfilediff.GitFileDiffCacheKey;
import com.google.gerrit.server.query.change.ConflictKey;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;
import com.google.inject.name.Named;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jgit.lib.Config;
import org.h2.Driver;

@Singleton
public class H2MigrationServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;
  protected static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final ChronicleMapCacheConfig.Factory configFactory;
  private final SitePaths site;
  private final Config gerritConfig;
  private final AdministerCachePermission adminCachePermission;

  public static int DEFAULT_SIZE_MULTIPLIER = 3;
  public static int DEFAULT_MAX_BLOAT_FACTOR = 3;

  public static final String MAX_BLOAT_FACTOR_PARAM = "max-bloat-factor";
  public static final String SIZE_MULTIPLIER_PARAM = "size-multiplier";

  private final Set<PersistentCacheDef<?, ?>> persistentCacheDefs;

  @Inject
  H2MigrationServlet(
      @GerritServerConfig Config cfg,
      SitePaths site,
      ChronicleMapCacheConfig.Factory configFactory,
      AdministerCachePermission permissionBackend,
      @Named("web_sessions") PersistentCacheDef<String, WebSessionManager.Val> webSessionsCacheDef,
      @Named("accounts")
          PersistentCacheDef<CachedAccountDetails.Key, CachedAccountDetails> accountsCacheDef,
      @Named("oauth_tokens") PersistentCacheDef<Account.Id, OAuthToken> oauthTokenDef,
      @Named("change_kind")
          PersistentCacheDef<ChangeKindCacheImpl.Key, ChangeKind> changeKindCacheDef,
      @Named("mergeability")
          PersistentCacheDef<MergeabilityCacheImpl.EntryKey, Boolean> mergeabilityCacheDef,
      @Named("pure_revert")
          PersistentCacheDef<Cache.PureRevertKeyProto, Boolean> pureRevertCacheDef,
      @Named("git_tags") PersistentCacheDef<String, TagSetHolder> gitTagsCacheDef,
      @Named("change_notes")
          PersistentCacheDef<ChangeNotesCache.Key, ChangeNotesState> changeNotesCacheDef,
      @Named("gerrit_file_diff")
          PersistentCacheDef<FileDiffCacheKey, FileDiffOutput> gerritFileDiffDef,
      @Named("git_file_diff") PersistentCacheDef<GitFileDiffCacheKey, GitFileDiff> gitFileDiffDef,
      @Named("diff_intraline")
          PersistentCacheDef<IntraLineDiffKey, IntraLineDiff> diffIntraLineCacheDef,
      @Named("diff_summary") PersistentCacheDef<DiffSummaryKey, DiffSummary> diffSummaryCacheDef,
      @Named("persisted_projects")
          PersistentCacheDef<Cache.ProjectCacheKeyProto, CachedProjectConfig>
              persistedProjectsCacheDef,
      @Named("conflicts") PersistentCacheDef<ConflictKey, Boolean> conflictsCacheDef) {
    this.configFactory = configFactory;
    this.site = site;
    this.gerritConfig = cfg;
    this.adminCachePermission = permissionBackend;
    this.persistentCacheDefs =
        Stream.of(
                webSessionsCacheDef,
                accountsCacheDef,
                oauthTokenDef,
                changeKindCacheDef,
                mergeabilityCacheDef,
                pureRevertCacheDef,
                gitTagsCacheDef,
                changeNotesCacheDef,
                gerritFileDiffDef,
                gitFileDiffDef,
                diffIntraLineCacheDef,
                diffSummaryCacheDef,
                persistedProjectsCacheDef,
                conflictsCacheDef)
            .collect(Collectors.toSet());
  }

  @Override
  protected void doPut(HttpServletRequest req, HttpServletResponse rsp) throws IOException {
    if (!checkAcceptHeader(req, rsp)) {
      return;
    }

    if (!adminCachePermission.isCurrentUserAllowed()) {
      setResponse(rsp, HttpServletResponse.SC_FORBIDDEN, "not permitted to administer caches");
      return;
    }

    Optional<Path> cacheDir = getCacheDir();

    int maxBloatFactor =
        Optional.ofNullable(req.getParameter(MAX_BLOAT_FACTOR_PARAM))
            .map(Integer::parseInt)
            .orElse(DEFAULT_MAX_BLOAT_FACTOR);

    int sizeMultiplier =
        Optional.ofNullable(req.getParameter(SIZE_MULTIPLIER_PARAM))
            .map(Integer::parseInt)
            .orElse(DEFAULT_SIZE_MULTIPLIER);

    if (!cacheDir.isPresent()) {
      setResponse(
          rsp,
          HttpServletResponse.SC_BAD_REQUEST,
          "Cannot run migration, cache directory is not configured");
      return;
    }

    logger.atInfo().log("Migrating H2 caches to Chronicle-Map...");
    logger.atInfo().log("* Size multiplier: %d", sizeMultiplier);
    logger.atInfo().log("* Max Bloat Factor: %d", maxBloatFactor);

    Config outputChronicleMapConfig = new Config();

    try {
      for (PersistentCacheDef<?, ?> in : persistentCacheDefs) {
        Optional<Path> h2CacheFile = getH2CacheFile(cacheDir.get(), in.name());
        Optional<ChronicleMapCacheConfig> chronicleMapConfig;

        if (h2CacheFile.isPresent()) {
          if (hasFullPersistentCacheConfiguration(in)) {
            if (sizeMultiplier != DEFAULT_SIZE_MULTIPLIER) {
              logger.atWarning().log(
                  "Size multiplier = %d ignored because of existing configuration found",
                  sizeMultiplier);
            }
            if (maxBloatFactor != DEFAULT_MAX_BLOAT_FACTOR) {
              logger.atWarning().log(
                  "Max Bloat Factor = %d ignored because of existing configuration found",
                  maxBloatFactor);
            }

            File cacheFile =
                ChronicleMapCacheFactory.fileName(cacheDir.get(), in.name(), in.version());
            chronicleMapConfig =
                Optional.of(
                    configFactory.create(
                        in.name(), cacheFile, in.expireAfterWrite(), in.refreshAfterWrite()));

          } else {
            if (hasPartialPersistentCacheConfiguration(in)) {
              logger.atWarning().log(
                  "Existing configuration for cache %s found gerrit.config and will be ignored"
                      + " because incomplete",
                  in.name());
            }
            chronicleMapConfig =
                optionalOf(getStats(h2CacheFile.get()))
                    .map(
                        (stats) ->
                            makeChronicleMapConfig(
                                configFactory,
                                cacheDir.get(),
                                in,
                                stats,
                                sizeMultiplier,
                                maxBloatFactor));
          }

          if (chronicleMapConfig.isPresent()) {
            ChronicleMapCacheConfig cacheConfig = chronicleMapConfig.get();
            ChronicleMapCacheImpl<?, ?> chronicleMapCache =
                new ChronicleMapCacheImpl<>(in, cacheConfig);

            doMigrate(h2CacheFile.get(), in, chronicleMapCache);
            chronicleMapCache.close();
            copyExistingCacheSettingsToConfig(outputChronicleMapConfig, cacheConfig);
          }
        }
      }
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("H2 to chronicle-map migration failed");
      setResponse(rsp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
    }

    logger.atInfo().log("Migration completed");
    setResponse(rsp, HttpServletResponse.SC_OK, outputChronicleMapConfig.toText());
  }

  private Optional<H2AggregateData> optionalOf(H2AggregateData stats) {
    if (stats.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(stats);
  }

  private boolean hasFullPersistentCacheConfiguration(PersistentCacheDef<?, ?> in) {
    return gerritConfig.getLong("cache", in.name(), "avgKeySize", 0L) > 0
        && gerritConfig.getLong("cache", in.name(), "avgValueSize", 0L) > 0
        && gerritConfig.getLong("cache", in.name(), "maxEntries", 0L) > 0
        && gerritConfig.getInt("cache", in.name(), "maxBloatFactor", 0) > 0;
  }

  private boolean hasPartialPersistentCacheConfiguration(PersistentCacheDef<?, ?> in) {
    return gerritConfig.getLong("cache", in.name(), "avgKeySize", 0L) > 0
        || gerritConfig.getLong("cache", in.name(), "avgValueSize", 0L) > 0
        || gerritConfig.getLong("cache", in.name(), "maxEntries", 0L) > 0
        || gerritConfig.getInt("cache", in.name(), "maxBloatFactor", 0) > 0;
  }

  protected Optional<Path> getCacheDir() throws IOException {
    String name = gerritConfig.getString("cache", null, "directory");
    if (name == null) {
      return Optional.empty();
    }
    Path loc = site.resolve(name);
    if (!Files.exists(loc)) {
      throw new IOException(
          String.format("disk cache is configured but doesn't exist: %s", loc.toAbsolutePath()));
    }
    if (!Files.isReadable(loc)) {
      throw new IOException(String.format("Can't read from disk cache: %s", loc.toAbsolutePath()));
    }
    logger.atFine().log("Enabling disk cache %s", loc.toAbsolutePath());
    return Optional.of(loc);
  }

  private Optional<Path> getH2CacheFile(Path cacheDir, String name) {
    Path h2CacheFile = cacheDir.resolve(String.format("%s%s", name, H2_SUFFIX));
    if (Files.exists(h2CacheFile)) {
      return Optional.of(h2CacheFile);
    }
    return Optional.empty();
  }

  protected static ChronicleMapCacheConfig makeChronicleMapConfig(
      ChronicleMapCacheConfig.Factory configFactory,
      Path cacheDir,
      PersistentCacheDef<?, ?> in,
      H2AggregateData stats,
      int sizeMultiplier,
      int maxBloatFactor) {
    return configFactory.createWithValues(
        in.configKey(),
        ChronicleMapCacheFactory.fileName(cacheDir, in.name(), in.version()),
        in.expireAfterWrite(),
        in.refreshAfterWrite(),
        stats.size() * sizeMultiplier,
        stats.avgKeySize(),
        stats.avgValueSize(),
        maxBloatFactor,
        persistIndexEvery());
  }

  private void doMigrate(
      Path h2File, PersistentCacheDef<?, ?> in, ChronicleMapCacheImpl<?, ?> chronicleMapCache)
      throws RestApiException {

    String url = jdbcUrl(h2File);
    try (Connection conn = Driver.load().connect(url, null)) {
      PreparedStatement preparedStatement =
          conn.prepareStatement("SELECT k, v, created FROM data WHERE version=?");
      preparedStatement.setInt(1, in.version());

      try (ResultSet r = preparedStatement.executeQuery()) {
        while (r.next()) {
          Object key =
              isStringType(in.keyType())
                  ? r.getString(1)
                  : in.keySerializer().deserialize(r.getBytes(1));
          Object value =
              isStringType(in.valueType())
                  ? r.getString(2)
                  : in.valueSerializer().deserialize(r.getBytes(2));
          Timestamp created = r.getTimestamp(3);
          chronicleMapCache.putUnchecked(key, value, created);
        }
      }

    } catch (Exception e) {
      String message = String.format("FATAL: error migrating %s H2 cache", in.name());
      logger.atSevere().withCause(e).log("%s", message);
      throw RestApiException.wrap(message, e);
    }
  }

  private boolean isStringType(TypeLiteral<?> typeLiteral) {
    return typeLiteral.getRawType().getSimpleName().equals("String");
  }

  private static void copyExistingCacheSettingsToConfig(
      Config outputConfig, ChronicleMapCacheConfig cacheConfig) {
    String cacheName = cacheConfig.getConfigKey();
    outputConfig.setLong("cache", cacheName, "avgKeySize", cacheConfig.getAverageKeySize());
    outputConfig.setLong("cache", cacheName, "avgValueSize", cacheConfig.getAverageValueSize());
    outputConfig.setLong("cache", cacheName, "maxEntries", cacheConfig.getMaxEntries());
    outputConfig.setLong("cache", cacheName, "maxBloatFactor", cacheConfig.getMaxBloatFactor());
  }
}
