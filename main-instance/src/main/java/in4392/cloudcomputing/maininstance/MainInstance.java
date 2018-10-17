package in4392.cloudcomputing.maininstance;

import javax.inject.Named;

@Named
public class MainInstance {
	private static final int ITERATION_WAIT_TIME = 1000;
	private static boolean keepAlive;
	private static boolean shadowDeployed;
	private static boolean isShadow;

	/**
	 * run main instance until stopped through API
	 */
	public static void run() {
		if (!isShadow && !shadowDeployed) {
			deployShadow();
		}
		keepAlive = true;
		while(keepAlive) {
			if (isShadow) {
				System.out.println("Do something useful here for the shadow instance, like checking the main instance");
			}
			else{
				System.out.println("Do something useful here for the main instance, like monitoring");
			}
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

	public static boolean isShadowDeployed() {
		return shadowDeployed;
	}

	public static boolean isShadow() {
		return isShadow;
	}

	public static void setShadow(boolean isShadow) {
		MainInstance.isShadow = isShadow;
	}

	private static void deployShadow() {
		// TODO actually deploy the shadow
		shadowDeployed = true;
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
