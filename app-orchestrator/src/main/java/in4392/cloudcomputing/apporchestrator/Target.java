package in4392.cloudcomputing.apporchestrator;

import java.net.URI;

public class Target {
	private String targetURI;
	private boolean isFree;
	private int currentAmountOfRequests;

	public Target(String targetURI, boolean isFree, int currentAmountOfRequests) {
		super();
		this.targetURI = targetURI;
		this.isFree = isFree;
		this.setCurrentAmountOfRequests(currentAmountOfRequests);
	}
	public String getTargetURI() {
		return targetURI;
	}
	public void setTargetURI(String targetURI) {
		this.targetURI = targetURI;
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
