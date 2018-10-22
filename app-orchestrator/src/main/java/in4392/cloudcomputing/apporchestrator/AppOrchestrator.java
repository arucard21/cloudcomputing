package in4392.cloudcomputing.apporchestrator;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Named;

import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.CreateKeyPairRequest;
import com.amazonaws.services.ec2.model.CreateKeyPairResult;
import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.KeyPair;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;

@Named
public class AppOrchestrator {
	private static final int ITERATION_WAIT_TIME = 1000;
	private static boolean keepAlive;
	private static final String KEY_NAME = "cloudcomputing";
	private static final String AMI_LOAD_BALANCER = "amixxx";
	
	
	private AmazonEC2 amazonEC2Client = AmazonEC2ClientBuilder.standard().build();
	private KeyPair keyPair;
	private String loadBalancerURI;
	private List<String> appInstancesURI = new ArrayList<String>();
	
	public void deployEC2Instance(String ami) {
		CreateKeyPairRequest createKeyPairRequest = new CreateKeyPairRequest();
		createKeyPairRequest.withKeyName(KEY_NAME);
		CreateKeyPairResult createKeyPairResult = amazonEC2Client.createKeyPair(createKeyPairRequest);
		
		KeyPair keyPair = new KeyPair();
		this.keyPair = createKeyPairResult.getKeyPair();
		String privateKey = keyPair.getKeyMaterial();
		
		RunInstancesRequest runInstancesRequest = new RunInstancesRequest();

		runInstancesRequest.withImageId(ami)
				           .withInstanceType(InstanceType.T1Micro)
				           .withMinCount(1)
				           .withMaxCount(1)
				           .withKeyName(KEY_NAME)
				           // haven't define this
				           .withSecurityGroups("my-security-group");
		
		RunInstancesResult result = amazonEC2Client.runInstances(runInstancesRequest);
		String uRI = result.getReservation().getInstances().get(0).getPublicDnsName();
		
		if (!ami.equals(AMI_LOAD_BALANCER)) {
			// add check that this does not already exists
			this.appInstancesURI.add(uRI);
		}else loadBalancerURI = uRI;
	}
	
	
	/**
	 * run until stopped through API
	 */
	public static void run() {
		// TODO do one-time things here
		keepAlive = true;
		while(keepAlive) {
			System.out.println("Do periodic things here");
			waitUntilNextIteration();
		}
	}

	/**
	 * Stop the main loop on the running master instance
	 */
	public static void destroy() {
		System.out.println("Destroying the Main Instance application");
		keepAlive = false;
	}

	/**
	 * Wait a specific amount of time before
	 */
	private static void waitUntilNextIteration() {
		try {
			Thread.sleep(ITERATION_WAIT_TIME);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
}
