package com.rreganjr.playingWithKafka;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.QueryableStoreType;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.annotation.Input;
import org.springframework.cloud.stream.annotation.Output;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.cloud.stream.binder.kafka.streams.QueryableStoreRegistry;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.rreganjr.analytics.AnalyticsApplication.AnalyticsBinding.PAGE_COUNTS_IN;
import static com.rreganjr.analytics.AnalyticsApplication.AnalyticsBinding.PAGE_COUNTS_OUT;
import static com.rreganjr.analytics.AnalyticsApplication.AnalyticsBinding.PAGE_COUNT_MV;

/**
 * from https://www.youtube.com/watch?v=YPDzcmqwCNo
 *
 */
@Slf4j
@SpringBootApplication
@EnableBinding(AnalyticsApplication.AnalyticsBinding.class)
public class AnalyticsApplication {

	@Component
	public static class PageViewEventSource implements ApplicationRunner {
		private final MessageChannel pageViewsOut;

		public PageViewEventSource(AnalyticsBinding binding) {
			this.pageViewsOut = binding.pageViewsOut();
		}

		@Override
		public void run(ApplicationArguments args) throws Exception {
			List<String> names = Arrays.asList("ron", "theresa", "stinky");
			List<String> pages = Arrays.asList("home", "site", "blog");

			Runnable runnable = () -> {
				String rPage = pages.get(new Random().nextInt(pages.size()));
				String rName = names.get(new Random().nextInt(names.size()));
				PageViewEvent pageViewEvent = new PageViewEvent(rName, rPage, (Math.random() > 0.5?10: 100));
				Message<PageViewEvent> message = MessageBuilder
					.withPayload(pageViewEvent)
					.setHeader(KafkaHeaders.MESSAGE_KEY, pageViewEvent.getUserId().getBytes())
					.build();
				try {
					this.pageViewsOut.send(message);
					log.info("message sent: " + message);
				} catch (Exception e) {
					log.error("error sending message: " + message, e);
				}
			};
			Executors.newScheduledThreadPool(1).scheduleAtFixedRate(runnable, 1, 1, TimeUnit.SECONDS);
		}
	}

	/**
	 * convert the stream of page views into a stream of counts by page
	 */
	@Component
	public static class PageViewEventProcessor {
		@StreamListener
		@SendTo(PAGE_COUNTS_OUT)
		public KStream<String,Long> process (@Input(AnalyticsBinding.PAGE_VIEWS_IN) KStream<String,PageViewEvent> events) {
//			KTable<String,Long> kTable = events
			return events
					.filter((key,value) -> value.getDuration() > 10)
					.map((key,value) -> new KeyValue<>(value.getPage(), "0"))
					.groupByKey()
	//				.windowedBy(TimeWindows.of(1000*60)) // everything in the last hour, changed result to KTable<Windowed<String>,Long> - a table of by hour data
					.count(Materialized.as(PAGE_COUNT_MV))
					.toStream();


	//		kTable.join(...)
//			events.leftJoin(kTable, (event, value) -> {
//				// join the table and stream!!!!
//			});
		}
	}

	@Component
	public static class PageCountSink {
		@StreamListener
		public void process(@Input(PAGE_COUNTS_IN) KTable<String, Long> counts) {
			counts
					.toStream()
					.foreach((key, value) -> log.info(key + "=" + value));
		}
	}
	public static void main(String[] args) {
		SpringApplication.run(AnalyticsApplication.class, args);
	}

	interface AnalyticsBinding {
		String PAGE_VIEWS_OUT = "pvout";
		String PAGE_VIEWS_IN = "pvin";
		String PAGE_COUNT_MV = "pcvm";
		String PAGE_COUNTS_OUT = "pcout";
		String PAGE_COUNTS_IN = "pcin";

		@Input(PAGE_VIEWS_IN)
		KStream<String, PageViewEvent> pageViewsIn();

		@Output(PAGE_VIEWS_OUT)
		MessageChannel pageViewsOut();

		@Output(PAGE_COUNTS_OUT)
		KStream<String,Long> pageCountOut();

		@Input(PAGE_COUNTS_IN)
		KTable<String,Long> pageCountIn();
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	static class PageViewEvent {
		private String userId, page;
		private long duration;
	}


	@RestController
	public static class CountRestController {
		private final QueryableStoreRegistry registry;

		public CountRestController(QueryableStoreRegistry registry) {
			this.registry = registry;
		}

		@GetMapping("/counts")
		Map<String,Long> counts () {
			Map<String,Long> counts = new HashMap<>();
			ReadOnlyKeyValueStore<String,Long> queriableStoreType = registry.getQueryableStoreType(AnalyticsBinding.PAGE_COUNT_MV, QueryableStoreTypes.keyValueStore());
			KeyValueIterator<String,Long> all = queriableStoreType.all();
			while (all.hasNext()) {
				KeyValue<String,Long> value = all.next();
				counts.put(value.key, value.value);
			}
			return counts;
		}
	}

}
