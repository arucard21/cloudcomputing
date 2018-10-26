package in4392.cloudcomputing.loadbalancer.api;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.inject.Named;
import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;


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
	 * @param data is the video that needs to be sent to the application
	 * @param failApplication will cause the application where the video gets sent to terminate 
	 * before the request is completed (for testing purposes)
	 * @param delayApplication is the amount of seconds that the application will be delayed from 
	 * completing the request (for testing purposes)
	 * @return the response from the instance
	 * @throws URISyntaxException 
	 * @throws IOException 
	 */
	@Path("entry")
	@POST
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	@Consumes(MediaType.APPLICATION_OCTET_STREAM)
	public InputStream redirectRequest(
			InputStream data, 
			@DefaultValue("false") 
			@QueryParam("failApplication") 
			boolean failApplication,
			@DefaultValue("0") 
			@QueryParam("delayApplication") 
			int delayApplication) throws URISyntaxException, IOException {
		
		System.out.println("Store input video to a file");
		File inputFile =  Paths.get(UUID.randomUUID().toString()).toFile();
        
        Files.copy(data, inputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        
		System.out.println("Requesting application instance from AppOrchestrator");
		String instanceID = ClientBuilder.newClient()
				.target(
						UriBuilder.fromUri(appOrchestratorURI)
						.port(8080)
						.path("application-orchestrator")
						.path("leastUtilizedInstance")
						.build())
				.request()
				.get(String.class);
		String instanceDN = EC2.retrieveEC2InstanceWithId(instanceID).getPublicDnsName();
		URI instanceURI = new URI("http", instanceDN , "", "");
		System.out.println("Redirecting video to application server " + instanceID + "at " + instanceURI.toString());
		
		InputStream video = null ;
		boolean waitingForConvertedVideo = true;
		int attempts = 0;
		while (waitingForConvertedVideo && attempts < 10) { 
			try {
				 video = ClientBuilder.newClient().
						 target(
								 UriBuilder.fromUri(instanceURI)
								 .port(8080)
								 .path("application")
								 .path("video")
								 .queryParam("failApplication", failApplication)
								 .queryParam("delayApplication", delayApplication)
								 .build())
						 .request()
						 .post(
								 Entity.entity(
										 Files.newInputStream(inputFile.toPath()), 
										 MediaType.APPLICATION_OCTET_STREAM),
								 InputStream.class);
				 System.out.println("Returning converted video to the user");
				 waitingForConvertedVideo = false;
			} catch (Exception e) {
				System.out.println("Decreasing request counter in app orchestrator since the request to this application instance failed");
				decrementRequestsForApplication(instanceID);
				System.out.println("Retrying connection after sleeping for 20 seconds");
				try {
					Thread.sleep(RETRY_WAIT_TIME);
				} catch (InterruptedException e2) {
					e2.printStackTrace();
				}
				attempts++;
				System.out.println("Requesting new application instance from AppOrchestrator");
				instanceID = ClientBuilder.newClient().
						target(
								UriBuilder.fromUri(appOrchestratorURI)
								.port(8080)
								.path("application-orchestrator")
								.path("leastUtilizedInstance")
								.build())
						.request()
						.get(String.class);
				System.out.println("Retrying request");
			}
		}
		System.out.println("Decreasing request counter in app orchestrator");
		decrementRequestsForApplication(instanceID);
		
		System.out.println("Deleting original input video");
		inputFile.delete();
		
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
