package in4392.cloudcomputing.maininstance.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import javax.inject.Named;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.ec2.model.Instance;

import in4392.cloudcomputing.maininstance.MainInstance;

@Named
@Path("main")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MainInstanceEndpoint {
	/**
	 * 
	 * @return a 204 HTTP status with no content, if the main instance works correctly or
	 * a 500 HTTP status code with no content, if not
	 * 
	 */
	@Path("health")
	@GET
	public Response healthCheck() {
		if (MainInstance.isAlive()) {
			return Response.noContent().build();
		}
		else {
			return Response.serverError().build();
		}
	}

	/**
	 * 
	 * @return a 200 HTTP status with a simple message, if successful
	 */
	@Path("stop")
	@GET
	public Response stopMain() {
		if(MainInstance.isAlive()) {
			MainInstance.stopMainLoop();
		}
		return Response.ok(new SimpleStatus("The Main instance loop has been stopped")).build();
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
		MainInstance.setCredentials(awsCredentials);
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
		if (MainInstance.getCredentials() == null) {
			throw new InternalServerErrorException("This instance does not have credentials configured");
		}
		return Response.ok(new SimpleStatus("This instance has credentials configured")).build();
	}
	
	@Path("log")
	@GET
	@Produces(MediaType.TEXT_PLAIN)
	public String showLog() throws IOException {
		return new String(Files.readAllBytes(Paths.get("/home/ubuntu/main-instance.log")), StandardCharsets.UTF_8);
	}
	
	@Path("instances/main")
	@GET
	public Instance describeMainInstance() {
		return MainInstance.getMainInstance();
	}
	
	@Path("instances/shadow")
	@GET
	public Instance describeShadowInstance() {
		return MainInstance.getShadow();
	}
	
	@Path("instances/application-orchestrator")
	@GET
	public Instance describeApplicationOrchestrator() {
		return MainInstance.getAppOrchestrator();
	}
	
	@Path("shadow")
	@GET
	public Response configureInstanceAsShadow(@QueryParam("mainInstanceId") String mainInstanceId) {
		MainInstance.configureThisInstanceAsShadowWithProvidedInstanceAsMain(mainInstanceId);
		return Response.ok().build();
	}
}
