package in4392.cloudcomputing.test.system;

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.UriBuilder;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

public class EndToEndest extends SystemTest{
	@Test
	public void checkOutputVideoNormal() {
		URI getLoadBalancerEntry = UriBuilder.fromUri(loadBalancerURI).port(8080).path("load-balancer").path("entry").build();
		InputStream inputVideo = new FileInputStream("input");
		InputStreamReader isr = new InputStreamReader(inputVideo, StandardCharsets.UTF_8);
		
	    InputStream outputVideo = client.target(getLoadBalancerEntry).request().post(Entity.entity(inputVideo,MediaType.APPLICATION_OCTET_STREAM));
		Assertions.assertNotNull(outputVideo);
	}
	
	@Test
	public void checkOutputVideoAppInstanceFail() {
		URI getLoadBalancerEntry = UriBuilder.fromUri(loadBalancerURI).port(8080).path("entry").build();
		InputStream inputVideo = new FileInputStream("input");
		InputStreamReader isr = new InputStreamReader(inputVideo, StandardCharsets.UTF_8);
		
		//Use thread that calls terminateInstance? First we need to request the AppInstanceID from the AppOrchestrator
		
	    InputStream outputVideo = client.target(getLoadBalancerEntry).request().post(Entity.entity(inputVideo,MediaType.APPLICATION_OCTET_STREAM));
		Assertions.assertNotNull(outputVideo);
	}


}
