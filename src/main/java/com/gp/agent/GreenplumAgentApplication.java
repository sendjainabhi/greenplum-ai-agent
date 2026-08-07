package com.gp.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.File;

@SpringBootApplication
@EnableScheduling
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

    public static String resolveDataDir() {
        // 1. Honour explicit env var if the path exists and is writable
        String env = System.getenv("AGENT_DATA_DIR");
        if (env != null && !env.trim().isEmpty()) {
            File d = new File(env.trim());
            if ((d.exists() || d.mkdirs()) && d.canWrite()) return d.getAbsolutePath();
        }

        // 2. Read the actual mount path from CF block-storage volume_mounts in VCAP_SERVICES
        String vcap = System.getenv("VCAP_SERVICES");
        if (vcap != null && !vcap.isBlank()) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(vcap);
                for (JsonNode services : root) {
                    for (JsonNode svc : services) {
                        JsonNode mounts = svc.path("volume_mounts");
                        if (mounts.isArray()) {
                            for (JsonNode mount : mounts) {
                                String dir  = mount.path("container_dir").asText("").trim();
                                String mode = mount.path("mode").asText("rw");
                                if (!dir.isEmpty() && !"ro".equals(mode)) {
                                    File d = new File(dir);
                                    if (d.exists() && d.canWrite()) return dir;
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // 3. Fallback to the JVM working directory
        return System.getProperty("user.dir");
    }
}
