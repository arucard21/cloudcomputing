package in4392.cloudcomputing.loadbalancer.api;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Named;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;


@Named
@Path("")
@Produces(MediaType.APPLICATION_JSON)
public class LoadBalancerEndpoint {
	private static final int MAX_REQUESTS_PER_INSTANCE = 5;
	private static final int MIN_FREE_INSTANCES = 10;
	int index;
	List<Target> targets = new ArrayList<>();
	URI appOrchestratorURI;
	
	@Path("health")
	@GET
	public Response healthCheck() {
		return Response.noContent().build();
	}
	
	/**
	 * Send the request to the correct instance in order to balance the load.
	 * 
	 * @return the response from the instance
	 */
	@Path("")
	@GET
	public Response getInstanceResponse() {
		checkSufficientFreeInstances();
		incrementIndex();
		Target curTarget = targets.get(index);
		curTarget.incrementCurrentAmountOfRequests();
		if (curTarget.getCurrentAmountOfRequests() == MAX_REQUESTS_PER_INSTANCE) {
			curTarget.setFree(false);
		}
		return ClientBuilder.newClient().target(curTarget.getTargetURI()).request().get();
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
