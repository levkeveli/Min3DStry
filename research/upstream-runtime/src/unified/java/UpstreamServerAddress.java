import mindustry.Vars;

/** Direct join address using the stock JoinDialog host/port convention over libctru's IPv4 transport. */
record UpstreamServerAddress(String host, int port) {
    static UpstreamServerAddress parseInput(byte[] text, int length) {
        // libctru returns UTF-8. IPv4 addresses and DNS names accepted by this transport are ASCII.
        var address = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            if (text[i] < 0) return null;
            address.append((char)text[i]);
        }
        return parse(address.toString());
    }

    static UpstreamServerAddress parse(String text) {
        String address = text.strip();
        int separator = address.indexOf(':');
        String host = separator < 0 ? address : address.substring(0, separator);
        if (host.isEmpty() || host.length() > 253 || !host.matches("[a-zA-Z0-9._-]+")
                || !host.matches(".*[a-zA-Z0-9].*")) {
            return null;
        }
        if (separator < 0) return new UpstreamServerAddress(host, Vars.port);

        String digits = address.substring(separator + 1);
        if (!digits.matches("[0-9]{1,5}")) return null;
        int port = Integer.parseInt(digits);
        if (port < 1 || port > 65535) return null;
        return new UpstreamServerAddress(host, port);
    }
}
