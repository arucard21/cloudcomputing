package in4392.cloudcomputing.apporchestrator.api;

import javax.inject.Named;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.amazonaws.auth.BasicAWSCredentials;

import in4392.cloudcomputing.apporchestrator.AppOrchestrator;
import in4392.cloudcomputing.apporchestrator.EC2;

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
	
	/**
	 * Set the credentials for the main instance. 
	 * 
	 * This requires a POST request with a JSON that looks like this:
	 * {
	 *     "aWSAccessKeyId": "<access key>",
	 *     "aWSSecretKey", "<secret key>"
	 * }
	 * @param credentials is the object representing the deserialized JSON from the incoming HTTP request 
	 * @return a 200 HTTP status with a simple message, if successful
	 */
	@Path("credentials")
	@POST
	public Response setAWSCredentials(SimpleAWSCredentials credentials) {
		BasicAWSCredentials awsCredentials = new BasicAWSCredentials(credentials.getAccessKey(), credentials.getSecretKey());
		EC2.setCredentials(awsCredentials);
		return Response.ok(new SimpleStatus("The credentials have been set")).build();
	}
	
	/**
	 * Check if the credentials are configured for this instance
	 * 
	 * @return a 200 HTTP status with a simple message, if the credentials are available in this instance.
	 * If the credentials are not available, this will return a 500 HTTP status. In this case, the credentials
	 * can be provided with a POST request to "main/credentials".
	 */
	@Path("credentials")
	@GET
	public Response checkAWSCredentials() {
		if (EC2.getCredentials() == null) {
			throw new InternalServerErrorException("This instance does not have credentials configured");
		}
		return Response.ok(new SimpleStatus("This instance has credentials configured")).build();
	}
}
