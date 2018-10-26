package in4392.cloudcomputing.test.system;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.UriBuilder;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

public class EndToEndest extends SystemTest{
	@Test
	public void checkOutputVideoNormal() throws IOException {
		URI getLoadBalancerEntry = UriBuilder.fromUri(loadBalancerURI).port(8080).path("load-balancer").path("entry").build();
		InputStream inputVideo = Files.newInputStream(Paths.get("inputVideo.avi"));
		
	    InputStream outputVideo = client.target(getLoadBalancerEntry).request().post(Entity.entity(inputVideo, MediaType.APPLICATION_OCTET_STREAM_VALUE), InputStream.class);
		Assertions.assertNotNull(outputVideo);
	}
	
	@Test
	public void checkOutputVideoAppInstanceFail() throws IOException {
		URI getLoadBalancerEntry = UriBuilder.fromUri(loadBalancerURI).port(8080).path("entry").build();
		InputStream inputVideo = Files.newInputStream(Paths.get("inputVideo.avi"));
		
		//Use thread that calls terminateInstance? First we need to request the AppInstanceID from the AppOrchestrator
		
	    InputStream outputVideo = client.target(getLoadBalancerEntry).request().post(Entity.entity(inputVideo, MediaType.APPLICATION_OCTET_STREAM_VALUE), InputStream.class);
		Assertions.assertNotNull(outputVideo);
	}


}
