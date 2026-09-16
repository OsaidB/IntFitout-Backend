package life.work.IntFit.backend.dto.daily_assignments;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.util.TokenBuffer;

import java.io.IOException;
import java.util.*;

/**
 * Reads {@code allocations} normally, but first records keys repeated inside an
 * entry. Jackson would otherwise keep the last value silently, which could
 * change which hours are saved; the planner rejects such entries instead.
 */
public class MemberAllocationListDeserializer extends StdDeserializer<List<MemberAllocationDTO>> {

    public MemberAllocationListDeserializer() {
        super(List.class);
    }

    @Override
    public List<MemberAllocationDTO> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        TokenBuffer buffer = ctxt.bufferAsCopyOfValue(p);
        Map<Integer, List<String>> duplicates = findDuplicateFields(buffer);

        JavaType type = ctxt.getTypeFactory().constructCollectionType(List.class, MemberAllocationDTO.class);
        List<MemberAllocationDTO> entries;
        try (JsonParser copy = buffer.asParserOnFirstToken()) {
            entries = ctxt.readValue(copy, type);
        }
        if (entries != null) {
            duplicates.forEach((index, fields) -> {
                if (index < entries.size() && entries.get(index) != null) {
                    entries.get(index).setDuplicateFields(fields);
                }
            });
        }
        return entries;
    }

    private static final class Frame {
        final boolean array;
        final String path;
        final Set<String> names = new HashSet<>();
        String field;
        int index = -1;

        Frame(boolean array, String path) {
            this.array = array;
            this.path = path;
        }

        String childPath() {
            if (array) {
                return path + "[" + index + "]";
            }
            return path.isEmpty() ? field : path + "." + field;
        }
    }

    /** Entry index → repeated key paths relative to that entry. */
    private static Map<Integer, List<String>> findDuplicateFields(TokenBuffer buffer) throws IOException {
        Map<Integer, List<String>> result = new HashMap<>();
        Deque<Frame> stack = new ArrayDeque<>();
        int entry = -1;
        try (JsonParser p = buffer.asParser()) {
            JsonToken token;
            while ((token = p.nextToken()) != null) {
                switch (token) {
                    case START_OBJECT, START_ARRAY -> {
                        Frame parent = stack.peek();
                        String path = "";
                        if (parent != null) {
                            if (parent.array) parent.index++;
                            if (stack.size() == 1) {
                                entry = parent.index;
                            } else {
                                path = parent.childPath();
                            }
                        }
                        stack.push(new Frame(token == JsonToken.START_ARRAY, path));
                    }
                    case END_OBJECT, END_ARRAY -> stack.pop();
                    case FIELD_NAME -> {
                        Frame frame = stack.peek();
                        String name = p.currentName();
                        if (frame != null && !frame.names.add(name) && entry >= 0) {
                            result.computeIfAbsent(entry, k -> new ArrayList<>())
                                    .add(frame.path.isEmpty() ? name : frame.path + "." + name);
                        }
                        if (frame != null) frame.field = name;
                    }
                    default -> {
                        Frame frame = stack.peek();
                        if (frame != null && frame.array) frame.index++;
                    }
                }
            }
        }
        return result;
    }
}
