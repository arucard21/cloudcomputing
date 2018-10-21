package in4392.cloudcomputing.maininstance.api;

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

import in4392.cloudcomputing.maininstance.MainInstance;

@Named
@Path("main")
public class MainInstanceEndpoint {
	/**
	 * 
	 * @return a 204 HTTP status with no content, if successful
	 */
	@Path("health")
	@GET
	@Produces(MediaType.APPLICATION_JSON)
	public Response healthCheck() {
		return Response.noContent().build();
	}

	/**
	 * 
	 * @return a 200 HTTP status with a simple message, if successful
	 */
	@Path("start")
	@GET
	@Produces(MediaType.APPLICATION_JSON)
	public Response startMain()  {
		if(!MainInstance.isAlive()) {
			MainInstance.restartMainLoop();
		}
		return Response.ok(new SimpleStatus("The Main instance loop has been started")).build();
	}
	
	/**
	 * 
	 * @return a 200 HTTP status with a simple message, if successful
	 */
	@Path("stop")
	@GET
	@Produces(MediaType.APPLICATION_JSON)
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
	@Consumes(MediaType.APPLICATION_JSON)
	@Path("credentials")
	@POST
	public Response setAWSCredentials(BasicAWSCredentials credentials) {
		MainInstance.setCredentials(credentials);
		return Response.ok(new SimpleStatus("The credentials have been set")).build();
	}
	
	/**
	 * Check if the credentials are configured for this instance
	 * 
	 * @return a 200 HTTP status with a simple message, if the credentials are available in this instance.
	 * If the credentials are not available, this will return a 500 HTTP status. In this case, the credentials
	 * can be provided with a POST request to "main/credentials".
	 */
	@Produces(MediaType.APPLICATION_JSON)
	@Path("credentials")
	@GET
	public Response checkAWSCredentials() {
		if (MainInstance.getCredentials() == null) {
			throw new InternalServerErrorException("This instance does not have credentials configured");
		}
		return Response.ok(new SimpleStatus("This instance has credentials configured")).build();
	}
}
