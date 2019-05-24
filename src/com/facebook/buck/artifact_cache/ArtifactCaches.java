/*
 * Copyright 2013-present Facebook, Inc.
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
package com.facebook.buck.artifact_cache;

import static com.facebook.buck.artifact_cache.config.ArtifactCacheMode.CacheType.local;
import static com.facebook.buck.artifact_cache.config.ArtifactCacheMode.CacheType.remote;

import com.facebook.buck.artifact_cache.config.ArtifactCacheBuckConfig;
import com.facebook.buck.artifact_cache.config.ArtifactCacheEntries;
import com.facebook.buck.artifact_cache.config.ArtifactCacheMode;
import com.facebook.buck.artifact_cache.config.ArtifactCacheMode.CacheType;
import com.facebook.buck.artifact_cache.config.DirCacheEntry;
import com.facebook.buck.artifact_cache.config.HttpCacheEntry;
import com.facebook.buck.artifact_cache.config.MultiFetchType;
import com.facebook.buck.artifact_cache.config.SQLiteCacheEntry;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.TargetConfigurationSerializer;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.NetworkEvent.BytesReceivedEvent;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.slb.HttpLoadBalancer;
import com.facebook.buck.slb.HttpService;
import com.facebook.buck.slb.LoadBalancedService;
import com.facebook.buck.slb.RetryingHttpService;
import com.facebook.buck.slb.SingleUriService;
import com.facebook.buck.support.bgtasks.BackgroundTask;
import com.facebook.buck.support.bgtasks.ImmutableBackgroundTask;
import com.facebook.buck.support.bgtasks.TaskAction;
import com.facebook.buck.support.bgtasks.TaskManagerCommandScope;
import com.facebook.buck.support.bgtasks.Timeout;
import com.facebook.buck.util.timing.DefaultClock;
import com.google.common.base.CharMatcher;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.net.ssl.HostnameVerifier;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.tls.HandshakeCertificates;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import okio.Source;

/** Creates instances of the {@link ArtifactCache}. */
public class ArtifactCaches implements ArtifactCacheFactory, AutoCloseable {

  private static final Logger LOG = Logger.get(ArtifactCaches.class);
  private static final int TIMEOUT_SECONDS = 60;

  private final ArtifactCacheBuckConfig buckConfig;
  private final BuckEventBus buckEventBus;
  private final Function<String, UnconfiguredBuildTargetView> unconfiguredBuildTargetFactory;
  private final TargetConfigurationSerializer targetConfigurationSerializer;
  private final ProjectFilesystem projectFilesystem;
  private final Optional<String> wifiSsid;
  private final ListeningExecutorService httpWriteExecutorService;
  private final ListeningExecutorService httpFetchExecutorService;
  private final ListeningExecutorService downloadHeavyBuildHttpFetchExecutorService;
  private List<ArtifactCache> artifactCaches = new ArrayList<>();
  private final ListeningExecutorService dirWriteExecutorService;
  private final TaskManagerCommandScope managerScope;
  private final String producerId;
  private final String producerHostname;
  private final Optional<ClientCertificateHandler> clientCertificateHandler;

  /** {@link TaskAction} implementation for {@link ArtifactCaches}. */
  static class ArtifactCachesCloseAction implements TaskAction<List<ArtifactCache>> {
    @Override
    public void run(List<ArtifactCache> artifactCaches) {
      for (ArtifactCache cache : artifactCaches) {
        try {
          cache.close();
        } catch (Exception e) {
          LOG.warn(e, "Exception when closing %s.", cache);
        }
      }
    }
  }

  @Override
  public void close() {
    // We clean up beyond client connection lifetime since it can take a
    // long time to stat and cleanup large disk artifact cache directories
    // See https://github.com/facebook/buck/issues/1842
    BackgroundTask<List<ArtifactCache>> closeTask =
        ImmutableBackgroundTask.<List<ArtifactCache>>builder()
            .setAction(new ArtifactCachesCloseAction())
            .setActionArgs(artifactCaches)
            .setName("ArtifactCaches_close")
            .setTimeout(Timeout.of(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            .build();
    managerScope.schedule(closeTask);

    buckEventBus.post(HttpArtifactCacheEvent.newShutdownEvent());
  }

  private interface NetworkCacheFactory {
    ArtifactCache newInstance(NetworkCacheArgs args);
  }

  /**
   * Creates a new instance of the cache factory for use during a build.
   *
   * @param buckConfig describes what kind of cache to create
   * @param buckEventBus event bus
   * @param projectFilesystem filesystem to store files on
   * @param wifiSsid current WiFi ssid to decide if we want the http cache or not
   * @param dirWriteExecutorService executor service used for dir cache stores
   * @param producerId free-form identifier of a user or machine uploading artifacts, can be used on
   *     cache server side for monitoring
   * @param clientCertificateHandler container for client certificate information
   */
  public ArtifactCaches(
      ArtifactCacheBuckConfig buckConfig,
      BuckEventBus buckEventBus,
      Function<String, UnconfiguredBuildTargetView> unconfiguredBuildTargetFactory,
      TargetConfigurationSerializer targetConfigurationSerializer,
      ProjectFilesystem projectFilesystem,
      Optional<String> wifiSsid,
      ListeningExecutorService httpWriteExecutorService,
      ListeningExecutorService httpFetchExecutorService,
      ListeningExecutorService downloadHeavyBuildHttpFetchExecutorService,
      ListeningExecutorService dirWriteExecutorService,
      TaskManagerCommandScope managerScope,
      String producerId,
      String producerHostname,
      Optional<ClientCertificateHandler> clientCertificateHandler) {
    this.buckConfig = buckConfig;
    this.buckEventBus = buckEventBus;
    this.unconfiguredBuildTargetFactory = unconfiguredBuildTargetFactory;
    this.targetConfigurationSerializer = targetConfigurationSerializer;
    this.projectFilesystem = projectFilesystem;
    this.wifiSsid = wifiSsid;
    this.httpWriteExecutorService = httpWriteExecutorService;
    this.httpFetchExecutorService = httpFetchExecutorService;
    this.downloadHeavyBuildHttpFetchExecutorService = downloadHeavyBuildHttpFetchExecutorService;
    this.dirWriteExecutorService = dirWriteExecutorService;
    this.managerScope = managerScope;
    this.producerId = producerId;
    this.producerHostname = producerHostname;
    this.clientCertificateHandler = clientCertificateHandler;
  }

  private static Request.Builder addHeadersToBuilder(
      Request.Builder builder, ImmutableMap<String, String> headers) {
    ImmutableSet<Map.Entry<String, String>> entries = headers.entrySet();
    for (Map.Entry<String, String> header : entries) {
      builder.addHeader(header.getKey(), header.getValue());
    }
    return builder;
  }

  @Override
  public ArtifactCache newInstance() {
    return newInstance(false, false);
  }

  /**
   * Creates a new instance of the cache for use during a build.
   *
   * @param distributedBuildModeEnabled true if this is a distributed build
   * @param isDownloadHeavyBuild true if creating cache connector for download heavy build
   * @return ArtifactCache instance
   */
  @Override
  public ArtifactCache newInstance(
      boolean distributedBuildModeEnabled, boolean isDownloadHeavyBuild) {
    return newInstanceInternal(
        ImmutableSet.of(), distributedBuildModeEnabled, isDownloadHeavyBuild);
  }

  @Override
  public ArtifactCache remoteOnlyInstance(
      boolean distributedBuildModeEnabled, boolean isDownloadHeavyBuild) {
    return newInstanceInternal(
        ImmutableSet.of(local), distributedBuildModeEnabled, isDownloadHeavyBuild);
  }

  @Override
  public ArtifactCache localOnlyInstance(
      boolean distributedBuildModeEnabled, boolean isDownloadHeavyBuild) {
    return newInstanceInternal(
        ImmutableSet.of(remote), distributedBuildModeEnabled, isDownloadHeavyBuild);
  }

  private ArtifactCache newInstanceInternal(
      ImmutableSet<CacheType> cacheTypeBlacklist,
      boolean distributedBuildModeEnabled,
      boolean isDownloadHeavyBuild) {
    ArtifactCacheConnectEvent.Started started = ArtifactCacheConnectEvent.started();
    buckEventBus.post(started);

    ArtifactCache artifactCache =
        newInstanceInternal(
            buckConfig,
            buckEventBus,
            unconfiguredBuildTargetFactory,
            targetConfigurationSerializer,
            projectFilesystem,
            wifiSsid,
            httpWriteExecutorService,
            isDownloadHeavyBuild
                ? downloadHeavyBuildHttpFetchExecutorService
                : httpFetchExecutorService,
            dirWriteExecutorService,
            cacheTypeBlacklist,
            distributedBuildModeEnabled,
            producerId,
            producerHostname,
            clientCertificateHandler);

    artifactCaches.add(artifactCache);

    buckEventBus.post(ArtifactCacheConnectEvent.finished(started));
    return artifactCache;
  }

  @Override
  public ArtifactCacheFactory cloneWith(BuckConfig newConfig) {
    return new ArtifactCaches(
        new ArtifactCacheBuckConfig(newConfig),
        buckEventBus,
        unconfiguredBuildTargetFactory,
        targetConfigurationSerializer,
        projectFilesystem,
        wifiSsid,
        httpWriteExecutorService,
        httpFetchExecutorService,
        downloadHeavyBuildHttpFetchExecutorService,
        dirWriteExecutorService,
        managerScope,
        producerId,
        producerHostname,
        clientCertificateHandler);
  }

  /**
   * Creates a new instance of the cache to be used to serve the dircache from the WebServer.
   *
   * @param buckConfig describes how to configure te cache
   * @param projectFilesystem filesystem to store files on
   * @return a cache
   */
  public static Optional<ArtifactCache> newServedCache(
      ArtifactCacheBuckConfig buckConfig,
      Function<String, UnconfiguredBuildTargetView> unconfiguredBuildTargetFactory,
      TargetConfigurationSerializer targetConfigurationSerializer,
      ProjectFilesystem projectFilesystem) {
    return buckConfig
        .getServedLocalCache()
        .map(
            input ->
                createDirArtifactCache(
                    Optional.empty(),
                    input,
                    unconfiguredBuildTargetFactory,
                    targetConfigurationSerializer,
                    projectFilesystem,
                    MoreExecutors.newDirectExecutorService()));
  }

  private static ArtifactCache newInstanceInternal(
      ArtifactCacheBuckConfig buckConfig,
      BuckEventBus buckEventBus,
      Function<String, UnconfiguredBuildTargetView> unconfiguredBuildTargetFactory,
      TargetConfigurationSerializer targetConfigurationSerializer,
      ProjectFilesystem projectFilesystem,
      Optional<String> wifiSsid,
      ListeningExecutorService httpWriteExecutorService,
      ListeningExecutorService httpFetchExecutorService,
      ListeningExecutorService dirWriteExecutorService,
      ImmutableSet<CacheType> cacheTypeBlacklist,
      boolean distributedBuildModeEnabled,
      String producerId,
      String producerHostname,
      Optional<ClientCertificateHandler> clientCertificateHandler) {
    ImmutableSet<ArtifactCacheMode> modes = buckConfig.getArtifactCacheModes();
    if (modes.isEmpty()) {
      return new NoopArtifactCache();
    }
    ArtifactCacheEntries cacheEntries = buckConfig.getCacheEntries();
    ImmutableList.Builder<ArtifactCache> builder = ImmutableList.builder();
    for (ArtifactCacheMode mode : modes) {
      if (cacheTypeBlacklist.contains(mode.getCacheType())) {
        continue;
      }

      switch (mode) {
        case unknown:
          break;
        case dir:
          initializeDirCaches(
              cacheEntries,
              buckEventBus,
              unconfiguredBuildTargetFactory,
              targetConfigurationSerializer,
              projectFilesystem,
              builder,
              dirWriteExecutorService);
          break;
        case http:
          initializeDistributedCaches(
              cacheEntries,
              buckConfig,
              buckEventBus,
              unconfiguredBuildTargetFactory,
              targetConfigurationSerializer,
              projectFilesystem,
              wifiSsid,
              httpWriteExecutorService,
              httpFetchExecutorService,
              builder,
              HttpArtifactCache::new,
              mode,
              clientCertificateHandler);
          break;
        case sqlite:
          initializeSQLiteCaches(
              cacheEntries,
              buckEventBus,
              unconfiguredBuildTargetFactory,
              targetConfigurationSerializer,
              projectFilesystem,
              builder);
          break;
        case thrift_over_http:
          Preconditions.checkArgument(
              buckConfig.getHybridThriftEndpoint().isPresent(),
              "Hybrid thrift endpoint path is mandatory for the ThriftArtifactCache.");
          initializeDistributedCaches(
              cacheEntries,
              buckConfig,
              buckEventBus,
              unconfiguredBuildTargetFactory,
              targetConfigurationSerializer,
              projectFilesystem,
              wifiSsid,
              httpWriteExecutorService,
              httpFetchExecutorService,
              builder,
              (args) ->
                  new ThriftArtifactCache(
                      args,
                      buckConfig.getHybridThriftEndpoint().get(),
                      distributedBuildModeEnabled,
                      buckEventBus.getBuildId(),
                      getMultiFetchLimit(buckConfig),
                      buckConfig.getHttpFetchConcurrency(),
                      buckConfig.getMultiCheckEnabled(),
                      producerId,
                      producerHostname),
              mode,
              clientCertificateHandler);
          break;
      }
    }
    ImmutableList<ArtifactCache> artifactCaches = builder.build();
    ArtifactCache result;

    if (artifactCaches.size() == 1) {
      // Don't bother wrapping a single artifact cache
      result = artifactCaches.get(0);
    } else {
      result = new MultiArtifactCache(artifactCaches);
    }

    // Always support reading two-level cache stores (in case we performed any in the past).
    result =
        new TwoLevelArtifactCacheDecorator(
            result,
            projectFilesystem,
            buckEventBus,
            buckConfig.getTwoLevelCachingEnabled(),
            buckConfig.getTwoLevelCachingMinimumSize(),
            buckConfig.getTwoLevelCachingMaximumSize());

    return result;
  }

  private static void initializeDirCaches(
      ArtifactCacheEntries artifactCacheEntries,
      BuckEventBus buckEventBus,
      Function<String, UnconfiguredBuildTargetView> unconfiguredBuildTargetFactory,
      TargetConfigurationSerializer targetConfigurationSerializer,
      ProjectFilesystem projectFilesystem,
      ImmutableList.Builder<ArtifactCache> builder,
      ListeningExecutorService storeExecutorService) {
    for (DirCacheEntry cacheEntry : artifactCacheEntries.getDirCacheEntries()) {
      builder.add(
          createDirArtifactCache(
              Optional.ofNullable(buckEventBus),
              cacheEntry,
              unconfiguredBuildTargetFactory,
              targetConfigurationSerializer,
              projectFilesystem,
              storeExecutorService));
    }
  }

  private static void initializeDistributedCaches(
      ArtifactCacheEntries artifactCacheEntries,
      ArtifactCacheBuckConfig buckConfig,
      BuckEventBus buckEventBus,
      Function<String, UnconfiguredBuildTargetView> unconfiguredBuildTargetFactory,
      TargetConfigurationSerializer targetConfigurationSerializer,
      ProjectFilesystem projectFilesystem,
      Optional<String> wifiSsid,
      ListeningExecutorService httpWriteExecutorService,
      ListeningExecutorService httpFetchExecutorService,
      ImmutableList.Builder<ArtifactCache> builder,
      NetworkCacheFactory factory,
      ArtifactCacheMode cacheMode,
      Optional<ClientCertificateHandler> clientCertificateHandler) {
    for (HttpCacheEntry cacheEntry : artifactCacheEntries.getHttpCacheEntries()) {
      if (!cacheEntry.isWifiUsableForDistributedCache(wifiSsid)) {
        buckEventBus.post(
            ConsoleEvent.warning(
                String.format(
                    "Remote Buck cache is disabled because the WiFi (%s) is not usable.",
                    wifiSsid)));
        LOG.warn("HTTP cache is disabled because WiFi is not usable.");
        continue;
      }

      builder.add(
          createRetryingArtifactCache(
              cacheEntry,
              buckConfig.getHostToReportToRemoteCacheServer(),
              buckEventBus,
              unconfiguredBuildTargetFactory,
              targetConfigurationSerializer,
              projectFilesystem,
              httpWriteExecutorService,
              httpFetchExecutorService,
              buckConfig,
              factory,
              cacheMode,
              clientCertificateHandler));
    }
  }

  private static void initializeSQLiteCaches(
      ArtifactCacheEntries artifactCacheEntries,
      BuckEventBus buckEventBus,
      Function<String, UnconfiguredBuildTargetView> unconfiguredBuildTargetFactory,
      TargetConfigurationSerializer targetConfigurationSerializer,
      ProjectFilesystem projectFilesystem,
      ImmutableList.Builder<ArtifactCache> builder) {
    artifactCacheEntries
        .getSQLiteCacheEntries()
        .forEach(
            cacheEntry ->
                builder.add(
                    createSQLiteArtifactCache(
                        buckEventBus,
                        cacheEntry,
                        unconfiguredBuildTargetFactory,
                        targetConfigurationSerializer,
                        projectFilesystem)));
  }

  private static ArtifactCache createDirArtifactCache(
      Optional<BuckEventBus> buckEventBus,
      DirCacheEntry dirCacheConfig,
      Function<String, UnconfiguredBuildTargetView> unconfiguredBuildTargetFactory,
      TargetConfigurationSerializer targetConfigurationSerializer,
      ProjectFilesystem projectFilesystem,
      ListeningExecutorService storeExecutorService) {
    Path cacheDir = dirCacheConfig.getCacheDir();
    try {
      DirArtifactCache dirArtifactCache =
          new DirArtifactCache(
              "dir",
              projectFilesystem,
              cacheDir,
              dirCacheConfig.getCacheReadMode(),
              dirCacheConfig.getMaxSizeBytes(),
              storeExecutorService);

      if (!buckEventBus.isPresent()) {
        return dirArtifactCache;
      }

      return new LoggingArtifactCacheDecorator(
          buckEventBus.get(),
          dirArtifactCache,
          new DirArtifactCacheEvent.DirArtifactCacheEventFactory(
              unconfiguredBuildTargetFactory, targetConfigurationSerializer));

    } catch (IOException e) {
      throw new HumanReadableException(
          e, "Failure initializing artifact cache directory: %s", cacheDir);
    }
  }

  private static ArtifactCache createRetryingArtifactCache(
      HttpCacheEntry cacheDescription,
      String hostToReportToRemote,
      BuckEventBus buckEventBus,
      Function<String, UnconfiguredBuildTargetView> unconfiguredBuildTargetFactory,
      TargetConfigurationSerializer targetConfigurationSerializer,
      ProjectFilesystem projectFilesystem,
      ListeningExecutorService httpWriteExecutorService,
      ListeningExecutorService httpFetchExecutorService,
      ArtifactCacheBuckConfig config,
      NetworkCacheFactory factory,
      ArtifactCacheMode cacheMode,
      Optional<ClientCertificateHandler> clientCertificateHandler) {
    ArtifactCache cache =
        createHttpArtifactCache(
            cacheDescription,
            hostToReportToRemote,
            buckEventBus,
            unconfiguredBuildTargetFactory,
            targetConfigurationSerializer,
            projectFilesystem,
            httpWriteExecutorService,
            httpFetchExecutorService,
            config,
            factory,
            cacheMode,
            clientCertificateHandler);
    return new RetryingCacheDecorator(cacheMode, cache, config.getMaxFetchRetries(), buckEventBus);
  }

  private static ArtifactCache createHttpArtifactCache(
      HttpCacheEntry cacheDescription,
      String hostToReportToRemote,
      BuckEventBus buckEventBus,
      Function<String, UnconfiguredBuildTargetView> unconfiguredBuildTargetFactory,
      TargetConfigurationSerializer targetConfigurationSerializer,
      ProjectFilesystem projectFilesystem,
      ListeningExecutorService httpWriteExecutorService,
      ListeningExecutorService httpFetchExecutorService,
      ArtifactCacheBuckConfig config,
      NetworkCacheFactory factory,
      ArtifactCacheMode cacheMode,
      Optional<ClientCertificateHandler> clientCertificateHandler) {

    // Setup the default client to use.
    OkHttpClient.Builder storeClientBuilder = new OkHttpClient.Builder();
    storeClientBuilder
        .networkInterceptors()
        .add(
            chain ->
                chain.proceed(
                    chain
                        .request()
                        .newBuilder()
                        .addHeader(
                            "X-BuckCache-User",
                            stripNonAscii(System.getProperty("user.name", "<unknown>")))
                        .addHeader("X-BuckCache-Host", stripNonAscii(hostToReportToRemote))
                        .build()));
    int connectTimeoutSeconds = cacheDescription.getConnectTimeoutSeconds();
    int readTimeoutSeconds = cacheDescription.getReadTimeoutSeconds();
    int writeTimeoutSeconds = cacheDescription.getWriteTimeoutSeconds();
    setTimeouts(storeClientBuilder, connectTimeoutSeconds, readTimeoutSeconds, writeTimeoutSeconds);
    storeClientBuilder.connectionPool(
        new ConnectionPool(
            /* maxIdleConnections */ (int) config.getThreadPoolSize(),
            /* keepAliveDurationMs */ config.getThreadPoolKeepAliveDurationMillis(),
            TimeUnit.MILLISECONDS));

    // The artifact cache effectively only connects to a single host at a time. We should allow as
    // many concurrent connections to that host as we allow threads.
    Dispatcher dispatcher = new Dispatcher();
    dispatcher.setMaxRequestsPerHost((int) config.getThreadPoolSize());
    storeClientBuilder.dispatcher(dispatcher);

    ImmutableMap<String, String> readHeaders = cacheDescription.getReadHeaders();
    ImmutableMap<String, String> writeHeaders = cacheDescription.getWriteHeaders();

    // If write headers are specified, add them to every default client request.
    if (!writeHeaders.isEmpty()) {
      storeClientBuilder
          .networkInterceptors()
          .add(
              chain ->
                  chain.proceed(
                      addHeadersToBuilder(chain.request().newBuilder(), writeHeaders).build()));
    }

    // Add client certificate information if present
    clientCertificateHandler.ifPresent(
        handler -> {
          HandshakeCertificates certificates = handler.getHandshakeCertificates();
          storeClientBuilder.sslSocketFactory(
              certificates.sslSocketFactory(), certificates.trustManager());
          handler.getHostnameVerifier().ifPresent(storeClientBuilder::hostnameVerifier);
        });

    OkHttpClient storeClient = storeClientBuilder.build();

    // For fetches, use a client with a read timeout.
    OkHttpClient.Builder fetchClientBuilder = storeClient.newBuilder();
    setTimeouts(fetchClientBuilder, connectTimeoutSeconds, readTimeoutSeconds, writeTimeoutSeconds);

    // If read headers are specified, add them to every read client request.
    if (!readHeaders.isEmpty()) {
      fetchClientBuilder
          .networkInterceptors()
          .add(
              chain ->
                  chain.proceed(
                      addHeadersToBuilder(chain.request().newBuilder(), readHeaders).build()));
    }

    Optional<HandshakeCertificates> handshakeCertificates = Optional.empty();
    Optional<HostnameVerifier> hostnameVerifier = Optional.empty();
    if (config.getClientTlsForSlb() && clientCertificateHandler.isPresent()) {
      handshakeCertificates =
          Optional.of(clientCertificateHandler.get().getHandshakeCertificates());
      hostnameVerifier = clientCertificateHandler.get().getHostnameVerifier();
    }

    fetchClientBuilder
        .networkInterceptors()
        .add(
            (chain -> {
              Response originalResponse = chain.proceed(chain.request());
              return originalResponse
                  .newBuilder()
                  .body(new ProgressResponseBody(originalResponse.body(), buckEventBus))
                  .build();
            }));
    OkHttpClient fetchClient = fetchClientBuilder.build();

    HttpService fetchService;
    HttpService storeService;
    switch (config.getLoadBalancingType()) {
      case CLIENT_SLB:
        HttpLoadBalancer clientSideSlb =
            config
                .getSlbConfig()
                .createClientSideSlb(
                    new DefaultClock(), buckEventBus, handshakeCertificates, hostnameVerifier);
        fetchService =
            new RetryingHttpService(
                buckEventBus,
                new LoadBalancedService(clientSideSlb, fetchClient, buckEventBus),
                "buck_cache_fetch_request_http_retries",
                config.getMaxFetchRetries());
        storeService =
            new RetryingHttpService(
                buckEventBus,
                new LoadBalancedService(clientSideSlb, storeClient, buckEventBus),
                "buck_cache_store_request_http_retries",
                config.getMaxStoreAttempts() - 1 /* maxNumberOfRetries */,
                config.getStoreRetryIntervalMillis());

        break;

      case SINGLE_SERVER:
        URI url = cacheDescription.getUrl();
        fetchService = new SingleUriService(url, fetchClient);
        storeService = new SingleUriService(url, storeClient);
        break;

      default:
        throw new IllegalArgumentException(
            "Unknown HttpLoadBalancer type: " + config.getLoadBalancingType());
    }

    return factory.newInstance(
        NetworkCacheArgs.builder()
            .setCacheName(cacheMode.name())
            .setCacheMode(cacheMode)
            .setRepository(config.getRepository())
            .setScheduleType(config.getScheduleType())
            .setFetchClient(fetchService)
            .setStoreClient(storeService)
            .setCacheReadMode(cacheDescription.getCacheReadMode())
            .setUnconfiguredBuildTargetFactory(unconfiguredBuildTargetFactory)
            .setTargetConfigurationSerializer(targetConfigurationSerializer)
            .setProjectFilesystem(projectFilesystem)
            .setBuckEventBus(buckEventBus)
            .setHttpWriteExecutorService(httpWriteExecutorService)
            .setHttpFetchExecutorService(httpFetchExecutorService)
            .setErrorTextTemplate(cacheDescription.getErrorMessageFormat())
            .setErrorTextLimit(cacheDescription.getErrorMessageLimit())
            .build());
  }

  private static ArtifactCache createSQLiteArtifactCache(
      BuckEventBus buckEventBus,
      SQLiteCacheEntry cacheConfig,
      Function<String, UnconfiguredBuildTargetView> unconfiguredBuildTargetFactory,
      TargetConfigurationSerializer targetConfigurationSerializer,
      ProjectFilesystem projectFilesystem) {
    Path cacheDir = cacheConfig.getCacheDir();
    try {
      SQLiteArtifactCache sqLiteArtifactCache =
          new SQLiteArtifactCache(
              "sqlite",
              projectFilesystem,
              cacheDir,
              buckEventBus,
              cacheConfig.getMaxSizeBytes(),
              cacheConfig.getMaxInlinedSizeBytes(),
              cacheConfig.getCacheReadMode());

      return new LoggingArtifactCacheDecorator(
          buckEventBus,
          sqLiteArtifactCache,
          new SQLiteArtifactCacheEvent.SQLiteArtifactCacheEventFactory(
              unconfiguredBuildTargetFactory, targetConfigurationSerializer));
    } catch (IOException | SQLException e) {
      throw new HumanReadableException(
          e, "Failure initializing artifact cache directory: %s", cacheDir);
    }
  }

  private static String stripNonAscii(String str) {
    if (CharMatcher.ascii().matchesAllOf(str)) {
      return str;
    }
    StringBuilder builder = new StringBuilder(str.length());
    for (int i = 0; i < str.length(); ++i) {
      char c = str.charAt(i);
      builder.append(CharMatcher.ascii().matches(c) ? c : '?');
    }
    return builder.toString();
  }

  private static OkHttpClient.Builder setTimeouts(
      OkHttpClient.Builder builder,
      int connectTimeoutSeconds,
      int readTimeoutSeconds,
      int writeTimeoutSeconds) {
    return builder
        .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS);
  }

  private static int getMultiFetchLimit(ArtifactCacheBuckConfig buckConfig) {
    return buckConfig.getMultiFetchType() == MultiFetchType.ENABLED
        ? buckConfig.getMultiFetchLimit()
        : 0;
  }

  private static class ProgressResponseBody extends ResponseBody {

    private final ResponseBody responseBody;
    private BuckEventBus buckEventBus;
    private BufferedSource bufferedSource;

    public ProgressResponseBody(ResponseBody responseBody, BuckEventBus buckEventBus) {
      this.responseBody = responseBody;
      this.buckEventBus = buckEventBus;
      this.bufferedSource = Okio.buffer(source(responseBody.source()));
    }

    @Override
    public MediaType contentType() {
      return responseBody.contentType();
    }

    @Override
    public long contentLength() {
      return responseBody.contentLength();
    }

    @Override
    public BufferedSource source() {
      return bufferedSource;
    }

    private Source source(Source source) {
      return new ForwardingSource(source) {
        @Override
        public long read(Buffer sink, long byteCount) throws IOException {
          long bytesRead = super.read(sink, byteCount);
          // read() returns the number of bytes read, or -1 if this source is exhausted.
          if (byteCount != -1) {
            buckEventBus.post(new BytesReceivedEvent(byteCount));
          }
          return bytesRead;
        }
      };
    }
  }
}
