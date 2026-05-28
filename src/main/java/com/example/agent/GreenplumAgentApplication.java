package com.example.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.system.ApplicationHome;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@SpringBootApplication
public class GreenplumAgentApplication {

    public static void main(String[] args) {
ApplicationHome home = new ApplicationHome(GreenplumAgentApplication.class);
        File dir = home.getDir();
        String jarDirectory = (dir != null) ? dir.getAbsolutePath() : System.getProperty("user.dir");
        String logFilePath = jarDirectory + File.separator + "greenplum-agent.log";

        // Feed the path directly into the Logback XML
        System.setProperty("AGENT_LOG_PATH", logFilePath);

        System.out.println("=========================================================");
        System.out.println("📄 LOGBACK INITIALIZING FILE AT: " + logFilePath);
        System.out.println("=========================================================");

        SpringApplication.run(GreenplumAgentApplication.class, args);
    }
}