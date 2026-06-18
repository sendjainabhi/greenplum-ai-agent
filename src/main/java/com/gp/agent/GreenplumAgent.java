package com.gp.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface GreenplumAgent {

    @SystemMessage(fromResource = "system-prompt.txt")
    String chat(@MemoryId String memoryId, @UserMessage String userMessage);
}
