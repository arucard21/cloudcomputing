package in4392.cloudcomputing.apporchestrator;

import com.amazonaws.services.ec2.model.Instance;

public class Target {
	private Instance targetInstance;
	private int currentAmountOfRequests;

	public Target(Instance targetInstance, int currentAmountOfRequests) {
		super();
		this.targetInstance = targetInstance;
		this.setCurrentAmountOfRequests(currentAmountOfRequests);
	}
	public Instance getTargetInstance() {
		return targetInstance;
	}
	public void setTargetInstance(Instance targetInstance) {
		this.targetInstance = targetInstance;
	}
	public int getCurrentAmountOfRequests() {
		return currentAmountOfRequests;
	}
	public void setCurrentAmountOfRequests(int currentAmountOfRequests) {
		this.currentAmountOfRequests = currentAmountOfRequests;
	}
	public void incrementCurrentAmountOfRequests() {
		this.currentAmountOfRequests++;
	}
	public void decrementCurrentAmountofRequests() {
		this.currentAmountOfRequests--;
	}
}
