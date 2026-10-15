package com.archnode.telemetry;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig {

    @Bean
    NewTopic logTopic(TelemetryProperties props) {
        return TopicBuilder.name(props.topic()).partitions(props.partitions()).replicas(1).build();
    }

    /** Runs LLM-bound incident analysis off the Kafka consumer threads, one virtual thread per task. */
    @Bean(destroyMethod = "close")
    ExecutorService analysisExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /** Each concurrent Kafka consumer loop runs on its own virtual thread. */
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory, TelemetryProperties props) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(props.concurrency());
        factory.getContainerProperties().setListenerTaskExecutor(new VirtualThreadTaskExecutor("kafka-log-"));
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000L, 3)));
        return factory;
    }
}
