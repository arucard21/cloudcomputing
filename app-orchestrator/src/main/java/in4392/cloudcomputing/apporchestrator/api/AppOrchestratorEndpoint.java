package in4392.cloudcomputing.apporchestrator.api;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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
import javax.ws.rs.core.UriBuilder;

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
	public Response start() {
		if(!AppOrchestrator.isStarted()) {
			AppOrchestrator.start();
			return Response.ok(new SimpleStatus("The Main instance has been started")).build();
		}
		return Response.ok(new SimpleStatus("The Main instance was already started")).build();
	}
	
	/**
	 * 
	 * @return a 200 HTTP status with a simple message, if successful
	 */
	@Path("stop")
	@GET
	public Response stop() {
		if(AppOrchestrator.isStarted()) {
			AppOrchestrator.stop();
			return Response.ok(new SimpleStatus("The Main instance has been stopped")).build();
		}
		return Response.ok(new SimpleStatus("The Main instance was already stopped")).build();
	}


	/**
	 * 
	 * @return a 200 HTTP status with a simple message, if successful
	 */
	@Path("kill")
	@GET
	public Response kill() {
		if(AppOrchestrator.isAlive()) {
			AppOrchestrator.kill();
			return Response.ok(new SimpleStatus("The Main instance has been killed")).build();
		}
		return Response.ok(new SimpleStatus("The Main instance was already killed")).build();
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
		return AppOrchestrator.getApplicationTargets().values()
				.stream()
				.map((target) -> target.getTargetInstance())
				.collect(Collectors.toList());
	}
	
	
	/**
	 * This is for sending the least loaded to the LoadBalancer
	 * Please check it guys, cause I am still experimenting with the api requests
	 * @throws NoSuchAlgorithmException
	 * @throws IOException
	 * @throws URISyntaxException 
	 */
	@Path("leastUtilizedInstance")
	@GET
	public URI sendLeastLoaded(@QueryParam("failedApplications") List<String> failedApplicationHostnames) throws NoSuchAlgorithmException, IOException, URISyntaxException{
		String minId = AppOrchestrator.findLeastLoadedAppInstance(failedApplicationHostnames);
		String applicationDnsName = AppOrchestrator.getApplicationTargets().get(minId).getTargetInstance().getPublicDnsName();
		AppOrchestrator.incrementRequests(minId);
		URI applicationURI = UriBuilder.fromPath("")
				.scheme("http")
				.host(applicationDnsName)
				.port(8080)
				.build();
		System.out.println("Sent application URL " + applicationURI + " to load balancer");
		return applicationURI;
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
		for(Target target : AppOrchestrator.getApplicationTargets().values()) {
			instanceUtilizations.put(target.getCurrentAmountOfRequests(), target.getTargetInstance().getPublicDnsName());
		}
		return instanceUtilizations;
	}
	
	/**
	 * method for receiving response from the LoadBalancer that the request has been 
	 * transferred and completed so the currentRequests counter can be decremented
	 * @throws URISyntaxException 
	 */
	@Path("completed")
	@GET
	public Response notificationForCompletedRequest(@QueryParam("applicationURI") URI applicationURI) throws URISyntaxException {
		AppOrchestrator.decrementRequests(applicationURI);
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
	
	@Path("backup/main-instance")
	@GET
	public Response configureMainInstanceId(@QueryParam("mainInstanceId") String mainInstanceId) {
		AppOrchestrator.setMainInstance(mainInstanceId);
		return Response.ok().build();
	}
	
	@Path("backup/load-balancer")
	@GET
	public Response backupLoadBalancerInstanceId(@QueryParam("loadBalancerId") String loadBalancerId) {
		AppOrchestrator.setRestoreIdForLoadBalancer(loadBalancerId);
		return Response.ok().build();
	}
	
	@Path("backup/applications")
	@GET
	public Response backupApplicationInstanceIds(@QueryParam("applicationIds") List<String> applicationIds) {
		AppOrchestrator.setRestoreIdsForApplications(applicationIds);
		return Response.ok().build();
	}
	
	@Path("backup/application-counter")
	@GET
	public Response backupApplicationCounter(@QueryParam("applicationId") String applicationId, @QueryParam("counter") int counter) {
		AppOrchestrator.setBackupApplicationCounter(applicationId, counter);
		return Response.ok().build();
	}
	
	@Path("terminateApplication")
	@GET
	public Response terminateApplication(@QueryParam("applicationDnsName") String hostname) {
		Optional<String> applicationIdOptional = AppOrchestrator.getApplicationTargets().entrySet()
				.stream()
				.map(entry -> entry.getValue().getTargetInstance())
				.filter(target -> hostname.equals(target.getPublicDnsName()))
				.map(target -> target.getInstanceId())
				.findFirst();
		if (!applicationIdOptional.isPresent()) {
			System.out.println("No application instance available at host: " + hostname);
		}
		else{
			EC2.terminateEC2(applicationIdOptional.get());
		}
		return Response.ok().build();
	}
}
