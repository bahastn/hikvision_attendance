package com.attendance.service;

import com.attendance.dto.FaceEvent;
import com.attendance.entity.CameraConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Hikvision ISAPI client (no native SDK).
 *
 * - HTTP/1.1 + Digest auth (MD5 / MD5-sess / SHA-256 / SHA-256-sess) with Basic fallback
 * - Real-time events through /ISAPI/Event/notification/alertStream (multipart stream)
 * - Optional polling of /ISAPI/AccessControl/AcsEvent (access-control terminals only)
 */
@Component
@Slf4j
public class HikvisionIsapiClient {

    static {
        // Cameras use self-signed certificates. Only matters for protocol=https. LAN use only.
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
    }

    /** Receives each detected event, plus the JPEG bytes when the camera sent one (may be null). */
    public interface AlertHandler {
        void onEvent(FaceEvent event, byte[] picture);
    }

    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter ISO_OFFSET_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final Pattern DIGEST_PARAM = Pattern.compile("(\\w+)=(\"([^\"]*)\"|[^,\\s]*)");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_PART_BYTES = 20 * 1024 * 1024;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient = newClient(false);
    private final HttpClient httpsClient = newClient(true);

    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "isapi-watchdog");
        t.setDaemon(true);
        return t;
    });

    /** Last event time per camera (used by the polling method). */
    private final Map<Long, LocalDateTime> lastEventTimeByCamera = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Connectivity + credentials test using the stored camera config. */
    public boolean testIsapiConnection(CameraConfig camera) {
        try {
            HttpResponse<String> response = send(camera, "GET",
                    buildUrl(camera, "/ISAPI/System/deviceInfo"), null, null,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return true;
            }
            String body = response.body() == null ? "empty"
                    : response.body().substring(0, Math.min(200, response.body().length()));
            log.warn("ISAPI test for {}:{} returned HTTP {}. Body: {}",
                    camera.getIpAddress(), camera.getPort(), response.statusCode(), body);
            return false;
        } catch (Exception e) {
            log.warn("ISAPI test failed for {}:{} -> {}: {}", camera.getIpAddress(), camera.getPort(),
                    e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }

    /** Test with explicit credentials, nothing is stored. */
    public boolean testConnectionWithCredentials(String ip, Integer port, String protocol,
                                                 String username, String password) {
        CameraConfig temp = CameraConfig.builder()
                .ipAddress(ip)
                .port(port != null ? port : 80)
                .protocol(protocol != null ? protocol : "http")
                .username(username)
                .password(password)
                .build();
        return testIsapiConnection(temp);
    }

    public boolean isReachable(CameraConfig camera) {
        return testIsapiConnection(camera);
    }

    /** Download a JPEG. urlPath may be a path ("/ISAPI/...") or a full URL returned by the camera. */
    public Optional<byte[]> downloadPicture(CameraConfig camera, String urlPath) {
        try {
            String url = urlPath.startsWith("http") ? urlPath : buildUrl(camera, urlPath);
            HttpResponse<byte[]> response = send(camera, "GET", url, null, null,
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200 && response.body() != null && response.body().length > 0) {
                return Optional.of(response.body());
            }
            log.warn("Picture download {} returned HTTP {}", url, response.statusCode());
        } catch (Exception e) {
            log.warn("Failed to download picture {} from {}: {}", urlPath, camera.getIpAddress(), e.getMessage());
        }
        return Optional.empty();
    }

    /** Take a snapshot of a channel (101 = camera 1 main stream). */
    public Optional<byte[]> snapshot(CameraConfig camera, int channel) {
        return downloadPicture(camera, "/ISAPI/Streaming/channels/" + channel + "/picture");
    }

    /**
     * RECOMMENDED: blocking, auto-reconnecting real-time listener.
     * Run it in its own thread, and set {@code running} to false to stop it.
     */
    public void streamEvents(CameraConfig camera, AlertHandler handler, java.util.concurrent.atomic.AtomicBoolean running) {
        long backoffMs = 2000;
        while (running.get()) {
            try {
                log.info("Opening alertStream on {}:{}", camera.getIpAddress(), camera.getPort());
                readAlertStream(camera, handler, null, running::get);
                backoffMs = 2000; // clean end, reconnect quickly
            } catch (Exception e) {
                log.warn("alertStream on {} ended: {}", camera.getIpAddress(), e.getMessage());
                backoffMs = Math.min(backoffMs * 2, 60_000);
            }
            if (!running.get()) break;
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Drop-in polling method (same signature as before). Tries the access-control search first, then
     * listens on the alert stream for a few seconds. Events between polls can be missed on cameras,
     * so prefer {@link #streamEvents} for real use.
     */
    public List<FaceEvent> fetchNewFaceEvents(CameraConfig camera, int initialLookbackMinutes) {
        LocalDateTime start = lastEventTimeByCamera.computeIfAbsent(
                camera.getId(), id -> LocalDateTime.now().minusMinutes(initialLookbackMinutes));

        List<FaceEvent> events = new ArrayList<>(searchAcsEvents(camera, start));

        if (events.isEmpty()) {
            try {
                readAlertStream(camera, (ev, pic) -> events.add(ev), Duration.ofSeconds(4), () -> true);
            } catch (Exception e) {
                log.debug("Short alertStream read failed for {}: {}", camera.getIpAddress(), e.getMessage());
            }
        }

        events.stream()
                .map(FaceEvent::getEventTimeMillis)
                .filter(Objects::nonNull)
                .max(Long::compare)
                .ifPresent(max -> lastEventTimeByCamera.put(camera.getId(),
                        LocalDateTime.ofInstant(Instant.ofEpochMilli(max), ZoneId.systemDefault()).plusSeconds(1)));
        return events;
    }

    // ------------------------------------------------------------------
    // Alert stream (multipart)
    // ------------------------------------------------------------------

    /**
     * Opens ONE alertStream connection and blocks until it closes, {@code maxDuration} passes
     * (null = unlimited), {@code keepRunning} returns false, or nothing is received for 60 seconds.
     */
    public void readAlertStream(CameraConfig camera, AlertHandler handler, Duration maxDuration,
                                BooleanSupplier keepRunning) throws Exception {
        HttpResponse<InputStream> response = send(camera, "GET",
                buildUrl(camera, "/ISAPI/Event/notification/alertStream"), null, null,
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            closeQuietly(response.body());
            throw new IOException("alertStream returned HTTP " + response.statusCode());
        }

        InputStream raw = response.body();
        String boundary = extractBoundary(response.headers().firstValue("Content-Type").orElse(""));

        long deadline = maxDuration == null ? Long.MAX_VALUE : System.nanoTime() + maxDuration.toNanos();
        AtomicLong lastData = new AtomicLong(System.nanoTime());
        ScheduledFuture<?> guard = watchdog.scheduleAtFixedRate(() -> {
            long now = System.nanoTime();
            boolean stale = now - lastData.get() > TimeUnit.SECONDS.toNanos(60);
            if (now > deadline || !keepRunning.getAsBoolean() || stale) {
                closeQuietly(raw); // unblocks the read below
            }
        }, 1, 1, TimeUnit.SECONDS);

        FaceEvent pending = null;
        try (InputStream in = new BufferedInputStream(raw, 65536)) {
            MultipartReader reader = new MultipartReader(in, boundary);
            Part part;
            while ((part = reader.next()) != null) {
                lastData.set(System.nanoTime());
                String type = part.contentType.toLowerCase(Locale.ROOT);

                if (type.contains("xml") || type.contains("json")) {
                    if (pending != null) {          // previous event had no picture
                        deliver(handler, pending, null);
                        pending = null;
                    }
                    String text = new String(part.body, StandardCharsets.UTF_8);
                    log.debug("alertStream payload: {}", text);
                    pending = parseAlert(text);
                } else if (type.startsWith("image/") && pending != null) {
                    deliver(handler, pending, part.body);
                    pending = null;
                }
            }
        } catch (IOException e) {
            // closed by the watchdog or dropped by the camera: normal end of a stream
            log.debug("alertStream closed: {}", e.getMessage());
        } finally {
            guard.cancel(false);
            if (pending != null) {
                deliver(handler, pending, null);
            }
        }
    }

    private void deliver(AlertHandler handler, FaceEvent event, byte[] picture) {
        try {
            handler.onEvent(event, picture);
        } catch (Exception e) {
            log.error("Event handler failed: {}", e.getMessage(), e);
        }
    }

    private static String extractBoundary(String contentType) {
        Matcher m = Pattern.compile("boundary=\"?([^\";\\s]+)\"?", Pattern.CASE_INSENSITIVE).matcher(contentType);
        return m.find() ? m.group(1) : "boundary";
    }

    private static final class Part {
        String contentType = "";
        byte[] body = new byte[0];
    }

    /** Minimal multipart/mixed reader. Uses Content-Length of each part (Hikvision sends it). */
    private static final class MultipartReader {
        private final InputStream in;
        private final String marker;
        private byte[] carried;

        MultipartReader(InputStream in, String boundary) {
            this.in = in;
            this.marker = boundary.startsWith("--") ? boundary : "--" + boundary;
        }

        Part next() throws IOException {
            // 1) find boundary line
            while (true) {
                byte[] line = carried != null ? carried : readRawLine();
                carried = null;
                if (line == null) return null;
                String s = new String(line, StandardCharsets.ISO_8859_1).trim();
                if (s.startsWith(marker)) {
                    if (s.equals(marker + "--")) return null;
                    break;
                }
            }
            // 2) headers
            Part part = new Part();
            int length = -1;
            boolean any = false;
            while (true) {
                byte[] raw = readRawLine();
                if (raw == null) return null;
                String h = new String(raw, StandardCharsets.ISO_8859_1).trim();
                if (h.isEmpty()) {
                    if (any) break;
                    continue;
                }
                any = true;
                int c = h.indexOf(':');
                if (c > 0) {
                    String k = h.substring(0, c).trim().toLowerCase(Locale.ROOT);
                    String v = h.substring(c + 1).trim();
                    if (k.equals("content-type")) part.contentType = v;
                    else if (k.equals("content-length")) {
                        try { length = Integer.parseInt(v); } catch (NumberFormatException ignored) { }
                    }
                }
            }
            // 3) body
            if (length >= 0) {
                if (length > MAX_PART_BYTES) throw new IOException("Part too large: " + length);
                byte[] body = in.readNBytes(length);
                if (body.length < length) return null;
                part.body = body;
            } else {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                while (true) {
                    byte[] line = readRawLine();
                    if (line == null) break;
                    if (new String(line, StandardCharsets.ISO_8859_1).trim().startsWith(marker)) {
                        carried = line;
                        break;
                    }
                    out.write(line);
                    out.write('\n');
                    if (out.size() > MAX_PART_BYTES) throw new IOException("Part too large");
                }
                part.body = out.toByteArray();
            }
            return part;
        }

        /** Reads up to (and excluding) '\n'. Returns null on EOF. */
        private byte[] readRawLine() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream(128);
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\n') return out.toByteArray();
                out.write(b);
                if (out.size() > MAX_PART_BYTES) throw new IOException("Line too long");
            }
            return out.size() == 0 ? null : out.toByteArray();
        }
    }

    // ------------------------------------------------------------------
    // Alert parsing (XML or JSON)
    // ------------------------------------------------------------------

    /** Returns a FaceEvent for face / access events, or null for heartbeats and unrelated events. */
    private FaceEvent parseAlert(String text) {
        try {
            text = text.trim();
            Function<String, String> finder;
            if (text.startsWith("{")) {
                JsonNode root = json.readTree(text);
                finder = key -> findJson(root, key);
            } else {
                Element root = parseXml(text);
                finder = key -> textOf(root, key);
            }

            String eventType = firstOf(finder, "eventType");
            if (eventType == null) return null;
            String type = eventType.toLowerCase(Locale.ROOT);
            if (type.contains("heartbeat") || type.contains("videoloss")) return null;

            String employeeNo = firstOf(finder, "employeeNoString", "employeeNo", "employeeId");
            boolean relevant = employeeNo != null || type.contains("face") || type.contains("accesscontroller");
            if (!relevant) return null;

            Long time = parseTime(firstOf(finder, "dateTime", "time"));
            return FaceEvent.builder()
                    .employeeNo(employeeNo)
                    .personName(firstOf(finder, "name", "personName"))
                    .similarity(parseDouble(firstOf(finder, "similarity")))
                    .eventTimeMillis(time != null ? time : System.currentTimeMillis())
                    .pictureUrl(firstOf(finder, "pictureURL", "picUrl", "snapPicURL"))
                    .deviceId(firstOf(finder, "deviceID", "macAddress"))
                    .build();
        } catch (Exception e) {
            log.debug("Failed to parse alert: {}", e.getMessage());
            return null;
        }
    }

    private String firstOf(Function<String, String> finder, String... keys) {
        for (String k : keys) {
            String v = finder.apply(k);
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    private String findJson(JsonNode node, String key) {
        if (node == null) return null;
        if (node.isObject()) {
            JsonNode v = node.get(key);
            if (v != null && v.isValueNode() && !v.asText().isBlank()) return v.asText().trim();
            for (JsonNode child : node) {
                String r = findJson(child, key);
                if (r != null) return r;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String r = findJson(child, key);
                if (r != null) return r;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // AcsEvent polling (access-control terminals)
    // ------------------------------------------------------------------

    private List<FaceEvent> searchAcsEvents(CameraConfig camera, LocalDateTime startTime) {
        try {
            ZoneId zone = ZoneId.systemDefault();
            Map<String, Object> cond = new LinkedHashMap<>();
            cond.put("searchID", UUID.randomUUID().toString());
            cond.put("searchResultPosition", 0);
            cond.put("maxResults", 50);
            cond.put("major", 0);
            cond.put("minor", 0);
            cond.put("startTime", startTime.atZone(zone).format(ISO_OFFSET_FMT));
            cond.put("endTime", OffsetDateTime.now().format(ISO_OFFSET_FMT));
            String body = json.writeValueAsString(Map.of("AcsEventCond", cond));

            HttpResponse<String> response = send(camera, "POST",
                    buildUrl(camera, "/ISAPI/AccessControl/AcsEvent?format=json"),
                    body, "application/json", HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null) {
                log.debug("AcsEvent search on {} returned HTTP {}", camera.getIpAddress(), response.statusCode());
                return Collections.emptyList();
            }

            List<FaceEvent> events = new ArrayList<>();
            JsonNode list = json.readTree(response.body()).path("AcsEvent").path("InfoList");
            for (JsonNode n : list) {
                String emp = n.path("employeeNoString").asText(null);
                String pic = n.path("pictureURL").asText(null);
                if ((emp == null || emp.isBlank()) && (pic == null || pic.isBlank())) continue;
                events.add(FaceEvent.builder()
                        .employeeNo(emp)
                        .personName(n.path("name").asText(null))
                        .eventTimeMillis(parseTime(n.path("time").asText(null)))
                        .pictureUrl(pic)
                        .deviceId(String.valueOf(camera.getId()))
                        .build());
            }
            return events;
        } catch (Exception e) {
            log.debug("AcsEvent search failed for {}: {}", camera.getIpAddress(), e.getMessage());
            return Collections.emptyList();
        }
    }

    // ------------------------------------------------------------------
    // HTTP + Digest authentication
    // ------------------------------------------------------------------

    private <T> HttpResponse<T> send(CameraConfig camera, String method, String url, String body,
                                     String contentType, HttpResponse.BodyHandler<T> handler) throws Exception {
        HttpClient client = url.startsWith("https") ? httpsClient : httpClient;

        HttpResponse<T> response = client.send(buildRequest(url, method, body, contentType, null), handler);
        if (response.statusCode() != 401) {
            return response;
        }

        List<String> challenges = response.headers().allValues("WWW-Authenticate");
        closeQuietly(response.body());

        String username = camera.getUsername() != null && !camera.getUsername().isBlank()
                ? camera.getUsername() : "admin";
        String password = camera.getPassword() != null ? camera.getPassword() : "";

        String digest = pickDigestChallenge(challenges);
        String authorization;
        if (digest != null) {
            authorization = buildDigestAuthorization(digest, username, password, method, url);
        } else if (challenges.stream().anyMatch(h -> h.toLowerCase(Locale.ROOT).startsWith("basic"))) {
            authorization = "Basic " + Base64.getEncoder()
                    .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        } else {
            log.warn("401 from {} without a usable WWW-Authenticate header: {}", url, challenges);
            return response;
        }
        return client.send(buildRequest(url, method, body, contentType, authorization), handler);
    }

    private HttpRequest buildRequest(String url, String method, String body, String contentType, String auth) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "*/*");
        if (auth != null) b.header("Authorization", auth);
        if (body != null) {
            b.header("Content-Type", contentType != null ? contentType : "application/xml");
            b.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return b.build();
    }

    /** Newer firmware sends one header per algorithm. Prefer plain MD5 for maximum compatibility. */
    private String pickDigestChallenge(List<String> headers) {
        List<String> digests = headers.stream()
                .filter(h -> h.regionMatches(true, 0, "Digest ", 0, 7))
                .collect(Collectors.toList());
        for (String h : digests) {
            if ("MD5".equalsIgnoreCase(parseDigestHeader(h).getOrDefault("algorithm", "MD5"))) return h;
        }
        return digests.isEmpty() ? null : digests.get(0);
    }

    private String buildDigestAuthorization(String challenge, String username, String password,
                                            String method, String url) throws Exception {
        Map<String, String> p = parseDigestHeader(challenge);
        String realm = p.getOrDefault("realm", "");
        String nonce = p.getOrDefault("nonce", "");
        String opaque = p.get("opaque");
        String algorithm = p.getOrDefault("algorithm", "MD5");

        String qop = null;
        if (p.get("qop") != null) {
            for (String q : p.get("qop").split(",")) {
                if (q.trim().equalsIgnoreCase("auth")) qop = "auth";
            }
        }

        URI uri = URI.create(url);
        String digestUri = uri.getRawPath() + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");

        byte[] rnd = new byte[8];
        RANDOM.nextBytes(rnd);
        String cnonce = toHex(rnd);
        String nc = "00000001";

        String ha1 = hash(algorithm, username + ":" + realm + ":" + password);
        if (algorithm.toLowerCase(Locale.ROOT).endsWith("-sess")) {
            ha1 = hash(algorithm, ha1 + ":" + nonce + ":" + cnonce);
        }
        String ha2 = hash(algorithm, method + ":" + digestUri);
        String response = qop != null
                ? hash(algorithm, ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2)
                : hash(algorithm, ha1 + ":" + nonce + ":" + ha2);

        StringBuilder sb = new StringBuilder("Digest ");
        sb.append("username=\"").append(username).append("\", ");
        sb.append("realm=\"").append(realm).append("\", ");
        sb.append("nonce=\"").append(nonce).append("\", ");
        sb.append("uri=\"").append(digestUri).append("\", ");
        sb.append("algorithm=").append(algorithm).append(", ");
        sb.append("response=\"").append(response).append("\"");
        if (opaque != null) sb.append(", opaque=\"").append(opaque).append("\"");
        if (qop != null) {
            sb.append(", qop=").append(qop)
                    .append(", nc=").append(nc)
                    .append(", cnonce=\"").append(cnonce).append("\"");
        }
        return sb.toString();
    }

    private Map<String, String> parseDigestHeader(String header) {
        Map<String, String> map = new HashMap<>();
        Matcher m = DIGEST_PARAM.matcher(header.substring(header.indexOf(' ') + 1));
        while (m.find()) {
            map.put(m.group(1).toLowerCase(Locale.ROOT), m.group(3) != null ? m.group(3) : m.group(2));
        }
        return map;
    }

    private String hash(String algorithm, String input) throws Exception {
        String jca = algorithm.toUpperCase(Locale.ROOT).startsWith("SHA-256") ? "SHA-256" : "MD5";
        return toHex(MessageDigest.getInstance(jca).digest(input.getBytes(StandardCharsets.UTF_8)));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static HttpClient newClient(boolean trustAll) {
        HttpClient.Builder b = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)   // cameras often choke on the h2c upgrade
                .connectTimeout(Duration.ofSeconds(5));
        if (trustAll) {
            try {
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, new TrustManager[]{new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] c, String a) { }
                    public void checkServerTrusted(X509Certificate[] c, String a) { }
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }}, new SecureRandom());
                b.sslContext(ctx);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return b.build();
    }

    private String buildUrl(CameraConfig camera, String path) {
        String protocol = camera.getProtocol() != null ? camera.getProtocol() : "http";
        int port = camera.getPort() != null ? camera.getPort() : ("https".equalsIgnoreCase(protocol) ? 443 : 80);
        return protocol + "://" + camera.getIpAddress() + ":" + port + path;
    }

    private Element parseXml(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        Document doc = f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        return doc.getDocumentElement();
    }

    private String textOf(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        if (list.getLength() > 0 && list.item(0).getTextContent() != null) {
            String t = list.item(0).getTextContent().trim();
            return t.isEmpty() ? null : t;
        }
        return null;
    }

    private Double parseDouble(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Handles "2026-09-29T10:00:00", "2026-09-29T10:00:00+03:00" and "...Z". */
    private Long parseTime(String value) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim();
        try {
            return OffsetDateTime.parse(v).toInstant().toEpochMilli();
        } catch (Exception ignored) { }
        try {
            return LocalDateTime.parse(v.substring(0, 19), ISO_FMT)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            return null;
        }
    }

    private static void closeQuietly(Object o) {
        if (o instanceof InputStream) {
            try {
                ((InputStream) o).close();
            } catch (IOException ignored) { }
        }
    }
}