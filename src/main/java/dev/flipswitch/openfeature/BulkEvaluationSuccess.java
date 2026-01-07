package dev.flipswitch.openfeature;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * OFREP bulk evaluation response.
 * Contains the evaluation results for all flags.
 */
public class BulkEvaluationSuccess {

    private final List<EvaluationResult> flags;
    private final Map<String, Object> metadata;

    @JsonCreator
    public BulkEvaluationSuccess(
            @JsonProperty("flags") List<EvaluationResult> flags,
            @JsonProperty("metadata") Map<String, Object> metadata) {
        this.flags = flags;
        this.metadata = metadata;
    }

    public List<EvaluationResult> getFlags() {
        return flags;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }
}
