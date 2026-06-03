# Persistent cache for Gerrit, based on ChronicleMap

Non-blocking and super-fast on-disk cache libModule for [Gerrit Code Review](https://gerritcodereview.com)
based on [ChronicleMap on-disk implementation](https://github.com/OpenHFT/Chronicle-Map).

## License

This project is licensed under the **Business Source License 1.1** (BSL 1.1).
This is a "source-available" license that balances free, open-source-style access to the code
with temporary commercial restrictions.

* The full text of the BSL 1.1 is available in the [LICENSE](LICENSE) file in this
  repository.
* If your intended use case falls outside the **Additional Use Grant** and you require a
  commercial license, please contact [GerritForge Sales](https://gerritforge.com/contact).


## How to build

This libModule is built like a Gerrit in-tree plugin, using Bazelisk. See the
[build instructions](src/main/resources/Documentation/build.md) for more details.

### Updating Bazel modules

When the plugin's Bazel module dependencies change, regenerate the Bazel
module lockfile to ensure all module versions are recorded and reproducible.

Example:

```bash
  cd plugins/cache-chroniclemap
  ln -sf ../../.bazelversion .
  bazelisk mod deps --lockfile_mode=update
```

This updates `MODULE.bazel.lock` with the currently resolved module versions.

### Pinning external dependencies

When the plugin's external dependencies are updated, regenerate the dependency
lockfile to pin the new versions.

Example:

```bash
  cd plugins/cache-chroniclemap
  ln -sf ../../.bazelversion .
  REPIN=1 bazelisk run @cache-chroniclemap_plugin_deps//:pin
```

This updates `cache-chroniclemap_plugin_deps.lock.json` with the latest pinned
dependency versions.

### Cleaning Bazel Output Directories

After updating Bazel modules or external dependencies, remove Bazel output
directories before building the plugin in the Gerrit workspace:

Example:

```bash
  cd plugins/cache-chroniclemap
  ln -sf ../../.bazelversion .
  bazelisk clean --expunge
```

This avoids intermittent build and test failures caused by stale Bazel output
trees. In particular, wildcard test targets such as:

Example:

```
  bazelisk test plugins/cache-chroniclemap/...
```

may fail unexpectedly if the output directories are not cleaned.


## Setup

* Install cache-chronicalmap module

Install the chronicle-map module into the `$GERRIT_SITE/lib` directory.

Add the cache-chroniclemap module to `$GERRIT_SITE/etc/gerrit.config` as follows:

```
[gerrit]
  installModule = com.gerritforge.gerrit.modules.cache.chroniclemap.ChronicleMapCacheModule
```

Note that in order to run on JDK 17 (or newer) the following parameters needs to be added
to `$GERRIT_SITE/etc/gerrit.config`:

```
[container]
  javaOptions = --add-exports=java.base/jdk.internal.ref=ALL-UNNAMED
  javaOptions = --add-exports=java.base/sun.nio.ch=ALL-UNNAMED
  javaOptions = --add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED
  javaOptions = --add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED
  javaOptions = --add-opens=jdk.compiler/com.sun.tools.javac=ALL-UNNAMED
  javaOptions = --add-opens=java.base/java.lang=ALL-UNNAMED
  javaOptions = --add-opens=java.base/java.lang.reflect=ALL-UNNAMED
  javaOptions = --add-opens=java.base/java.io=ALL-UNNAMED
  javaOptions = --add-opens=java.base/java.util=ALL-UNNAMED
```

For further information and supported options, refer to [config](src/main/resources/Documentation/config.md)
documentation.

## Migration from H2 caches

You can check how to migrate from H2 to chronicle-map [here](src/main/resources/Documentation/migration.md).