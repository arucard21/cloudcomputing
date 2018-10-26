package in4392.cloudcomputing.test.system;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.UriBuilder;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

public class EndToEndTest extends SystemTest{
	@Test
	public void video_shouldBeProcessedAndReturned_whenEverythingWorksCorrectly() throws IOException {
		URI getLoadBalancerEntry = UriBuilder.fromUri(loadBalancerURI)
				.port(8080)
				.path("load-balancer")
				.path("entry")
				.build();
		try(InputStream inputVideo = Files.newInputStream(testVideoSmall)){
			try(InputStream processedVideoInputStream = client
		    		.target(getLoadBalancerEntry)
		    		.request()
		    		.post(
		    				Entity.entity(inputVideo, MediaType.APPLICATION_OCTET_STREAM_VALUE), 
		    				InputStream.class)){
				Assertions.assertNotNull(processedVideoInputStream);
				Assertions.assertTrue(processedVideoInputStream.available() > 0);
			}
		}
	}
	
	@Test
	public void video_shouldBeProcessedAndReturned_whenTheProcessingApplicationInstanceFails() throws IOException {
		URI getLoadBalancerEntry = UriBuilder.fromUri(loadBalancerURI)
				.port(8080)
				.path("load-balancer")
				.path("entry")
				.build();
		try(InputStream inputVideo = Files.newInputStream(testVideoSmall)){
			
			try(InputStream processedVideoInputStream = client
		    		.target(getLoadBalancerEntry)
		    		.request()
		    		.post(
		    				Entity.entity(inputVideo, MediaType.APPLICATION_OCTET_STREAM_VALUE), 
		    				InputStream.class)){
				Assertions.assertNotNull(processedVideoInputStream);
				Assertions.assertTrue(processedVideoInputStream.available() > 0);
			}
		}
	}
}

