// Copyright (C) 2010 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.config;

import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.CoreDownloadSchemes;
import com.google.gerrit.extensions.client.GeneralPreferencesInfo.DownloadCommand;
import com.google.gerrit.server.change.ArchiveFormatInternal;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.EnumSet;
import java.util.List;
import org.eclipse.jgit.lib.Config;

/**
 * Download protocol from {@code gerrit.config}.
 *
 * <p>Only used to configure the built-in set of schemes and commands in the core download-commands
 * plugin; not used by other plugins.
 */
@Singleton
public class DownloadConfig {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final ImmutableSet<String> downloadSchemes;
  private final ImmutableSet<DownloadCommand> downloadCommands;
  private final ImmutableSet<ArchiveFormatInternal> archiveFormats;

  @Inject
  public DownloadConfig(@GerritServerConfig Config cfg) {
    String[] allSchemes = cfg.getStringList("download", null, "scheme");
    if (allSchemes.length == 0) {
      downloadSchemes =
          ImmutableSet.of(
              CoreDownloadSchemes.SSH, CoreDownloadSchemes.HTTP, CoreDownloadSchemes.ANON_HTTP);
    } else {
      ImmutableSet.Builder<String> normalized =
          ImmutableSet.builderWithExpectedSize(allSchemes.length);
      for (String s : allSchemes) {
        String core = toCoreScheme(s);
        if (core == null) {
          logger.atWarning().log("not a core download scheme: %s", s);
          continue;
        }
        normalized.add(core);
      }
      downloadSchemes = normalized.build();
    }

    DownloadCommand[] downloadCommandValues = DownloadCommand.values();
    List<DownloadCommand> allCommands =
        ConfigUtil.getEnumList(cfg, "download", null, "command", downloadCommandValues, null);
    if (isOnlyNull(allCommands)) {
      downloadCommands = ImmutableSet.copyOf(downloadCommandValues);
    } else {
      downloadCommands = ImmutableSet.copyOf(allCommands);
    }

    String v = cfg.getString("download", null, "archive");
    if (v == null) {
      archiveFormats = ImmutableSet.copyOf(EnumSet.allOf(ArchiveFormatInternal.class));
    } else if (v.isEmpty() || "off".equalsIgnoreCase(v)) {
      archiveFormats = ImmutableSet.of();
    } else {
      archiveFormats =
          ImmutableSet.copyOf(
              ConfigUtil.getEnumList(cfg, "download", null, "archive", ArchiveFormatInternal.TGZ));
    }
  }

  private static boolean isOnlyNull(List<?> list) {
    return list.size() == 1 && list.get(0) == null;
  }

  @Nullable
  private static String toCoreScheme(String s) {
    try {
      Field f = CoreDownloadSchemes.class.getField(s.toUpperCase());
      int m = Modifier.PUBLIC | Modifier.STATIC | Modifier.FINAL;
      if ((f.getModifiers() & m) == m && f.getType() == String.class) {
        return (String) f.get(null);
      }
      return null;
    } catch (NoSuchFieldException
        | SecurityException
        | IllegalArgumentException
        | IllegalAccessException e) {
      return null;
    }
  }

  /** Scheme used to download. */
  public ImmutableSet<String> getDownloadSchemes() {
    return downloadSchemes;
  }

  /** Command used to download. */
  public ImmutableSet<DownloadCommand> getDownloadCommands() {
    return downloadCommands;
  }

  /** Archive formats for downloading. */
  public ImmutableSet<ArchiveFormatInternal> getArchiveFormats() {
    return archiveFormats;
  }
}
