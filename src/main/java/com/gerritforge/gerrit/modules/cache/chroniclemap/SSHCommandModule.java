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

import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.sshd.PluginCommandModule;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Key;

public class SSHCommandModule extends PluginCommandModule {
  private final Injector injector;

  @Inject
  SSHCommandModule(Injector injector, @PluginName String pluginName) {
    super(pluginName);
    this.injector = injector;
  }

  @Override
  protected void configureCommands() {
    /*
     This module can be installed as a plugin, as a lib or both, depending on the wanted usage
     (refer to the docs for more details on why this is needed). For this reason, some binding
     might or might have not already been configured.
    */
    if (injector.getExistingBinding(Key.get(ChronicleMapCacheConfig.Factory.class)) == null) {
      factory(ChronicleMapCacheConfig.Factory.class);
    }
    command("analyze-h2-caches").to(AnalyzeH2Caches.class);
    command("auto-adjust-caches").to(AutoAdjustCachesCommand.class);
  }
}
