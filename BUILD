load("@rules_java//java:defs.bzl", "java_library")
load(
    "@com_googlesource_gerrit_bazlets//:gerrit_plugin.bzl",
    "gerrit_plugin",
    "gerrit_plugin_dependency_tests",
    "gerrit_plugin_test_util",
    "gerrit_plugin_tests",
)

# Only the Chronicle modules whose types appear in `import` statements need to
# be listed here; the rest (algorithms, threads, values, wire, compiler,
# affinity, posix, jna, jna-platform, javapoet) are pulled in transitively
# at runtime via chronicle-map's Maven POM and end up bundled into the plugin
# JAR regardless. Bazel's strict_java_deps enforces only this direct subset.
PLUGIN_DEPS = [
    "@cache-chroniclemap_plugin_deps//:net_openhft_chronicle_bytes",
    "@cache-chroniclemap_plugin_deps//:net_openhft_chronicle_core",
    "@cache-chroniclemap_plugin_deps//:net_openhft_chronicle_map",
]

# Compile-only access to artifacts that Gerrit's runtime classpath already
# provides; wrap with neverlink so they do not get bundled into the plugin JAR
# (the overlap test would otherwise fail). bazlets' gerrit_plugin(provided_deps)
# unfortunately still merges into runtime deps, so this wrapper is the
# effective workaround.
java_library(
    name = "provided-deps-neverlink",
    neverlink = 1,
    exports = [
        "//lib:h2",
        "//lib/commons:io",
        "//proto:cache_java_proto",
        "@external_deps//:com_google_errorprone_error_prone_annotations",
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
    name = "cache-chroniclemap",
    srcs = glob(["src/main/java/**/*.java"]),
    manifest_entries = [
        "Gerrit-Module: com.gerritforge.gerrit.modules.cache.chroniclemap.CapabilityModule",
        "Gerrit-SshModule: com.gerritforge.gerrit.modules.cache.chroniclemap.SSHCommandModule",
        "Gerrit-HttpModule: com.gerritforge.gerrit.modules.cache.chroniclemap.HttpModule",
    ],
    resources = glob(["src/main/resources/**/*"]),
    deps = PLUGIN_DEPS + PROVIDED_DEPS,
)

gerrit_plugin_tests(
    name = "cache-chroniclemap_tests",
    srcs = glob(
        ["src/test/java/**/*Test.java"],
    ),
    jvm_flags = TEST_JVM_FLAGS,
    visibility = ["//visibility:public"],
    deps = [
        ":cache-chroniclemap__plugin",
        ":chroniclemap-test-lib",
        "@cache-chroniclemap_plugin_deps//:net_openhft_chronicle_bytes",
        "@cache-chroniclemap_plugin_deps//:net_openhft_chronicle_core",
    ],
)

[gerrit_plugin_tests(
    name = f[:f.index(".")].replace("/", "_"),
    srcs = [f],
    jvm_flags = TEST_JVM_FLAGS,
    tags = ["server"],
    deps = [
        ":cache-chroniclemap__plugin",
        ":chroniclemap-test-lib",
        "//java/com/google/gerrit/server/cache/h2",
        "//java/com/google/gerrit/server/cache/serialize",
        "//proto:cache_java_proto",
        "@cache-chroniclemap_plugin_deps//:net_openhft_chronicle_bytes",
    ],
) for f in glob(["src/test/java/**/*IT.java"])]

gerrit_plugin_test_util(
    name = "chroniclemap-test-lib",
    srcs = ["src/test/java/com/gerritforge/gerrit/modules/cache/chroniclemap/TestPersistentCacheDef.java"],
    visibility = ["//visibility:public"],
    deps = PLUGIN_DEPS + PROVIDED_DEPS + [
        ":cache-chroniclemap__plugin",
    ],
)

gerrit_plugin_dependency_tests(plugin = "cache-chroniclemap")
