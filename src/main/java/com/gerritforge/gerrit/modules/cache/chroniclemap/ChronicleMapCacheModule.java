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
import com.google.gerrit.common.Nullable;
import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.gerrit.server.ModuleImpl;
import com.google.gerrit.server.cache.CacheModule;
import com.google.gerrit.server.cache.PersistentCacheFactory;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.openhft.compiler.CompilerUtils;
import org.eclipse.jgit.lib.Config;

@ModuleImpl(name = CacheModule.PERSISTENT_MODULE)
public class ChronicleMapCacheModule extends LifecycleModule {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Override
  protected void configure() {
    ChronicleMapCompatAliases.ensureRegistered();
    // chronicle-values invokes javac at runtime to generate value-type
    // subclasses. ValueModel.createClass() calls
    // CACHED_COMPILER.loadFromJava(cl, ...) with the VALUE TYPE's classloader
    // (e.g. Gerrit core's, for CachedAccountDetails$Key and friends), not
    // this plugin's. So the bundled org.jetbrains.annotations.NotNull class
    // file inside this plugin JAR is invisible to that compiler unless the
    // JAR is explicitly registered. Append it to
    // CompilerUtils.CACHED_COMPILER's classpath so javac can complete symbol
    // resolution on inherited @NotNull/@Nullable signatures.
    //
    // Resolve via toURI() rather than URL.getPath() so a plugin path with
    // spaces or non-ASCII characters is URL-decoded into a real filesystem
    // path. Check the boolean return so registration failures fail fast
    // here rather than silently leaving chronicle-values to throw
    // Symbol$CompletionFailure on first cache initialization with no
    // breadcrumb back to this site.
    String pluginJarPath;
    try {
      pluginJarPath =
          Paths.get(
                  ChronicleMapCacheModule.class
                      .getProtectionDomain()
                      .getCodeSource()
                      .getLocation()
                      .toURI())
              .toString();
    } catch (URISyntaxException e) {
      throw new IllegalStateException(
          "Cannot resolve cache-chroniclemap plugin JAR path for chronicle compiler", e);
    }
    if (!CompilerUtils.addClassPath(pluginJarPath)) {
      throw new IllegalStateException(
          "Failed to register plugin JAR with chronicle compiler: "
              + pluginJarPath
              + " (chronicle-values runtime javac would later fail with"
              + " Symbol$CompletionFailure for org.jetbrains.annotations.NotNull)");
    }
    factory(ChronicleMapCacheConfig.Factory.class);
    bind(PersistentCacheFactory.class).to(ChronicleMapCacheFactory.class);
    listener().to(ChronicleMapCacheFactory.class);
    bind(CachesWithoutChronicleMapConfigMetric.class).asEagerSingleton();
  }

  @Provides
  @Singleton
  @Nullable
  @ChronicleMapDir
  Path getChronicleMapDir(SitePaths site, @GerritServerConfig Config config) {
    String name = config.getString("cache", null, "directory");
    if (name == null) {
      return null;
    }
    Path loc = site.resolve(name);
    if (!Files.exists(loc)) {
      try {
        Files.createDirectories(loc);
      } catch (IOException e) {
        logger.atWarning().log("Can't create disk cache: %s", loc.toAbsolutePath());
        return null;
      }
    }
    if (!Files.isWritable(loc)) {
      logger.atWarning().log("Can't write to disk cache: %s", loc.toAbsolutePath());
      return null;
    }
    logger.atInfo().log("Enabling disk cache %s", loc.toAbsolutePath());
    return loc;
  }
}
