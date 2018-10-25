package in4392.cloudcomputing.test.system;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.UriBuilder;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.core.type.TypeReference;

public class PerformanceStressTest extends SystemTest{
	@Test
	public void measureLatency() {
		URI getLoadBalancerEntry = UriBuilder.fromUri(loadBalancerURI).port(8080).path("load-balancer").path("entry").build();
		InputStream inputVideo = new FileInputStream("input");
		long startTime = System.currentTimeMillis();
		
	    InputStream outputVideo = client.target(getLoadBalancerEntry).request().post(Entity.entity(inputVideo,MediaType.APPLICATION_OCTET_STREAM));
		Assertions.assertNotNull(outputVideo);
		long endTime = System.currentTimeMillis();
		long timeElapsed = endTime - startTime;

		System.out.println("Execution time in milliseconds: " + timeElapsed);
	}
	
	@Test
	public void measureThroughput() {
		URI getLoadBalancerEntry = UriBuilder.fromUri(loadBalancerURI).port(8080).path("load-balancer").path("entry").build();
		InputStream inputVideo = new FileInputStream("input");
		long inputVideoSize = new File("input").length();
		long startTime = System.currentTimeMillis();
		
	    InputStream outputVideo = client.target(getLoadBalancerEntry).request().post(Entity.entity(inputVideo,MediaType.APPLICATION_OCTET_STREAM));
		Assertions.assertNotNull(outputVideo);
		long endTime = System.currentTimeMillis();
		long timeElapsed = endTime - startTime;

		System.out.println("Throughput is : " + inputVideoSize/timeElapsed + "Bytes/s");
	}

}
