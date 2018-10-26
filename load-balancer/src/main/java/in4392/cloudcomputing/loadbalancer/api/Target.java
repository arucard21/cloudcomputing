package in4392.cloudcomputing.loadbalancer.api;

import java.net.URI;

public class Target {
	private URI targetURI;
	private int currentAmountOfRequests;

	public Target(URI targetURI, int currentAmountOfRequests) {
		super();
		this.targetURI = targetURI;
		this.setCurrentAmountOfRequests(currentAmountOfRequests);
	}
	public URI getTargetURI() {
		return targetURI;
	}
	public void setTargetURI(URI targetURI) {
		this.targetURI = targetURI;
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
