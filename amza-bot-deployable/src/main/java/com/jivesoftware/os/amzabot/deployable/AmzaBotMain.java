package com.jivesoftware.os.amzabot.deployable;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jivesoftware.os.amza.api.BAInterner;
import com.jivesoftware.os.amza.client.http.AmzaClientProvider;
import com.jivesoftware.os.amza.client.http.HttpPartitionClientFactory;
import com.jivesoftware.os.amza.client.http.HttpPartitionHostsProvider;
import com.jivesoftware.os.amza.client.http.RingHostHttpClientProvider;
import com.jivesoftware.os.amzabot.deployable.endpoint.AmzaBotEndpoints;
import com.jivesoftware.os.amzabot.deployable.ui.amzabot.AmzaBotUIEndpoints;
import com.jivesoftware.os.amzabot.deployable.ui.amzabot.AmzaBotUIInitializer;
import com.jivesoftware.os.amzabot.deployable.ui.amzabot.AmzaBotUIInitializer.AmzaBotUIServiceConfig;
import com.jivesoftware.os.amzabot.deployable.ui.amzabot.AmzaBotUIService;
import com.jivesoftware.os.amzabot.deployable.ui.health.UiEndpoints;
import com.jivesoftware.os.amzabot.deployable.ui.health.UiService;
import com.jivesoftware.os.amzabot.deployable.ui.health.UiServiceInitializer;
import com.jivesoftware.os.amzabot.deployable.ui.health.UiServiceInitializer.UiServiceConfig;
import com.jivesoftware.os.routing.bird.deployable.Deployable;
import com.jivesoftware.os.routing.bird.deployable.DeployableHealthCheckRegistry;
import com.jivesoftware.os.routing.bird.deployable.ErrorHealthCheckConfig;
import com.jivesoftware.os.routing.bird.deployable.InstanceConfig;
import com.jivesoftware.os.routing.bird.deployable.config.extractor.ConfigBinder;
import com.jivesoftware.os.routing.bird.endpoints.base.HasUI;
import com.jivesoftware.os.routing.bird.health.api.HealthFactory;
import com.jivesoftware.os.routing.bird.health.checkers.FileDescriptorCountHealthChecker;
import com.jivesoftware.os.routing.bird.health.checkers.GCLoadHealthChecker;
import com.jivesoftware.os.routing.bird.health.checkers.GCPauseHealthChecker;
import com.jivesoftware.os.routing.bird.health.checkers.LoadAverageHealthChecker;
import com.jivesoftware.os.routing.bird.health.checkers.ServiceStartupHealthCheck;
import com.jivesoftware.os.routing.bird.health.checkers.SystemCpuHealthChecker;
import com.jivesoftware.os.routing.bird.http.client.HttpClient;
import com.jivesoftware.os.routing.bird.http.client.HttpClientException;
import com.jivesoftware.os.routing.bird.http.client.HttpDeliveryClientHealthProvider;
import com.jivesoftware.os.routing.bird.http.client.HttpRequestHelperUtils;
import com.jivesoftware.os.routing.bird.http.client.TenantAwareHttpClient;
import com.jivesoftware.os.routing.bird.http.client.TenantRoutingHttpClientInitializer;
import com.jivesoftware.os.routing.bird.server.util.Resource;
import java.io.File;
import java.util.Arrays;
import java.util.concurrent.Executors;

public class AmzaBotMain {

    public static void main(String[] args) throws Exception {
        new AmzaBotMain().run(args);
    }

    public void run(String[] args) throws Exception {

        ServiceStartupHealthCheck serviceStartupHealthCheck = new ServiceStartupHealthCheck();
        try {
            ConfigBinder configBinder = new ConfigBinder(args);
            InstanceConfig instanceConfig = configBinder.bind(InstanceConfig.class);

            final Deployable deployable = new Deployable(args, configBinder, instanceConfig, null);
            HealthFactory.initialize(deployable::config, new DeployableHealthCheckRegistry(deployable));
            deployable.buildStatusReporter(null).start();
            deployable.addManageInjectables(HasUI.class, new HasUI(Arrays.asList(new HasUI.UI("manage", "manage", "/manage/ui"),
                new HasUI.UI("Reset Errors", "manage", "/manage/resetErrors"),
                new HasUI.UI("Reset Health", "manage", "/manage/resetHealth"),
                new HasUI.UI("Tail", "manage", "/manage/tail"),
                new HasUI.UI("Thread Dump", "manage", "/manage/threadDump"),
                new HasUI.UI("Health", "manage", "/manage/ui"),
                new HasUI.UI("AmzaBot", "main", "/"))));
            deployable.addHealthCheck(new GCPauseHealthChecker(deployable.config(GCPauseHealthChecker.GCPauseHealthCheckerConfig.class)));
            deployable.addHealthCheck(new GCLoadHealthChecker(deployable.config(GCLoadHealthChecker.GCLoadHealthCheckerConfig.class)));
            deployable.addHealthCheck(new SystemCpuHealthChecker(deployable.config(SystemCpuHealthChecker.SystemCpuHealthCheckerConfig.class)));
            deployable.addHealthCheck(new LoadAverageHealthChecker(deployable.config(LoadAverageHealthChecker.LoadAverageHealthCheckerConfig.class)));
            deployable.addHealthCheck(
                new FileDescriptorCountHealthChecker(deployable.config(FileDescriptorCountHealthChecker.FileDescriptorCountHealthCheckerConfig.class)));
            deployable.addHealthCheck(serviceStartupHealthCheck);
            deployable.addErrorHealthChecks(deployable.config(ErrorHealthCheckConfig.class));
            deployable.buildManageServer().start();

            HttpDeliveryClientHealthProvider clientHealthProvider = new HttpDeliveryClientHealthProvider(instanceConfig.getInstanceKey(),
                HttpRequestHelperUtils.buildRequestHelper(instanceConfig.getRoutesHost(), instanceConfig.getRoutesPort()),
                instanceConfig.getConnectionsHealth(), 5_000, 100);

            TenantRoutingHttpClientInitializer<String> tenantRoutingHttpClientInitializer = new TenantRoutingHttpClientInitializer<>();

            @SuppressWarnings("unchecked")
            TenantAwareHttpClient<String> amzaClient = tenantRoutingHttpClientInitializer.builder(
                deployable.getTenantRoutingProvider().getConnections("amza", "main", 10_000), // TODO config
                clientHealthProvider)
                .deadAfterNErrors(10)
                .checkDeadEveryNMillis(10_000)
                .build(); // TODO expose to conf

            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

            AmzaBotConfig amzaBotConfig = configBinder.bind(AmzaBotConfig.class);

            BAInterner interner = new BAInterner();

            AmzaClientProvider<HttpClient, HttpClientException> amzaClientProvider = new AmzaClientProvider<>(
                new HttpPartitionClientFactory(interner),
                new HttpPartitionHostsProvider(interner, amzaClient, objectMapper),
                new RingHostHttpClientProvider(amzaClient),
                Executors.newFixedThreadPool(amzaBotConfig.getAmzaCallerThreadPoolSize()),
                amzaBotConfig.getAmzaAwaitLeaderElectionForNMillis(),
                -1,
                -1);

            String cacheToken = String.valueOf(System.currentTimeMillis());
            AmzaBotUIServiceConfig contentUIServiceConfig = deployable.config(AmzaBotUIServiceConfig.class);
            AmzaBotService amzaBotService = new AmzaBotService(amzaBotConfig, amzaClientProvider);
            AmzaBotUIService contentUIService = new AmzaBotUIInitializer()
                .initialize(cacheToken, contentUIServiceConfig, amzaBotService);

            UiServiceConfig uiServiceConfig = deployable.config(UiServiceConfig.class);
            UiService uiService = new UiServiceInitializer().initialize(cacheToken, uiServiceConfig);

            File staticResourceDir = new File(System.getProperty("user.dir"));
            Resource sourceTree = new Resource(staticResourceDir)
                .addResourcePath(contentUIServiceConfig.getPathToStaticResources())
                .setContext("/content/static");

            deployable.addEndpoints(AmzaBotEndpoints.class);
            deployable.addInjectables(AmzaBotService.class, amzaBotService);

            deployable.addEndpoints(AmzaBotUIEndpoints.class);
            deployable.addInjectables(AmzaBotUIService.class, contentUIService);

            deployable.addEndpoints(UiEndpoints.class);
            deployable.addInjectables(UiService.class, uiService);

            deployable.addResource(sourceTree);

            deployable.buildServer().start();
            clientHealthProvider.start();
            serviceStartupHealthCheck.success();
        } catch (Throwable t) {
            serviceStartupHealthCheck.info("Failure encountered during startup.", t);
        }
    }

}