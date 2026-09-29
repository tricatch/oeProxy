package tricatch.oe.proxy.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import tricatch.oe.proxy.http.io.HeaderLines;

public class JsonUtil {

    private static final ObjectMapper objectMapper = createObjectMapper();

    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        SimpleModule module = new SimpleModule();
        module.addSerializer(HeaderLines.class, new HeaderLinesJsonSerializer());
        mapper.registerModule(module);
        return mapper;
    }

    public static String pretty(Object object) throws JsonProcessingException {

        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(object);
    }

    public static String toJson(Object object) throws JsonProcessingException {

        return objectMapper.writeValueAsString(object);
    }

}
