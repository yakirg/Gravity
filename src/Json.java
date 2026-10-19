import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON writer/parser. Values: Map, List, String, Double, Boolean, null. */
final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i < text.length())
            throw p.err("unexpected trailing text");
        return v;
    }

    static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(v, sb, 0);
        return sb.append('\n').toString();
    }

    private static void indent(StringBuilder sb, int n) {
        sb.append('\n');
        for (int k = 0; k < n; k++)
            sb.append("  ");
    }

    @SuppressWarnings("unchecked")
    private static void write(Object v, StringBuilder sb, int d) {
        if (v == null)
            sb.append("null");
        else if (v instanceof String str) {
            sb.append('"');
            for (char c : str.toCharArray()) {
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20)
                            sb.append(String.format("\\u%04x", (int) c));
                        else
                            sb.append(c);
                    }
                }
            }
            sb.append('"');
        } else if (v instanceof Number || v instanceof Boolean)
            sb.append(v);
        else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
                if (!first)
                    sb.append(',');
                first = false;
                indent(sb, d + 1);
                write(e.getKey(), sb, d + 1);
                sb.append(": ");
                write(e.getValue(), sb, d + 1);
            }
            indent(sb, d);
            sb.append('}');
        } else if (v instanceof List<?> l) {
            if (l.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append('[');
            boolean first = true;
            for (Object o : l) {
                if (!first)
                    sb.append(',');
                first = false;
                indent(sb, d + 1);
                write(o, sb, d + 1);
            }
            indent(sb, d);
            sb.append(']');
        } else
            throw new IllegalArgumentException("Unsupported JSON value " + v.getClass());
    }

    private IllegalArgumentException err(String m) {
        return new IllegalArgumentException("Invalid JSON at character " + i + ": " + m);
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i)))
            i++;
    }

    private void expect(char c) {
        if (i >= s.length() || s.charAt(i) != c)
            throw err("expected '" + c + "'");
        i++;
    }

    private Object value() {
        if (i >= s.length())
            throw err("unexpected end");
        char c = s.charAt(i);
        if (c == '{') {
            i++;
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            if (s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                String k = string();
                ws();
                expect(':');
                ws();
                m.put(k, value());
                ws();
                if (i < s.length() && s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                expect('}');
                return m;
            }
        }
        if (c == '[') {
            i++;
            List<Object> l = new ArrayList<>();
            ws();
            if (s.charAt(i) == ']') {
                i++;
                return l;
            }
            while (true) {
                ws();
                l.add(value());
                ws();
                if (i < s.length() && s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                expect(']');
                return l;
            }
        }
        if (c == '"')
            return string();
        if (s.startsWith("true", i)) {
            i += 4;
            return Boolean.TRUE;
        }
        if (s.startsWith("false", i)) {
            i += 5;
            return Boolean.FALSE;
        }
        if (s.startsWith("null", i)) {
            i += 4;
            return null;
        }
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0)
            i++;
        if (st == i)
            throw err("unexpected character '" + c + "'");
        return Double.parseDouble(s.substring(st, i));
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (i < s.length() && s.charAt(i) != '"') {
            char c = s.charAt(i++);
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> sb.append(e);
                }
            } else
                sb.append(c);
        }
        expect('"');
        return sb.toString();
    }
}
