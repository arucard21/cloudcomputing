package in4392.cloudcomputing.apporchestrator;

import javax.inject.Named;

@Named
public class AppOrchestrator {
	private static final int ITERATION_WAIT_TIME = 1000;
	private static boolean keepAlive;

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
