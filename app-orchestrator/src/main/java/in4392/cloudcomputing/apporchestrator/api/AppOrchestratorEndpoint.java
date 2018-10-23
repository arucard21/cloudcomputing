package in4392.cloudcomputing.apporchestrator.api;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;

import javax.inject.Named;
import javax.ws.rs.GET;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.amazonaws.auth.BasicAWSCredentials;

import in4392.cloudcomputing.apporchestrator.AppOrchestrator;
import in4392.cloudcomputing.apporchestrator.EC2;

@Named
@Path("application-orchestrator")
@Produces(MediaType.APPLICATION_JSON)
public class AppOrchestratorEndpoint {
	private static final int MAX_REQUESTS_PER_INSTANCE = 5;
	/**
	 * 
	 * @return a 204 HTTP status with no content, if successful
	 */
	@Path("health")
	@GET
	public Response healthCheck() {
		return Response.noContent().build();
	}

	/**
	 * 
	 * @return a 200 HTTP status with a simple message, if successful
	 */
	@Path("start")
	@GET
	public Response startMain() {
		if(!AppOrchestrator.isAlive()) {
			AppOrchestrator.restartMainLoop();
		}
		return Response.ok(new SimpleStatus("The Main instance loop has been started")).build();
	}
	
	/**
	 * 
	 * @return a 200 HTTP status with a simple message, if successful
	 */
	@Path("stop")
	@GET
	public Response stopMain() {
		if(AppOrchestrator.isAlive()) {
			AppOrchestrator.stopMainLoop();
		}
		return Response.ok(new SimpleStatus("The Main instance loop has been stopped")).build();
	}
	
	
	/**
	 * This is for sending the least loaded to the LoadBalancer
	 * Please check it guys, cause I am still experimenting with the api requests
	 * @throws NoSuchAlgorithmException
	 * @throws IOException
	 */
	@Path("")
	@POST
	public Response sendLeastLoaded() throws NoSuchAlgorithmException, IOException{
		String minId = AppOrchestrator.findLeastLoadedAppInstance();
		ClientBuilder.newClient()
					 .target(AppOrchestrator.getLoadBalancerURI())
					 .request()
					 .post(Entity.entity(minId, MediaType.APPLICATION_JSON));
		int currentRequests = AppOrchestrator.incrementRequests(minId);
		if (currentRequests == MAX_REQUESTS_PER_INSTANCE) 
			AppOrchestrator.setInstanceFreeStatus(minId, false);
		return Response.ok().build();
	}
	
	/**
	 * TODO method for receiving response from the LoadBalancer that the request has been 
	 * transferred and completed so the currentRequests counter can be decremented
	 */
	public Response notificationForCompletedRequest() {
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
