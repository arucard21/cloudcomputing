package in4392.cloudcomputing.test.system;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;

import javax.ws.rs.client.Entity;
import javax.ws.rs.client.InvocationCallback;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.UriBuilder;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import in4392.cloudcomputing.maininstance.InstanceMetrics;

/**
 * 
 * Tests the performance of the system when receiving many requests.
 * 
 * Note that the requests may not all succeed as the stress test may cause requests
 * to time out. The point of this test is to check the performance of the system, 
 * not its reliability. So even when requests time out, these tests will pass.
 *
 */
public class PerformanceStressTest extends SystemTest{
	
	public void measureLatencyAndThroughput(int amountOfRequests) throws IOException, InterruptedException {
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
											// actually read the inputstream so the connection doesn't get broken prematurely
											UUID convertedFileName = UUID.randomUUID();
											Path convertedFile = Paths.get(convertedFileName+".mkv");
											Files.copy(response, convertedFile);
											convertedFile.toFile().delete();
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
			Thread.sleep(1 * 1000);
		}
		for (int i = 0; i < 360; i++) {
			if (ongoingRequests.stream().allMatch((request) -> request.isDone())) {
				break;
			}
			try {
				Thread.sleep(10 * 1000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		long endTime = System.currentTimeMillis();
		totalDuration = totalDuration + (endTime - startTime);
		
		Duration requestDuration = Duration.ofMillis(totalDuration);
		double throughput = totalInputVideoSize/((double)totalDuration/1000);
		System.err.printf("Execution time (in ISO8601 format) for %d requests: %s\n", amountOfRequests, requestDuration.toString());
		System.err.printf("Throughput for %d requests is : %s Bytes per second\n", amountOfRequests, throughput);
		URI mainInstanceMetricsURI = UriBuilder.fromUri(mainInstanceURI)
				.port(8080)
				.path("main")
				.path("metrics")
				.build();
		Map<String, InstanceMetrics> metrics = client.target(mainInstanceMetricsURI)
				.request()
				.get(new GenericType<Map<String, InstanceMetrics>>(
						new TypeReference<Map<String, InstanceMetrics>>() {}.getType()));
		ObjectMapper jsonMapper = new ObjectMapper();
		jsonMapper.enable(SerializationFeature.INDENT_OUTPUT);
		System.err.println(jsonMapper.writeValueAsString(metrics));
	}
	
	@Test
	public void stressTestWithOneRequest() throws IOException, InterruptedException {
		measureLatencyAndThroughput(1);
	}
	
	@Test
	public void stressTestWithTenRequests() throws IOException, InterruptedException {
		measureLatencyAndThroughput(10);
	}
	
	@Test
	public void stressTestWithTwentyRequests() throws IOException, InterruptedException {
		measureLatencyAndThroughput(20);
	}
	
	@Test
	public void stressTestWithFiftyRequests() throws IOException, InterruptedException {
		measureLatencyAndThroughput(50);
	}
}
