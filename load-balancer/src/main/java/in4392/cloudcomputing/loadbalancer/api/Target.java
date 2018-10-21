package in4392.cloudcomputing.loadbalancer.api;

import java.net.URI;

public class Target {
	private URI targetURI;
	private boolean isFree;
	private int currentAmountOfRequests;

	public Target(URI targetURI, boolean isFree, int currentAmountOfRequests) {
		super();
		this.targetURI = targetURI;
		this.isFree = isFree;
		this.setCurrentAmountOfRequests(currentAmountOfRequests);
	}
	public URI getTargetURI() {
		return targetURI;
	}
	public void setTargetURI(URI targetURI) {
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
}
