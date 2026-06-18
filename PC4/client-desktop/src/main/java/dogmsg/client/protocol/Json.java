package dogmsg.client.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Codificador / decodificador JSON minimo y SIN dependencias externas.
 *
 * <p>Solo soporta objetos planos {@code {"clave":valor,...}} donde los valores
 * son string, numero o booleano. Es suficiente para los payloads del protocolo
 * (AUTH_REQUEST, QR_GENERATE, metadata de archivo, comandos de grupo, etc.).
 * No soporta arrays ni objetos anidados a proposito, para mantenerlo simple y
 * predecible entre Java / Kotlin / Python.</p>
 */
public final class Json {

    private Json() {}

    /** Construye JSON a partir de un mapa ordenado de clave -> valor. */
    public static String encode(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escape(e.getKey())).append('"').append(':');
            Object v = e.getValue();
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append('"').append(escape(v.toString())).append('"');
            }
        }
        return sb.append('}').toString();
    }

    /** Builder fluido de conveniencia. */
    public static Builder obj() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<String, Object> map = new LinkedHashMap<>();
        public Builder put(String k, Object v) { map.put(k, v); return this; }
        public String build() { return encode(map); }
    }

    /**
     * Parsea un objeto plano JSON a un mapa. Todos los valores se devuelven como
     * String (el llamador convierte a numero/bool si lo necesita).
     */
    public static Map<String, String> decode(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        if (json == null) return result;
        String s = json.trim();
        if (s.isEmpty() || s.charAt(0) != '{') return result;

        int i = 1;
        int n = s.length();
        while (i < n) {
            // saltar espacios y comas
            while (i < n && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ',')) i++;
            if (i < n && s.charAt(i) == '}') break;
            if (i >= n) break;

            // clave (siempre string entre comillas)
            if (s.charAt(i) != '"') break;
            StringBuilder key = new StringBuilder();
            i = readString(s, i, key);

            // dos puntos
            while (i < n && Character.isWhitespace(s.charAt(i))) i++;
            if (i < n && s.charAt(i) == ':') i++;
            while (i < n && Character.isWhitespace(s.charAt(i))) i++;

            // valor
            StringBuilder val = new StringBuilder();
            if (i < n && s.charAt(i) == '"') {
                i = readString(s, i, val);
            } else {
                // numero / booleano / null hasta coma o cierre
                while (i < n && s.charAt(i) != ',' && s.charAt(i) != '}') {
                    val.append(s.charAt(i));
                    i++;
                }
            }
            result.put(key.toString(), val.toString().trim());
        }
        return result;
    }

    /**
     * Parsea un array JSON de objetos planos, como el payload de HISTORY_SYNC
     * (protocol_spec.md 3.13): {@code [{"id":1,"from":3,...}, {...}]}.
     * Cada elemento se decodifica con {@link #decode}. No soporta anidamiento
     * mas profundo a proposito (igual que decode()).
     */
    public static java.util.List<Map<String, String>> decodeArray(String json) {
        java.util.List<Map<String, String>> out = new java.util.ArrayList<>();
        if (json == null) return out;
        String s = json.trim();
        if (s.isEmpty() || s.charAt(0) != '[') return out;

        int i = 1;
        int n = s.length();
        while (i < n) {
            while (i < n && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ',')) i++;
            if (i >= n || s.charAt(i) == ']') break;
            if (s.charAt(i) != '{') break;

            int start = i;
            int depth = 0;
            boolean inStr = false;
            while (i < n) {
                char c = s.charAt(i);
                if (inStr) {
                    if (c == '\\' && i + 1 < n) { i += 2; continue; }
                    if (c == '"') inStr = false;
                } else {
                    if (c == '"') inStr = true;
                    else if (c == '{') depth++;
                    else if (c == '}') { depth--; if (depth == 0) { i++; break; } }
                }
                i++;
            }
            out.add(decode(s.substring(start, i)));
        }
        return out;
    }

    /** Lee un literal string JSON empezando en la comilla de apertura. */
    private static int readString(String s, int i, StringBuilder out) {
        i++; // saltar comilla inicial
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case 'n': out.append('\n'); break;
                    case 't': out.append('\t'); break;
                    case 'r': out.append('\r'); break;
                    case '"': out.append('"'); break;
                    case '\\': out.append('\\'); break;
                    case '/': out.append('/'); break;
                    default: out.append(next);
                }
                i += 2;
            } else if (c == '"') {
                return i + 1; // posicion despues de la comilla de cierre
            } else {
                out.append(c);
                i++;
            }
        }
        return i;
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }
}