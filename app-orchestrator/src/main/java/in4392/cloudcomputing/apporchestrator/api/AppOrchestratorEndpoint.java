package in4392.cloudcomputing.apporchestrator.api;

import javax.inject.Named;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import in4392.cloudcomputing.apporchestrator.AppOrchestrator;

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
}
