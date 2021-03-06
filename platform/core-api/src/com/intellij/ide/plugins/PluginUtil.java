// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.plugins;

import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.extensions.PluginId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface PluginUtil {

  static PluginUtil getInstance() {
    return ServiceManager.getService(PluginUtil.class);
  }

  @Nullable PluginId getCallerPlugin(int stackFrameCount);

  @Nullable PluginId findPluginId(@NotNull Throwable t);

}
