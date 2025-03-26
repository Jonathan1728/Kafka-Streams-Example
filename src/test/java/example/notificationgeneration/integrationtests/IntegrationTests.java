package example.notificationgeneration.integrationtests;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import com.google.gson.reflect.TypeToken;
import example.events.v1.AccountEvent;
import example.events.v1.NotificationEvent;
import example.events.v1.OrderEvent;
import example.events.v1.SubscriptionEvent;
import example.notificationgeneration.service.NotificationGenerationImpl;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.test.TestRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.stream.JsonReader;

import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;

import static org.junit.jupiter.api.Assertions.assertFalse;

class IntegrationTests {
	private static final Gson GSON = new GsonBuilder()
			.registerTypeAdapter(Instant.class,
					(JsonDeserializer<Instant>) (jsonElement, type, jsonDeserializationContext) -> Instant
							.ofEpochMilli(jsonElement.getAsJsonPrimitive().getAsLong()))
			.setLenient().serializeNulls().create();
	private static final String SCHEMA_REGISTRY_SCOPE = IntegrationTests.class.getName();
	private static final String MOCK_SCHEMA_REGISTRY_URL = "mock://" + SCHEMA_REGISTRY_SCOPE;

	private static TestInputTopic<String, AccountEvent> accountTopic;
	private static TestInputTopic<String, OrderEvent> orderTopic;
	private static TestInputTopic<String, SubscriptionEvent> subscriptionTopic;
	private static TestOutputTopic<String, NotificationEvent> pibOutputTopic;
	private static TopologyTestDriver testDriver;

	Path resourceDirectory = Paths.get("src", "test", "resources");
	String absolutePath = resourceDirectory.toFile().getAbsolutePath();

	@BeforeEach
	void setupTestTopology() {

		// We need to define Serdes for de/serialization of kafka streams
		Serde<String> stringSerde = Serdes.String();

		// Input Serdes
		Serde<AccountEvent> accountSerde = new SpecificAvroSerde<>();
		Serde<OrderEvent> orderSerde = new SpecificAvroSerde<>();
		Serde<SubscriptionEvent> subscriptionSerde = new SpecificAvroSerde<>();

		// Output Serdes
		Serde<NotificationEvent> notificationOutputSerde = new SpecificAvroSerde<>();

		// Avro specific Serde config is also required
		Map<String, String> config = Map.of(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG,
				MOCK_SCHEMA_REGISTRY_URL);
		accountSerde.configure(config, false);
		orderSerde.configure(config, false);
		subscriptionSerde.configure(config, false);
		notificationOutputSerde.configure(config, false);

		// Define classes required by the test and the application, standard unit
		// test-like procedure
		NotificationGenerationImpl pibFlatteningTransformerImpl = new NotificationGenerationImpl();

		// Here we begin to put together the topology of the application using the
		// streamsBuilder
		StreamsBuilder streamsBuilder = new StreamsBuilder();
		Properties properties = buildStreamDummyConfiguration();

		// Create the input streams for the app
		final KTable<String, AccountEvent> accountStream = streamsBuilder.table("account_topic",
				Consumed.with(stringSerde, accountSerde));
		final KStream<String, OrderEvent> orderStream = streamsBuilder.stream("order_topic",
				Consumed.with(stringSerde, orderSerde));
		final KStream<String, SubscriptionEvent> subscriptionStream = streamsBuilder.stream("subscription_topic",
				Consumed.with(stringSerde, subscriptionSerde));

		// Apply the previously defined function to the input stream, producing the
		// output KStream, and then pipe the output to output topic(s)
		pibFlatteningTransformerImpl
				.generateNotification()
				.apply(accountStream)
				.apply(orderStream)
				.apply(subscriptionStream)
				.to("notification_topic");

		// Finally create the test driver, the vehicle which allows integration testing
		// to function properly by building our topology for us
		testDriver = new TopologyTestDriver(streamsBuilder.build(), properties);

		// Define input and ouput topics with correct Serdes
		accountTopic = testDriver.createInputTopic("account_topic", stringSerde.serializer(), accountSerde.serializer());
		orderTopic = testDriver.createInputTopic("order_topic", stringSerde.serializer(), orderSerde.serializer());
		subscriptionTopic = testDriver.createInputTopic("subscription_topic", stringSerde.serializer(), subscriptionSerde.serializer());
		pibOutputTopic = testDriver.createOutputTopic("notification_topic", stringSerde.deserializer(), notificationOutputSerde.deserializer());
	}

	// Test
	@Test
	void TestHarness() throws IOException {
		// 1.) load data from files
		List<TestRecord<String, AccountEvent>> accountEvents = loadAccountEventsFromFile();
		List<TestRecord<String, OrderEvent>> orderEvents = loadOrderEventsFromFile();
		List<TestRecord<String, SubscriptionEvent>> subscriptionEvents = loadSubscriptionEventsFromFile();
		// 2.) Pipe input events
		accountTopic.pipeRecordList(accountEvents);
		orderTopic.pipeRecordList(orderEvents);
		subscriptionTopic.pipeRecordList(subscriptionEvents);

		// 3.) Inspect output data
		Path testdataPath = Paths.get("target", "testdata");
		Files.createDirectories(testdataPath);
		String absolutePath = testdataPath.toFile().getAbsolutePath();

		assertFalse(pibOutputTopic.isEmpty());

		var events = pibOutputTopic.readKeyValuesToList();
		FileWriter fileWriter = new FileWriter(absolutePath + "/notificationOutputTopic.json",
				false);
		for (KeyValue<String, NotificationEvent> event : events) {
			fileWriter.write(event.value.toString() + System.getProperty("line.separator"));
		}
		fileWriter.close();
	}

	@AfterEach
	void afterEach() {
		testDriver.close();
		MockSchemaRegistry.dropScope(SCHEMA_REGISTRY_SCOPE);
	}

	private static Properties buildStreamDummyConfiguration() {
		Properties props = new Properties();
		props.put(StreamsConfig.APPLICATION_ID_CONFIG, "test");
		props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:1234");
		props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG,
				"org.apache.kafka.common.serialization.Serdes$StringSerde");
		props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, SpecificAvroSerde.class);
		props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, MOCK_SCHEMA_REGISTRY_URL);
		props.put(StreamsConfig.STATESTORE_CACHE_MAX_BYTES_CONFIG, 0);
		props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 1000);
		return props;
	}

	private List<TestRecord<String, AccountEvent>> loadAccountEventsFromFile() throws IOException {

		try (FileReader fReader = new FileReader(absolutePath + "/testdata/accountEvents.json")) {
			JsonReader reader = new JsonReader(fReader);
			Type listType = new TypeToken<ArrayList<AccountEvent>>() {
			}.getType();
			List<AccountEvent> events = GSON.fromJson(reader, listType);
			return events.stream()
					.map(event -> new TestRecord<>(event.getId(), event))
					.collect(Collectors.toList());
		}
	}

	private List<TestRecord<String, OrderEvent>> loadOrderEventsFromFile() throws IOException {

		try (FileReader fReader = new FileReader(absolutePath + "/testdata/orderEvents.json")) {
			JsonReader reader = new JsonReader(fReader);
			Type listType = new TypeToken<ArrayList<OrderEvent>>() {
			}.getType();
			List<OrderEvent> events = GSON.fromJson(reader, listType);
			return events.stream()
					.map(event -> new TestRecord<>(event.getId(), event))
					.collect(Collectors.toList());
		}
	}

	private List<TestRecord<String, SubscriptionEvent>> loadSubscriptionEventsFromFile() throws IOException {

		try (FileReader fReader = new FileReader(absolutePath + "/testdata/subscriptionEvents.json")) {
			JsonReader reader = new JsonReader(fReader);
			Type listType = new TypeToken<ArrayList<SubscriptionEvent>>() {
			}.getType();
			List<SubscriptionEvent> events = GSON.fromJson(reader, listType);
			return events.stream()
					.map(event -> new TestRecord<>(event.getId(), event))
					.collect(Collectors.toList());
		}
	}

}
