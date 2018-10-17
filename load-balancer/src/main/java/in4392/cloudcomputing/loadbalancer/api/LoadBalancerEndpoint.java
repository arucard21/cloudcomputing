package in4392.cloudcomputing.loadbalancer.api;

import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.PATCH;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Named
@Path("")
@Produces(MediaType.APPLICATION_JSON)
public class LoadBalancerEndpoint {
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
	@GET
	@POST
	@PUT
	@PATCH
	@DELETE
	public Response incomingRequest(@Context HttpServletRequest request) {
		// TODO implement load balancer logic
		return Response.ok().build();
	}
}
