import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.Nullable;

public class Monitor implements Runnable {
    private static final ObjectMapper mapper = new ObjectMapper();
    private String owner;
    private String repo ;
    private String token ;
    private Instant timestamp;
    private boolean running = true;
    private final HttpClient httpClient;


    private final String GET_WORKFLOWS_RUNS = "https://api.github.com/repos/%s/%s/actions/runs";

    public Monitor (String repo, @Nullable Instant timestamp, String token , String owner) {
        this.timestamp = timestamp;
        this.repo = repo;
        this.token = token;
        this.owner = owner;
        httpClient = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_2)
                    .build();
    }

    @Override
    public void run() {
        HttpRequest.Builder requestBase =  HttpRequest.newBuilder()
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .GET();
        HttpRequest requestRuns = requestBase
                .uri(URI.create(String.format(GET_WORKFLOWS_RUNS, owner, repo)))
                .build();

        while (running) {
            try {
                HttpResponse<String> responseRuns = httpClient.send(
                        requestRuns,
                        HttpResponse.BodyHandlers.ofString()
                );
                JsonNode workflowRuns = mapper.readTree(responseRuns.body());
                int runsNumber = workflowRuns.get("total_count").asInt();
                Instant workflowTime = timestamp;
                JsonNode array = workflowRuns.get("workflow_runs");
                for (JsonNode node : array) {
                    Instant time = Instant.parse(node.get("updated_at").asText());

                    if (timestamp == null || time.isAfter(workflowTime)) {
                        System.out.println("This is some info for a workflow run");
                    }
                    // Find the most recent time
                    if (workflowTime == null || time.isAfter(workflowTime)) {
                        workflowTime = time;
                    }
                }
                timestamp = workflowTime;
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                // if we ever use interrupt, we can also break here
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void stop() throws IOException {
        running = false;
        try (FileWriter writer = new FileWriter("Repositories.txt", true)) {
            writer.write(String.format("%s %s\n", repo, timestamp.toString()));
        }
    }
}
