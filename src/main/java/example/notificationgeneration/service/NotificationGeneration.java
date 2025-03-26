package example.notificationgeneration.service;

import example.events.v1.AccountEvent;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;

import java.util.function.Function;

public interface NotificationGeneration {

	Function<KTable<String, AccountEvent>,
			Function<KStream<String, example.events.v1.OrderEvent>,
	Function<KStream<String, example.events.v1.SubscriptionEvent>,
	KStream<String, example.events.v1.NotificationEvent>>>> generateNotification();

}
