package com.mesosphere.sdk.kafka.scheduler;

import com.mesosphere.sdk.api.types.EndpointProducer;
import com.mesosphere.sdk.dcos.DcosConstants;
import com.mesosphere.sdk.kafka.api.*;
import com.mesosphere.sdk.kafka.cmd.CmdExecutor;
import com.mesosphere.sdk.kafka.upgrade.CuratorStateStoreFilter;
import com.mesosphere.sdk.kafka.upgrade.KafkaConfigUpgrade;
import com.mesosphere.sdk.offer.evaluate.placement.RegexMatcher;
import com.mesosphere.sdk.scheduler.DefaultScheduler;
import com.mesosphere.sdk.specification.DefaultService;
import com.mesosphere.sdk.specification.DefaultServiceSpec;
import com.mesosphere.sdk.specification.ServiceSpec;
import com.mesosphere.sdk.specification.yaml.RawServiceSpec;
import com.mesosphere.sdk.specification.yaml.YAMLServiceSpecFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;

/**
 * Kafka Service.
 */
public class KafkaService extends DefaultService {
    protected static final Logger LOGGER = LoggerFactory.getLogger(KafkaService.class);

    public KafkaService(File pathToYamlSpecification) throws Exception {
        RawServiceSpec rawServiceSpec = YAMLServiceSpecFactory.generateRawSpecFromYAML(pathToYamlSpecification);

        DefaultServiceSpec serviceSpec = YAMLServiceSpecFactory.generateServiceSpec(rawServiceSpec);
        DefaultServiceSpec.Builder serviceSpecBuilder = DefaultServiceSpec.newBuilder(serviceSpec);
        serviceSpecBuilder.secret(System.getenv().getOrDefault("SERVICE_SECRET", ""));

        DefaultScheduler.Builder schedulerBuilder = DefaultScheduler.newBuilder(serviceSpecBuilder.build());
        schedulerBuilder.setPlansFrom(rawServiceSpec);

        /* Upgrade */
        new KafkaConfigUpgrade(schedulerBuilder.getServiceSpec());
        CuratorStateStoreFilter stateStore = new CuratorStateStoreFilter(schedulerBuilder.getServiceSpec().getName(),
                DcosConstants.MESOS_MASTER_ZK_CONNECTION_STRING);
        stateStore.setIgnoreFilter(RegexMatcher.create("broker-[0-9]*"));
        schedulerBuilder.setStateStore(stateStore);
        /* Upgrade */

        schedulerBuilder.setEndpointProducer("zookeeper", EndpointProducer.constant(
                schedulerBuilder.getServiceSpec().getZookeeperConnection() +
                        DcosConstants.SERVICE_ROOT_PATH_PREFIX + schedulerBuilder.getServiceSpec().getName()));

        initService(schedulerBuilder);
    }

    @Override
    protected void startApiServer(DefaultScheduler defaultScheduler,
                                  int apiPort,
                                  Collection<Object> additionalResources) {
        final ServiceSpec serviceSpec = super.getServiceSpec();
        final Collection<Object> apiResources = new ArrayList<>();

        //DcosConstants.SERVICE_ROOT_PATH_PREFIX + super.getServiceSpec().getName()
        final String zkUri = String.format("/kafka-%s", serviceSpec.getName());
        final KafkaZKClient kafkaZKClient = new KafkaZKClient(System.getenv("KAFKA_ZOOKEEPER_CONNECT"), zkUri);

        apiResources.add(new BrokerResource(kafkaZKClient));
        apiResources.add(new TopicResource(new CmdExecutor(kafkaZKClient, System.getenv("KAFKA_VERSION_PATH")),
                kafkaZKClient));

        apiResources.addAll(additionalResources);

        LOGGER.info("Starting API server with additional resources: {}", apiResources);
        super.startApiServer(defaultScheduler, apiPort, apiResources);
    }
}
