import mindustry.Vars;

/** Direct join address using the stock JoinDialog host/port convention over libctru's IPv4 transport. */
record UpstreamServerAddress(String host, int port) {
    static UpstreamServerAddress parse(String text) {
        String address = text.strip();
        int separator = address.indexOf(':');
        String host = separator < 0 ? address : address.substring(0, separator);
        if (host.isEmpty() || host.length() > 253 || !host.matches("[a-zA-Z0-9._-]+")
                || !host.matches(".*[a-zA-Z0-9].*")) {
            throw new IllegalArgumentException("Invalid IPv4 address or hostname");
        }
        if (separator < 0) return new UpstreamServerAddress(host, Vars.port);

        String digits = address.substring(separator + 1);
        if (!digits.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid server port");
        int port = Integer.parseInt(digits);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid server port");
        return new UpstreamServerAddress(host, port);
    }
}
