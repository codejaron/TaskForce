package com.agent.domain.taskboard.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class IntegerListDeserializer extends JsonDeserializer<List<Integer>> {

    @Override
    public List<Integer> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonToken token = p.currentToken();
        if (token == null) {
            token = p.nextToken();
        }

        if (token == JsonToken.VALUE_NULL) {
            return new ArrayList<>();
        }

        if (token == JsonToken.START_OBJECT) {
            p.skipChildren();
            return new ArrayList<>();
        }

        if (token == JsonToken.START_ARRAY) {
            List<Integer> result = new ArrayList<>();
            while (p.nextToken() != JsonToken.END_ARRAY) {
                JsonToken itemToken = p.currentToken();
                if (itemToken == JsonToken.VALUE_NUMBER_INT) {
                    result.add(p.getIntValue());
                } else if (itemToken == JsonToken.VALUE_STRING) {
                    String value = p.getValueAsString();
                    if (value != null && !value.isBlank()) {
                        result.add(Integer.parseInt(value));
                    }
                } else if (itemToken == JsonToken.START_OBJECT || itemToken == JsonToken.START_ARRAY) {
                    p.skipChildren();
                }
            }
            return result;
        }

        ctxt.reportInputMismatch(List.class, "Expected array/object/null for integer list, got %s", token);
        return new ArrayList<>();
    }
}
