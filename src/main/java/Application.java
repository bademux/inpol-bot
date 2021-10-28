import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Map.entry;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toUnmodifiableList;

public class Application {

    private final static Logger log = Logger.getLogger(Application.class.getName());

    private final static HttpClient client = HttpClient.newHttpClient();
    private final static ObjectMapper objectMapper = new ObjectMapper();
    private final static Random random = new Random();
    private static final String INPOL_BASE_URL = "https://inpol.mazowieckie.pl/api/";

    /**
     * Go to cases https://inpol.mazowieckie.pl/home/cases
     * select case, select "Make an appointment at the office", select location and queue, then hijack queueId in the URL:
     * https://inpol.mazowieckie.pl/api/reservations/queue/{queueId}/dates
     * The same is for token: look for http header "Authorization: Bearer {token}" for the request above.
     * caseId can be grabbed from address bar:  https://inpol.mazowieckie.pl/home/cases/{caseId}
     */
    public static void main(String... args) throws IOException, InterruptedException {
        log.finest("Start application");

        String caseId = null;
        String queueId = null;
        String token = null;
        for (int i = 0; i < args.length; i++) {
            if ("-caseId".equals(args[i])) {
                caseId = args[++i];
            } else if ("-queueId".equals(args[i])) {
                queueId = args[++i];
            } else if ("-token".equals(args[++i])) {
                token = args[++i];
            }
        }
        requireNonNull(caseId, "Please provide caseId: -caseId c4e64338-37c7-11ec-8d3d-0242ac130003");
        requireNonNull(queueId, "Please provide queueId: -queueId b8ce0ab6-cd6f-4bc7-ab6b-c125b8f31b86");
        requireNonNull(token, "Please provide caseId: -token ABCDEF12345XXXXXX");

        var profile = fetchProfile(caseId, queueId, token);
        log.info("Actual profile is : " + profile.get("firstName") + " " + profile.get("surname"));
        var availDates = fetchAvailableDates(caseId, queueId, token);
        log.info("Available dates are: " + String.join(", ", availDates));
        var slots = fetchSlots(caseId, queueId, token, availDates);
        log.info("Available slots are: " + slots.stream().map(Entry::toString).collect(Collectors.joining(", ")));
        reserveFirst(caseId, queueId, token, slots, profile.get("firstName"), profile.get("surname"), profile.get("dateOfBirth"));
        log.finest("Stop application");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> fetchProfile(String caseId, String queueId, String token) throws IOException, InterruptedException {

        var request = HttpRequest.newBuilder()
                .uri(URI.create(INPOL_BASE_URL + "reservations/queue/" + queueId + "/reserve"))
                .headers(createHeaders(caseId, token))
                .GET()
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString(UTF_8));
        if (response.statusCode() == 200) {
            Map<String, ?> value = objectMapper.readValue(response.body(), Map.class);
            addLatency();
            return value;
        }
        throw new IllegalStateException("Unknown response " + response);
    }


    private static void reserveFirst(String caseId, String queueId, String token, Collection<Entry<Integer, String>> slots,
                                     Object name, Object lastName, Object dateOfBirth) throws IOException, InterruptedException {
        for (Entry<Integer, String> slot : slots) {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(INPOL_BASE_URL + "reservations/queue/" + queueId + "/reserve"))
                    .headers(createHeaders(caseId, token))
                    .header("Origin", " https://inpol.mazowieckie.pl")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(Map.of(
                                    "proceedingId", caseId,
                                    "slotId", slot.getKey(),
                                    "name", name, "lastName", lastName, "dateOfBirth", dateOfBirth
                            ))
                    ))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString(UTF_8));
            if (response.statusCode() == 200) {
                log.warning("Slot RESERVED for you " + slot.getValue());
                return;
            }
            log.info("Slot is already reserved " + slot.getValue());
            addLatency();
        }
    }

    private static void addLatency() {
        try {
            Thread.sleep(random.nextInt(1000) + 100);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Collection<Entry<Integer, String>> fetchSlots(String caseId, String queueId, String token, Collection<String> availDates) throws IOException, InterruptedException {
        var builder = Stream.<Map<String, ?>>builder();
        for (String availDate : availDates) {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(INPOL_BASE_URL + "reservations/queue/" + queueId + '/' + availDate + "/slots"))
                    .headers(createHeaders(caseId, token))
                    .GET()
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString(UTF_8));
            if (response.statusCode() != 200) {
                log.warning("Unknown response " + response);
                continue;
            }
            for (Map<String, ?> slot : objectMapper.readValue(response.body(), Map[].class)) {
                builder.accept(slot);
            }
            addLatency();
        }
        return builder.build().map(map -> entry((Integer) map.get("id"), (String) map.get("date"))).collect(toUnmodifiableList());
    }

    private static Collection<String> fetchAvailableDates(String caseId, String queueId, String token) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(INPOL_BASE_URL + "reservations/queue/" + queueId + "/dates"))
                .headers(createHeaders(caseId, token))
                .GET()
                .build();


        var response = client.send(request, HttpResponse.BodyHandlers.ofString(UTF_8));
        if (response.statusCode() == 200) {
            addLatency();
            return List.of(objectMapper.readValue(response.body(), String[].class));
        }
        throw new IllegalStateException("Unknown response " + response);
    }

    private static String[] createHeaders(String caseId, String token) {
        return new String[]{"Authorization", "Bearer " + token,
                "Referer", "https://inpol.mazowieckie.pl/home/cases/" + caseId,
                "sec-ch-ua", "\"Chromium\";v=\"95\", \";Not A Brand\";v=\"99\"",
                "Accept", "application/json, text/plain, */*",
                "Content-Type", "application/json",
                "sec-ch-ua-mobile", "?0",
                "User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/95.0.4638.54 Safari/537.36",
                "sec-ch-ua-platform", "\"Linux\"",
                "Origin", " https://inpol.mazowieckie.pl",
                "Sec-Fetch-Site", "same-origin",
                "Sec-Fetch-Mode", "cors",
                "Sec-Fetch-Dest", "empty",
                "Accept-Language", "en-US,en;q=0.9,pl-PL;q=0.8,pl;q=0.7,be-BY;q=0.6,be;q=0.5,ru-RU;q=0.4,ru;q=0.3",
                "Cookie", "cookieconsent_status=dismiss"};

    }

}
