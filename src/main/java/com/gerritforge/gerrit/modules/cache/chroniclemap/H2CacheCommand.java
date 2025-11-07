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

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.server.config.SitePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import org.apache.commons.io.FilenameUtils;
import org.eclipse.jgit.lib.Config;
import org.h2.Driver;

public class H2CacheCommand {
  protected static final FluentLogger logger = FluentLogger.forEnclosingClass();
  public static final String H2_SUFFIX = "-v2.mv.db";

  public static String baseName(Path h2File) {
    String filename = h2File.toString();
    return FilenameUtils.getBaseName(filename.substring(0, filename.length() - H2_SUFFIX.length()));
  }

  protected static H2AggregateData getStats(Path h2File) throws Exception {
    String url = jdbcUrl(h2File);
    String baseName = baseName(h2File);
    try {

      try (Connection conn = Driver.load().connect(url, null);
          Statement s = conn.createStatement();
          ResultSet r =
              s.executeQuery(
                  "SELECT COUNT(*), AVG(OCTET_LENGTH(k)), AVG(OCTET_LENGTH(v)) FROM data")) {
        if (r.next()) {
          long size = r.getLong(1);
          long avgKeySize = r.getLong(2);
          long avgValueSize = r.getLong(3);

          // Account for extra serialization bytes of TimedValue entries.
          short TIMED_VALUE_WRAPPER_OVERHEAD = Long.BYTES + Integer.BYTES;
          return H2AggregateData.create(
              baseName, size, avgKeySize, avgValueSize + TIMED_VALUE_WRAPPER_OVERHEAD);
        }
        return H2AggregateData.empty(baseName);
      }
    } catch (SQLException e) {
      throw new Exception("fatal: " + e.getMessage(), e);
    }
  }

  protected static String jdbcUrl(Path h2FilePath) {
    final String normalized =
        FilenameUtils.removeExtension(FilenameUtils.removeExtension(h2FilePath.toString()));
    return "jdbc:h2:" + normalized + ";AUTO_SERVER=TRUE;DATABASE_TO_UPPER=false";
  }

  protected static Optional<Path> getCacheDir(Config gerritConfig, SitePaths site)
      throws IOException {
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

  protected static void appendToConfig(Config config, H2AggregateData stats) {
    config.setLong("cache", stats.cacheName(), "maxEntries", stats.size());
    config.setLong("cache", stats.cacheName(), "avgKeySize", stats.avgKeySize());
    config.setLong("cache", stats.cacheName(), "avgValueSize", stats.avgValueSize());
  }
}
