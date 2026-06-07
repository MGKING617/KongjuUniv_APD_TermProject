package com.termproject.mentalhealth.api;

import com.termproject.mentalhealth.dto.AiSummaryRequest;
import com.termproject.mentalhealth.dto.AiSummaryResponse;
import com.termproject.mentalhealth.dto.LikertScoreRequest;
import com.termproject.mentalhealth.dto.LikertScoreResponse;
import com.termproject.mentalhealth.service.LlmService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/llm")
public class LlmController {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LlmService llmService;

    public LlmController(LlmService llmService) {
        this.llmService = llmService;
    }

    @PostMapping("/score-likert")
    public LikertScoreResponse scoreLikert(@Valid @RequestBody LikertScoreRequest request) {
        return llmService.scoreLikertAnswer(request.question(), request.answer());
    }

    @PostMapping("/assessment-summary")
    public AiSummaryResponse summarizeAssessment(@RequestBody AiSummaryRequest request) {
        return llmService.summarizeAssessment(request);
    }

    @PostMapping(value = "/assessment-summary/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public StreamingResponseBody streamAssessmentSummary(@RequestBody AiSummaryRequest request) {
        return outputStream -> {
            AiSummaryResponse response = llmService.streamAssessmentSummary(
                    request,
                    chunk -> writeSseUnchecked(outputStream, "delta", Map.of("content", chunk))
            );
            writeSse(outputStream, "done", response);
        };
    }

    private void writeSseUnchecked(OutputStream outputStream, String event, Object data) {
        try {
            writeSse(outputStream, event, data);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private void writeSse(OutputStream outputStream, String event, Object data) throws IOException {
        String payload = "event: " + event + "\n"
                + "data: " + objectMapper.writeValueAsString(data) + "\n\n";
        outputStream.write(payload.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }
}
