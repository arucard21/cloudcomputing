package in4392.cloudcomputing.loadbalancer.api;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.inject.Named;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.PATCH;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;


@Named
@Path("")
@Produces(MediaType.APPLICATION_JSON)
public class LoadBalancerEndpoint {
	
	static final int maxRequestsPerInstance = 5;
	static final int minFreeInstances = 10;
	int index;
	List<WebTarget> targets;
	List<URI> targetsURI;
	List<Integer> requestsPerInstance;
	List<Boolean> isInstanceFree;
	URI reliabilityURI;
	WebTarget reliabilityInstance;
	
	
	public LoadBalancerEndpoint(){
		this.index = -1;
		this.targets = new ArrayList<WebTarget>();
		this.targetsURI = new ArrayList<URI>();
		this.requestsPerInstance = new ArrayList<Integer>();
		this.isInstanceFree = new ArrayList<Boolean>();
		
		this.reliabilityInstance = ClientBuilder.newBuilder()
												.property("connection.timeout", 100)
												.build()
												.target(this.reliabilityURI);
	}
	
	@Path("health")
	@GET
	public Response healthCheck() {
		return Response.noContent().build();
	}
	
	
	/**
	 * Send the request to the correct instance in order to balance the load.
	 * 
	 * @return the response from the instance
	 */
	@Path("")
	@GET
	public Response  getInstanceResponse() {
		//Client client = ClientBuilder.newBuilder().property("connection.timeout", 100).build();
		Response response = targets.get(index).request().get();
	
		
		
		try {
			if (response.getStatus() == 200){
				return Response.ok().build();
			}else {
				//something else
			}
			
		}finally {
			
			response.close();
		}
		return Response.ok().build();
	}
	
	
	@POST
	
	/**
	 * add an instance to the LoadBalancer list
	 * @param uri
	 */
	@PUT
	public void addTarget(URI uri) {
		int flag = 0; 
		
		Iterator<WebTarget> it = targets.iterator();
		while(it.hasNext()) {
			if(it.next().getUri()==uri) {
				System.out.println("Already exists");
				flag = 1;
				break;
			}
		}
		if (flag==0) {
			Client client = ClientBuilder.newBuilder().property("connection.timeout", 100).build();
			
	 
			this.targets.add(client.target(uri));
			this.requestsPerInstance.add(0);
			this.isInstanceFree.add(true);
			
			//Since new targets are created the next free is the first new instance appended to the list
			//index = it.size();
		}
	}
	
	@PATCH
	
	
	/**
	 * Delete an instance from the list of LoadBalancer
	 * @param uri
	 */
	@DELETE
	public void removeTarget(URI uri) {
		Iterator<WebTarget> it = targets.iterator();
		int c=0;
		
		while (it.hasNext()) {
			if(it.next().getUri()==uri) {
				this.targets.remove(c);
				this.isInstanceFree.remove(c);
				this.requestsPerInstance.remove(c);
			}
			else System.out.println("Not found\n");
		}
		
	}
	
	public void countFreeInstances() {
		Iterator<Boolean> it = this.isInstanceFree.iterator();
		int count = 0;
		
		while (it.hasNext()) {
			if (it.next() == true)
				count++;
		}
		if (count < minFreeInstances) {
			//addTarget()
			requestNewInstances(minFreeInstances - count, reliabilityURI);
			
		}
	}
	
	/**
	 * talk to the reliability instance for those
	 * @param num
	 */
	@Path("reliability/")
	public void requestNewInstances(int num, URI uri) {
		MultivaluedMap<String, String> formData = new MultivaluedHashMap<String, String>();
	    formData.add("numNewInstances", String.valueOf(num));
		
	    //add accept
		Response response = reliabilityInstance.request().post(Entity.form(formData));
	}
	
	public void updateList() {
	
		MultivaluedMap<String, Object> uriList = reliabilityInstance.request().get().getHeaders();
		Iterator<URI> it = targetsURI.iterator();
		while (it.hasNext()) {
			if (!uriList.containsValue((URI) it.next())) {
				this.removeTarget(it.next());
				targetsURI.remove(it.next());
			}
		}
		Iterator<String> itt = uriList.keySet().iterator();
		
		while(it.hasNext()) {
			if (!targetsURI.contains(uriList.get(itt.next()))) {
				//add and update index
			}
			
		}
			
	}
		
		
		
	
	
	public Response incomingRequest(@Context HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		// TODO implement load balancer logic
		this.countFreeInstances();
		this.requestsPerInstance.set(index, this.requestsPerInstance.get(index) + 1);
		if (this.requestsPerInstance.get(index).intValue() == maxRequestsPerInstance) this.isInstanceFree.set(index, false);
		
		
		RequestDispatcher rd = request.getRequestDispatcher(targets.get(index).getUri().getPath());
		rd.forward(request, response);
		
		
		
		return Response.ok().build();
	}
}
