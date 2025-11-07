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

import com.google.common.collect.ImmutableSet;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.api.access.PluginPermission;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.server.permissions.GlobalPermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.inject.Inject;
import java.util.function.Consumer;

class AdministerCachePermission {
  private final PermissionBackend permissionBackend;
  private final String pluginName;

  @Inject
  AdministerCachePermission(PermissionBackend permissionBackend, @PluginName String pluginName) {
    this.permissionBackend = permissionBackend;
    this.pluginName = pluginName;
  }

  boolean isCurrentUserAllowed() {
    try {
      checkCurrentUserAllowed(null);
      return true;
    } catch (AuthException | PermissionBackendException e) {
      return false;
    }
  }

  void checkCurrentUserAllowed(@Nullable Consumer<Exception> failureFunction)
      throws AuthException, PermissionBackendException {
    try {
      permissionBackend
          .currentUser()
          .checkAny(
              ImmutableSet.of(
                  GlobalPermission.ADMINISTRATE_SERVER,
                  new PluginPermission(pluginName, AdministerCachesCapability.ID)));
    } catch (AuthException | PermissionBackendException e) {
      if (failureFunction != null) {
        failureFunction.accept(e);
      }
      throw e;
    }
  }
}
