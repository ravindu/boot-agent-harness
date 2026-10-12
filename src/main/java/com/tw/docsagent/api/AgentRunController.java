package com.tw.docsagent.api;

import com.tw.docsagent.agent.AgentLoop;
import com.tw.docsagent.agent.AgentRunResult;
import com.tw.docsagent.trace.RunTrace;
import com.tw.docsagent.trace.RunTraceRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Runs the agent. Synchronous in M1: the request waits for the loop to finish (bounded by
 * {@code docsagent.agent.run-timeout}). The result always carries an explicit status; a run that
 * hit a limit is still a 200 with that status, because the request itself succeeded.
 */
@RestController
@RequestMapping("/api/agent/runs")
@RequiredArgsConstructor
class AgentRunController {

    record AskRequest(@NotBlank @Size(max = 2000) String question) {
    }

    private final AgentLoop agentLoop;
    private final RunTraceRepository traces;

    @PostMapping
    AgentRunResult ask(@Valid @RequestBody AskRequest request) {
        return agentLoop.run(request.question().strip());
    }

    @GetMapping("/{runId}")
    RunTrace trace(@PathVariable UUID runId) {
        return traces.find(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No agent run " + runId));
    }
}
