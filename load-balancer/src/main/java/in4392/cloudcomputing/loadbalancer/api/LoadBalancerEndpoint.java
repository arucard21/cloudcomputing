package in4392.cloudcomputing.loadbalancer.api;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Named;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;


@Named
@Path("")
@Produces(MediaType.APPLICATION_JSON)
public class LoadBalancerEndpoint {
	private static final int MAX_REQUESTS_PER_INSTANCE = 5;
	private static final int MIN_FREE_INSTANCES = 10;
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
	 */
	@Path("")
	@POST
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	@Consumes(MediaType.APPLICATION_OCTET_STREAM)
	public Response getInstanceResponse(InputStream data) {
		
		//checkSufficientFreeInstances();
		//incrementIndex();
		//Target curTarget = targets.get(index);
		//curTarget.incrementCurrentAmountOfRequests();
		//if (curTarget.getCurrentAmountOfRequests() == MAX_REQUESTS_PER_INSTANCE) {
		//	curTarget.setFree(false);
		//}
		//return ClientBuilder.newClient().target(UriBuilder.fromUri(curTarget.getTargetURI()).path("video").build()).request().post(Entity.entity(data, MediaType.APPLICATION_OCTET_STREAM));
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
		System.out.println("Redirecting video to application server");
		Response video = null ;
		boolean flag = true;
		while (flag) {
			try {
				 video = ClientBuilder.newClient().target(UriBuilder.fromUri("http://localhost:8090/application").path("video").build()).request(MediaType.APPLICATION_OCTET_STREAM).accept(MediaType.APPLICATION_OCTET_STREAM).post(Entity.entity(is, MediaType.APPLICATION_OCTET_STREAM));
				 flag = false;
			} catch (Exception e) {
				is = null;
				System.out.println("Retrying connection after sleeping for 20 seconds");
				try {
					Thread.sleep(RETRY_WAIT_TIME);
				} catch (InterruptedException e2) {
					e2.printStackTrace();
				}
				System.out.println("Retrying connection");
				is = new ByteArrayInputStream(baos.toByteArray()); 

			}
		}
		System.out.println("Returning converted video to the user");
		return video;
	}
	
	public int incrementIndex() {
		do {
			index++;
		}while (targets.get(index).isFree());
		return index;
	}
	
	/**
	 * add an instance to the LoadBalancer list
	 * @param uri
	 * TODO this may need to be a synchronized method, or we need to figure out how to do handle multiple requests
	 * from multiple instances of this endpoint. 
	 */
	@POST
	@Path("targets")
	public Response addTarget(URI uri) {
		for(Target target : targets) {
			if (target.getTargetURI().equals(uri)) {
				return Response.ok().build();
			}
		}
		Target newTarget = new Target(uri, true, 0);
		targets.add(newTarget);
		return Response.ok().build();
	}	
	
	/**
	 * Delete an instance from the list of LoadBalancer
	 * @param uri
	 */
	@DELETE
	public Response removeTarget(URI uri) {
		Target toBeRemoved = null;
		for(Target target : targets) {
			if (target.getTargetURI().equals(uri)) {
				toBeRemoved = target;
			}
		}
		if(toBeRemoved != null) {
			targets.remove(toBeRemoved);
		}
		return Response.ok().build();
	}
	
	public void checkSufficientFreeInstances() {
		int count = 0;
		for(Target target : targets) {
			count = target.isFree() ? count + 1 : count;
		}
		if (count < MIN_FREE_INSTANCES) {
			requestNewInstances(MIN_FREE_INSTANCES - count);	
		}
	}
	
	@Path("")
	@POST
	public void requestNewInstances(int num) {
		ClientBuilder.newClient().target(appOrchestratorURI).request().post(Entity.entity(num, MediaType.APPLICATION_JSON));
	}
}
