/*
 * Copyright 2017-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.skylark.parser;

import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.pathformat.PathFormatter;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.BuildFileManifestPojoizer;
import com.facebook.buck.parser.api.ImmutableBuildFileManifest;
import com.facebook.buck.parser.api.PojoTransformer;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.parser.events.ParseBuckFileEvent;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.implicit.ImplicitInclude;
import com.facebook.buck.parser.implicit.PackageImplicitIncludesFinder;
import com.facebook.buck.parser.options.ProjectBuildFileParserOptions;
import com.facebook.buck.parser.syntax.ImmutableListWithSelects;
import com.facebook.buck.parser.syntax.ImmutableSelectorValue;
import com.facebook.buck.skylark.io.GlobSpec;
import com.facebook.buck.skylark.io.GlobSpecWithResult;
import com.facebook.buck.skylark.io.Globber;
import com.facebook.buck.skylark.io.GlobberFactory;
import com.facebook.buck.skylark.io.impl.CachingGlobber;
import com.facebook.buck.skylark.packages.PackageContext;
import com.facebook.buck.skylark.parser.context.ParseContext;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.syntax.BuildFileAST;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.Environment.Extension;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.ParserInputSource;
import com.google.devtools.build.lib.syntax.SkylarkImport;
import com.google.devtools.build.lib.syntax.SkylarkUtils;
import com.google.devtools.build.lib.syntax.SkylarkUtils.Phase;
import com.google.devtools.build.lib.syntax.StarlarkSemantics;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.immutables.value.Value;

/**
 * Parser for build files written using Skylark syntax.
 *
 * <p>NOTE: This parser is still a work in progress and does not support some functions provided by
 * Python DSL parser like {@code include_defs}, so use in production at your own risk.
 */
public class SkylarkProjectBuildFileParser implements ProjectBuildFileParser {

  private static final Logger LOG = Logger.get(SkylarkProjectBuildFileParser.class);

  private final FileSystem fileSystem;

  private final ProjectBuildFileParserOptions options;
  private final BuckEventBus buckEventBus;
  private final EventHandler eventHandler;
  private final BuckGlobals buckGlobals;
  private final GlobberFactory globberFactory;
  private final Cache<com.google.devtools.build.lib.vfs.Path, BuildFileAST> astCache;
  private final Cache<com.google.devtools.build.lib.vfs.Path, ExtensionData> extensionDataCache;
  private final LoadingCache<LoadImport, IncludesData> includesDataCache;
  private final PackageImplicitIncludesFinder packageImplicitIncludeFinder;

  private SkylarkProjectBuildFileParser(
      ProjectBuildFileParserOptions options,
      BuckEventBus buckEventBus,
      FileSystem fileSystem,
      BuckGlobals buckGlobals,
      EventHandler eventHandler,
      GlobberFactory globberFactory) {
    this.options = options;
    this.buckEventBus = buckEventBus;
    this.fileSystem = fileSystem;
    this.eventHandler = eventHandler;
    this.buckGlobals = buckGlobals;
    this.globberFactory = globberFactory;

    this.astCache = CacheBuilder.newBuilder().build();
    this.extensionDataCache = CacheBuilder.newBuilder().build();

    this.includesDataCache =
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<LoadImport, IncludesData>() {
                  @Override
                  public IncludesData load(LoadImport loadImport)
                      throws IOException, InterruptedException {
                    return loadInclude(loadImport);
                  }
                });

    this.packageImplicitIncludeFinder =
        PackageImplicitIncludesFinder.fromConfiguration(options.getPackageImplicitIncludes());
  }

  @VisibleForTesting
  protected SkylarkProjectBuildFileParser(SkylarkProjectBuildFileParser other) {
    this(
        other.options,
        other.buckEventBus,
        other.fileSystem,
        other.buckGlobals,
        other.eventHandler,
        other.globberFactory);
  }

  /** Create an instance of Skylark project build file parser using provided options. */
  public static SkylarkProjectBuildFileParser using(
      ProjectBuildFileParserOptions options,
      BuckEventBus buckEventBus,
      FileSystem fileSystem,
      BuckGlobals buckGlobals,
      EventHandler eventHandler,
      GlobberFactory globberFactory) {
    return new SkylarkProjectBuildFileParser(
        options, buckEventBus, fileSystem, buckGlobals, eventHandler, globberFactory);
  }

  @Override
  public BuildFileManifest getBuildFileManifest(Path buildFile)
      throws BuildFileParseException, InterruptedException, IOException {
    ParseResult parseResult = parseBuildFile(buildFile);

    // By contract, BuildFileManifestPojoizer converts any Map to ImmutableMap.
    // ParseResult.getRawRules() returns ImmutableMap<String, Map<String, Object>>, so it is
    // a safe downcast here
    @SuppressWarnings("unchecked")
    ImmutableMap<String, Map<String, Object>> targets =
        (ImmutableMap<String, Map<String, Object>>)
            getBuildFileManifestPojoizer().convertToPojo(parseResult.getRawRules());

    return ImmutableBuildFileManifest.of(
        targets,
        ImmutableSortedSet.copyOf(parseResult.getLoadedPaths()),
        parseResult.getReadConfigurationOptions(),
        Optional.empty(),
        parseResult.getGlobManifestWithResult());
  }

  private static BuildFileManifestPojoizer getBuildFileManifestPojoizer() {
    // Convert Skylark-specific classes to Buck API POJO classes to decouple them from parser
    // implementation. BuildFileManifest should only have POJO classes.
    BuildFileManifestPojoizer pojoizer = BuildFileManifestPojoizer.of();
    pojoizer.addPojoTransformer(
        PojoTransformer.of(
            com.google.devtools.build.lib.syntax.SelectorList.class,
            obj -> {
              com.google.devtools.build.lib.syntax.SelectorList skylarkSelectorList =
                  (com.google.devtools.build.lib.syntax.SelectorList) obj;
              // recursively convert list elements
              @SuppressWarnings("unchecked")
              ImmutableList<Object> elements =
                  (ImmutableList<Object>) pojoizer.convertToPojo(skylarkSelectorList.getElements());
              return ImmutableListWithSelects.of(elements, skylarkSelectorList.getType());
            }));
    pojoizer.addPojoTransformer(
        PojoTransformer.of(
            com.google.devtools.build.lib.syntax.SelectorValue.class,
            obj -> {
              com.google.devtools.build.lib.syntax.SelectorValue skylarkSelectorValue =
                  (com.google.devtools.build.lib.syntax.SelectorValue) obj;
              // recursively convert dictionary elements
              @SuppressWarnings("unchecked")
              ImmutableMap<String, Object> dictionary =
                  (ImmutableMap<String, Object>)
                      pojoizer.convertToPojo(skylarkSelectorValue.getDictionary());
              return ImmutableSelectorValue.of(dictionary, skylarkSelectorValue.getNoMatchError());
            }));
    pojoizer.addPojoTransformer(
        PojoTransformer.of(
            com.google.devtools.build.lib.syntax.SkylarkNestedSet.class,
            obj -> {
              com.google.devtools.build.lib.syntax.SkylarkNestedSet skylarkNestedSet =
                  (com.google.devtools.build.lib.syntax.SkylarkNestedSet) obj;
              // recursively convert set elements
              return pojoizer.convertToPojo(skylarkNestedSet.toCollection());
            }));
    return pojoizer;
  }

  /**
   * Retrieves build files requested in {@code buildFile}.
   *
   * @param buildFile The build file to parse.
   * @return The {@link ParseResult} with build rules defined in {@code buildFile}.
   */
  private ParseResult parseBuildFile(Path buildFile)
      throws BuildFileParseException, InterruptedException, IOException {
    ImmutableMap<String, Map<String, Object>> rules = ImmutableMap.of();
    ParseBuckFileEvent.Started startEvent = ParseBuckFileEvent.started(buildFile, this.getClass());
    buckEventBus.post(startEvent);
    ParseResult parseResult;
    try {
      parseResult = parseBuildRules(buildFile);
      rules = parseResult.getRawRules();
    } finally {
      buckEventBus.post(
          ParseBuckFileEvent.finished(startEvent, rules.size(), 0L, Optional.empty()));
    }
    return parseResult;
  }

  private ImplicitlyLoadedExtension loadImplicitExtension(Path basePath, Label containingLabel)
      throws IOException, InterruptedException {
    Optional<ImplicitInclude> implicitInclude =
        packageImplicitIncludeFinder.findIncludeForBuildFile(basePath);
    if (!implicitInclude.isPresent()) {
      return ImplicitlyLoadedExtension.empty();
    }

    // Only export requested symbols, and ensure that all requsted symbols are present.
    ExtensionData data =
        loadExtension(LoadImport.of(containingLabel, implicitInclude.get().getLoadPath()));
    ImmutableMap<String, Object> symbols = data.getExtension().getBindings();
    ImmutableMap<String, String> expectedSymbols = implicitInclude.get().getSymbols();
    Builder<String, Object> loaded = ImmutableMap.builderWithExpectedSize(expectedSymbols.size());
    for (Entry<String, String> kvp : expectedSymbols.entrySet()) {
      Object symbol = symbols.get(kvp.getValue());
      if (symbol == null) {
        throw BuildFileParseException.createForUnknownParseError(
            String.format(
                "Could not find symbol '%s' in implicitly loaded extension '%s'",
                kvp.getValue(), implicitInclude.get().getLoadPath().getImportString()));
      }
      loaded.put(kvp.getKey(), symbol);
    }
    return ImplicitlyLoadedExtension.of(data, loaded.build());
  }

  /** @return The parsed build rules defined in {@code buildFile}. */
  private ParseResult parseBuildRules(Path buildFile)
      throws IOException, BuildFileParseException, InterruptedException {
    com.google.devtools.build.lib.vfs.Path buildFilePath = fileSystem.getPath(buildFile.toString());

    String basePath = getBasePath(buildFile);
    Label containingLabel = createContainingLabel(basePath);
    ImplicitlyLoadedExtension implicitLoad =
        loadImplicitExtension(buildFile.getFileSystem().getPath(basePath), containingLabel);

    BuildFileAST buildFileAst = parseBuildFile(buildFilePath, containingLabel);
    CachingGlobber globber = newGlobber(buildFile);
    PackageContext packageContext =
        createPackageContext(basePath, globber, implicitLoad.getLoadedSymbols());
    ParseContext parseContext = new ParseContext(packageContext);
    try (Mutability mutability = Mutability.create("parsing " + buildFile)) {
      EnvironmentData envData =
          createBuildFileEvaluationEnvironment(
              buildFilePath,
              containingLabel,
              buildFileAst,
              mutability,
              parseContext,
              implicitLoad.getExtensionData());
      boolean exec = buildFileAst.exec(envData.getEnvironment(), eventHandler);
      if (!exec) {
        throw BuildFileParseException.createForUnknownParseError(
            "Cannot evaluate build file " + buildFile);
      }
      ImmutableMap<String, ImmutableMap<String, Object>> rules = parseContext.getRecordedRules();
      if (LOG.isVerboseEnabled()) {
        LOG.verbose("Got rules: %s", rules.values());
      }
      LOG.verbose("Parsed %d rules from %s", rules.size(), buildFile);
      ImmutableList.Builder<String> loadedPaths =
          ImmutableList.builderWithExpectedSize(envData.getLoadedPaths().size() + 1);
      loadedPaths.add(buildFilePath.toString());
      loadedPaths.addAll(envData.getLoadedPaths());
      return ParseResult.of(
          rules,
          loadedPaths.build(),
          parseContext.getAccessedConfigurationOptions(),
          globber.createGlobManifest());
    }
  }

  private BuildFileAST parseBuildFile(
      com.google.devtools.build.lib.vfs.Path buildFilePath, Label containingLabel)
      throws IOException {
    BuildFileAST buildFileAst =
        parseSkylarkFile(buildFilePath, containingLabel).validateBuildFile(eventHandler);

    if (buildFileAst.containsErrors()) {
      throw BuildFileParseException.createForUnknownParseError(
          "Cannot parse build file %s", buildFilePath);
    }
    return buildFileAst;
  }

  /** Creates a globber for the package defined by the provided build file path. */
  private CachingGlobber newGlobber(Path buildFile) {
    return CachingGlobber.of(
        globberFactory.create(fileSystem.getPath(buildFile.getParent().toString())));
  }

  /**
   * @return The environment that can be used for evaluating build files. It includes built-in
   *     functions like {@code glob} and native rules like {@code java_library}.
   */
  private EnvironmentData createBuildFileEvaluationEnvironment(
      com.google.devtools.build.lib.vfs.Path buildFilePath,
      Label containingLabel,
      BuildFileAST buildFileAst,
      Mutability mutability,
      ParseContext parseContext,
      @Nullable ExtensionData implicitLoadExtensionData)
      throws IOException, InterruptedException, BuildFileParseException {
    ImmutableList<ExtensionData> dependencies =
        loadExtensions(containingLabel, buildFileAst.getImports());
    ImmutableMap<String, Environment.Extension> importMap =
        toImportMap(dependencies, implicitLoadExtensionData);
    Environment env =
        Environment.builder(mutability)
            .setImportedExtensions(importMap)
            .setGlobals(buckGlobals.getBuckBuildFileContextGlobals())
            .setSemantics(StarlarkSemantics.DEFAULT_SEMANTICS)
            .setEventHandler(eventHandler)
            .build();
    SkylarkUtils.setPhase(env, Phase.LOADING);

    parseContext.setup(env);

    return EnvironmentData.of(
        env, toLoadedPaths(buildFilePath, dependencies, implicitLoadExtensionData));
  }

  @Nonnull
  private PackageContext createPackageContext(
      String basePath, Globber globber, ImmutableMap<String, Object> implicitlyLoadedSymbols) {
    return PackageContext.of(
        globber,
        options.getRawConfig(),
        PackageIdentifier.create(
            RepositoryName.createFromValidStrippedName(options.getCellName()),
            PathFragment.create(basePath)),
        eventHandler,
        implicitlyLoadedSymbols);
  }

  private Label createContainingLabel(String basePath) {
    return Label.createUnvalidated(
        PackageIdentifier.create(
            RepositoryName.createFromValidStrippedName(options.getCellName()),
            PathFragment.create(basePath)),
        "BUCK");
  }

  /**
   * @param containingPath the path of the build or extension file that has provided dependencies.
   * @param dependencies the list of extension dependencies that {@code containingPath} has.
   * @return transitive closure of all paths loaded during parsing of {@code containingPath}
   *     including {@code containingPath} itself as the first element.
   */
  private ImmutableList<String> toLoadedPaths(
      com.google.devtools.build.lib.vfs.Path containingPath,
      ImmutableList<ExtensionData> dependencies,
      @Nullable ExtensionData implicitLoadExtensionData) {
    // expected size is used to reduce the number of unnecessary resize invocations
    int expectedSize = 1;
    if (implicitLoadExtensionData != null) {
      expectedSize += implicitLoadExtensionData.getLoadTransitiveClosure().size();
    }
    for (int i = 0; i < dependencies.size(); ++i) {
      expectedSize += dependencies.get(i).getLoadTransitiveClosure().size();
    }
    ImmutableList.Builder<String> loadedPathsBuilder =
        ImmutableList.builderWithExpectedSize(expectedSize);
    // for loop is used instead of foreach to avoid iterator overhead, since it's a hot spot
    loadedPathsBuilder.add(containingPath.toString());
    for (int i = 0; i < dependencies.size(); ++i) {
      loadedPathsBuilder.addAll(dependencies.get(i).getLoadTransitiveClosure());
    }
    if (implicitLoadExtensionData != null) {
      loadedPathsBuilder.addAll(implicitLoadExtensionData.getLoadTransitiveClosure());
    }
    return loadedPathsBuilder.build();
  }

  /**
   * Reads file and returns abstract syntax tree for that file.
   *
   * @param path file path to read the data from.
   * @return abstract syntax tree; does not handle any errors.
   */
  @VisibleForTesting
  protected BuildFileAST readSkylarkAST(com.google.devtools.build.lib.vfs.Path path)
      throws IOException {
    return BuildFileAST.parseSkylarkFile(
        ParserInputSource.create(
            FileSystemUtils.readContent(path, StandardCharsets.UTF_8), path.asFragment()),
        eventHandler);
  }

  private BuildFileAST parseSkylarkFile(
      com.google.devtools.build.lib.vfs.Path path, Label containingLabel)
      throws BuildFileParseException, IOException {
    BuildFileAST result = astCache.getIfPresent(path);
    if (result == null) {
      try {
        result = readSkylarkAST(path);
      } catch (FileNotFoundException e) {
        throw BuildFileParseException.createForUnknownParseError(
            "%s cannot be loaded because it does not exist. It was referenced from %s",
            path, containingLabel);
      }
      if (result.containsErrors()) {
        throw BuildFileParseException.createForUnknownParseError(
            "Cannot parse %s.  It was referenced from %s", path, containingLabel);
      }
      astCache.put(path, result);
    }
    return result;
  }

  /**
   * Creates an {@code IncludesData} object from a {@code path}.
   *
   * @param loadImport an import label representing an extension to load.
   */
  private IncludesData loadInclude(LoadImport loadImport)
      throws IOException, BuildFileParseException, InterruptedException {
    Label label = loadImport.getLabel();
    com.google.devtools.build.lib.vfs.Path filePath = getImportPath(label, loadImport.getImport());

    BuildFileAST fileAst = parseSkylarkFile(filePath, loadImport.getContainingLabel());

    ImmutableList<IncludesData> dependencies =
        fileAst.getImports().isEmpty()
            ? ImmutableList.of()
            : loadIncludes(label, fileAst.getImports());

    return IncludesData.of(
        filePath, dependencies, toIncludedPaths(filePath.toString(), dependencies, null));
  }

  /** Collects all the included files identified by corresponding {@link SkylarkImport}s. */
  private ImmutableList<IncludesData> loadIncludes(
      Label containingLabel, ImmutableList<SkylarkImport> skylarkImports)
      throws BuildFileParseException, IOException, InterruptedException {
    Set<SkylarkImport> processed = new HashSet<>(skylarkImports.size());
    ImmutableList.Builder<IncludesData> includes =
        ImmutableList.builderWithExpectedSize(skylarkImports.size());
    // foreach is not used to avoid iterator overhead
    for (int i = 0; i < skylarkImports.size(); ++i) {
      SkylarkImport skylarkImport = skylarkImports.get(i);
      // sometimes users include the same extension multiple times...
      if (!processed.add(skylarkImport)) continue;
      LoadImport loadImport = LoadImport.of(containingLabel, skylarkImport);
      try {
        includes.add(includesDataCache.getUnchecked(loadImport));
      } catch (UncheckedExecutionException e) {
        propagateRootCause(e);
      }
    }
    return includes.build();
  }

  private ImmutableSet<String> toIncludedPaths(
      String containingPath,
      ImmutableList<IncludesData> dependencies,
      @Nullable ExtensionData implicitLoadExtensionData) {
    ImmutableSet.Builder<String> includedPathsBuilder = ImmutableSet.builder();
    includedPathsBuilder.add(containingPath);
    // for loop is used instead of foreach to avoid iterator overhead, since it's a hot spot
    for (int i = 0; i < dependencies.size(); ++i) {
      includedPathsBuilder.addAll(dependencies.get(i).getLoadTransitiveClosure());
    }
    if (implicitLoadExtensionData != null) {
      includedPathsBuilder.addAll(implicitLoadExtensionData.getLoadTransitiveClosure());
    }
    return includedPathsBuilder.build();
  }

  /** Loads all extensions identified by corresponding {@link SkylarkImport}s. */
  private ImmutableList<ExtensionData> loadExtensions(
      Label containingLabel, ImmutableList<SkylarkImport> skylarkImports)
      throws BuildFileParseException, IOException, InterruptedException {
    Set<SkylarkImport> processed = new HashSet<>(skylarkImports.size());
    ImmutableList.Builder<ExtensionData> extensions =
        ImmutableList.builderWithExpectedSize(skylarkImports.size());
    // foreach is not used to avoid iterator overhead
    for (int i = 0; i < skylarkImports.size(); ++i) {
      SkylarkImport skylarkImport = skylarkImports.get(i);
      // sometimes users include the same extension multiple times...
      if (!processed.add(skylarkImport)) continue;
      try {
        extensions.add(loadExtension(LoadImport.of(containingLabel, skylarkImport)));
      } catch (UncheckedExecutionException e) {
        propagateRootCause(e);
      }
    }
    return extensions.build();
  }

  /**
   * Propagates underlying parse exception from {@link UncheckedExecutionException}.
   *
   * <p>This is an unfortunate consequence of having to use {@link
   * LoadingCache#getUnchecked(Object)} in when using stream transformations :(
   *
   * <p>TODO(ttsugrii): the logic of extracting root causes to make them user-friendly should be
   * happening somewhere in {@link com.facebook.buck.cli.MainRunner}, since this behavior is not
   * unique to parsing.
   */
  private void propagateRootCause(UncheckedExecutionException e)
      throws IOException, InterruptedException {
    Throwable rootCause = Throwables.getRootCause(e);
    if (rootCause instanceof BuildFileParseException) {
      throw (BuildFileParseException) rootCause;
    }
    if (rootCause instanceof IOException) {
      throw (IOException) rootCause;
    }
    if (rootCause instanceof InterruptedException) {
      throw (InterruptedException) rootCause;
    }
    throw e;
  }

  /**
   * @return The map from skylark import string like {@code //pkg:build_rules.bzl} to an {@link
   *     Environment.Extension} for provided {@code dependencies}.
   */
  private ImmutableMap<String, Environment.Extension> toImportMap(
      ImmutableList<ExtensionData> dependencies,
      @Nullable ExtensionData implicitLoadExtensionData) {
    ImmutableMap.Builder<String, Environment.Extension> builder =
        ImmutableMap.builderWithExpectedSize(
            dependencies.size() + (implicitLoadExtensionData == null ? 0 : 1));
    // foreach is not used to avoid iterator overhead
    for (int i = 0; i < dependencies.size(); ++i) {
      ExtensionData extensionData = dependencies.get(i);
      builder.put(extensionData.getImportString(), extensionData.getExtension());
    }
    if (implicitLoadExtensionData != null) {
      builder.put(
          implicitLoadExtensionData.getImportString(), implicitLoadExtensionData.getExtension());
    }
    return builder.build();
  }

  /**
   * A struct like class to help loadExtension implementation to represent the state needed for load
   * of a single extension/file.
   */
  @VisibleForTesting
  class ExtensionLoadState {
    // Extension key being loaded.
    private final LoadImport load;
    // Path for the extension.
    private final com.google.devtools.build.lib.vfs.Path path;
    // List of dependencies this extension uses.
    private final Set<LoadImport> dependencies;
    // This extension AST.
    private @Nullable BuildFileAST ast;

    private ExtensionLoadState(
        LoadImport load, com.google.devtools.build.lib.vfs.Path extensionPath) {
      this.load = load;
      this.path = extensionPath;
      this.dependencies = new HashSet<LoadImport>();
      this.ast = null;
    }

    public com.google.devtools.build.lib.vfs.Path getPath() {
      return path;
    }

    // Methods to get/set/test AST for this extension.
    public boolean haveAST() {
      return ast != null;
    }

    public void setAST(BuildFileAST ast) {
      Preconditions.checkArgument(!haveAST(), "AST can be set only once");
      this.ast = ast;
    }

    public BuildFileAST getAST() {
      Preconditions.checkNotNull(ast);
      return ast;
    }

    // Adds a single dependency key for this extension.
    public void addDependency(LoadImport dependency) {
      dependencies.add(dependency);
    }

    // Returns the list of dependencies for this extension.
    public Set<LoadImport> getDependencies() {
      return dependencies;
    }

    // Returns the label of the file including this extension.
    public Label getParentLabel() {
      return load.getContainingLabel();
    }

    // Returns this extensions label.
    public Label getLabel() {
      return load.getLabel();
    }

    // Returns SkylarkImport object for this extension load
    public SkylarkImport getSkylarkImport() {
      return load.getImport();
    }
  }

  /*
   * Given the list of load imports, returns the list of extension data corresponding to those loads.
   * Requires all of the extensions are available in the extension data  cache.
   *
   * @param label {@link Label} identifying extension with dependencies
   * @param dependencies list of load import dependencies
   * @returns list of ExtensionData
   */
  private ImmutableList<ExtensionData> getDependenciesExtensionData(
      Label label, Set<LoadImport> dependencies) throws BuildFileParseException {
    ImmutableList.Builder<ExtensionData> depBuilder =
        ImmutableList.builderWithExpectedSize(dependencies.size());

    for (LoadImport dependency : dependencies) {
      ExtensionData extension =
          lookupExtensionForImport(
              getImportPath(dependency.getLabel(), dependency.getImport()),
              dependency.getImport().getImportString());

      if (extension == null) {
        throw BuildFileParseException.createForUnknownParseError(
            "Cannot evaluate extension file %s; missing dependency is %s",
            label, dependency.getLabel());
      }

      depBuilder.add(extension);
    }

    return depBuilder.build();
  }

  /**
   * Retrieves extension data from the cache, and returns a copy suitable for the specified skylark
   * import string.
   *
   * @param path a path for the extension to lookup
   * @param importString a skylark import string for this extension load
   * @return {@link ExtensionData} suitable for the requested extension and importString, or null if
   *     no such extension found.
   */
  private @Nullable ExtensionData lookupExtensionForImport(
      com.google.devtools.build.lib.vfs.Path path, String importString) {
    ExtensionData ext = extensionDataCache.getIfPresent(path);
    return ext == null ? ext : ExtensionData.copyOf(ext).withImportString(importString);
  }

  /**
   * Loads extensions abstract syntax tree if needed.
   *
   * @param load {@link ExtensionLoadState} representing the extension being loaded.
   * @returns true if AST was loaded, false otherwise.
   */
  private boolean maybeLoadAST(ExtensionLoadState load) throws IOException {
    if (load.haveAST()) {
      return false;
    }
    load.setAST(parseSkylarkFile(load.getPath(), load.getParentLabel()));
    return true;
  }

  /**
   * Updates extension load state with the list of its dependencies, and schedules any unsatisfied
   * dependencies to be loaded by adding those dependencies to the work queue.
   *
   * @param load {@link ExtensionLoadState} representing extension currently loaded
   * @param queue a work queue of extensions that still need to be loaded.
   * @return true if this extension has any unsatisfied dependencies
   */
  private boolean processExtensionDependencies(
      ExtensionLoadState load, ArrayDeque<ExtensionLoadState> queue) {
    // Update this load state with the list of its dependencies.
    // Schedule missing dependencies to be loaded.
    boolean haveUnsatisfiedDeps = false;
    for (int i = 0; i < load.getAST().getImports().size(); ++i) {
      LoadImport dependency = LoadImport.of(load.getLabel(), load.getAST().getImports().get(i));

      // Record dependency for this load.
      load.addDependency(dependency);
      com.google.devtools.build.lib.vfs.Path extensionPath =
          getImportPath(dependency.getLabel(), dependency.getImport());
      if (extensionDataCache.getIfPresent(extensionPath) == null) {
        // Schedule dependency to be loaded if needed.
        haveUnsatisfiedDeps = true;
        queue.push(new ExtensionLoadState(dependency, extensionPath));
      }
    }
    return haveUnsatisfiedDeps;
  }

  /**
   * Given fully loaded extension represented by {@link ExtensionLoadState}, evaluates extension and
   * returns {@link ExtensionData}
   *
   * @param load {@link ExtensionLoadState} representing loaded extension
   * @returns {@link ExtensionData} for this extions.
   */
  @VisibleForTesting
  protected ExtensionData buildExtensionData(ExtensionLoadState load) throws InterruptedException {
    ImmutableList<ExtensionData> dependencies =
        getDependenciesExtensionData(load.getLabel(), load.getDependencies());
    Extension loadedExtension = null;
    try (Mutability mutability = Mutability.create("importing extension")) {
      Environment.Builder envBuilder =
          Environment.builder(mutability)
              .setEventHandler(eventHandler)
              .setGlobals(buckGlobals.getBuckLoadContextGlobals());
      envBuilder.setImportedExtensions(toImportMap(dependencies, null));

      // Create this extension.
      Environment extensionEnv =
          envBuilder.setSemantics(StarlarkSemantics.DEFAULT_SEMANTICS).build();
      boolean success = load.getAST().exec(extensionEnv, eventHandler);
      if (!success) {
        throw BuildFileParseException.createForUnknownParseError(
            "Cannot evaluate extension %s referenced from %s",
            load.getLabel(), load.getParentLabel());
      }
      loadedExtension = new Extension(extensionEnv);
    }

    return ExtensionData.of(
        loadedExtension,
        load.getPath(),
        dependencies,
        load.getSkylarkImport().getImportString(),
        toLoadedPaths(load.getPath(), dependencies, null));
  }

  /**
   * Creates an extension from a {@code path}.
   *
   * @param loadImport an import label representing an extension to load.
   */
  private ExtensionData loadExtension(LoadImport loadImport)
      throws IOException, BuildFileParseException, InterruptedException {
    ExtensionData extension = null;
    ArrayDeque<ExtensionLoadState> work = new ArrayDeque<>();
    work.push(
        new ExtensionLoadState(
            loadImport, getImportPath(loadImport.getLabel(), loadImport.getImport())));

    while (!work.isEmpty()) {
      ExtensionLoadState load = work.peek();
      extension =
          lookupExtensionForImport(load.getPath(), load.getSkylarkImport().getImportString());

      if (extension != null) {
        // It's possible that some lower level dependencies already loaded
        // this work item.  We're done with it, so pop the queue.
        work.pop();
        continue;
      }

      // Load BuildFileAST if needed.
      boolean astLoaded = maybeLoadAST(load);
      boolean haveUnsatisfiedDeps = astLoaded && processExtensionDependencies(load, work);

      // NB: If we have unsatisfied dependencies, we don't do anything;
      // more importantly we do not pop the work queue in this case.
      // This load is kept on the queue until all of its dependencies are satisfied.

      if (!haveUnsatisfiedDeps) {
        // We are done with this load; build it and cache it.
        work.removeFirst();
        extension = buildExtensionData(load);
        extensionDataCache.put(load.getPath(), extension);
      }
    }

    Preconditions.checkNotNull(extension);
    return extension;
  }

  /**
   * @return The path to a Skylark extension. For example, for {@code load("//pkg:foo.bzl", "foo")}
   *     import it would return {@code /path/to/repo/pkg/foo.bzl} and for {@code
   *     load("@repo//pkg:foo.bzl", "foo")} it would return {@code /repo/pkg/foo.bzl} assuming that
   *     {@code repo} is located at {@code /repo}.
   */
  private com.google.devtools.build.lib.vfs.Path getImportPath(
      Label containingLabel, SkylarkImport skylarkImport) throws BuildFileParseException {
    if (isRelativeLoad(skylarkImport) && skylarkImport.getImportString().contains("/")) {
      throw BuildFileParseException.createForUnknownParseError(
          "Relative loads work only for files in the same directory but "
              + skylarkImport.getImportString()
              + " is trying to load a file from a nested directory. "
              + "Please use absolute label instead ([cell]//pkg[/pkg]:target).");
    }
    PathFragment relativeExtensionPath = containingLabel.toPathFragment();
    RepositoryName repository = containingLabel.getPackageIdentifier().getRepository();
    if (repository.isMain()) {
      return fileSystem.getPath(
          options.getProjectRoot().resolve(relativeExtensionPath.toString()).toString());
    }
    // Skylark repositories have an "@" prefix, but Buck roots do not, so ignore it
    String repositoryName = repository.getName().substring(1);
    @Nullable Path repositoryPath = options.getCellRoots().get(repositoryName);
    if (repositoryPath == null) {
      throw BuildFileParseException.createForUnknownParseError(
          skylarkImport.getImportString() + " references an unknown repository " + repositoryName);
    }
    return fileSystem.getPath(repositoryPath.resolve(relativeExtensionPath.toString()).toString());
  }

  private boolean isRelativeLoad(SkylarkImport skylarkImport) {
    return skylarkImport.getImportString().startsWith(":");
  }

  /**
   * @return The path path of the provided {@code buildFile}. For example, for {@code
   *     /Users/foo/repo/src/bar/BUCK}, where {@code /Users/foo/repo} is the path to the repo, it
   *     would return {@code src/bar}.
   */
  private String getBasePath(Path buildFile) {
    return Optional.ofNullable(options.getProjectRoot().relativize(buildFile).getParent())
        .map(PathFormatter::pathWithUnixSeparators)
        .orElse("");
  }

  @Override
  public void reportProfile() {
    // this method is a noop since Skylark profiling is completely orthogonal to parsing and is
    // controlled by com.google.devtools.build.lib.profiler.Profiler
  }

  @Override
  public ImmutableSortedSet<String> getIncludedFiles(Path buildFile)
      throws BuildFileParseException, InterruptedException, IOException {
    com.google.devtools.build.lib.vfs.Path buildFilePath = fileSystem.getPath(buildFile.toString());

    String basePath = getBasePath(buildFile);
    Label containingLabel = createContainingLabel(basePath);
    ImplicitlyLoadedExtension implicitLoad =
        loadImplicitExtension(buildFile.getFileSystem().getPath(basePath), containingLabel);
    BuildFileAST buildFileAst = parseBuildFile(buildFilePath, containingLabel);
    ImmutableList<IncludesData> dependencies =
        loadIncludes(containingLabel, buildFileAst.getImports());

    // it might be potentially faster to keep sorted sets for each dependency separately and just
    // merge sorted lists as we aggregate transitive close up
    // But Guava does not seem to have a built-in way of merging sorted lists/sets
    return ImmutableSortedSet.copyOf(
        toIncludedPaths(buildFile.toString(), dependencies, implicitLoad.getExtensionData()));
  }

  @Override
  public boolean globResultsMatchCurrentState(
      Path buildFile, ImmutableList<GlobSpecWithResult> existingGlobsWithResults)
      throws IOException, InterruptedException {
    CachingGlobber globber = newGlobber(buildFile);
    for (GlobSpecWithResult globSpecWithResult : existingGlobsWithResults) {
      final GlobSpec globSpec = globSpecWithResult.getGlobSpec();
      Set<String> globResult =
          globber.run(
              globSpec.getInclude(), globSpec.getExclude(), globSpec.getExcludeDirectories());
      if (!globSpecWithResult.getFilePaths().equals(globResult)) {
        return false;
      }
    }

    return true;
  }

  @Override
  public void close() throws BuildFileParseException {
    // nothing to do
  }

  /**
   * A value object for information about load function import, since {@link SkylarkImport} does not
   * provide enough context. For instance, the same {@link SkylarkImport} can represent different
   * logical imports depending on which repository it is resolved in.
   */
  @Value.Immutable(builder = false)
  @BuckStyleImmutable
  abstract static class AbstractLoadImport {
    /** Returns a label of the file containing this import. */
    @Value.Parameter
    abstract Label getContainingLabel();

    /** Returns a Skylark import. */
    @Value.Parameter
    abstract SkylarkImport getImport();

    /** Returns a label of current import file. */
    Label getLabel() {
      return getImport().getLabel(getContainingLabel());
    }
  }

  /**
   * A value object for information about implicit loads. This allows us to both validate implicit
   * import information, and return some additional information needed to setup build file
   * environments in one swoop.
   */
  @Value.Immutable(builder = false, copy = false)
  @BuckStyleImmutable
  abstract static class AbstractImplicitlyLoadedExtension {
    @Value.Parameter
    abstract @Nullable ExtensionData getExtensionData();

    @Value.Parameter
    abstract ImmutableMap<String, Object> getLoadedSymbols();

    @Value.Lazy
    static ImplicitlyLoadedExtension empty() {
      return ImplicitlyLoadedExtension.of(null, ImmutableMap.of());
    }
  }
}
