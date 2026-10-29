package com.tw.docsagent.api;

import com.tw.docsagent.agent.AgentLoop;
import com.tw.docsagent.agent.AgentRunResult;
import com.tw.docsagent.agent.NoSuchRunException;
import com.tw.docsagent.agent.RunNotWaitingException;
import com.tw.docsagent.agent.RunStatus;
import com.tw.docsagent.trace.RunTrace;
import com.tw.docsagent.trace.RunTraceRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Runs the agent. Synchronous: the request waits until the loop finishes or pauses (bounded by
 * {@code docsagent.agent.run-timeout}). A run that hit a limit is still a 200 with that status.
 *
 * <p>A run that asked the user a question is a 202: it is not finished, and it continues when
 * {@code POST /{runId}/answer} arrives (M3).
 */
@RestController
@RequestMapping("/api/agent/runs")
@RequiredArgsConstructor
class AgentRunController {

    record AskRequest(@NotBlank @Size(max = 2000) String question) {
    }

    record AnswerRequest(@NotBlank @Size(max = 2000) String answer) {
    }

    private final AgentLoop agentLoop;
    private final RunTraceRepository traces;

    @PostMapping
    ResponseEntity<AgentRunResult> ask(@Valid @RequestBody AskRequest request) {
        return respond(agentLoop.run(request.question().strip()));
    }

    @PostMapping("/{runId}/answer")
    ResponseEntity<AgentRunResult> answer(@PathVariable UUID runId, @Valid @RequestBody AnswerRequest request) {
        try {
            return respond(agentLoop.resume(runId, request.answer().strip()));
        } catch (NoSuchRunException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (RunNotWaitingException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @GetMapping("/{runId}")
    RunTrace trace(@PathVariable UUID runId) {
        return traces.find(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No agent run " + runId));
    }

    private static ResponseEntity<AgentRunResult> respond(AgentRunResult result) {
        HttpStatus status = result.status() == RunStatus.WAITING_FOR_USER ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result);
    }
}
