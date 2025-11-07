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

import static com.gerritforge.gerrit.modules.cache.chroniclemap.H2CacheCommand.H2_SUFFIX;
import static com.gerritforge.gerrit.modules.cache.chroniclemap.H2CacheCommand.appendToConfig;
import static com.gerritforge.gerrit.modules.cache.chroniclemap.H2CacheCommand.baseName;
import static com.gerritforge.gerrit.modules.cache.chroniclemap.H2CacheCommand.getCacheDir;
import static com.gerritforge.gerrit.modules.cache.chroniclemap.H2CacheCommand.getStats;
import static com.gerritforge.gerrit.modules.cache.chroniclemap.H2CacheCommand.logger;

import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.sshd.SshCommand;
import com.google.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.jgit.lib.Config;

public class AnalyzeH2Caches extends SshCommand {

  private final Config gerritConfig;
  private final SitePaths site;
  private final AdministerCachePermission adminCachePermission;

  @Inject
  AnalyzeH2Caches(
      @GerritServerConfig Config cfg,
      SitePaths site,
      AdministerCachePermission adminCachePermission) {
    this.gerritConfig = cfg;
    this.site = site;
    this.adminCachePermission = adminCachePermission;
  }

  @Override
  protected void run() throws Exception {
    adminCachePermission.checkCurrentUserAllowed(e -> stderr.println(e.getLocalizedMessage()));

    Set<Path> h2Files = getH2CacheFiles();
    stdout.println("Extracting information from H2 caches...");

    Config config = new Config();
    for (Path h2 : h2Files) {
      H2AggregateData stats = getStats(h2);
      String baseName = baseName(h2);

      if (stats.isEmpty()) {
        stdout.println(String.format("WARN: Cache %s is empty, skipping.", baseName));
        continue;
      }
      appendToConfig(config, stats);
    }
    stdout.println();
    stdout.println("****************************");
    stdout.println("** Chronicle-map template **");
    stdout.println("****************************");
    stdout.println();
    stdout.println(config.toText());
  }

  private Set<Path> getH2CacheFiles() throws Exception {

    try {
      return getCacheDir(gerritConfig, site)
          .map(
              cacheDir -> {
                try {
                  return Files.walk(cacheDir)
                      .filter(path -> path.toString().endsWith(H2_SUFFIX))
                      .collect(Collectors.toSet());
                } catch (IOException e) {
                  logger.atSevere().withCause(e).log("Could not read H2 files");
                  return Collections.<Path>emptySet();
                }
              })
          .orElse(Collections.emptySet());
    } catch (IOException e) {
      throw die(e);
    }
  }
}
