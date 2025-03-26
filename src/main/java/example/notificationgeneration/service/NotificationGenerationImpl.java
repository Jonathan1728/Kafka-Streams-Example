package example.notificationgeneration.service;

import example.events.v1.AccountEvent;
import example.events.v1.NotificationEvent;
import example.events.v1.OrderEvent;
import lombok.extern.slf4j.Slf4j;

import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

import java.util.function.Function;

@Slf4j
@Service
public class NotificationGenerationImpl implements NotificationGeneration {

	@Bean
	public Function<KTable<String, AccountEvent>,
			Function<KStream<String, example.events.v1.OrderEvent>,
					Function<KStream<String, example.events.v1.SubscriptionEvent>,
							KStream<String, example.events.v1.NotificationEvent>>>> generateNotification() {

		return accountEvent -> ( orderEvent -> ( subscriptionEvent -> {

			return orderEvent
					.selectKey((k, v) -> v.getAccountId())
					.filter(this::dummyLogic)
					.join(accountEvent, this::buildNotificationEvent);
		}));
	}

	private boolean dummyLogic(String key, OrderEvent orderEvent) {
		return true;
	}

	private NotificationEvent buildNotificationEvent(OrderEvent orderEvent, AccountEvent accountEvent) {
		return NotificationEvent.newBuilder()
				.setId(orderEvent.getId())
				.setAccountId(orderEvent.getAccountId())
				.setTimestamp(orderEvent.getOrderCreatedTimestamp())
				.setEmail(accountEvent.getEmail())
				.setContent("Congrats on your order of " + orderEvent.getProductCode())
				.setNotificationTemplate("order_placed_notification")
				.build();
	}

}
