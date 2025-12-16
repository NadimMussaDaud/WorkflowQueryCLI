import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.Nullable;

public class Monitor implements Runnable {
    //TODO: Implement colors scheme: Green for completed, Yellow for in_progress... and Red for failures
    //TODO: Try to separate Jobs, Workflow runs and Steps with some structure.

    //This is an executor for many Threads since we assume MANY JOBS
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String GET_WORKFLOWS_RUNS = "https://api.github.com/repos/%s/%s/actions/runs";
    private static final String GET_JOBS_RUNS = "https://api.github.com/repos/%s/%s/actions/runs/%s/jobs";

    private final String owner;
    private final String repo ;
    private final String token ;
    private Instant timestamp;
    private boolean running = true;
    private final HttpClient httpClient;

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
                Instant workflowTime = timestamp;
                JsonNode array = workflowRuns.get("workflow_runs");
                for (JsonNode node : array) {
                    Instant time = Instant.parse(node.get("updated_at").asText());
                    String id = node.get("id").asText();

                    if (timestamp == null || time.isAfter(workflowTime)) {
                        System.out.printf("Id: %s | status: %s.%n",
                                id,
                                node.get("status").asText());
                        printJobsWithSteps(id, requestBase);
                    }
                    // Find the most recent time
                    if (workflowTime == null || time.isAfter(workflowTime)) {
                        workflowTime = time;
                    }
                }
                timestamp = workflowTime;
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
        try (FileWriter writer = new FileWriter(Main.FILENAME, true)) {
            writer.write(String.format("%s %s\n", repo, timestamp.toString()));
        }
    }

    private void printJobsWithSteps(String id, HttpRequest.Builder requestBase ) throws IOException, InterruptedException {
        HttpRequest jobsRequest = requestBase
                .uri(URI.create(String.format(GET_JOBS_RUNS, owner, repo, id)))
                .build();
        HttpResponse<String> responseRuns = httpClient.send(
                jobsRequest,
                HttpResponse.BodyHandlers.ofString()
        );

        JsonNode jobsRuns = mapper.readTree(responseRuns.body());
        JsonNode array = jobsRuns.get("jobs");
        for (JsonNode job : array) {
            String JobId = job.get("id").asText();
            boolean completionStatus = jobsRuns.get("status").asText().equals("completed");

            if(!completionStatus) {
                //TODO: Here we need a thread to keep running addressing each job. To keep overseeing the Steps within the Job and its status
                executorService.submit(() -> monitorJobSteps(JobId, jobsRequest));
            } else {
                //TODO: Print that Job has been completed and related info.
            }
        }
    }

    private void monitorJobSteps(String jobId, HttpRequest request) {
        // TODO: While the JOB is not completed keep printing the steps status and timestamps together with Job ID with those steps
        // TODO: If completed then just print that it has been completed and related info.
    }
}
