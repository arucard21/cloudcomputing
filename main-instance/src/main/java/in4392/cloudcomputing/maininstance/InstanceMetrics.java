package in4392.cloudcomputing.maininstance;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.amazonaws.services.cloudwatch.model.Datapoint;
import com.amazonaws.services.ec2.model.Instance;

public class InstanceMetrics {
	private Instance instance;
	private Map<String, List<Datapoint>> cloudWatchMetrics = new HashMap<>();
	

	public Map<String, List<Datapoint>> getCloudWatchMetrics() {
		return cloudWatchMetrics;
	}

	public void setCloudWatchMetrics(Map<String, List<Datapoint>> cloudWatchMetrics) {
		this.cloudWatchMetrics = cloudWatchMetrics;
	}

	public Instance getInstance() {
		return instance;
	}

	public void setInstance(Instance instance) {
		this.instance = instance;
	}
}
