package in4392.cloudcomputing.test.system;

import java.net.URI;
import java.net.URISyntaxException;

import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.amazonaws.services.ec2.model.Instance;

import in4392.cloudcomputing.maininstance.EC2;

public class AppOrchestratorTest extends SystemTest {
	/**
	 * maximum amount of recovery iterations to wait for an instance to be recovered
	 * 
	 * This can take quite some time since it needs:
	 * - at least 1 minute for the next iteration to try and detect the broken instance
	 * - some time to deploy the new instance
	 * - some time to wait for the new instance to be running 
	 * - some time to copy over the application files
	 * - some time to start the application
	 * - some time to configure the state of the application 
	 * 
	 * For now, we wait 10 iterations, which is 10 minutes
	 */
	private static int RECOVERY_WAIT_MAX_ITERATIONS = 10;
	/**
	 * Amount of time to wait before checking the recovery again, 1 minute
	 */
	private static int RECOVERY_WAIT_ITERATION = 60 * 1000;
	
	@Test
	public void appOrchestratorRecoverLoadBalancer() throws URISyntaxException, InterruptedException {
		String terminatedLoadBalancerId = loadBalancer.getInstanceId();
		EC2.terminateEC2(terminatedLoadBalancerId);
		while(true) {
			if (EC2.retrieveEC2InstanceWithId(terminatedLoadBalancerId).getState().getCode() == EC2.INSTANCE_TERMINATED) {
				break;
			}
		}
		for (int i = 0; i < RECOVERY_WAIT_MAX_ITERATIONS; i++) {
			Thread.sleep(RECOVERY_WAIT_ITERATION);
			URI applicationOrchestratorDescribeLoadBalancerURI = UriBuilder.fromUri(applicationOrchestratorURI)
					.port(8080)
					.path("application-orchestrator")
					.path("instances")
					.path("load-balancer")
					.build();
			Instance recoveredloadBalancer = client.target(applicationOrchestratorDescribeLoadBalancerURI)
					.request()
					.get(Instance.class);
			if (!terminatedLoadBalancerId.equals(recoveredloadBalancer.getInstanceId())) {
				URI recoveredLoadBalancerHealthURI = UriBuilder.fromPath("")
						.scheme("http")
						.host(recoveredloadBalancer.getPublicDnsName())
						.path("load-balancer")
						.path("health")
						.build();
				Response healthResponse = client.target(recoveredLoadBalancerHealthURI)
						.request()
						.get();
				if(healthResponse.getStatus() == 204) {
					return;
				}
			}
		}
		Assertions.fail("The load balancer did not recover within an acceptable amount of time");
	}
}
