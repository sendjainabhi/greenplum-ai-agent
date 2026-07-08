package com.gp.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface OpenMetadataAgent {

    @SystemMessage(fromResource = "system-prompt-openmetadata.txt")
    String chat(@MemoryId String memoryId, @UserMessage String userMessage);
}
