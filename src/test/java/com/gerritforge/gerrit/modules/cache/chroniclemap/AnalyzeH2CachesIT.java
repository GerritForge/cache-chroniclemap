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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Joiner;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.Sandboxed;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.acceptance.UseSsh;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

@UseSsh
@TestPlugin(
    name = "cache-chroniclemap",
    sshModule = "com.gerritforge.gerrit.modules.cache.chroniclemap.SSHCommandModule")
@UseLocalDisk
@Sandboxed
public class AnalyzeH2CachesIT extends LightweightPluginDaemonTest {
  private String cmd = Joiner.on(" ").join("cache-chroniclemap", "analyze-h2-caches");

  @Test
  public void shouldAnalyzeH2Cache() throws Exception {
    createChange();

    String result = adminSshSession.exec(cmd);

    adminSshSession.assertSuccess();
    assertThat(result).contains("[cache \"git_file_diff\"]");
    assertThat(result).contains("[cache \"gerrit_file_diff\"]");
    assertThat(result).contains("[cache \"accounts\"]");
    assertThat(result).contains("[cache \"diff_summary\"]");
    assertThat(result).contains("[cache \"persisted_projects\"]");
  }

  @Test
  public void shouldDenyAccessToAnalyzeH2Cache() throws Exception {
    userSshSession.exec(cmd);
    userSshSession.assertFailure("not permitted");
  }

  @Test
  public void shouldProduceWarningWhenCacheFileIsEmpty() throws Exception {
    String expectedPattern = "WARN: Cache .[a-z]+ is empty, skipping";
    String result = adminSshSession.exec(cmd);

    adminSshSession.assertSuccess();
    assertThat(result).containsMatch(expectedPattern);
  }

  @Test
  public void shouldIgnoreNonH2Files() throws Exception {
    Path cacheDirectory = sitePaths.resolve(cfg.getString("cache", null, "directory"));
    Files.write(cacheDirectory.resolve("some.dat"), "some_content".getBytes());

    @SuppressWarnings("unused")
    String result = adminSshSession.exec(cmd);

    adminSshSession.assertSuccess();
  }

  @Test
  public void shouldFailWhenCacheDirectoryDoesNotExists() throws Exception {
    cfg.setString("cache", null, "directory", "/tmp/non_existing_directory");

    adminSshSession.exec(cmd);
    adminSshSession.assertFailure(
        "fatal: disk cache is configured but doesn't exist: /tmp/non_existing_directory");
  }
}
