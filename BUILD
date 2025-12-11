load("//tools/bzl:junit.bzl", "junit_tests")
load("//javatests/com/google/gerrit/acceptance:tests.bzl", "acceptance_tests")
load(
    "//tools/bzl:plugin.bzl",
    "PLUGIN_DEPS",
    "PLUGIN_TEST_DEPS",
    "gerrit_plugin",
)

gerrit_plugin(
    name = "cache-chroniclemap",
    srcs = glob(["src/main/java/**/*.java"]),
    manifest_entries = [
        "Gerrit-Module: com.gerritforge.gerrit.modules.cache.chroniclemap.CapabilityModule",
        "Gerrit-SshModule: com.gerritforge.gerrit.modules.cache.chroniclemap.SSHCommandModule",
        "Gerrit-HttpModule: com.gerritforge.gerrit.modules.cache.chroniclemap.HttpModule",
    ],
    resources = glob(["src/main/resources/**/*"]),
    deps = [
        "//lib:h2",
        "//lib/commons:io",
        "//proto:cache_java_proto",
        "@chronicle-affinity//jar",
        "@chronicle-algo//jar",
        "@chronicle-bytes//jar",
        "@chronicle-compiler//jar",
        "@chronicle-core//jar",
        "@chronicle-map//jar",
        "@chronicle-posix//jar",
        "@chronicle-threads//jar",
        "@chronicle-values//jar",
        "@chronicle-wire//jar",
        "@commons-lang3//jar",
        "@dev-jna//jar",
        "@error-prone-annotations//jar",
        "@javapoet//jar",
        "@jna-platform//jar",
    ],
)

junit_tests(
    name = "cache-chroniclemap_tests",
    srcs = glob(
        ["src/test/java/**/*Test.java"],
    ),
    jvm_flags = [
        "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED",
        "--add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
    ],
    visibility = ["//visibility:public"],
    deps = [
        ":cache-chroniclemap__plugin",
        ":chroniclemap-test-lib",
        "@chronicle-bytes//jar",
        "@chronicle-core//jar",
    ],
)

[junit_tests(
    name = f[:f.index(".")].replace("/", "_"),
    srcs = [f],
    jvm_flags = [
        "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED",
        "--add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
    ],
    tags = ["server"],
    deps = [
        ":cache-chroniclemap__plugin",
        ":chroniclemap-test-lib",
        "//java/com/google/gerrit/server/cache/h2",
        "//java/com/google/gerrit/server/cache/serialize",
        "//proto:cache_java_proto",
        "@chronicle-bytes//jar",
    ],
) for f in glob(["src/test/java/**/*IT.java"])]

java_library(
    name = "chroniclemap-test-lib",
    testonly = True,
    srcs = ["src/test/java/com/gerritforge/gerrit/modules/cache/chroniclemap/TestPersistentCacheDef.java"],
    exports = PLUGIN_DEPS + PLUGIN_TEST_DEPS,
    deps = PLUGIN_DEPS + PLUGIN_TEST_DEPS,
)
