package com.termproject.mentalhealth.api;

import com.termproject.mentalhealth.dto.ChatMessageResponse;
import com.termproject.mentalhealth.dto.ChatSessionResponse;
import com.termproject.mentalhealth.dto.CreateChatSessionRequest;
import com.termproject.mentalhealth.dto.RegenerateChatRequest;
import com.termproject.mentalhealth.dto.RegenerateChatResponse;
import com.termproject.mentalhealth.dto.SendChatMessageRequest;
import com.termproject.mentalhealth.dto.StartSurveyChatRequest;
import com.termproject.mentalhealth.dto.SurveyChatStartResponse;
import com.termproject.mentalhealth.dto.SurveyChatTurnRequest;
import com.termproject.mentalhealth.dto.SurveyChatTurnResponse;
import com.termproject.mentalhealth.dto.ChatMessageDto;
import com.termproject.mentalhealth.service.LlmService;
import com.termproject.mentalhealth.service.ChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChatService chatService;
    private final LlmService llmService;

    public ChatController(ChatService chatService, LlmService llmService) {
        this.chatService = chatService;
        this.llmService = llmService;
    }

    @PostMapping("/sessions")
    public ChatSessionResponse createSession(@Valid @RequestBody CreateChatSessionRequest request) {
        return chatService.createSession(request.userId());
    }

    @PostMapping("/sessions/{sessionId}/messages")
    public ChatMessageResponse sendMessage(
            @PathVariable Long sessionId,
            @Valid @RequestBody SendChatMessageRequest request
    ) {
        return chatService.sendMessage(sessionId, request.content(), request.tone());
    }

    @PostMapping(value = "/sessions/{sessionId}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public StreamingResponseBody streamMessage(
            @PathVariable Long sessionId,
            @Valid @RequestBody SendChatMessageRequest request
    ) {
        return outputStream -> {
            ChatService.StreamedChatStart start = chatService.beginStreamedMessage(sessionId, request.content());
            String botContent = streamBotContent(outputStream, request.content(), request.tone(), start.riskEvent() == null, start.riskEvent() == null ? null : start.riskEvent().actionTaken());
            ChatMessageDto botMessage = chatService.saveStreamedBotMessage(sessionId, botContent);
            writeSse(outputStream, "done", new ChatMessageResponse(start.userMessage(), botMessage, start.riskEvent()));
        };
    }

    @PostMapping("/sessions/{sessionId}/regenerate")
    public RegenerateChatResponse regenerateReply(
            @PathVariable Long sessionId,
            @Valid @RequestBody RegenerateChatRequest request
    ) {
        return chatService.regenerateReply(sessionId, request.content(), request.tone());
    }

    @PostMapping(value = "/sessions/{sessionId}/regenerate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public StreamingResponseBody streamRegeneratedReply(
            @PathVariable Long sessionId,
            @Valid @RequestBody RegenerateChatRequest request
    ) {
        return outputStream -> {
            ChatService.StreamedChatStart start = chatService.beginStreamedRegenerate(sessionId, request.content());
            String botContent = streamBotContent(outputStream, request.content(), request.tone(), start.riskEvent() == null, start.riskEvent() == null ? null : start.riskEvent().actionTaken());
            ChatMessageDto botMessage = chatService.saveStreamedBotMessage(sessionId, botContent);
            writeSse(outputStream, "done", new RegenerateChatResponse(botMessage, start.riskEvent()));
        };
    }

    @PostMapping("/sessions/{sessionId}/survey/start")
    public SurveyChatStartResponse startSurveyChat(
            @PathVariable Long sessionId,
            @Valid @RequestBody StartSurveyChatRequest request
    ) {
        return chatService.startSurveyChat(
                sessionId,
                request.questionTitle(),
                request.questionIndex(),
                request.totalQuestions(),
                request.tone()
        );
    }

    @PostMapping("/sessions/{sessionId}/survey/turn")
    public SurveyChatTurnResponse sendSurveyTurn(
            @PathVariable Long sessionId,
            @Valid @RequestBody SurveyChatTurnRequest request
    ) {
        return chatService.sendSurveyTurn(
                sessionId,
                request.content(),
                request.questionTitle(),
                request.nextQuestionTitle(),
                request.remainingQuestions(),
                request.questionIndex(),
                request.totalQuestions(),
                request.tone()
        );
    }

    private String streamBotContent(OutputStream outputStream, String content, String tone, boolean useLlm, String safetyGuide) throws IOException {
        if (!useLlm) {
            writeSse(outputStream, "delta", Map.of("content", safetyGuide));
            return safetyGuide;
        }
        return llmService.streamSupportiveReply(content, tone, chunk -> writeSseUnchecked(outputStream, "delta", Map.of("content", chunk)));
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
