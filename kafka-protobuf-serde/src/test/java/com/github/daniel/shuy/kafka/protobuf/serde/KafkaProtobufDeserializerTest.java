package com.github.daniel.shuy.kafka.protobuf.serde;

import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Deserializer;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.util.concurrent.ListenableFuture;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;

@RunWith(SpringRunner.class)
@EmbeddedKafka(controlledShutdown = true, topics = KafkaProtobufDeserializerTest.TOPIC)
@DirtiesContext
public class KafkaProtobufDeserializerTest {

    static final String TOPIC = "topic";

    @Configuration
    static class ContextConfiguration {

        @Autowired
        private EmbeddedKafkaBroker embeddedKafka;

        @Bean
        public ProducerFactory<byte[], byte[]> producerFactory() {
            Map<String, Object> producerProps = KafkaTestUtils.producerProps(embeddedKafka);

            return new DefaultKafkaProducerFactory<>(producerProps,
                    new ByteArraySerializer(),
                    new ByteArraySerializer());
        }

        @Bean
        public KafkaTemplate<byte[], byte[]> kafkaTemplate() {
            return new KafkaTemplate<>(
                    producerFactory(),
                    true);
        }
    }

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    @Autowired
    private KafkaTemplate<byte[], byte[]> template;

    private <MessageType extends MessageLite> void deserialize(
            MessageType input, Parser<MessageType> parser,
            String kafkaConsumerGroupId) throws Exception {
        Deserializer<MessageType> deserializer = new KafkaProtobufDeserializer<>(parser);

        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                kafkaConsumerGroupId, Boolean.FALSE.toString(), embeddedKafka);

        ConsumerFactory<MessageType, MessageType> consumerFactory = new DefaultKafkaConsumerFactory<>(
                consumerProps,
                deserializer, deserializer);

        BlockingQueue<ConsumerRecord<MessageType, MessageType>> records = new LinkedBlockingQueue<>();

        ContainerProperties containerProps = new ContainerProperties(TOPIC);
        containerProps.setMessageListener((MessageListener<MessageType, MessageType>) (ConsumerRecord<MessageType, MessageType> record) -> {
            records.add(record);
        });

        MessageListenerContainer container = new KafkaMessageListenerContainer<>(
                consumerFactory,
                containerProps);

        try {
            container.start();

            ContainerTestUtils.waitForAssignment(container, embeddedKafka.getPartitionsPerTopic());

            byte[] data = input.toByteArray();
            ProducerRecord<byte[], byte[]> producerRecord = new ProducerRecord<>(TOPIC, data, data);
            ListenableFuture<SendResult<byte[], byte[]>> producerFuture = template.send(producerRecord);

            try {
                producerFuture.get();
            } catch (InterruptedException e) {
                return;
            } catch (ExecutionException e) {
                throw new KafkaException("Error sending message to Kafka.", e.getCause());
            }

            ConsumerRecord<MessageType, MessageType> consumerRecord;
            try {
                consumerRecord = records.take();
            } catch (InterruptedException e) {
                return;
            }

            MessageType key = consumerRecord.key();
            Assert.assertEquals(key, input);

            MessageType value = consumerRecord.value();
            Assert.assertEquals(value, input);
        } finally {
            container.stop();
        }
    }

    @Test(timeout = 10000)
    public void deserializeProto2() throws Exception {
        Proto2Message message = Proto2Message.newBuilder()
                .setStr("Hello World")
                .setBoolean(true)
                .setInt(Long.MIN_VALUE)
                .setDbl(Double.MIN_VALUE)
                .build();
        deserialize(message, Proto2Message.parser(), "test.deserializer.proto2");
    }

    @Test(timeout = 10000)
    public void deserializeProto3() throws Exception {
        Proto3Message message = Proto3Message.newBuilder()
                .setStr("Goodbye World")
                .setBoolean(false)
                .setInt(Long.MAX_VALUE)
                .setDbl(Double.MAX_VALUE)
                .build();
        deserialize(message, Proto3Message.parser(), "test.deserializer.proto3");
    }
}
