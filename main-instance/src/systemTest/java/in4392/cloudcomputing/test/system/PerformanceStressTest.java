package in4392.cloudcomputing.test.system;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.UriBuilder;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

public class PerformanceStressTest extends SystemTest{
	@Test
	public void measureLatency() throws IOException {
		URI getLoadBalancerEntry = UriBuilder.fromUri(loadBalancerURI).port(8080).path("load-balancer").path("entry").build();
		InputStream inputVideo = Files.newInputStream(testVideoSmall);
		long startTime = System.currentTimeMillis();
		InputStream outputVideo = client.target(getLoadBalancerEntry).request().post(Entity.entity(inputVideo, MediaType.APPLICATION_OCTET_STREAM_VALUE), InputStream.class);
		Assertions.assertNotNull(outputVideo);
		long endTime = System.currentTimeMillis();
		long timeElapsed = endTime - startTime;

		System.out.println("Execution time in milliseconds: " + timeElapsed);
	}
	
	@Test
	public void measureThroughput() throws IOException {
		URI getLoadBalancerEntry = UriBuilder.fromUri(loadBalancerURI).port(8080).path("load-balancer").path("entry").build();
		InputStream inputVideo = Files.newInputStream(testVideoSmall);
		long inputVideoSize = new File("input").length();
		long startTime = System.currentTimeMillis();
		
	    InputStream outputVideo = client.target(getLoadBalancerEntry).request().post(Entity.entity(inputVideo, MediaType.APPLICATION_OCTET_STREAM_VALUE), InputStream.class);
		Assertions.assertNotNull(outputVideo);
		long endTime = System.currentTimeMillis();
		long timeElapsed = endTime - startTime;

		System.out.println("Throughput is : " + inputVideoSize/timeElapsed + "Bytes/s");
	}

}
