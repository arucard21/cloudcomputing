package in4392.cloudcomputing.application.api;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import javax.inject.Named;
import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

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
	
	@Path("log")
	@GET
	@Produces(MediaType.TEXT_PLAIN)
	public String showLog() throws IOException {
		return new String(Files.readAllBytes(Paths.get("/home/ubuntu/application.log")), StandardCharsets.UTF_8);
	}
	
	@Path("video")
	@POST
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	@Consumes(MediaType.APPLICATION_OCTET_STREAM)
	public InputStream convert(
			@Context
			UriInfo uriInfo,
			InputStream data, 
			@DefaultValue("0") 
			@QueryParam("delayApplication") 
			int delayApplication) throws IOException, InterruptedException {
		String outputFormat = ".mkv";
		if(delayApplication > 0) {
			Thread.sleep(delayApplication * 1000);
		}
        File inputFile =  Paths.get(UUID.randomUUID().toString()).toFile();
        File outputFile =  Paths.get(UUID.randomUUID().toString( )+ outputFormat).toFile();
        
        Files.copy(data, inputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        outputFile.createNewFile();
		
        String cmd = String.format(
        		"ffmpeg -y -i %s -codec:v libx264 -codec:a copy %s", 
        		inputFile.getName(), 
        		outputFile.getName());
        File workingDirectory = inputFile.getAbsoluteFile().toPath().getParent().toFile();
     	System.out.println("Executing command: " + cmd);
     	System.out.println("\nFrom working directory: " + workingDirectory);
        Process p = Runtime.getRuntime().exec(cmd, null, workingDirectory);
        int result = p.waitFor();
        
        System.out.println("Process exit code: " + result);
        System.out.println();
        System.out.println("Result:");
        try(BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))){	        	
        	while (reader.ready()) {
        		System.out.println(reader.readLine());
        	}
        }

		if(inputFile.delete()) { 
	    	System.out.println("Input file deleted successfully"); 
	    } 
	    else { 
	    	System.out.println("Failed to delete the input file"); 
	    }
		try(ByteArrayInputStream inMemOutputFile = new ByteArrayInputStream(Files.readAllBytes(outputFile.toPath()))){
			if(outputFile.delete()) {
				System.out.println("Output file deleted successfully");
			}
			else {
				System.out.println("Failed to delete output file");
			}
			return inMemOutputFile;
		}
	}
}
