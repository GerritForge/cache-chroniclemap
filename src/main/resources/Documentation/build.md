# Build

This plugin is built with Bazel in-tree build.

## Build in Gerrit tree

Create a symbolic link of the repsotiory source to the Gerrit source
tree /plugins/cache-chronicalmap directory, and the external_plugin_deps.bzl
dependencies linked to /plugins/external_plugin_deps.bzl.

Example:

```sh
git clone https://gerrit.googlesource.com/gerrit
git clone https://github.com/GerritForge/cache-chroniclemap
cd gerrit/plugins
ln -s ../../cache-chroniclemap .
ln -sf ../../cache-chroniclemap/external_plugin_deps.bzl .
```

From the Gerrit source tree issue the command `bazelsk build plugins/cache-chroniclemap`.

Example:

```sh
bazelisk build plugins/cache-chroniclemap
```

The libModule jar file is created under `basel-bin/plugins/cache-chroniclemap/cache-chroniclemap.jar`

To execute the tests run `bazelisk test plugins/cache-chroniclemap/...` from the Gerrit source tree.

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
