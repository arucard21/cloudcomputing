package in4392.cloudcomputing.loadbalancer.api;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Named;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;

import org.springframework.web.bind.annotation.RequestBody;


@Named
@Path("load-balancer")
@Produces(MediaType.APPLICATION_JSON)
public class LoadBalancerEndpoint {
	private static final int RETRY_WAIT_TIME = 20000;
	int index;
	List<Target> targets = new ArrayList<>();
	URI appOrchestratorURI;
	
	@Path("health")
	@GET
	public Response healthCheck() {
		return Response.noContent().build();
	}
	
	@Path("log")
	@GET
	@Produces(MediaType.TEXT_PLAIN)
	public String showLog() throws IOException {
		return new String(Files.readAllBytes(Paths.get("/home/ubuntu/load-balancer.log")), StandardCharsets.UTF_8);
	}
	
	/**
	 * Send the request to the correct instance in order to balance the load.
	 * 
	 * @return the response from the instance
	 * @throws URISyntaxException 
	 */
	@Path("entry")
	@POST
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	@Consumes(MediaType.APPLICATION_OCTET_STREAM)
	public Response redirectRequest(@RequestBody InputStream data) throws URISyntaxException {
		
		System.out.println("Store input video to a stream");
		ByteArrayOutputStream baos = new ByteArrayOutputStream();

		byte[] buffer = new byte[1024];
		int len;
		try {
			while ((len = data.read(buffer)) > -1 ) {
			    baos.write(buffer, 0, len);
			}
		} catch (IOException e3) {
			// TODO Auto-generated catch block
			e3.printStackTrace();
		}
		try {
			baos.flush();
		} catch (IOException e3) {
			// TODO Auto-generated catch block
			e3.printStackTrace();
		}

		InputStream is = new ByteArrayInputStream(baos.toByteArray()); 
		System.out.println("Requesting application instance from AppOrchestrator");
		String instanceID = ClientBuilder.newClient().target(UriBuilder.fromUri(appOrchestratorURI).port(8080).path("application-orchestrator").path("leastUtilizedInstance").build()).request().get(String.class);
		String instanceDN = EC2.retrieveEC2InstanceWithId(instanceID).getPublicDnsName();
		URI instanceURI = new URI("http",instanceDN ,"","");
		System.out.println("Redirecting video to application server " + instanceID);
		Response video = null ;
		boolean flag = true;
		int attempts = 0;
		while (flag && attempts < 10) { 
			try {
				 video = ClientBuilder.newClient().target(UriBuilder.fromUri(instanceURI).port(8080).path("application").path("video").build()).request().post(Entity.entity(is, MediaType.APPLICATION_OCTET_STREAM));
				 System.out.println("Returning converted video to the user");
				 flag = false;
			} catch (Exception e) {
				is = null;
				System.out.println("Decreasing request counter in app orchestrator since the request to this application instance failed");
				decrementRequestsForApplication(instanceID);
				System.out.println("Retrying connection after sleeping for 20 seconds");
				try {
					Thread.sleep(RETRY_WAIT_TIME);
				} catch (InterruptedException e2) {
					e2.printStackTrace();
				}
				attempts += 1;
				System.out.println("Requesting application instance from AppOrchestrator");
				instanceID = ClientBuilder.newClient().target(UriBuilder.fromUri(appOrchestratorURI).port(8080).path("application-orchestrator").path("leastUtilizedInstance").build()).request().get(String.class);
				System.out.println("Retrying connection");
				is = new ByteArrayInputStream(baos.toByteArray()); 

			}
		}
		System.out.println("Decreasing request counter in app orchestrator");
		decrementRequestsForApplication(instanceID);
		
		System.out.println("Returning converted video to the user");
		return video;
	}

	private void decrementRequestsForApplication(String instanceID) {
		ClientBuilder.newClient()
		.target(UriBuilder.fromUri(appOrchestratorURI).port(8080)
				.path("application-orchestrator")
				.path("completed")
				.queryParam("id", instanceID)
				.build())
		.request()
		.get();
	}
	
	
	@Path("")
	@POST
	public void requestNewInstances(int num) {
		ClientBuilder.newClient().target(appOrchestratorURI).request().post(Entity.entity(num, MediaType.APPLICATION_JSON));
	}
	
	@Path("appOrchestratorURI")
	@POST
	@Consumes(MediaType.TEXT_PLAIN)
	public Response setAppOrchestratorURI(String uri) throws URISyntaxException {
		appOrchestratorURI = new URI("http",uri,"","");
		System.out.println("AppOrchestrator is at " + appOrchestratorURI);
		return Response.ok().build();
	}
}
