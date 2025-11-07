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

import static com.gerritforge.gerrit.modules.cache.chroniclemap.HttpServletOps.checkAcceptHeader;
import static com.gerritforge.gerrit.modules.cache.chroniclemap.HttpServletOps.setResponse;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jgit.lib.Config;

@Singleton
public class AutoAdjustCachesServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;
  protected static final FluentLogger logger = FluentLogger.forEnclosingClass();

  // This needs to be a provider so that every doPut() call is reentrant because
  // uses a different auto-adjuster for the caches.
  private final Provider<AutoAdjustCaches> autoAdjustCachesProvider;

  @Inject
  AutoAdjustCachesServlet(Provider<AutoAdjustCaches> autoAdjustCachesProvider) {
    this.autoAdjustCachesProvider = autoAdjustCachesProvider;
  }

  @Override
  protected void doPut(HttpServletRequest req, HttpServletResponse rsp) throws IOException {
    AutoAdjustCaches autoAdjustCachesEngine = autoAdjustCachesProvider.get();
    if (!checkAcceptHeader(req, rsp)) {
      return;
    }

    autoAdjustCachesEngine.setDryRun(
        Optional.ofNullable(req.getParameter("dry-run"))
            .or(() -> Optional.ofNullable(req.getParameter("d")))
            .isPresent());

    autoAdjustCachesEngine.setAdjustCachesOnDefaults(
        Optional.ofNullable(req.getParameter("adjust-caches-on-defaults"))
            .or(() -> Optional.ofNullable(req.getParameter("a")))
            .isPresent());

    autoAdjustCachesEngine.setOptionalMaxEntries(
        Optional.ofNullable(req.getParameter("max-entries"))
            .or(() -> Optional.ofNullable(req.getParameter("m")))
            .map(Long::parseLong));

    String[] cacheNames = req.getParameterValues("CACHE_NAME");
    if (cacheNames != null) {
      autoAdjustCachesEngine.addCacheNames(Arrays.asList(cacheNames));
    }

    try {
      Config outputChronicleMapConfig = autoAdjustCachesEngine.run(null);

      if (outputChronicleMapConfig.getSections().isEmpty()) {
        setResponse(
            rsp,
            HttpServletResponse.SC_NO_CONTENT,
            "All existing caches are already tuned: no changes needed.");
        return;
      }

      setResponse(rsp, HttpServletResponse.SC_CREATED, outputChronicleMapConfig.toText());
    } catch (AuthException | PermissionBackendException e) {
      setResponse(
          rsp,
          HttpServletResponse.SC_FORBIDDEN,
          "not permitted to administer caches : " + e.getLocalizedMessage());
      return;
    }
  }
}
