package in4392.cloudcomputing.apporchestrator.api;

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

import in4392.cloudcomputing.apporchestrator.AppOrchestrator;
import in4392.cloudcomputing.apporchestrator.Target;

@Named
@Path("main")
@Produces(MediaType.APPLICATION_JSON)
public class AppOrchestratorEndpoint {
	@Path("health")
	@GET
	public Response healthCheck() {
		return Response.noContent().build();
	}

	@Path("stop")
	@GET
	public String stopMain() throws Exception {
		AppOrchestrator.destroy();
		// maybe the VM can be destroyed as well
		return "stopped main instance application but the VM is still running";
	}
	
	
	/**
	 * might this be unnecessary
	 * @param amount
	 * @return
	 */
	@POST
	@Path("")
	@Consumes(MediaType.APPLICATION_JSON)
	public Response requestNewInstances(int amount) {
		//TODO create the requested amount of instances
		
		
		return Response.ok().build();
	}
}
