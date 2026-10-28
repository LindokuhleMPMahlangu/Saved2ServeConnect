package com.saved2serve.lambda;

import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.Message;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

final class BedrockDraftGenerator {
    private static final Map<String, String> TOPIC_LABELS = Map.of(
            "community_encouragement", "a general message of encouragement for the community",
            "volunteer_invitation", "a respectful invitation to volunteer",
            "welcome_note", "a warm welcome note for a new community member",
            "prayer_support", "a gentle offer of prayer support"
    );

    private final BedrockRuntimeClient client;
    private final String modelId;

    BedrockDraftGenerator(BedrockRuntimeClient client, String modelId) {
        this.client = Objects.requireNonNull(client);
        this.modelId = Objects.requireNonNull(modelId);
    }

    String generate(String topic) {
        String subject = TOPIC_LABELS.get(topic);
        if (subject == null) {
            throw new IllegalArgumentException("Topic is unsupported.");
        }
        String prompt = "Write a concise, warm draft for an internal church leader to review. "
                + "Topic: " + subject + ". Use inclusive, non-judgmental language. "
                + "Do not invent personal details, make promises, give professional advice, "
                + "or claim the draft has been approved. Return only the draft text.";
        var response = client.converse(ConverseRequest.builder()
                .modelId(modelId)
                .messages(Message.builder()
                        .role(ConversationRole.USER)
                        .content(ContentBlock.fromText(prompt))
                        .build())
                .inferenceConfig(InferenceConfiguration.builder()
                        .maxTokens(350)
                        .temperature(0.3f)
                        .build())
                .build());
        return response.output().message().content().stream()
                .filter(content -> content.text() != null)
                .map(ContentBlock::text)
                .collect(Collectors.joining("\n"))
                .trim();
    }
}
