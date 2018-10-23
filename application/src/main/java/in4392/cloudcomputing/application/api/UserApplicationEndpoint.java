package in4392.cloudcomputing.application.api;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

import javax.inject.Named;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Named
@Path("application")
public class UserApplicationEndpoint {
	/**
	 * 
	 * @return a 204 HTTP status with no content, if successful
	 */
	@Path("health")
	@GET
	@Produces(MediaType.APPLICATION_JSON)
	public Response healthCheck() {
		return Response.noContent().build();
	}
	
	@Path("video")
	@POST
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	@Consumes(MediaType.APPLICATION_OCTET_STREAM)
	public Response convert(InputStream data) {
		//ProcessBuilder builder = new ProcessBuilder();
	    //builder.command("ffmpeg -i "+ inp.avi + " -codec:v libx264 -codec:a copy " + + ".mkv");
		byte[] buffer = new byte[4096];
		int n;
		
		try {
			OutputStream inp = new FileOutputStream(new File("input"));
			try {
				while ((n = data.read(buffer)) != -1) 
				{
				    inp.write(buffer, 0, n);
				}
				inp.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
		} catch (FileNotFoundException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		
		try {
	        String cmd = "ffmpeg -i  input -codec:v libx264 -codec:a copy input.mkv";
	        //String cmd = "ls " + dir;
			System.out.println("Executing command: " + cmd);
	        Process p = Runtime.getRuntime().exec(cmd);
	        int result = p.waitFor();
	        
	        System.out.println("Process exit code: " + result);
	        System.out.println();
	        System.out.println("Result:");
	        BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));

	        String line = "";
	        while ((line = reader.readLine()) != null) {
	        System.out.println(line);
	        }

	    } catch (Exception e) {
	        e.printStackTrace();
	    }
		
		 File file = new File("input.mkv");
		 return Response.ok(file, MediaType.APPLICATION_OCTET_STREAM)
		            .build();
		
	}
	
	
	
}
