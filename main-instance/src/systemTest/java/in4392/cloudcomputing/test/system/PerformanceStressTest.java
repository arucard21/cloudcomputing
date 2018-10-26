package in4392.cloudcomputing.test.system;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

import javax.ws.rs.client.Entity;
import javax.ws.rs.client.InvocationCallback;
import javax.ws.rs.core.UriBuilder;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

public class PerformanceStressTest extends SystemTest{
	
	public void measureLatencyAndThroughput(int amountOfRequests) throws IOException {
		URI getLoadBalancerEntry = UriBuilder.fromUri(loadBalancerURI)
				.port(8080)
				.path("load-balancer")
				.path("entry")
				.build();
		long totalInputVideoSize = 0;
		long totalDuration = 0;
		List<Future<InputStream>> ongoingRequests = new ArrayList<>();
		long startTime = System.currentTimeMillis();
		for(int i = 0; i < amountOfRequests; i++) {			
			try(InputStream inputVideo = Files.newInputStream(testVideoSmall)){
				totalInputVideoSize = totalInputVideoSize + testVideoSmall.toFile().length();
				ongoingRequests.add(client
						.target(getLoadBalancerEntry)
						.request()
						.async()
						.post(
								Entity.entity(inputVideo, MediaType.APPLICATION_OCTET_STREAM_VALUE), 
								new InvocationCallback<InputStream>() {
									@Override
									public void completed(InputStream response) {
										Assertions.assertNotNull(response);
										try {
											Assertions.assertTrue(response.available() > 0);
										} catch (IOException e) {
											e.printStackTrace();
										}
									}

									@Override
									public void failed(Throwable throwable) {
										throwable.printStackTrace();
									}
								}));				
			}
		}
		while(true) {
			if (ongoingRequests.stream().allMatch((request) -> request.isDone())) {
				break;
			}
			try {
				Thread.sleep(1 * 1000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		long endTime = System.currentTimeMillis();
		totalDuration = totalDuration + (endTime - startTime);
		
		Duration requestDuration = Duration.ofMillis(totalDuration);
		long throughput = totalInputVideoSize/totalDuration;
		System.err.printf("Execution time (in ISO8601 format) for %d requests: %s\n", amountOfRequests, requestDuration.toString());
		System.err.printf("Throughput for %d requests is : %s Bytes per second\n", amountOfRequests, throughput);
	}
	
	@Test
	public void stressTestWithOneRequest() throws IOException {
		measureLatencyAndThroughput(1);
	}
	
	@Test
	public void stressTestWithTenRequests() throws IOException {
		measureLatencyAndThroughput(10);
	}
	
	@Test
	public void stressTestWithOneHundredRequests() throws IOException {
		measureLatencyAndThroughput(100);
	}
	
	@Test
	public void stressTestWithOneThousandRequests() throws IOException {
		measureLatencyAndThroughput(1000);
	}
}
