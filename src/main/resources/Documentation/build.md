# Build

This plugin is built with Bazel in-tree build.

## Build in Gerrit tree

Clone (or link) this plugin to the `plugins` directory of Gerrit's source tree.

Wire this plugin's Bazel module into Gerrit's plugin dependency module
fragment. If this is the only external plugin, copy the fragment:

```sh
git clone https://gerrit.googlesource.com/gerrit
git clone https://github.com/GerritForge/cache-chroniclemap
cd gerrit/plugins
ln -s ../../cache-chroniclemap .
cp cache-chroniclemap/external_plugin_deps.MODULE.bazel external_plugin_deps.MODULE.bazel
```

If `external_plugin_deps.MODULE.bazel` already contains entries for other
plugins, merge the contents of
`cache-chroniclemap/external_plugin_deps.MODULE.bazel` into it instead.

Then issue:

```sh
bazelisk build //plugins/cache-chroniclemap
```

in the root of Gerrit's source tree. The plugin jar is created under
`bazel-bin/plugins/cache-chroniclemap/cache-chroniclemap.jar`.

To execute the tests run `bazelisk test plugins/cache-chroniclemap/...`
from the Gerrit source tree.

Example:

```sh
bazelisk test plugins/cache-chroniclemap/...
```

## Run tests in IDE

The cache-chroniclemap internals are JDK 17 compatible however JDK since that
version is more restrictive on which modules are by default accessible to the
third party libraries. Considering that, in order to run tests in IDE (e.g.
Eclipse), one needs to add the following VM arguments to the particular test's
_Debug/Run Configuration_:

```
--add-exports=java.base/sun.nio.ch=ALL-UNNAMED
--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED
--add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED
--add-opens=jdk.compiler/com.sun.tools.javac=ALL-UNNAMED
--add-opens=java.base/java.lang=ALL-UNNAMED
--add-opens=java.base/java.lang.reflect=ALL-UNNAMED
--add-opens=java.base/java.io=ALL-UNNAMED
--add-opens=java.base/java.util=ALL-UNNAMED
```
