package com.gp.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.File;

@SpringBootApplication
public class GreenplumAgentApplication {

    public static void main(String[] args) {
        String dataDir = resolveDataDir();
        new File(dataDir).mkdirs();

        String logFilePath = dataDir + File.separator + "greenplum-agent.log";
        System.setProperty("AGENT_LOG_PATH", logFilePath);

        System.out.println("=========================================================");
        System.out.println("DATA DIR      : " + dataDir);
        System.out.println("LOG FILE      : " + logFilePath);
        System.out.println("=========================================================");

        SpringApplication.run(GreenplumAgentApplication.class, args);
    }

    static String resolveDataDir() {
        String env = System.getenv("AGENT_DATA_DIR");
        if (env != null && !env.trim().isEmpty()) return env.trim();
        // Default: the directory the JVM was launched from (i.e. the app directory)
        return System.getProperty("user.dir");
    }
}
