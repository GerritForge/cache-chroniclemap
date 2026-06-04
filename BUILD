load(
    "@com_googlesource_gerrit_bazlets//:gerrit_plugin.bzl",
    "gerrit_plugin",
    "gerrit_plugin_test_util",
    "gerrit_plugin_tests",
)
load("@rules_java//java:defs.bzl", "java_library")

PLUGIN = "cache-chroniclemap"

# Only the Chronicle modules whose types appear in `import` statements need to
# be listed here; the rest (algorithms, threads, values, wire, compiler,
# affinity, posix, jna, jna-platform, javapoet) are pulled in transitively
# at runtime via chronicle-map's Maven POM and end up bundled into the plugin
# JAR regardless. Bazel's strict_java_deps enforces only this direct subset.
EXT_DEPS = [
    "net.openhft:chronicle-bytes",
    "net.openhft:chronicle-core",
    "net.openhft:chronicle-map",
]

# Compile-only access to artifacts that Gerrit's runtime classpath already
# provides; wrap with neverlink so they do not get bundled into the plugin JAR
# (the overlap test would otherwise fail).
java_library(
    name = "provided-deps-neverlink",
    neverlink = True,
    exports = [
        "//lib:h2",
        "//lib/commons:io",
        "//lib/errorprone:annotations",
        "//proto:cache_java_proto",
    ],
)

PROVIDED_DEPS = [":provided-deps-neverlink"]

TEST_JVM_FLAGS = [
    "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED",
    "--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED",
    "--add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
    "--add-opens=jdk.compiler/com.sun.tools.javac=ALL-UNNAMED",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
    "--add-opens=java.base/java.io=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
]

gerrit_plugin(
    srcs = glob(["src/main/java/**/*.java"]),
    ext_deps = EXT_DEPS,
    manifest_entries = [
        "Gerrit-Module: com.gerritforge.gerrit.modules.cache.chroniclemap.CapabilityModule",
        "Gerrit-SshModule: com.gerritforge.gerrit.modules.cache.chroniclemap.SSHCommandModule",
        "Gerrit-HttpModule: com.gerritforge.gerrit.modules.cache.chroniclemap.HttpModule",
    ],
    plugin = PLUGIN,
    resources = glob(["src/main/resources/**/*"]),
    deps = PROVIDED_DEPS,
)

gerrit_plugin_tests(
    srcs = glob(["src/test/java/**/*Test.java"]),
    ext_deps = EXT_DEPS,
    jvm_flags = TEST_JVM_FLAGS,
    plugin = PLUGIN,
    visibility = ["//visibility:public"],
    deps = [":chroniclemap-test-lib"],
)

[gerrit_plugin_tests(
    name = f[:f.index(".")].replace("/", "_"),
    srcs = [f],
    ext_deps = EXT_DEPS,
    jvm_flags = TEST_JVM_FLAGS,
    plugin = PLUGIN,
    skip_dependency_tests = True,
    deps = [
        ":chroniclemap-test-lib",
        "//java/com/google/gerrit/server/cache/h2",
        "//proto:cache_java_proto",
    ],
) for f in glob(["src/test/java/**/*IT.java"])]

gerrit_plugin_test_util(
    name = "chroniclemap-test-lib",
    srcs = ["src/test/java/com/gerritforge/gerrit/modules/cache/chroniclemap/TestPersistentCacheDef.java"],
    ext_deps = EXT_DEPS,
    plugin = PLUGIN,
    visibility = ["//visibility:public"],
    deps = PROVIDED_DEPS,
)
