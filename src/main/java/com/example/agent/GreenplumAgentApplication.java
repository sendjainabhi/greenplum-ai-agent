package com.example.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.system.ApplicationHome;

import java.io.File;

@SpringBootApplication
public class GreenplumAgentApplication {

    public static void main(String[] args) {
        
        // Dynamically determine the application's running directory to place logs next to the JAR file
        ApplicationHome home = new ApplicationHome(GreenplumAgentApplication.class);
        File dir = home.getDir();
        String jarDirectory = (dir != null) ? dir.getAbsolutePath() : System.getProperty("user.dir");
        String logFilePath = jarDirectory + File.separator + "greenplum-agent.log";

        // Feed the resolved path directly into the Logback XML configuration
        System.setProperty("AGENT_LOG_PATH", logFilePath);

        System.out.println("=========================================================");
        System.out.println("📄 LOGBACK INITIALIZING FILE AT: " + logFilePath);
        System.out.println("=========================================================");

        SpringApplication.run(GreenplumAgentApplication.class, args);
    }
}