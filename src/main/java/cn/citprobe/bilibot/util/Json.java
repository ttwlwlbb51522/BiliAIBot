package cn.citprobe.bilibot.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;

public final class Json {

    public static final ObjectMapper BILIBILI = createMapper(true);
    public static final ObjectMapper DEEPSEEK = createMapper(true);
    public static final ObjectMapper STATE = createMapper(false);

    private Json() {
    }

    private static ObjectMapper createMapper(boolean snakeCase) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        if (snakeCase) {
            mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        }
        return mapper;
    }
}
