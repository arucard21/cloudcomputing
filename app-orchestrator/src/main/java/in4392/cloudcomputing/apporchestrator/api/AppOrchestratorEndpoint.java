package in4392.cloudcomputing.apporchestrator.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

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
import com.amazonaws.services.ec2.model.Instance;

import in4392.cloudcomputing.apporchestrator.AppOrchestrator;
import in4392.cloudcomputing.apporchestrator.EC2;
import in4392.cloudcomputing.apporchestrator.Target;

@Named
@Path("application-orchestrator")
@Consumes(MediaType.APPLICATION_JSON)
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

	@Path("log")
	@GET
	@Produces(MediaType.TEXT_PLAIN)
	public String showLog() throws IOException {
		return new String(Files.readAllBytes(Paths.get("/home/ubuntu/app-orchestrator.log")), StandardCharsets.UTF_8);
	}
	
	@Path("instances/load-balancer")
	@GET
	public Instance describeMainInstance() {
		return AppOrchestrator.getLoadBalancer();
	}
	
	@Path("instances/applications")
	@GET
	public Collection<Instance> describeApplications() {
		return AppOrchestrator.getApplicationEC2Instances().values();
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
	@Path("leastUtilizedInstance")
	@GET
	public Response sendLeastLoaded() throws NoSuchAlgorithmException, IOException{
		String minId = AppOrchestrator.findLeastLoadedAppInstance();
		int currentRequests = AppOrchestrator.incrementRequests(minId);
		if (currentRequests == MAX_REQUESTS_PER_INSTANCE) 
			AppOrchestrator.setInstanceFreeStatus(minId, false);
		String minURI = EC2.retrieveEC2InstanceWithId(minId).getPublicDnsName(); 
		System.out.println("Sent app instance " + minURI + " to load balancer");
		return Response.ok().entity(minURI).build();
	}
	
	/**
	 * 
	 * @return a map showing the current utilization as key and the hostname of the
	 * application instance as value
	 */
	@Path("instance-utilization")
	@GET
	public Map<Integer, String> retrieveInstanceUtilizations(){
		Map<Integer, String> instanceUtilizations = new HashMap<>();
		for(Target target : AppOrchestrator.getApplicationEC2Targets().values()) {
			instanceUtilizations.put(target.getCurrentAmountOfRequests(), target.getTargetInstance().getPublicDnsName());
		}
		return instanceUtilizations;
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
