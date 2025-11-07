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

import static com.google.common.net.HttpHeaders.CONTENT_TYPE;
import static com.google.common.truth.Truth.assertThat;
import static org.apache.http.HttpHeaders.ACCEPT;
import static org.eclipse.jgit.util.HttpSupport.TEXT_PLAIN;

import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.RestSession;
import com.google.gerrit.acceptance.TestPlugin;
import org.apache.http.message.BasicHeader;
import org.junit.Test;

@TestPlugin(
    name = "cache-chroniclemap",
    httpModule = "com.gerritforge.gerrit.modules.cache.chroniclemap.HttpModule")
public class MigrateH2CachesInMemoryIT extends LightweightPluginDaemonTest {
  private static final String MIGRATION_ENDPOINT = "/plugins/cache-chroniclemap/migrate";

  @Test
  public void shouldReturnTexPlain() throws Exception {
    RestResponse result = runMigration(adminRestSession);
    assertThat(result.getHeader(CONTENT_TYPE)).contains(TEXT_PLAIN);
  }

  @Test
  public void shouldReturnBadRequestWhenTextPlainIsNotAnAcceptedHeader() throws Exception {
    runMigrationWithAcceptHeader(adminRestSession, "application/json").assertBadRequest();
  }

  @Test
  public void shouldFailWhenUserHasNoAdminServerCapability() throws Exception {
    RestResponse result = runMigration(userRestSession);
    result.assertForbidden();
    assertThat(result.getEntityContent()).contains("not permitted");
  }

  @Test
  public void shouldFailWhenCacheDirectoryIsNotDefined() throws Exception {
    RestResponse result = runMigration(adminRestSession);
    result.assertBadRequest();
    assertThat(result.getEntityContent())
        .contains("Cannot run migration, cache directory is not configured");
  }

  private RestResponse runMigration(RestSession restSession) throws Exception {
    return runMigrationWithAcceptHeader(restSession, TEXT_PLAIN);
  }

  private RestResponse runMigrationWithAcceptHeader(RestSession restSession, String acceptHeader)
      throws Exception {
    return restSession.putWithHeaders(MIGRATION_ENDPOINT, new BasicHeader(ACCEPT, acceptHeader));
  }
}
