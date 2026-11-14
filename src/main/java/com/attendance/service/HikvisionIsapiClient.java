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
                    buildUrl(camera, "/ISAPI/System/deviceInfo"), (byte[]) null, null,
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
            HttpResponse<byte[]> response = send(camera, "GET", url, (byte[]) null, null,
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

    // ------------------------------------------------------------------
    // Face library (FDLib) management
    // ------------------------------------------------------------------

    /** Result of an FDLib call. pid is set when the camera assigned a person ID. */
    public static final class FaceLibResult {
        public final boolean success;
        public final String pid;
        public final String message;

        FaceLibResult(boolean success, String pid, String message) {
            this.success = success;
            this.pid = pid;
            this.message = message;
        }
    }

    /**
     * Add a person with a face photo to a face library.
     * POST /ISAPI/Intelligent/FDLib/{lib}/picture as multipart/form-data:
     * field "FaceAppendData" (XML) + field "importImage" (JPEG).
     * The camera answers with a {@code <PID>} element identifying the new person.
     */
    public FaceLibResult addFaceToLibrary(CameraConfig camera, String libId, String name,
                                          String certificateNumber, byte[] jpeg) {
        String url = buildUrl(camera, "/ISAPI/Intelligent/FDLib/" + libId + "/picture");
        String xml = faceAppendXml(name, certificateNumber, null);
        return sendFaceMultipart(camera, "POST", url, xml, jpeg);
    }

    /**
     * Replace a person's data (and photo when jpeg != null).
     * PUT /ISAPI/Intelligent/FDLib/{lib}/picture/{pid}. With a photo the request is
     * multipart like the add; without a photo it is a plain XML body.
     */
    public FaceLibResult updateFaceInLibrary(CameraConfig camera, String libId, String pid, String name,
                                             String certificateNumber, byte[] jpeg) {
        String url = buildUrl(camera, "/ISAPI/Intelligent/FDLib/" + libId + "/picture/" + pid);
        String xml = faceAppendXml(name, certificateNumber, null);
        if (jpeg == null) {
            try {
                HttpResponse<String> response = send(camera, "PUT", url, xml, "application/xml",
                        HttpResponse.BodyHandlers.ofString());
                return parseFaceLibResponse(response.statusCode(), response.body());
            } catch (Exception e) {
                return new FaceLibResult(false, pid, e.getMessage());
            }
        }
        return sendFaceMultipart(camera, "PUT", url, xml, jpeg);
    }

    /** Remove a person from a face library. DELETE /ISAPI/Intelligent/FDLib/{lib}/picture/{pid}. */
    public FaceLibResult deleteFaceFromLibrary(CameraConfig camera, String libId, String pid) {
        String url = buildUrl(camera, "/ISAPI/Intelligent/FDLib/" + libId + "/picture/" + pid);
        try {
            HttpResponse<String> response = send(camera, "DELETE", url, (byte[]) null, null,
                    HttpResponse.BodyHandlers.ofString());
            return parseFaceLibResponse(response.statusCode(), response.body());
        } catch (Exception e) {
            return new FaceLibResult(false, pid, e.getMessage());
        }
    }

    /**
     * Download a person's face photo from a face library.
     * GET /ISAPI/Intelligent/FDLib/{lib}/picture/{pid} returns the stored JPEG.
     */
    public Optional<byte[]> downloadFacePicture(CameraConfig camera, String libId, String pid) {
        String url = buildUrl(camera, "/ISAPI/Intelligent/FDLib/" + libId + "/picture/" + pid);
        try {
            HttpResponse<byte[]> response = send(camera, "GET", url, (byte[]) null, null,
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200 && response.body() != null && response.body().length > 0) {
                return Optional.of(response.body());
            }
            log.debug("Face picture download for PID {} on {} returned HTTP {}",
                    pid, camera.getIpAddress(), response.statusCode());
        } catch (Exception e) {
            log.debug("Failed to download face picture PID {} from {}: {}",
                    pid, camera.getIpAddress(), e.getMessage());
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------
    // Face library search (FDSearch)
    // ------------------------------------------------------------------

    /** A person stored in a face library. The pid is camera-local; certificateNumber holds the employee ID. */
    public static final class FaceLibPerson {
        public final String pid;
        public final String name;
        public final String certificateNumber;

        FaceLibPerson(String pid, String name, String certificateNumber) {
            this.pid = pid;
            this.name = name;
            this.certificateNumber = certificateNumber;
        }
    }

    /**
     * People stored in a face library. POST /ISAPI/Intelligent/FDLib/FDSearch — the
     * same search verified against the camera with curl. Returns one page of up to
     * {@code maxResults} entries, or an empty list when the search fails.
     */
    public List<FaceLibPerson> searchFaceLibrary(CameraConfig camera, String libId, int maxResults) {
        String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<FDSearchDescription>"
                + "<FDID>" + escapeXml(libId) + "</FDID>"
                + "<startTime>1970-01-01</startTime>"
                + "<endTime>2035-12-31</endTime>"
                + "<searchID>1</searchID>"
                + "<maxResults>" + maxResults + "</maxResults>"
                + "<searchResultPosition>1</searchResultPosition>"
                + "</FDSearchDescription>";
        try {
            HttpResponse<String> response = send(camera, "POST",
                    buildUrl(camera, "/ISAPI/Intelligent/FDLib/FDSearch"),
                    body, "application/xml", HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null) {
                log.warn("FDSearch on {} returned HTTP {}", camera.getIpAddress(), response.statusCode());
                return Collections.emptyList();
            }
            return parseFdSearch(response.body());
        } catch (Exception e) {
            log.warn("FDSearch failed on {}: {}", camera.getIpAddress(), e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Find a person by certificate number (the employee ID sent at enrollment). */
    public Optional<FaceLibPerson> findFaceByCertificateNumber(CameraConfig camera, String libId,
                                                               String certificateNumber) {
        if (certificateNumber == null || certificateNumber.isBlank()) {
            return Optional.empty();
        }
        String wanted = certificateNumber.trim();
        return searchFaceLibrary(camera, libId, 100).stream()
                .filter(p -> wanted.equals(p.certificateNumber))
                .findFirst();
    }

    /**
     * FDSearch answers with one PID + name + certificateNumber block per person, followed
     * by per-person modeling status blocks that repeat PID and name. Pairing each
     * certificateNumber with the nearest preceding PID (document order) picks out the people.
     */
    private List<FaceLibPerson> parseFdSearch(String xml) {
        Map<String, FaceLibPerson> byPid = new LinkedHashMap<>();
        Matcher m = Pattern.compile("<(PID|name|certificateNumber)>\\s*([^<]*?)\\s*</\\1>").matcher(xml);
        String pid = null;
        String name = null;
        while (m.find()) {
            String tag = m.group(1);
            String value = m.group(2);
            switch (tag) {
                case "PID" -> {
                    pid = value;
                    name = null;
                }
                case "name" -> {
                    if (pid != null && name == null) name = value;
                }
                case "certificateNumber" -> {
                    if (pid != null && !value.isBlank()) {
                        byPid.put(pid, new FaceLibPerson(pid, name, value));
                    }
                }
                default -> { }
            }
        }
        return new ArrayList<>(byPid.values());
    }

    // ------------------------------------------------------------------
    // Face comparison results (FCSearch) — the camera's own match history
    // ------------------------------------------------------------------

    /** One face-comparison match recorded by the camera. */
    public static final class FaceComparison {
        public final String snapTime;
        public final long snapTimeMillis;
        public final String name;
        public final String certificateNumber;
        public final String pid;
        public final Double similarity;
        public final String facePicUrl;
        public final String snapPicUrl;

        FaceComparison(String snapTime, long snapTimeMillis, String name, String certificateNumber,
                       String pid, Double similarity, String facePicUrl, String snapPicUrl) {
            this.snapTime = snapTime;
            this.snapTimeMillis = snapTimeMillis;
            this.name = name;
            this.certificateNumber = certificateNumber;
            this.pid = pid;
            this.similarity = similarity;
            this.facePicUrl = facePicUrl;
            this.snapPicUrl = snapPicUrl;
        }
    }

    /**
     * Face-comparison matches recorded on the camera. POST /ISAPI/Intelligent/FDLib/FCSearch —
     * the same endpoint the camera's "Face Picture Comparison Result" page queries. This is the
     * reliable way to fetch matches on firmware that does not push faceMatch events on the
     * alertStream. One FaceComparison per captured face (the first library match in the element).
     */
    public List<FaceComparison> searchFaceComparisons(CameraConfig camera, LocalDateTime start,
                                                      LocalDateTime end, int maxResults) {
        String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<FCSearchDescription>"
                + "<searchID>" + Math.abs(RANDOM.nextLong()) + "</searchID>"
                + "<maxResults>" + maxResults + "</maxResults>"
                + "<searchResultPosition>1</searchResultPosition>"
                + "<startTime>" + start.format(ISO_FMT) + "</startTime>"
                + "<endTime>" + end.format(ISO_FMT) + "</endTime>"
                + "</FCSearchDescription>";
        try {
            HttpResponse<String> response = send(camera, "POST",
                    buildUrl(camera, "/ISAPI/Intelligent/FDLib/FCSearch"),
                    body, "application/xml", HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null) {
                log.warn("FCSearch on {} returned HTTP {}", camera.getIpAddress(), response.statusCode());
                return Collections.emptyList();
            }
            return parseFcSearchResult(response.body());
        } catch (Exception e) {
            log.warn("FCSearch failed on {}: {}", camera.getIpAddress(), e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<FaceComparison> parseFcSearchResult(String xml) {
        List<FaceComparison> out = new ArrayList<>();
        // One chunk per captured face. The person's fields appear before the
        // PersonInfoExtendList block, so the first occurrence in a chunk is the person.
        for (String chunk : xml.split("<MatchElement>")) {
            if (!chunk.contains("</MatchElement>")) continue;
            String name = findRegex(chunk, "<name>\\s*([^<]+?)\\s*</name>");
            String cert = findRegex(chunk, "<certificateNumber>\\s*([^<]*?)\\s*</certificateNumber>");
            String pid = findRegex(chunk, "<PID>\\s*([^<\\s]+)\\s*</PID>");
            Double similarity = parseDouble(findRegex(chunk, "<similarity>\\s*([^<]+?)\\s*</similarity>"));
            String snapTime = findRegex(chunk, "<snapTime>\\s*([^<]+?)\\s*</snapTime>");
            if ((name == null && cert == null) || snapTime == null) continue; // no library match / no time
            Long millis = parseTime(snapTime);
            if (millis == null) continue;
            String facePic = unescapeXml(findRegex(chunk, "<facePicURL>\\s*([^<]+?)\\s*</facePicURL>"));
            String snapPic = unescapeXml(findRegex(chunk, "<snapPicURL>\\s*([^<]+?)\\s*</snapPicURL>"));
            out.add(new FaceComparison(snapTime, millis, name, cert, pid, similarity, facePic, snapPic));
        }
        return out;
    }

    private static String unescapeXml(String s) {
        if (s == null) return null;
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'");
    }

    private FaceLibResult sendFaceMultipart(CameraConfig camera, String method, String url,
                                            String faceAppendXml, byte[] jpeg) {
        try {
            String boundary = "----hik" + Long.toHexString(RANDOM.nextLong()).replace('-', 'a');
            byte[] body = buildMultipart(boundary, faceAppendXml, jpeg);
            HttpResponse<String> response = send(camera, method, url, body,
                    "multipart/form-data; boundary=" + boundary, HttpResponse.BodyHandlers.ofString());
            return parseFaceLibResponse(response.statusCode(), response.body());
        } catch (Exception e) {
            return new FaceLibResult(false, null, e.getMessage());
        }
    }

    private String faceAppendXml(String name, String certificateNumber, String sex) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<FaceAppendData>");
        sb.append("<name>").append(escapeXml(name)).append("</name>");
        sb.append("<bornTime>2000-01-01</bornTime>");
        if (sex != null && !sex.isBlank()) {
            sb.append("<sex>").append(escapeXml(sex)).append("</sex>");
        }
        if (certificateNumber != null && !certificateNumber.isBlank()) {
            sb.append("<certificateNumber>").append(escapeXml(certificateNumber)).append("</certificateNumber>");
        }
        sb.append("</FaceAppendData>");
        return sb.toString();
    }

    /** Multipart body shaped exactly like the curl form: FaceAppendData field, then importImage file. */
    private byte[] buildMultipart(String boundary, String faceAppendXml, byte[] jpeg) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(jpeg.length + 1024);
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"FaceAppendData\"\r\n\r\n"
                + faceAppendXml + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"importImage\"; filename=\"face.jpg\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(jpeg);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private FaceLibResult parseFaceLibResponse(int status, String body) {
        String text = body == null ? "" : body;
        String pid = findRegex(text, "<PID>\\s*([^<\\s]+)\\s*</PID>");
        String statusString = findRegex(text, "<statusString>\\s*([^<]+?)\\s*</statusString>");
        String subStatus = findRegex(text, "<subStatusCode>\\s*([^<]+?)\\s*</subStatusCode>");
        boolean ok = status == 200
                && (statusString == null || statusString.equalsIgnoreCase("OK"))
                && (subStatus == null || subStatus.equalsIgnoreCase("ok"));
        String message = ok ? "OK"
                : "HTTP " + status + (statusString != null ? " " + statusString : "")
                + (subStatus != null ? " (" + subStatus + ")" : "")
                + (text.isBlank() ? "" : " — " + text.substring(0, Math.min(200, text.length())));
        if (pid != null) {
            return new FaceLibResult(ok, pid, message);
        }
        return new FaceLibResult(ok, null, message);
    }

    private static String findRegex(String text, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
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
                buildUrl(camera, "/ISAPI/Event/notification/alertStream"), (byte[]) null, null,
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

            // The person's identifier: cameras report it as employeeNoString (access events),
            // certificateNumber / customHumanID (face match against an FDLib person) or PID.
            String employeeNo = firstOf(finder, "employeeNoString", "employeeNo", "employeeId",
                    "customHumanID", "certificateNumber", "cardNo", "PID", "pid");
            boolean relevant = employeeNo != null || type.contains("face") || type.contains("accesscontroller");
            if (!relevant) return null;

            if (type.contains("facematch") && employeeNo == null) {
                // A match event whose person field we did not recognise: log it so the
                // real field name can be added to the list above.
                log.info("faceMatch event without a known person field: {}",
                        text.length() > 1000 ? text.substring(0, 1000) : text);
            }

            Long time = parseTime(firstOf(finder, "dateTime", "time", "captureTime"));
            return FaceEvent.builder()
                    .employeeNo(employeeNo)
                    .personName(firstOf(finder, "name", "personName", "humanName"))
                    .similarity(parseDouble(firstOf(finder, "similarity", "similarityScore")))
                    .eventTimeMillis(time != null ? time : System.currentTimeMillis())
                    .pictureUrl(firstOf(finder, "pictureURL", "picUrl", "snapPicURL", "faceURL", "captureURL"))
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
        return send(camera, method, url,
                body != null ? body.getBytes(StandardCharsets.UTF_8) : null, contentType, handler);
    }

    private <T> HttpResponse<T> send(CameraConfig camera, String method, String url, byte[] body,
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

    private HttpRequest buildRequest(String url, String method, byte[] body, String contentType, String auth) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "*/*");
        if (auth != null) b.header("Authorization", auth);
        if (body != null) {
            b.header("Content-Type", contentType != null ? contentType : "application/xml");
            b.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
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