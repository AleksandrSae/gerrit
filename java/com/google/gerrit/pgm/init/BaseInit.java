// Copyright (C) 2013 The Android Open Source Project
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

package com.google.gerrit.pgm.init;

import static com.google.inject.Scopes.SINGLETON;
import static com.google.inject.Stage.PRODUCTION;

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.Die;
import com.google.gerrit.common.IoUtil;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.index.IndexType;
import com.google.gerrit.metrics.DisabledMetricMaker;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.pgm.init.api.ConsoleUI;
import com.google.gerrit.pgm.init.api.InitFlags;
import com.google.gerrit.pgm.init.api.InstallAllPlugins;
import com.google.gerrit.pgm.init.api.InstallPlugins;
import com.google.gerrit.pgm.init.api.LibraryDownload;
import com.google.gerrit.pgm.init.index.IndexManagerOnInit;
import com.google.gerrit.pgm.init.index.IndexModuleOnInit;
import com.google.gerrit.pgm.init.index.lucene.LuceneIndexModuleOnInit;
import com.google.gerrit.pgm.util.SiteProgram;
import com.google.gerrit.server.config.GerritServerConfigModule;
import com.google.gerrit.server.config.SitePath;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.index.IndexModule;
import com.google.gerrit.server.plugins.JarScanner;
import com.google.gerrit.server.schema.NoteDbSchemaUpdater;
import com.google.gerrit.server.schema.UpdateUI;
import com.google.gerrit.server.securestore.SecureStore;
import com.google.gerrit.server.securestore.SecureStoreClassName;
import com.google.gerrit.server.securestore.SecureStoreProvider;
import com.google.inject.AbstractModule;
import com.google.inject.CreationException;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.TypeLiteral;
import com.google.inject.spi.Message;
import com.google.inject.util.Providers;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Initialize a new Gerrit installation. */
public class BaseInit extends SiteProgram {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final boolean standalone;
  protected final PluginsDistribution pluginsDistribution;
  private final List<String> pluginsToInstall;

  private Injector sysInjector;

  protected BaseInit(PluginsDistribution pluginsDistribution, List<String> pluginsToInstall) {
    this.standalone = true;
    this.pluginsDistribution = pluginsDistribution;
    this.pluginsToInstall = pluginsToInstall;
  }

  public BaseInit(
      Path sitePath,
      boolean standalone,
      PluginsDistribution pluginsDistribution,
      List<String> pluginsToInstall) {
    super(sitePath);
    this.standalone = standalone;
    this.pluginsDistribution = pluginsDistribution;
    this.pluginsToInstall = pluginsToInstall;
  }

  @Override
  public int run() throws Exception {
    final SiteInit init = createSiteInit();
    if (beforeInit(init)) {
      return 0;
    }

    init.flags.autoStart = getAutoStart() && init.site.isNew;
    init.flags.dev = isDev() && init.site.isNew;
    init.flags.skipPlugins = skipPlugins();
    init.flags.deleteCaches = getDeleteCaches();
    init.flags.isNew = init.site.isNew;

    final SiteRun run;
    try {
      init.initializer.run();
      init.flags.deleteOnFailure = false;

      Injector sysInjector = createSysInjector(init);
      IndexManagerOnInit indexManager = sysInjector.getInstance(IndexManagerOnInit.class);
      try {
        indexManager.start();
        run = createSiteRun(init);
        try {
          run.upgradeSchema();
        } catch (StorageException e) {
          String msg = "Couldn't upgrade schema. Expected if slave and read-only database";
          System.err.println(msg);
          logger.atSevere().withCause(e).log("%s", msg);
        }

        init.initializer.postRun(sysInjector);
      } finally {
        indexManager.stop();
      }
    } catch (Exception | Error failure) {
      if (init.flags.deleteOnFailure) {
        recursiveDelete(getSitePath());
      }
      throw failure;
    }

    System.err.println("Initialized " + getSitePath().toRealPath().normalize());
    afterInit(run);
    return 0;
  }

  protected boolean skipPlugins() {
    return false;
  }

  protected String getSecureStoreLib() {
    return null;
  }

  protected boolean skipAllDownloads() {
    return false;
  }

  protected List<String> getSkippedDownloads() {
    return Collections.emptyList();
  }

  /**
   * Invoked before site init is called.
   *
   * @param init initializer instance.
   */
  protected boolean beforeInit(SiteInit init) throws Exception {
    return false;
  }

  /**
   * Invoked after site init is called.
   *
   * @param run completed run instance.
   */
  protected void afterInit(SiteRun run) throws Exception {}

  @Nullable
  protected List<String> getInstallPlugins() {
    try {
      if (pluginsToInstall != null && pluginsToInstall.isEmpty()) {
        return Collections.emptyList();
      }
      List<String> names = pluginsDistribution.listPluginNames();
      if (pluginsToInstall != null) {
        names.removeIf(n -> !pluginsToInstall.contains(n));
      }
      return names;
    } catch (FileNotFoundException e) {
      logger.atWarning().log(
          "Couldn't find distribution archive location. No plugin will be installed");
      return null;
    }
  }

  protected boolean installAllPlugins() {
    return false;
  }

  protected boolean getAutoStart() {
    return false;
  }

  public static class SiteInit {
    public final SitePaths site;
    final InitFlags flags;
    final ConsoleUI ui;
    final SitePathInitializer initializer;

    @Inject
    SiteInit(
        final SitePaths site,
        final InitFlags flags,
        final ConsoleUI ui,
        final SitePathInitializer initializer) {
      this.site = site;
      this.flags = flags;
      this.ui = ui;
      this.initializer = initializer;
    }
  }

  private SiteInit createSiteInit() {
    final ConsoleUI ui = getConsoleUI();
    final Path sitePath = getSitePath();
    final List<Module> m = new ArrayList<>();
    final SecureStoreInitData secureStoreInitData = discoverSecureStoreClass();
    final String currentSecureStoreClassName = getConfiguredSecureStoreClass();

    if (secureStoreInitData != null
        && currentSecureStoreClassName != null
        && !currentSecureStoreClassName.equals(secureStoreInitData.className)) {
      String err =
          String.format(
              "Different secure store was previously configured: %s. "
                  + "Use SwitchSecureStore program to switch between implementations.",
              currentSecureStoreClassName);
      throw die(err);
    }

    m.add(new GerritServerConfigModule());
    m.add(new InitModule(standalone));
    m.add(
        new AbstractModule() {
          @Override
          protected void configure() {
            bind(ConsoleUI.class).toInstance(ui);
            bind(Path.class).annotatedWith(SitePath.class).toInstance(sitePath);
            List<String> plugins = MoreObjects.firstNonNull(getInstallPlugins(), new ArrayList<>());
            bind(new TypeLiteral<List<String>>() {})
                .annotatedWith(InstallPlugins.class)
                .toInstance(plugins);
            bind(new TypeLiteral<Boolean>() {})
                .annotatedWith(InstallAllPlugins.class)
                .toInstance(installAllPlugins());
            bind(PluginsDistribution.class).toInstance(pluginsDistribution);

            String secureStoreClassName;
            if (secureStoreInitData != null) {
              secureStoreClassName = secureStoreInitData.className;
            } else {
              secureStoreClassName = currentSecureStoreClassName;
            }
            if (secureStoreClassName != null) {
              ui.message("Using secure store: %s\n", secureStoreClassName);
            }
            bind(SecureStoreInitData.class).toProvider(Providers.of(secureStoreInitData));
            bind(String.class)
                .annotatedWith(SecureStoreClassName.class)
                .toProvider(Providers.of(secureStoreClassName));
            bind(SecureStore.class).toProvider(SecureStoreProvider.class).in(SINGLETON);
            bind(new TypeLiteral<List<String>>() {})
                .annotatedWith(LibraryDownload.class)
                .toInstance(getSkippedDownloads());
            bind(Boolean.class).annotatedWith(LibraryDownload.class).toInstance(skipAllDownloads());

            bind(MetricMaker.class).to(DisabledMetricMaker.class);
          }
        });

    try {
      return Guice.createInjector(PRODUCTION, m).getInstance(SiteInit.class);
    } catch (CreationException ce) {
      final Message first = ce.getErrorMessages().iterator().next();
      Throwable why = first.getCause();

      if (why instanceof Die) {
        throw (Die) why;
      }

      final StringBuilder buf = new StringBuilder(ce.getMessage());
      while (why != null) {
        buf.append("\n");
        buf.append(why.getMessage());
        why = why.getCause();
        if (why != null) {
          buf.append("\n  caused by ");
        }
      }
      throw die(buf.toString(), new RuntimeException("InitInjector failed", ce));
    }
  }

  protected ConsoleUI getConsoleUI() {
    return ConsoleUI.getInstance(false);
  }

  @Nullable
  private SecureStoreInitData discoverSecureStoreClass() {
    String secureStore = getSecureStoreLib();
    if (Strings.isNullOrEmpty(secureStore)) {
      return null;
    }

    Path secureStoreLib = Paths.get(secureStore);
    if (!Files.exists(secureStoreLib)) {
      throw new InvalidSecureStoreException(String.format("File %s doesn't exist", secureStore));
    }
    try (JarScanner scanner = new JarScanner(secureStoreLib)) {
      List<String> secureStores = scanner.findSubClassesOf(SecureStore.class);
      if (secureStores.isEmpty()) {
        throw new InvalidSecureStoreException(
            String.format(
                "Cannot find class implementing %s interface in %s",
                SecureStore.class.getName(), secureStore));
      }
      if (secureStores.size() > 1) {
        throw new InvalidSecureStoreException(
            String.format(
                "%s has more that one implementation of %s interface",
                secureStore, SecureStore.class.getName()));
      }
      IoUtil.loadJARs(secureStoreLib);
      return new SecureStoreInitData(secureStoreLib, secureStores.get(0));
    } catch (IOException e) {
      throw new InvalidSecureStoreException(String.format("%s is not a valid jar", secureStore), e);
    }
  }

  public static class SiteRun {
    public final ConsoleUI ui;
    public final SitePaths site;
    public final InitFlags flags;
    final NoteDbSchemaUpdater noteDbSchemaUpdater;
    final GitRepositoryManager repositoryManager;

    @Inject
    SiteRun(
        ConsoleUI ui,
        SitePaths site,
        InitFlags flags,
        NoteDbSchemaUpdater noteDbSchemaUpdater,
        GitRepositoryManager repositoryManager) {
      this.ui = ui;
      this.site = site;
      this.flags = flags;
      this.noteDbSchemaUpdater = noteDbSchemaUpdater;
      this.repositoryManager = repositoryManager;
    }

    void upgradeSchema() {
      noteDbSchemaUpdater.update(new UpdateUIImpl(ui));
    }

    private static class UpdateUIImpl implements UpdateUI {
      private final ConsoleUI consoleUi;

      UpdateUIImpl(ConsoleUI consoleUi) {
        this.consoleUi = consoleUi;
      }

      @Override
      public void message(String message) {
        System.err.println(message);
        System.err.flush();
      }

      @Override
      public boolean yesno(boolean defaultValue, String message) {
        return consoleUi.yesno(defaultValue, message);
      }

      @Override
      public void waitForUser() {
        consoleUi.waitForUser();
      }

      @Override
      public String readString(String defaultValue, Set<String> allowedValues, String message) {
        return consoleUi.readString(defaultValue, allowedValues, message);
      }

      @Override
      public boolean isBatch() {
        return consoleUi.isBatch();
      }
    }
  }

  private SiteRun createSiteRun(SiteInit init) {
    return createSysInjector(init).getInstance(SiteRun.class);
  }

  private Injector createSysInjector(SiteInit init) {
    if (sysInjector == null) {
      final List<Module> modules = new ArrayList<>();
      modules.add(
          new AbstractModule() {
            @Override
            protected void configure() {
              bind(ConsoleUI.class).toInstance(init.ui);
              bind(InitFlags.class).toInstance(init.flags);
            }
          });
      Injector dbInjector = createDbInjector();

      IndexType indexType = IndexModule.getIndexType(dbInjector);
      if (indexType.isLucene()) {
        modules.add(new LuceneIndexModuleOnInit());
      } else if (indexType.isFake()) {
        try {
          Class<?> clazz = Class.forName("com.google.gerrit.index.testing.FakeIndexModuleOnInit");
          Module indexOnInitModule = (Module) clazz.getDeclaredConstructor().newInstance();
          modules.add(indexOnInitModule);
        } catch (InstantiationException
            | IllegalAccessException
            | ClassNotFoundException
            | NoSuchMethodException
            | InvocationTargetException e) {
          throw new IllegalStateException("unable to create fake index", e);
        }
        modules.add(new IndexModuleOnInit());
      } else {
        throw new IllegalStateException("unsupported index.type = " + indexType);
      }
      sysInjector = dbInjector.createChildInjector(modules);
    }
    return sysInjector;
  }

  private static void recursiveDelete(Path path) {
    final String msg = "warn: Cannot remove ";
    try {
      Files.walkFileTree(
          path,
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) throws IOException {
              try {
                Files.delete(f);
              } catch (IOException e) {
                System.err.println(msg + f);
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException err) {
              try {
                // Previously warned if err was not null; if dir is not empty as a
                // result, will cause an error that will be logged below.
                Files.delete(dir);
              } catch (IOException e) {
                System.err.println(msg + dir);
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path f, IOException e) {
              System.err.println(msg + f);
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      System.err.println(msg + path);
    }
  }

  protected boolean isDev() {
    return false;
  }

  protected boolean getDeleteCaches() {
    return false;
  }
}
