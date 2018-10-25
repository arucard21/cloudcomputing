package in4392.cloudcomputing.apporchestrator;

import com.amazonaws.services.ec2.model.Instance;

public class Target {
	private Instance targetInstance;
	private boolean isFree;
	private int currentAmountOfRequests;

	public Target(Instance targetInstance, boolean isFree, int currentAmountOfRequests) {
		super();
		this.targetInstance = targetInstance;
		this.isFree = isFree;
		this.setCurrentAmountOfRequests(currentAmountOfRequests);
	}
	public Instance getTargetInstance() {
		return targetInstance;
	}
	public void setTargetInstance(Instance targetInstance) {
		this.targetInstance = targetInstance;
	}
	public boolean isFree() {
		return isFree;
	}
	public void setFree(boolean isFree) {
		this.isFree = isFree;
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
